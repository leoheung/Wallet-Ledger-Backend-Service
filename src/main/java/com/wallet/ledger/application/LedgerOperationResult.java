package com.wallet.ledger.application;

import com.wallet.ledger.domain.model.WalletTransaction;

/**
 * Outcome of a credit/debit request. {@code replayed} is true when an
 * idempotent retry was detected and the original transaction was returned
 * without applying a second balance change.
 */
public record LedgerOperationResult(WalletTransaction transaction, boolean replayed) {
}
