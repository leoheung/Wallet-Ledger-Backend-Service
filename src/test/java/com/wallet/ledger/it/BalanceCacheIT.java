package com.wallet.ledger.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.ledger.application.BalanceView;
import com.wallet.ledger.domain.exception.BusinessException;
import com.wallet.ledger.domain.exception.ErrorCode;
import com.wallet.ledger.domain.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Real PostgreSQL + real Redis cache-aside behaviour: lazy fill on miss,
 * post-commit eviction on writes, no eviction on idempotent replays, TTL set,
 * and no caching of 404s.
 */
class BalanceCacheIT extends AbstractPgIT {

    @SpyBean
    private WalletRepository walletRepositorySpy;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void firstBalanceRead_missesBackFillsWithTtl_secondReadHitsCache() throws Exception {
        Long playerId = newPlayer();
        walletAppService.credit(playerId, "USD", new BigDecimal("100.00"),
                com.wallet.ledger.domain.enums.TransactionReason.MISSION_REWARD, null, "seed");
        // The credit evicted; start the read experiment from a clean slate.
        assertThat(redis.hasKey(balanceKey(playerId))).isFalse();

        clearInvocations(walletRepositorySpy);

        BalanceView first = walletQueryService.getBalanceView(playerId);
        assertThat(first.balanceMinor()).isEqualTo(10_000L);

        // Cache entry exists with the expected payload and a bounded TTL.
        String json = redis.opsForValue().get(balanceKey(playerId));
        assertThat(json).isNotNull();
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.get("balanceMinor").asLong()).isEqualTo(10_000L);
        assertThat(node.get("currencyCode").asText()).isEqualTo("USD");
        Long ttlSeconds = redis.getExpire(balanceKey(playerId));
        assertThat(ttlSeconds).isNotNull();
        assertThat(ttlSeconds).isBetween(1L, 30L);

        BalanceView second = walletQueryService.getBalanceView(playerId);
        assertThat(second.balanceMinor()).isEqualTo(10_000L);

        // Exactly one DB read for both GETs: the second was served from Redis.
        verify(walletRepositorySpy, times(1)).findByPlayerId(playerId);
    }

    @Test
    void committedWrite_evictsCache_nextReadSeesNewBalance() {
        Long playerId = newPlayer();
        walletAppService.credit(playerId, "USD", new BigDecimal("100.00"),
                com.wallet.ledger.domain.enums.TransactionReason.MISSION_REWARD, null, "seed");
        walletQueryService.getBalanceView(playerId); // warm cache
        assertThat(redis.hasKey(balanceKey(playerId))).isTrue();

        walletAppService.credit(playerId, "USD", new BigDecimal("25.00"),
                com.wallet.ledger.domain.enums.TransactionReason.MISSION_REWARD, null, "raise");

        // AFTER_COMMIT eviction removed the old value.
        assertThat(redis.hasKey(balanceKey(playerId))).isFalse();

        BalanceView view = walletQueryService.getBalanceView(playerId);
        assertThat(view.balanceMinor()).isEqualTo(12_500L);
        assertThat(redis.hasKey(balanceKey(playerId))).isTrue();
    }

    @Test
    void idempotentReplay_doesNotEvict_andBalanceUnchanged() {
        Long playerId = newPlayer();
        walletAppService.credit(playerId, "USD", new BigDecimal("100.00"),
                com.wallet.ledger.domain.enums.TransactionReason.MISSION_REWARD, null, "once");
        walletQueryService.getBalanceView(playerId); // warm cache
        assertThat(redis.hasKey(balanceKey(playerId))).isTrue();

        // Replay: same key + same body returns the original txn, publishes no event.
        var first = walletAppService.credit(playerId, "USD", new BigDecimal("100.00"),
                com.wallet.ledger.domain.enums.TransactionReason.MISSION_REWARD, null, "once");
        var replay = walletAppService.credit(playerId, "USD", new BigDecimal("100.00"),
                com.wallet.ledger.domain.enums.TransactionReason.MISSION_REWARD, null, "once");
        assertThat(first.transaction().getId()).isEqualTo(replay.transaction().getId());
        assertThat(replay.replayed()).isTrue();

        assertThat(redis.hasKey(balanceKey(playerId)))
                .as("replay must not evict an unchanged balance")
                .isTrue();
        assertThat(walletQueryService.getBalanceView(playerId).balanceMinor()).isEqualTo(10_000L);
    }

    @Test
    void missingPlayer_returns404_andIsNotCached() {
        assertThatThrownBy(() -> walletQueryService.getBalanceView(999_999L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.PLAYER_NOT_FOUND);

        assertThat(redis.hasKey(balanceKey(999_999L))).isFalse();
    }
}
