package com.wallet.ledger.api.dto;

import com.wallet.ledger.application.ReconciliationReport;

import java.math.BigDecimal;

public record ReconciliationResponse(Long playerId,
                                     Long walletId,
                                     String currencyCode,
                                     BigDecimal currentBalance,
                                     BigDecimal recomputedBalance,
                                     BigDecimal difference,
                                     boolean consistent) {

    public static ReconciliationResponse from(ReconciliationReport report) {
        return new ReconciliationResponse(
                report.playerId(),
                report.walletId(),
                report.currencyCode(),
                report.currentBalance(),
                report.recomputedBalance(),
                report.difference(),
                report.consistent());
    }
}
