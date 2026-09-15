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
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.List;
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

    /**
     * Move currency between two players in ONE transaction. Both wallet rows are
     * locked in ascending id order so concurrent transfers in opposite
     * directions can never deadlock. The sender gets a DEBIT leg and the
     * receiver a CREDIT leg, cross-linked; balance is conserved.
     */
    @Transactional
    public TransferResult transfer(Long fromPlayerId,
                                   Long toPlayerId,
                                   String currencyCode,
                                   BigDecimal amount,
                                   String referenceId,
                                   String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        if (fromPlayerId.equals(toPlayerId)) {
            throw new BusinessException(ErrorCode.TRANSFER_SAME_PLAYER);
        }
        long amountMinor = Money.toMinorUnits(amount, currencyCode);
        requirePlayer(fromPlayerId);
        requirePlayer(toPlayerId);

        // Load AND lock both wallet rows in one query (ascending wallet id,
        // regardless of transfer direction) so bidirectional concurrent
        // transfers always acquire locks in the same global order. The rows
        // must not be pre-loaded unlocked: stale @Version instances in the
        // persistence context would make the flush fail under contention.
        List<Wallet> locked = wallets.findByPlayerIdsForUpdateOrdered(
                List.of(fromPlayerId, toPlayerId));
        if (locked.size() != 2) {
            throw new BusinessException(ErrorCode.WALLET_NOT_FOUND);
        }
        Wallet sender = locked.stream()
                .filter(w -> w.getPlayer().getId().equals(fromPlayerId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.WALLET_NOT_FOUND));
        Wallet receiver = locked.stream()
                .filter(w -> w.getPlayer().getId().equals(toPlayerId))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.WALLET_NOT_FOUND));

        if (!sender.getCurrencyCode().equals(currencyCode)
                || !receiver.getCurrencyCode().equals(currencyCode)) {
            throw new BusinessException(ErrorCode.CURRENCY_MISMATCH,
                    "Both wallets must hold the transfer currency %s".formatted(currencyCode));
        }

        String requestHash = fingerprint("TRANSFER",
                Long.toString(amountMinor), currencyCode,
                referenceId == null ? "" : referenceId,
                "from:" + fromPlayerId, "to:" + toPlayerId);

        Optional<WalletTransaction> existingSenderLeg =
                transactions.findByWalletIdAndIdempotencyKey(sender.getId(), idempotencyKey);
        if (existingSenderLeg.isPresent()) {
            WalletTransaction previous = existingSenderLeg.get();
            if (!previous.getRequestHash().equals(requestHash)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            WalletTransaction previousCounterparty = previous.getRelatedTransactionId() == null
                    ? null
                    : transactions.findById(previous.getRelatedTransactionId()).orElse(null);
            return new TransferResult(previous, previousCounterparty, true);
        }

        // Throws INSUFFICIENT_FUNDS before anything is written.
        sender.debit(amountMinor);
        receiver.credit(amountMinor);

        // Persist the receiver leg first to learn its id, then the sender leg
        // referencing it, and finally back-link the receiver leg.
        WalletTransaction receiverLeg = recordLeg(receiver, TransactionType.CREDIT,
                TransactionReason.PLAYER_TRANSFER, referenceId, amountMinor, currencyCode,
                idempotencyKey, requestHash, receiver.getBalance(), null, null);
        WalletTransaction senderLeg = recordLeg(sender, TransactionType.DEBIT,
                TransactionReason.PLAYER_TRANSFER, referenceId, amountMinor, currencyCode,
                idempotencyKey, requestHash, sender.getBalance(),
                receiverLeg.getId(), null);
        receiverLeg.linkCounterparty(senderLeg.getId());
        transactions.save(receiverLeg);

        publishChange(toPlayerId, receiver, receiverLeg);
        publishChange(fromPlayerId, sender, senderLeg);

        return new TransferResult(senderLeg, receiverLeg, false);
    }

    /**
     * Fully reverse a previously completed credit/debit transaction. The
     * original row stays immutable; the reversal is a new transaction (reason
     * REFUND) linked via originalTransactionId. A transaction can be refunded
     * at most once (service check + DB unique index). Refunding a CREDIT
     * debits the wallet and can fail with INSUFFICIENT_FUNDS.
     */
    @Transactional
    public LedgerOperationResult refund(Long playerId,
                                        Long originalTransactionId,
                                        String idempotencyKey) {
        requireIdempotencyKey(idempotencyKey);
        requirePlayer(playerId);

        Wallet wallet = wallets.findByPlayerIdForUpdate(playerId)
                .orElseThrow(() -> new BusinessException(ErrorCode.WALLET_NOT_FOUND));

        WalletTransaction original = transactions
                .findByIdAndWalletId(originalTransactionId, wallet.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND,
                        "Transaction %d does not belong to player %d"
                                .formatted(originalTransactionId, playerId)));

        String requestHash = fingerprint("REFUND",
                Long.toString(original.getId()),
                Long.toString(original.getAmount()),
                original.getCurrencyCode(),
                original.getType().name());

        Optional<WalletTransaction> existing =
                transactions.findByWalletIdAndIdempotencyKey(wallet.getId(), idempotencyKey);
        if (existing.isPresent()) {
            WalletTransaction previous = existing.get();
            if (!previous.getRequestHash().equals(requestHash)) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return new LedgerOperationResult(previous, true);
        }

        // Only plain credit/debit transactions are refundable: refunds of
        // transfers should be expressed as a new transfer in the other
        // direction, and refunds are not chainable.
        if (original.getOriginalTransactionId() != null
                || original.getRelatedTransactionId() != null) {
            throw new BusinessException(ErrorCode.TRANSACTION_NOT_REFUNDABLE);
        }
        if (transactions.existsByOriginalTransactionId(original.getId())) {
            throw new BusinessException(ErrorCode.TRANSACTION_ALREADY_REFUNDED);
        }

        TransactionType reversalType = original.getType() == TransactionType.CREDIT
                ? TransactionType.DEBIT
                : TransactionType.CREDIT;
        long amountMinor = original.getAmount();
        String currencyCode = original.getCurrencyCode();

        // INSUFFICIENT_FUNDS (when reversing a CREDIT) is thrown before writes.
        if (reversalType == TransactionType.CREDIT) {
            wallet.credit(amountMinor);
        } else {
            wallet.debit(amountMinor);
        }

        try {
            WalletTransaction reversal = recordLeg(wallet, reversalType,
                    TransactionReason.REFUND, null, amountMinor, currencyCode,
                    idempotencyKey, requestHash, wallet.getBalance(),
                    null, original.getId());
            publishChange(playerId, wallet, reversal);
            return new LedgerOperationResult(reversal, false);
        } catch (DataIntegrityViolationException e) {
            // Lost a concurrent refund race; the unique index rejected the 2nd row.
            throw new BusinessException(ErrorCode.TRANSACTION_ALREADY_REFUNDED);
        }
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

        String requestHash = fingerprint(type.name(),
                Long.toString(amountMinor), reason.name(),
                referenceId == null ? "" : referenceId, currencyCode);

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

        WalletTransaction txn = recordLeg(wallet, type, reason, referenceId, amountMinor,
                currencyCode, idempotencyKey, requestHash, wallet.getBalance(), null, null);
        publishChange(playerId, wallet, txn);
        return new LedgerOperationResult(txn, false);
    }

    /** Persist one transaction row and its immutable ledger entry. */
    private WalletTransaction recordLeg(Wallet wallet,
                                        TransactionType type,
                                        TransactionReason reason,
                                        String referenceId,
                                        long amountMinor,
                                        String currencyCode,
                                        String idempotencyKey,
                                        String requestHash,
                                        long balanceAfter,
                                        Long relatedTransactionId,
                                        Long originalTransactionId) {
        WalletTransaction txn = new WalletTransaction(
                wallet, type, TransactionStatus.COMPLETED, reason, referenceId,
                amountMinor, currencyCode, idempotencyKey, requestHash, balanceAfter,
                relatedTransactionId, originalTransactionId);
        transactions.save(txn); // IDENTITY id is populated immediately on insert

        ledgerEntries.save(new LedgerEntry(
                txn, wallet,
                type == TransactionType.CREDIT ? EntryDirection.CREDIT : EntryDirection.DEBIT,
                amountMinor, balanceAfter));
        return txn;
    }

    private void publishChange(Long playerId, Wallet wallet, WalletTransaction txn) {
        eventPublisher.publishEvent(new WalletBalanceChangedEvent(
                playerId, wallet.getId(), txn.getId(), txn.getType(), txn.getAmount(),
                txn.getBalanceAfter(), txn.getCurrencyCode(), txn.getReason(),
                txn.getIdempotencyKey(), OffsetDateTime.now()));
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

    private String fingerprint(String... canonicalParts) {
        String canonical = String.join("|", canonicalParts);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
