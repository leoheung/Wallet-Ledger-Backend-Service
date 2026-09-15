package com.wallet.ledger.application;

import com.wallet.ledger.domain.enums.EntryDirection;
import com.wallet.ledger.domain.enums.TransactionReason;
import com.wallet.ledger.domain.enums.TransactionStatus;
import com.wallet.ledger.domain.enums.TransactionType;
import com.wallet.ledger.domain.event.WalletBalanceChangedEvent;
import com.wallet.ledger.domain.exception.BusinessException;
import com.wallet.ledger.domain.exception.ErrorCode;
import com.wallet.ledger.domain.model.LedgerEntry;
import com.wallet.ledger.domain.model.Player;
import com.wallet.ledger.domain.model.Wallet;
import com.wallet.ledger.domain.model.WalletTransaction;
import com.wallet.ledger.domain.repository.LedgerEntryRepository;
import com.wallet.ledger.domain.repository.PlayerRepository;
import com.wallet.ledger.domain.repository.WalletRepository;
import com.wallet.ledger.domain.repository.WalletTransactionRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Money-moving use cases. Every write method executes inside ONE transaction:
 * row lock -> idempotency check -> balance rule -> transaction + ledger entry
 * -> cached balance update. Any failure rolls the whole unit back.
 */
@Service
public class WalletAppService {

    private final PlayerRepository players;
    private final WalletRepository wallets;
    private final WalletTransactionRepository transactions;
    private final LedgerEntryRepository ledgerEntries;
    private final ApplicationEventPublisher eventPublisher;

    public WalletAppService(PlayerRepository players,
                            WalletRepository wallets,
                            WalletTransactionRepository transactions,
                            LedgerEntryRepository ledgerEntries,
                            ApplicationEventPublisher eventPublisher) {
        this.players = players;
        this.wallets = wallets;
        this.transactions = transactions;
        this.ledgerEntries = ledgerEntries;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public Player createPlayer(String displayName, String currencyCode) {
        String ccy = (currencyCode == null || currencyCode.isBlank())
                ? "USD"
                : Money.requireSupportedCurrency(currencyCode).getCurrencyCode();
        Player player = players.save(new Player(displayName));
        wallets.save(new Wallet(player, ccy));
        return player;
    }

    @Transactional(readOnly = true)
    public Wallet getWallet(Long playerId) {
        requirePlayer(playerId);
        return wallets.findByPlayerId(playerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WALLET_NOT_FOUND));
    }

    @Transactional
    public LedgerOperationResult credit(Long playerId,
                                       String currencyCode,
                                       BigDecimal amount,
                                       TransactionReason reason,
                                       String referenceId,
                                       String idempotencyKey) {
        long amountMinor = Money.toMinorUnits(amount, currencyCode);
        return apply(playerId, TransactionType.CREDIT, currencyCode, amountMinor,
                reason, referenceId, idempotencyKey);
    }

    @Transactional
    public LedgerOperationResult debit(Long playerId,
                                      String currencyCode,
                                      BigDecimal amount,
                                      TransactionReason reason,
                                      String referenceId,
                                      String idempotencyKey) {
        long amountMinor = Money.toMinorUnits(amount, currencyCode);
        return apply(playerId, TransactionType.DEBIT, currencyCode, amountMinor,
                reason, referenceId, idempotencyKey);
    }

    @Transactional(readOnly = true)
    public Page<WalletTransaction> history(Long playerId, Pageable pageable) {
        Wallet wallet = getWallet(playerId);
        return transactions.findByWalletIdOrderByCreatedAtDescIdDesc(wallet.getId(), pageable);
    }

    // ------------------------------------------------------------------ core

    private LedgerOperationResult apply(Long playerId,
                                        TransactionType type,
                                        String currencyCode,
                                        long amountMinor,
                                        TransactionReason reason,
                                        String referenceId,
                                        String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        requirePlayer(playerId);

        // Pessimistic row lock: concurrent requests for the same wallet queue
        // here, so the idempotency lookup and the balance check are race-free.
        Wallet wallet = wallets.findByPlayerIdForUpdate(playerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WALLET_NOT_FOUND));

        if (!wallet.getCurrencyCode().equals(currencyCode)) {
            throw new BusinessException(ErrorCode.CURRENCY_MISMATCH,
                    "Wallet currency is %s but request was %s"
                            .formatted(wallet.getCurrencyCode(), currencyCode));
        }

        String requestHash = fingerprint(type, amountMinor, reason, referenceId, currencyCode);

        Optional<WalletTransaction> existing =
                transactions.findByWalletIdAndIdempotencyKey(wallet.getId(), idempotencyKey);
        if (existing.isPresent()) {
            WalletTransaction previous = existing.get();
            if (!previous.getRequestHash().equals(requestHash)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return new LedgerOperationResult(previous, true);
        }

        if (type == TransactionType.CREDIT) {
            wallet.credit(amountMinor);
        } else {
            // Throws INSUFFICIENT_FUNDS before anything is written.
            wallet.debit(amountMinor);
        }
        long balanceAfter = wallet.getBalance();

        WalletTransaction txn = new WalletTransaction(
                wallet, type, TransactionStatus.COMPLETED, reason, referenceId,
                amountMinor, currencyCode, idempotencyKey, requestHash, balanceAfter);
        transactions.save(txn); // IDENTITY id is populated immediately on insert

        ledgerEntries.save(new LedgerEntry(
                txn, wallet,
                type == TransactionType.CREDIT ? EntryDirection.CREDIT : EntryDirection.DEBIT,
                amountMinor, balanceAfter));

        eventPublisher.publishEvent(new WalletBalanceChangedEvent(
                playerId, wallet.getId(), txn.getId(), type, amountMinor, balanceAfter,
                currencyCode, reason, idempotencyKey, OffsetDateTime.now()));

        return new LedgerOperationResult(txn, false);
    }

    private void requirePlayer(Long playerId) {
        if (!players.existsById(playerId)) {
            throw new BusinessException(ErrorCode.PLAYER_NOT_FOUND,
                    "Player with id %d does not exist".formatted(playerId));
        }
    }

    private void requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Idempotency-Key header is required");
        }
        if (idempotencyKey.length() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Idempotency-Key must be at most 100 characters");
        }
    }

    private String fingerprint(TransactionType type,
                               long amountMinor,
                               TransactionReason reason,
                               String referenceId,
                               String currencyCode) {
        String canonical = String.join("|",
                type.name(),
                Long.toString(amountMinor),
                reason.name(),
                referenceId == null ? "" : referenceId,
                currencyCode);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
