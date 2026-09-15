package com.wallet.ledger.infrastructure.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wallet.ledger.application.cache.BalanceCache;
import com.wallet.ledger.application.cache.BalanceSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis cache-aside implementation.
 *
 * Every Redis call is isolated: an unavailable/slow Redis must never break the
 * balance API — failures are counted (wallet.balance.cache{result=fallback_error})
 * and the caller transparently uses PostgreSQL. The entry TTL is independent of
 * post-commit eviction, so a lost DEL can at most leave a stale value until
 * expiry (default 30s).
 */
@Component
@ConditionalOnProperty(name = "wallet.cache.enabled", havingValue = "true", matchIfMissing = true)
public class RedisBalanceCache implements BalanceCache {

    private static final Logger log = LoggerFactory.getLogger(RedisBalanceCache.class);
    private static final String KEY_PREFIX = "wallet:balance:";
    private static final String METRIC_NAME = "wallet.balance.cache";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Duration ttl;

    public RedisBalanceCache(StringRedisTemplate redis,
                             ObjectMapper objectMapper,
                             MeterRegistry meterRegistry,
                             @Value("${wallet.cache.balance-ttl-seconds:30}") long ttlSeconds) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    static String key(Long playerId) {
        return KEY_PREFIX + playerId;
    }

    @Override
    public Optional<BalanceSnapshot> find(Long playerId) {
        String key = key(playerId);
        try {
            String json = redis.opsForValue().get(key);
            if (json == null) {
                count("miss");
                return Optional.empty();
            }
            count("hit");
            return Optional.of(objectMapper.readValue(json, BalanceSnapshot.class));
        } catch (Exception e) {
            // Covers RedisConnectionFailureException and bad payloads: treat both
            // as a miss and back-fill a fresh snapshot from the DB.
            log.warn("Balance cache lookup failed for player {}; using DB", playerId, e);
            count("fallback_error");
            return Optional.empty();
        }
    }

    @Override
    public void put(Long playerId, BalanceSnapshot snapshot) {
        try {
            redis.opsForValue().set(key(playerId), objectMapper.writeValueAsString(snapshot), ttl);
        } catch (Exception e) {
            log.warn("Balance cache back-fill failed for player {}", playerId, e);
            count("fallback_error");
        }
    }

    @Override
    public void evict(Long playerId) {
        try {
            redis.delete(key(playerId));
            count("evict");
        } catch (Exception e) {
            // The entry TTL guarantees eventual consistency even when DEL is lost.
            log.error("Balance cache eviction failed for player {}; stale value may be served "
                    + "until TTL ({}) expires", playerId, ttl, e);
            count("evict_failed");
            count("fallback_error");
        }
    }

    private void count(String result) {
        meterRegistry.counter(METRIC_NAME, Tags.of("result", result)).increment();
    }
}
