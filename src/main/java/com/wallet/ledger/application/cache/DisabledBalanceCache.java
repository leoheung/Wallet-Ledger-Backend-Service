package com.wallet.ledger.application.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * No-op implementation selected when wallet.cache.enabled=false (H2 unit
 * tests, zero-install h2 profile). Every balance query goes straight to the
 * database and no Redis connection is ever made.
 */
@Component
@ConditionalOnProperty(name = "wallet.cache.enabled", havingValue = "false")
public class DisabledBalanceCache implements BalanceCache {

    @Override
    public Optional<BalanceSnapshot> find(Long playerId) {
        return Optional.empty();
    }

    @Override
    public void put(Long playerId, BalanceSnapshot snapshot) {
        // no-op
    }

    @Override
    public void evict(Long playerId) {
        // no-op
    }
}
