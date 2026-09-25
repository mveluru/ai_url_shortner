package com.urlshortener.common.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.urlshortener.common.config.AppProperties;
import com.urlshortener.common.config.AppProperties.RateLimit.Limit;
import com.urlshortener.common.error.RateLimitedException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * Rate limiting for the three scopes in design doc section 10.4/11.3: per-API-key create, per-IP redirect, and a
 * coarse per-IP limit on the management API standing in for the gateway's pre-auth limit.
 *
 * <p>A limit is "requests per minute, with a burst". Resilience4j expresses "N permits per refresh period", so
 * {@code burst} permits are granted every {@code burst / requestsPerMinute} minutes: the sustained rate is exactly
 * {@code requestsPerMinute}, and no window can ever start with more than {@code burst}. Limits are configuration,
 * never code (section 11.3).
 *
 * <p>Known limitation (section 20.8): limiters are per-instance, so the effective global limit scales with the
 * instance count until a shared (Redis-backed) limiter replaces this.
 */
@Component
public class RateLimits {

    private final MeterRegistry meters;
    private final Scope createPerKey;
    private final Scope redirectPerIp;
    private final Scope apiPerIp;

    public RateLimits(AppProperties props, MeterRegistry meters) {
        this.meters = meters;
        this.createPerKey = new Scope("create_per_key", props.rateLimit().createPerKey());
        this.redirectPerIp = new Scope("redirect_per_ip", props.rateLimit().redirectPerIp());
        this.apiPerIp = new Scope("api_per_ip", props.rateLimit().apiPerIp());
    }

    public void checkCreate(String apiKeyId) {
        check(createPerKey, apiKeyId);
    }

    public void checkRedirect(String clientIp) {
        check(redirectPerIp, clientIp);
    }

    public void checkApi(String clientIp) {
        check(apiPerIp, clientIp);
    }

    private void check(Scope scope, String key) {
        if (!scope.limiterFor(key).acquirePermission()) {
            meters.counter("rate_limited", "limiter", scope.name).increment();
            throw new RateLimitedException("Rate limit exceeded. Retry after the indicated delay.", scope.retryAfterSeconds);
        }
    }

    private static final class Scope {
        final String name;
        final long retryAfterSeconds;
        final RateLimiterConfig config;
        /** Bounded and expiring, so per-IP limiters cannot grow without limit under a spoofed-source flood. */
        final Cache<String, RateLimiter> limiters = Caffeine.newBuilder()
                .expireAfterAccess(Duration.ofMinutes(10)).maximumSize(200_000).build();

        Scope(String name, Limit limit) {
            if (limit.requestsPerMinute() <= 0 || limit.burst() <= 0) {
                throw new IllegalArgumentException("rate limit '" + name + "' must be positive");
            }
            this.name = name;
            long refreshNanos = Duration.ofMinutes(1).toNanos() * limit.burst() / limit.requestsPerMinute();
            Duration refresh = Duration.ofNanos(Math.max(refreshNanos, 1));
            this.retryAfterSeconds = Math.max(1, (long) Math.ceil(refresh.toMillis() / 1000.0));
            this.config = RateLimiterConfig.custom()
                    .limitForPeriod(limit.burst())
                    .limitRefreshPeriod(refresh)
                    .timeoutDuration(Duration.ZERO)          // reject immediately, never queue a request
                    .build();
        }

        RateLimiter limiterFor(String key) {
            return limiters.get(key, k -> RateLimiter.of(name + ":" + k, config));
        }
    }
}
