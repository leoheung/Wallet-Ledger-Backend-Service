package com.wallet.ledger.it;

import com.wallet.ledger.application.LedgerOperationResult;
import com.wallet.ledger.domain.enums.TransactionReason;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * After a transfer (two wallets change in one commit) and after a refund,
 * every affected player's Redis cache entry must have been evicted, so the
 * next balance read returns database truth.
 */
class TransferRefundCacheIT extends AbstractPgIT {

    @Test
    void transfer_evictsBothCaches_andReadsReturnNewBalances() {
        Long alice = newPlayer();
        Long bob = newPlayer();
        walletAppService.credit(alice, "USD", new BigDecimal("100.00"),
                TransactionReason.ADMIN_ADJUSTMENT, null, "seed-a");

        // Warm both caches to pre-transfer balances.
        assertThat(walletQueryService.getBalanceView(alice).balanceMinor()).isEqualTo(10_000L);
        assertThat(walletQueryService.getBalanceView(bob).balanceMinor()).isZero();
        assertThat(redis.hasKey(balanceKey(alice))).isTrue();
        assertThat(redis.hasKey(balanceKey(bob))).isTrue();

        walletAppService.transfer(alice, bob, "USD", new BigDecimal("30.00"),
                null, "cache-transfer");

        // Both AFTER_COMMIT evictions fired.
        assertThat(redis.hasKey(balanceKey(alice))).isFalse();
        assertThat(redis.hasKey(balanceKey(bob))).isFalse();

        assertThat(walletQueryService.getBalanceView(alice).balanceMinor()).isEqualTo(7_000L);
        assertThat(walletQueryService.getBalanceView(bob).balanceMinor()).isEqualTo(3_000L);
        assertThat(redis.hasKey(balanceKey(alice))).isTrue();
        assertThat(redis.hasKey(balanceKey(bob))).isTrue();
    }

    @Test
    void refund_evictsCache_andReadReturnsReversedBalance() {
        Long playerId = newPlayer();
        LedgerOperationResult reward = walletAppService.credit(playerId, "USD",
                new BigDecimal("50.00"), TransactionReason.MISSION_REWARD, null, "cache-reward");
        assertThat(walletQueryService.getBalanceView(playerId).balanceMinor()).isEqualTo(5_000L);
        assertThat(redis.hasKey(balanceKey(playerId))).isTrue();

        walletAppService.refund(playerId, reward.transaction().getId(), "cache-refund");

        assertThat(redis.hasKey(balanceKey(playerId))).isFalse();
        assertThat(walletQueryService.getBalanceView(playerId).balanceMinor()).isZero();
        assertThat(redis.hasKey(balanceKey(playerId))).isTrue();
    }
}
