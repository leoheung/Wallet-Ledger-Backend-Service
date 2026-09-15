package com.wallet.ledger.application;

import com.wallet.ledger.application.cache.BalanceCache;
import com.wallet.ledger.application.cache.BalanceSnapshot;
import com.wallet.ledger.domain.model.Player;
import com.wallet.ledger.domain.model.Wallet;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Even if a (misbehaving) cache port throws, the balance query must still
 * return the database value and must not propagate cache errors.
 */
class WalletQueryServiceFallbackTest {

    private final WalletAppService walletAppService = mock(WalletAppService.class);
    private final BalanceCache balanceCache = mock(BalanceCache.class);
    private final WalletQueryService queryService =
            new WalletQueryService(walletAppService, balanceCache);

    @Test
    void cacheMiss_readsFromDatabase_andBackFillsCache() {
        Wallet wallet = new Wallet(new Player("Alice"), "USD");
        wallet.credit(50_000L);
        when(walletAppService.getWallet(7L)).thenReturn(wallet);
        when(balanceCache.find(7L)).thenReturn(Optional.empty());

        BalanceView view = queryService.getBalanceView(7L);

        assertThat(view.balanceMinor()).isEqualTo(50_000L);
        assertThat(view.currencyCode()).isEqualTo("USD");
        verify(balanceCache).put(eq(7L), any(BalanceSnapshot.class));
    }

    @Test
    void cacheHit_doesNotTouchDatabase() {
        when(balanceCache.find(7L)).thenReturn(Optional.of(
                new BalanceSnapshot(7L, 9L, "USD", 50_000L, 2L)));

        BalanceView view = queryService.getBalanceView(7L);

        assertThat(view.balanceMinor()).isEqualTo(50_000L);
        verify(walletAppService, never()).getWallet(7L);
    }

    @Test
    void cacheFindThrowing_fallsBackToDatabase() {
        when(balanceCache.find(7L)).thenThrow(new RuntimeException("redis exploded"));
        Wallet wallet = new Wallet(new Player("Alice"), "USD");
        wallet.credit(1_000L);
        when(walletAppService.getWallet(7L)).thenReturn(wallet);

        BalanceView view = queryService.getBalanceView(7L);

        assertThat(view.balanceMinor()).isEqualTo(1_000L);
    }

    @Test
    void cachePutThrowing_stillReturnsDatabaseValue() {
        when(balanceCache.find(7L)).thenReturn(Optional.empty());
        org.mockito.Mockito.doThrow(new RuntimeException("redis exploded"))
                .when(balanceCache).put(eq(7L), any(BalanceSnapshot.class));
        Wallet wallet = new Wallet(new Player("Alice"), "USD");
        wallet.credit(2_000L);
        when(walletAppService.getWallet(7L)).thenReturn(wallet);

        assertThat(queryService.getBalanceView(7L).balanceMinor()).isEqualTo(2_000L);
    }
}
