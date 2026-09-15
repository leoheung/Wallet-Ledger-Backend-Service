package com.wallet.ledger.application;

import java.math.BigDecimal;

/** Result of comparing the cached wallet balance against the ledger sum. */
public record ReconciliationReport(
        Long playerId,
        Long walletId,
        String currencyCode,
        BigDecimal currentBalance,
        BigDecimal recomputedBalance,
        BigDecimal difference,
        boolean consistent) {
}
