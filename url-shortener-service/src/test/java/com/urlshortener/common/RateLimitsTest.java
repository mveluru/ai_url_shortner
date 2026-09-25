package com.urlshortener.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.common.error.RateLimitedException;
import com.urlshortener.common.ratelimit.RateLimits;
import com.urlshortener.testsupport.Covers;
import com.urlshortener.testsupport.TestProps;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RateLimitsTest {

    private static RateLimits limits(String perMinute, String burst) {
        return new RateLimits(TestProps.with(Map.of(
                "app.rate-limit.create-per-key.requests-per-minute", perMinute,
                "app.rate-limit.create-per-key.burst", burst)), new SimpleMeterRegistry());
    }

    @Test
    @Covers({"E19"})
    @DisplayName("s11.3: 100/min burst 20 -> 20 immediate permits, then RateLimitedException with a sane Retry-After (12s refresh)")
    void designDefault() {
        RateLimits limits = new RateLimits(TestProps.defaults(), new SimpleMeterRegistry());
        for (int i = 0; i < 20; i++) {
            limits.checkCreate("key");
        }
        assertThatThrownBy(() -> limits.checkCreate("key")).isInstanceOfSatisfying(RateLimitedException.class,
                e -> assertThat(e.retryAfterSeconds()).isEqualTo(12));      // 20 permits per 12s == 100 per minute
    }

    @Test
    @Covers({"E19"})
    @DisplayName("limits are per key: one key's exhaustion does not touch another's")
    void perKeyIsolation() {
        RateLimits limits = limits("60", "2");
        limits.checkCreate("a");
        limits.checkCreate("a");
        assertThatThrownBy(() -> limits.checkCreate("a")).isInstanceOf(RateLimitedException.class);
        limits.checkCreate("b");
        limits.checkCreate("b");
    }

    @Test
    @DisplayName("scopes are independent: exhausting the create limit does not throttle redirects or the API IP limit")
    void scopesAreIndependent() {
        RateLimits limits = limits("60", "1");
        limits.checkCreate("k");
        assertThatThrownBy(() -> limits.checkCreate("k")).isInstanceOf(RateLimitedException.class);
        limits.checkRedirect("k");
        limits.checkApi("k");
    }

    @Test
    @DisplayName("a non-positive limit is rejected at startup rather than silently disabling throttling")
    void invalidConfig() {
        assertThatThrownBy(() -> limits("0", "5")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limits("60", "0")).isInstanceOf(IllegalArgumentException.class);
    }
}
