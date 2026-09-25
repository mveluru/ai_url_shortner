package com.urlshortener.common.resilience;

import io.github.resilience4j.core.IntervalFunction;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Full jitter (design doc section 8.2): {@code delay = random(0, min(cap, base * 2^attempt))}.
 *
 * <p>Chosen over plain exponential backoff because retries from many concurrent requests that timed out at the
 * same instant would otherwise bunch up and stampede a dependency that was only briefly struggling. Resilience4j's
 * {@code ofExponentialRandomBackoff} is jittered but not <em>full</em> jitter, hence this explicit function.
 */
public final class FullJitterIntervalFunction implements IntervalFunction {

    private final long baseMillis;
    private final long capMillis;

    public FullJitterIntervalFunction(Duration base, Duration cap) {
        this.baseMillis = base.toMillis();
        this.capMillis = cap.toMillis();
    }

    /** @param attempt 1 for the wait before the first retry */
    @Override
    public Long apply(Integer attempt) {
        long ceiling = Math.min(capMillis, baseMillis * (1L << Math.min(attempt - 1, 20)));
        return ceiling <= 0 ? 0L : ThreadLocalRandom.current().nextLong(ceiling + 1);
    }
}
