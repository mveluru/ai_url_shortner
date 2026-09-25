package com.urlshortener.redirect.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.common.config.AppProperties;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis implementation (design doc sections 3.2, F1, F2, F7). Every operation runs behind the shared per-dependency
 * {@code redis-cache} circuit breaker with one short full-jitter retry inside it (section 8.2.1), so a struggling Redis
 * costs at most one ~10-50ms retry before the caller falls back to MySQL, and once the breaker opens the cost is zero.
 *
 * <p>Stored as JSON strings rather than via {@code @Cacheable}: F1/F2 need code-level control over fallback and
 * invalidate-not-update semantics that declarative caching would hide (section 20.1).
 */
@Component
public class RedisUrlCache implements UrlCache {

    private static final Logger log = LoggerFactory.getLogger(RedisUrlCache.class);

    static final String KEY_PREFIX = "url:";
    static final String STALE_PREFIX = "url:stale:";
    static final String LOCK_PREFIX = "url:lock:";

    private static final DefaultRedisScript<Long> COMPARE_AND_DELETE = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            else
              return 0
            end""", Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final AppProperties.Cache config;
    private final Clock clock;

    public RedisUrlCache(StringRedisTemplate redis, ObjectMapper mapper, AppProperties props, Clock clock) {
        this.redis = redis;
        this.mapper = mapper;
        this.config = props.cache();
        this.clock = clock;
    }

    @Override
    @CircuitBreaker(name = "redis-cache")
    @Retry(name = "redis-cache")
    public Optional<CachedUrl> get(String shortCode) {
        return read(KEY_PREFIX + shortCode);
    }

    @Override
    @CircuitBreaker(name = "redis-cache")
    @Retry(name = "redis-cache")
    public Optional<CachedUrl> getStale(String shortCode) {
        return read(STALE_PREFIX + shortCode);
    }

    @Override
    @CircuitBreaker(name = "redis-cache")
    @Retry(name = "redis-cache")
    public void put(String shortCode, CachedUrl value) {
        Duration untilExpiry = value.expiresAt() == null ? null : Duration.between(clock.instant(), value.expiresAt());
        if (!value.active() || (untilExpiry != null && !untilExpiry.isPositive())) {
            return;                                   // never cache something that cannot be served
        }
        String json = write(value);
        redis.opsForValue().set(KEY_PREFIX + shortCode, json, capped(withJitter(config.ttl()), untilExpiry));
        redis.opsForValue().set(STALE_PREFIX + shortCode, json, capped(config.staleTtl(), untilExpiry));
    }

    @Override
    @CircuitBreaker(name = "redis-cache")
    @Retry(name = "redis-cache")
    public void invalidate(String shortCode) {
        redis.delete(List.of(KEY_PREFIX + shortCode, STALE_PREFIX + shortCode));
    }

    @Override
    @CircuitBreaker(name = "redis-cache")
    @Retry(name = "redis-cache")
    public boolean tryLock(String shortCode, String token) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_PREFIX + shortCode, token, config.lockTtl()));
    }

    @Override
    @CircuitBreaker(name = "redis-cache")
    @Retry(name = "redis-cache")
    public void unlock(String shortCode, String token) {
        redis.execute(COMPARE_AND_DELETE, List.of(LOCK_PREFIX + shortCode), token);
    }

    private Optional<CachedUrl> read(String key) {
        String json = redis.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(json, CachedUrl.class));
        } catch (JsonProcessingException e) {
            // A corrupt entry must behave like a miss (the DB is the source of truth), never fail the redirect.
            log.warn("Discarding unreadable cache entry {}", key, e);
            redis.delete(key);
            return Optional.empty();
        }
    }

    private String write(CachedUrl value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize cache entry", e);
        }
    }

    /** +/-10% so entries created together do not all expire (and stampede the DB) together. */
    private static Duration withJitter(Duration ttl) {
        long millis = ttl.toMillis();
        long spread = millis / 10;
        return Duration.ofMillis(millis - spread + ThreadLocalRandom.current().nextLong(2 * spread + 1));
    }

    /** A cache entry must never outlive the link's own expiry. */
    private static Duration capped(Duration ttl, Duration untilExpiry) {
        return untilExpiry != null && untilExpiry.compareTo(ttl) < 0 ? untilExpiry : ttl;
    }
}
