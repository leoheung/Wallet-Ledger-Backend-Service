package com.wallet.ledger.application.cache;

/**
 * Immutable serialisable shape of a wallet balance stored in the cache.
 * Deliberately a plain record (not a JPA entity): it carries no lazy
 * associations and survives JSON serialisation.
 */
public record BalanceSnapshot(Long playerId,
                              Long walletId,
                              String currencyCode,
                              long balanceMinor,
                              long version) {
}
