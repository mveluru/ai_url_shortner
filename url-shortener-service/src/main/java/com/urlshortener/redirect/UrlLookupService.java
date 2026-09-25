package com.urlshortener.redirect;

import com.urlshortener.common.config.AppProperties;
import com.urlshortener.common.error.ResourceNotFoundException;
import com.urlshortener.redirect.cache.BestEffortCache;
import com.urlshortener.redirect.cache.CacheLookupResult;
import com.urlshortener.redirect.cache.CachedUrl;
import com.urlshortener.redirect.cache.UrlCache;
import com.urlshortener.shortener.service.UrlReader;
import com.urlshortener.shortener.domain.UrlView;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The Redirect Service's read path (design doc sections 3.2, 8.1, F1, F2, F7).
 *
 * <p>Hard dependencies: at least one of {Redis, MySQL}. It has <em>no</em> dependency on the queue, the analytics
 * store or the auth service - the central reliability decision of the system (section 8.1).
 *
 * <p>No {@code synchronized} anywhere on this path: virtual threads pinned to a carrier would defeat their purpose
 * (section 20.4). Coordination across instances is the Redis lock; within an instance it is not needed.
 */
@Service
public class UrlLookupService {

    private static final Logger log = LoggerFactory.getLogger(UrlLookupService.class);

    /** E18: anything that cannot be a code is "no such code" without a cache or DB round trip. */
    private static final Pattern VALID_CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{2,19}");

    private final UrlCache cache;
    private final BestEffortCache bestEffortCache;
    private final UrlReader reader;
    private final RedirectMetrics metrics;
    private final MeterRegistry meters;
    private final Clock clock;
    private final AppProperties.Cache config;

    public UrlLookupService(UrlCache cache, BestEffortCache bestEffortCache, UrlReader reader, RedirectMetrics metrics,
                            MeterRegistry meters, Clock clock, AppProperties props) {
        this.cache = cache;
        this.bestEffortCache = bestEffortCache;
        this.reader = reader;
        this.metrics = metrics;
        this.meters = meters;
        this.clock = clock;
        this.config = props.cache();
    }

    /** @throws ResourceNotFoundException for unknown, expired, deactivated or malformed codes - one uniform answer (E14-E16, E18) */
    public Resolution resolve(String shortCode) {
        long started = System.nanoTime();
        Outcome outcome = new Outcome();
        String result = "not_found";
        try {
            if (!VALID_CODE.matcher(shortCode).matches()) {
                throw new ResourceNotFoundException("No such short URL.");                // E18
            }
            Instant now = clock.instant();
            Resolution resolution = switch (lookupCache(shortCode)) {
                case CacheLookupResult.Hit hit -> {
                    outcome.cache = "hit";
                    yield serve(hit.value(), "hit", now);
                }
                case CacheLookupResult.Miss miss -> {
                    outcome.cache = "miss";
                    yield loadOnMiss(shortCode, now, outcome);
                }
                case CacheLookupResult.CircuitOpen open -> {
                    outcome.cache = "bypass";
                    log.debug("Cache bypassed ({}); serving from the database", open.reason());
                    yield loadFromDatabase(shortCode, "bypass", now, false);
                }
            };
            result = "found";
            return resolution;
        } finally {
            metrics.record(outcome.cache, result, started);
        }
    }

    /** Which cache path served (or failed to serve) the request, for the latency metric's {@code cache} tag. */
    private static final class Outcome {
        String cache = "invalid";
    }

    // ---- cache ------------------------------------------------------------------------------------------------

    private CacheLookupResult lookupCache(String shortCode) {
        try {
            return cache.get(shortCode)
                    .<CacheLookupResult>map(CacheLookupResult.Hit::new)
                    .orElseGet(CacheLookupResult.Miss::new);
        } catch (CallNotPermittedException e) {
            return new CacheLookupResult.CircuitOpen("redis-cache breaker is open");
        } catch (RuntimeException e) {
            return new CacheLookupResult.CircuitOpen("redis error: " + e.getClass().getSimpleName());
        }
    }

    /** Expiry and the active flag are evaluated NOW, on every hit, never assumed away by TTL or the sweep job (F12). */
    private Resolution serve(CachedUrl cached, String cacheOutcome, Instant now) {
        if (!cached.isServable(now)) {
            throw new ResourceNotFoundException("No such short URL.");                    // E14, E15
        }
        return new Resolution(cached.longUrl(), cacheOutcome);
    }

    // ---- miss handling: stampede guard (F7) ---------------------------------------------------------------------

    private Resolution loadOnMiss(String shortCode, Instant now, Outcome outcome) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = tryLock(shortCode, token);
        if (acquired == null) {
            outcome.cache = "bypass";
            return loadFromDatabase(shortCode, "bypass", now, false);                    // Redis failed mid-flight
        }
        if (acquired) {
            try {
                return loadFromDatabase(shortCode, "miss", now, true);
            } finally {
                unlockQuietly(shortCode, token);
            }
        }
        return awaitLockHolder(shortCode, now, outcome);
    }

    /**
     * Another request is already repopulating this key. Give it a moment, then prefer a stale copy over a DB hit, and
     * only as a last resort read the database ourselves. Bounded: nobody waits longer than {@code lockWait}.
     */
    private Resolution awaitLockHolder(String shortCode, Instant now, Outcome outcome) {
        long deadline = System.nanoTime() + config.lockWait().toNanos();
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(config.lockPollInterval().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            CacheLookupResult polled = lookupCache(shortCode);
            if (polled instanceof CacheLookupResult.Hit hit) {
                meters.counter("cache_stampede_coalesced", "via", "primary").increment();
                outcome.cache = "hit";
                return serve(hit.value(), "hit", now);
            }
            if (polled instanceof CacheLookupResult.CircuitOpen) {
                break;
            }
        }
        Optional<CachedUrl> stale = staleQuietly(shortCode);
        if (stale.isPresent()) {
            meters.counter("cache_stampede_coalesced", "via", "stale").increment();
            outcome.cache = "stale";
            return serve(stale.get(), "stale", now);
        }
        meters.counter("cache_stampede_fallthrough").increment();
        return loadFromDatabase(shortCode, "miss", now, false);
    }

    // ---- database ---------------------------------------------------------------------------------------------

    private Resolution loadFromDatabase(String shortCode, String cacheOutcome, Instant now, boolean populate) {
        Optional<UrlView> view = reader.findView(shortCode);                              // mysql-read breaker + retry (F4)
        if (view.isEmpty() || !view.get().isServable(now)) {
            throw new ResourceNotFoundException("No such short URL.");                    // E14, E15, E16
        }
        if (populate) {
            bestEffortCache.putQuietly(shortCode, CachedUrl.from(view.get()));
        }
        return new Resolution(view.get().longUrl(), cacheOutcome);
    }

    // ---- lock helpers: every Redis failure degrades to "go to the DB", never to an error ------------------------

    private Boolean tryLock(String shortCode, String token) {
        try {
            return cache.tryLock(shortCode, token);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void unlockQuietly(String shortCode, String token) {
        try {
            cache.unlock(shortCode, token);
        } catch (RuntimeException e) {
            // The lock has a short TTL, so an unreleased lock heals itself.
            log.debug("Could not release stampede lock for a code; it will expire on its own");
        }
    }

    private Optional<CachedUrl> staleQuietly(String shortCode) {
        try {
            return cache.getStale(shortCode);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
