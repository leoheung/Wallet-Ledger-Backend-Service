package com.wallet.ledger.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.ledger.application.cache.BalanceSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure Mockito tests (no Spring container, no Redis): prove that any Redis
 * failure is contained inside the cache and never reaches balance callers.
 */
class RedisBalanceCacheFallbackTest {

    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private SimpleMeterRegistry meterRegistry;
    private RedisBalanceCache cache;

    private static final BalanceSnapshot SNAPSHOT =
            new BalanceSnapshot(7L, 9L, "USD", 12_500L, 3L);

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        meterRegistry = new SimpleMeterRegistry();
        cache = new RedisBalanceCache(redis, new ObjectMapper(), meterRegistry, 30);
    }

    @Test
    void find_hit_returnsSnapshot_andCountsHit() throws Exception {
        String json = new ObjectMapper().writeValueAsString(SNAPSHOT);
        when(ops.get("wallet:balance:7")).thenReturn(json);

        Optional<BalanceSnapshot> result = cache.find(7L);

        assertThat(result).contains(SNAPSHOT);
        assertThat(counter("hit")).isEqualTo(1);
    }

    @Test
    void find_miss_countsMiss() {
        when(ops.get(anyString())).thenReturn(null);
        assertThat(cache.find(7L)).isEmpty();
        assertThat(counter("miss")).isEqualTo(1);
    }

    @Test
    void find_whenRedisDown_returnsEmptyAndCountsFallback() {
        when(ops.get(anyString())).thenThrow(
                new RedisConnectionFailureException("connection refused", null));

        assertThatCode(() -> assertThat(cache.find(7L)).isEmpty()).doesNotThrowAnyException();
        assertThat(counter("fallback_error")).isEqualTo(1);
    }

    @Test
    void put_writesJsonWithTtl() {
        cache.put(7L, SNAPSHOT);
        verify(ops).set(eq("wallet:balance:7"), anyString(), eq(Duration.ofSeconds(30)));
    }

    @Test
    void put_whenRedisDown_isSwallowed() {
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down", null))
                .when(ops).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> cache.put(7L, SNAPSHOT)).doesNotThrowAnyException();
        assertThat(counter("fallback_error")).isEqualTo(1);
    }

    @Test
    void evict_deletesKey() {
        cache.evict(7L);
        verify(redis).delete("wallet:balance:7");
        assertThat(counter("evict")).isEqualTo(1);
    }

    @Test
    void evict_whenRedisDown_isSwallowed_andReportsEvictFailed() {
        org.mockito.Mockito.doThrow(new RedisConnectionFailureException("down", null))
                .when(redis).delete(anyString());

        assertThatCode(() -> cache.evict(7L)).doesNotThrowAnyException();
        assertThat(counter("evict_failed")).isEqualTo(1);
        verify(redis).delete("wallet:balance:7");
    }

    @Test
    void corruptedJson_isTreatedAsMiss_neverThrown() {
        when(ops.get(anyString())).thenReturn("not-json{");
        assertThat(cache.find(7L)).isEmpty();
        assertThat(counter("fallback_error")).isEqualTo(1);
        verify(redis, never()).delete(anyString());
    }

    private double counter(String result) {
        return meterRegistry.find("wallet.balance.cache")
                .tag("result", result)
                .counter()
                .count();
    }
}
