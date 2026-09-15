package com.wallet.ledger.application.cache;

import java.util.Optional;

/**
 * Port for the getBalance read cache. Contract: implementations MUST NOT
 * propagate cache-infrastructure exceptions — the database is always the
 * source of truth and a cache problem degrades to a direct DB read.
 */
public interface BalanceCache {

    Optional<BalanceSnapshot> find(Long playerId);

    void put(Long playerId, BalanceSnapshot snapshot);

    void evict(Long playerId);
}
