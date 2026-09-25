package com.urlshortener.redirect.cache;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cache writes made by the Shortener after the database commit. A failure here must never fail or roll back the
 * request: a missed write-through is not a correctness problem (cache-aside repopulates on the next miss).
 *
 * <p>A missed <em>invalidation</em> is different - it would leave a deleted link redirecting (F2). So a failed
 * invalidation is remembered and retried every few seconds until Redis accepts it, which shrinks the exposure window from
 * "the entry's TTL" to "how long Redis was unreachable". The queue is per instance and in memory, so it is a mitigation, not
 * a guarantee: the TTL remains the hard upper bound and {@code cache_invalidation_pending} makes any backlog alertable.
 */
@Component
public class BestEffortCache {

    private static final Logger log = LoggerFactory.getLogger(BestEffortCache.class);
    private static final int MAX_PENDING = 10_000;

    private final UrlCache cache;
    private final MeterRegistry meters;
    private final Set<String> pendingInvalidations = ConcurrentHashMap.newKeySet();

    public BestEffortCache(UrlCache cache, MeterRegistry meters) {
        this.cache = cache;
        this.meters = meters;
        meters.counter("cache_write_failed", "op", "put");
        meters.counter("cache_write_failed", "op", "invalidate");
        Gauge.builder("cache_invalidation_pending", pendingInvalidations, Set::size)
                .description("codes whose cache invalidation failed and is being retried").register(meters);
    }

    public void putQuietly(String shortCode, CachedUrl value) {
        try {
            cache.put(shortCode, value);
        } catch (RuntimeException e) {
            meters.counter("cache_write_failed", "op", "put").increment();
            log.warn("Cache write-through skipped for a new code (cache unavailable): {}", e.toString());
        }
    }

    /** @return true if the key was invalidated now, false if Redis could not be reached (it will be retried) */
    public boolean invalidateQuietly(String shortCode) {
        try {
            cache.invalidate(shortCode);
            pendingInvalidations.remove(shortCode);
            return true;
        } catch (RuntimeException e) {
            meters.counter("cache_write_failed", "op", "invalidate").increment();
            if (pendingInvalidations.size() < MAX_PENDING) {
                pendingInvalidations.add(shortCode);
            }
            log.error("Cache invalidation FAILED for a mutated code; queued for retry, and a stale entry may be served "
                    + "until Redis is reachable or the entry's TTL elapses: {}", e.toString());
            return false;
        }
    }

    /** Re-attempts failed invalidations. Cheap when idle; stops at the first failure so it does not hammer a down Redis. */
    @Scheduled(fixedDelay = 5_000)
    void retryPendingInvalidations() {
        for (Iterator<String> it = pendingInvalidations.iterator(); it.hasNext(); ) {
            String code = it.next();
            try {
                cache.invalidate(code);
                it.remove();
            } catch (RuntimeException e) {
                return;
            }
        }
    }

    int pendingCount() {
        return pendingInvalidations.size();
    }
}
