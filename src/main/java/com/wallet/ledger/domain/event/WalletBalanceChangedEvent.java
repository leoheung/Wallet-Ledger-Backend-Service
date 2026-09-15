package com.wallet.ledger.domain.event;

import com.wallet.ledger.domain.enums.TransactionReason;
import com.wallet.ledger.domain.enums.TransactionType;

import java.time.OffsetDateTime;

/**
 * Published after a wallet balance change has been committed.
 * Listeners only fire AFTER_COMMIT, so downstream consumers never observe a
 * change that later rolls back.
 */
public record WalletBalanceChangedEvent(
        Long playerId,
        Long walletId,
        Long transactionId,
        TransactionType type,
        long amountMinor,
        long balanceAfterMinor,
        String currencyCode,
        TransactionReason reason,
        String idempotencyKey,
        OffsetDateTime occurredAt) {
}
