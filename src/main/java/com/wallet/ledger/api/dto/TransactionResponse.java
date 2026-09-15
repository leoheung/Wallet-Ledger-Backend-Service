package com.wallet.ledger.api.dto;

import com.wallet.ledger.application.Money;
import com.wallet.ledger.domain.enums.TransactionReason;
import com.wallet.ledger.domain.enums.TransactionStatus;
import com.wallet.ledger.domain.enums.TransactionType;
import com.wallet.ledger.domain.model.WalletTransaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record TransactionResponse(Long transactionId,
                                  TransactionType type,
                                  TransactionStatus status,
                                  TransactionReason reason,
                                  String referenceId,
                                  String currencyCode,
                                  BigDecimal amount,
                                  BigDecimal balanceAfter,
                                  OffsetDateTime createdAt) {

    public static TransactionResponse from(WalletTransaction txn) {
        return new TransactionResponse(
                txn.getId(),
                txn.getType(),
                txn.getStatus(),
                txn.getReason(),
                txn.getReferenceId(),
                txn.getCurrencyCode(),
                Money.toMajorUnits(txn.getAmount(), txn.getCurrencyCode()),
                Money.toMajorUnits(txn.getBalanceAfter(), txn.getCurrencyCode()),
                txn.getCreatedAt().withOffsetSameInstant(ZoneOffset.UTC));
    }
}
