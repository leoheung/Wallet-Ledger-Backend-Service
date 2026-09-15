package com.wallet.ledger.application;

import com.wallet.ledger.domain.enums.EntryDirection;
import com.wallet.ledger.domain.model.Wallet;
import com.wallet.ledger.domain.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Independently recomputes a wallet balance from the append-only ledger and
 * compares it with the cached balance on the wallet row. Read-only; it never
 * mutates data, it only reports drift.
 */
@Service
public class ReconciliationService {

    private final WalletAppService wallets;
    private final LedgerEntryRepository ledgerEntries;

    public ReconciliationService(WalletAppService wallets, LedgerEntryRepository ledgerEntries) {
        this.wallets = wallets;
        this.ledgerEntries = ledgerEntries;
    }

    @Transactional(readOnly = true)
    public ReconciliationReport reconcile(Long playerId) {
        Wallet wallet = wallets.getWallet(playerId);
        long recomputed = ledgerEntries.sumSignedAmountByWalletId(wallet.getId(), EntryDirection.CREDIT);
        long difference = wallet.getBalance() - recomputed;

        BigDecimal current = Money.toMajorUnits(wallet.getBalance(), wallet.getCurrencyCode());
        BigDecimal recomputedMajor = Money.toMajorUnits(recomputed, wallet.getCurrencyCode());
        BigDecimal differenceMajor = Money.toMajorUnits(Math.abs(difference), wallet.getCurrencyCode());

        return new ReconciliationReport(
                playerId,
                wallet.getId(),
                wallet.getCurrencyCode(),
                current,
                recomputedMajor,
                differenceMajor,
                difference == 0);
    }
}
