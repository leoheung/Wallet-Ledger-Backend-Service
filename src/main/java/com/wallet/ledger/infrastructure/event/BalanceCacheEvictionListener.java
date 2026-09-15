package com.wallet.ledger.infrastructure.event;

import com.wallet.ledger.application.cache.BalanceCache;
import com.wallet.ledger.domain.event.WalletBalanceChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Removes a wallet's cached balance only AFTER its balance-change transaction
 * has committed. Evicting before commit would be wasted on rollback and races
 * with concurrent readers back-filling the old value.
 *
 * Idempotent replays publish no event, so an unchanged balance never evicts.
 * A failed eviction is swallowed by the cache (TTL is the final guarantee).
 */
@Component
public class BalanceCacheEvictionListener {

    private final BalanceCache balanceCache;

    public BalanceCacheEvictionListener(BalanceCache balanceCache) {
        this.balanceCache = balanceCache;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBalanceChanged(WalletBalanceChangedEvent event) {
        balanceCache.evict(event.playerId());
    }
}
