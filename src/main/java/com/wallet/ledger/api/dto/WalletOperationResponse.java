package com.wallet.ledger.api.dto;

import com.wallet.ledger.application.LedgerOperationResult;
import com.wallet.ledger.application.Money;
import com.wallet.ledger.domain.enums.TransactionReason;
import com.wallet.ledger.domain.enums.TransactionStatus;
import com.wallet.ledger.domain.enums.TransactionType;
import com.wallet.ledger.domain.model.WalletTransaction;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record WalletOperationResponse(Long transactionId,
                                      Long walletId,
                                      TransactionType type,
                                      TransactionStatus status,
                                      TransactionReason reason,
                                      String referenceId,
                                      String currencyCode,
                                      BigDecimal amount,
                                      BigDecimal balanceAfter,
                                      boolean replayed,
                                      OffsetDateTime createdAt,
                                      Long relatedTransactionId,
                                      Long originalTransactionId) {

    public static WalletOperationResponse from(LedgerOperationResult result) {
        WalletTransaction txn = result.transaction();
        return new WalletOperationResponse(
                txn.getId(),
                txn.getWallet().getId(),
                txn.getType(),
                txn.getStatus(),
                txn.getReason(),
                txn.getReferenceId(),
                txn.getCurrencyCode(),
                Money.toMajorUnits(txn.getAmount(), txn.getCurrencyCode()),
                Money.toMajorUnits(txn.getBalanceAfter(), txn.getCurrencyCode()),
                result.replayed(),
                txn.getCreatedAt().withOffsetSameInstant(ZoneOffset.UTC),
                txn.getRelatedTransactionId(),
                txn.getOriginalTransactionId());
    }
}
