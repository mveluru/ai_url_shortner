package com.urlshortener.common.resilience;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.testsupport.Covers;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FullJitterIntervalFunctionTest {

    @Test
    @Covers({"F1"})
    @DisplayName("s8.2: delay = random(0, min(cap, base * 2^attempt)) - bounded by the cap and by the exponential ceiling")
    void boundedByCeilingAndCap() {
        FullJitterIntervalFunction fn = new FullJitterIntervalFunction(Duration.ofMillis(10), Duration.ofMillis(50));
        for (int i = 0; i < 5_000; i++) {
            assertThat(fn.apply(1)).isBetween(0L, 10L);          // base * 2^0
            assertThat(fn.apply(2)).isBetween(0L, 20L);          // base * 2^1
            assertThat(fn.apply(3)).isBetween(0L, 40L);
            assertThat(fn.apply(4)).isBetween(0L, 50L);          // 80 capped at 50
            assertThat(fn.apply(30)).isBetween(0L, 50L);         // no overflow at large attempt counts
        }
    }

    @Test
    @Covers({"F1"})
    @DisplayName("s8.2: it is FULL jitter - the whole range down to 0 is used, so retries from many callers de-synchronise")
    void usesTheWholeRange() {
        FullJitterIntervalFunction fn = new FullJitterIntervalFunction(Duration.ofMillis(100), Duration.ofMillis(100));
        Set<Long> seen = new HashSet<>();
        long min = Long.MAX_VALUE;
        long max = 0;
        for (int i = 0; i < 5_000; i++) {
            long v = fn.apply(1);
            seen.add(v);
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        assertThat(min).as("reaches (near) zero, unlike equal-jitter which never goes below half").isLessThan(5);
        assertThat(max).isGreaterThan(95);
        assertThat(seen.size()).as("spread over many distinct delays, not bunched").isGreaterThan(60);
    }

    @Test
    @DisplayName("a zero base or cap yields no delay rather than an error")
    void zeroBounds() {
        assertThat(new FullJitterIntervalFunction(Duration.ZERO, Duration.ofMillis(50)).apply(1)).isZero();
        assertThat(new FullJitterIntervalFunction(Duration.ofMillis(10), Duration.ZERO).apply(1)).isZero();
    }
}
