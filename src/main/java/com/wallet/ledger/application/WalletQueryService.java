package com.wallet.ledger.application;

import com.wallet.ledger.application.cache.BalanceCache;
import com.wallet.ledger.application.cache.BalanceSnapshot;
import com.wallet.ledger.domain.model.Wallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Read side of the wallet. getBalance follows cache-aside:
 *   Redis hit  -> return snapshot
 *   Redis miss -> read PostgreSQL (source of truth), back-fill the cache
 * Cache failures never escape: the query transparently falls back to the DB.
 * Money-moving writes and reconciliation deliberately do NOT use this path.
 */
@Service
public class WalletQueryService {

    private static final Logger log = LoggerFactory.getLogger(WalletQueryService.class);

    private final WalletAppService walletAppService;
    private final BalanceCache balanceCache;

    public WalletQueryService(WalletAppService walletAppService, BalanceCache balanceCache) {
        this.walletAppService = walletAppService;
        this.balanceCache = balanceCache;
    }

    @Transactional(readOnly = true)
    public BalanceView getBalanceView(Long playerId) {
        try {
            Optional<BalanceSnapshot> cached = balanceCache.find(playerId);
            if (cached.isPresent()) {
                return BalanceView.from(cached.get());
            }
        } catch (RuntimeException e) {
            // Port contract says this never happens; stay defensive so the API
            // works even if a custom cache implementation throws.
            log.warn("Balance cache read failed for player {}; falling back to DB", playerId, e);
        }

        Wallet wallet = walletAppService.getWallet(playerId);
        BalanceView view = BalanceView.from(playerId, wallet);

        try {
            balanceCache.put(playerId, view.toSnapshot());
        } catch (RuntimeException e) {
            log.warn("Balance cache back-fill failed for player {}", playerId, e);
        }
        return view;
    }
}
