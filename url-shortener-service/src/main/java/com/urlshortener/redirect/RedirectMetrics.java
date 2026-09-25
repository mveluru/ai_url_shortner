package com.urlshortener.redirect;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Redirect-path metrics (design doc section 12.1): {@code redirect_latency_ms} tagged by cache outcome and
 * {@code redirect_cache_hit_rate}. Recorded as a plain-millisecond distribution so the exported names match the design
 * exactly (a Micrometer Timer would export {@code *_seconds}).
 */
@Component
public class RedirectMetrics {

    private final MeterRegistry meters;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public RedirectMetrics(MeterRegistry meters) {
        this.meters = meters;
        Gauge.builder("redirect_cache_hit_rate", this, RedirectMetrics::hitRate)
                .description("cache hits / (hits + misses); breaker-open bypasses are excluded").register(meters);
    }

    /** @param cache hit | miss | stale | bypass | invalid */
    public void record(String cache, String result, long startedNanos) {
        switch (cache) {
            case "hit" -> hits.incrementAndGet();
            case "miss", "stale" -> misses.incrementAndGet();
            default -> { }
        }
        DistributionSummary.builder("redirect_latency_ms")
                .tag("cache", cache).tag("result", result)
                .serviceLevelObjectives(5, 10, 25, 50, 100, 150, 250, 500, 1000)
                .register(meters)
                .record((System.nanoTime() - startedNanos) / 1_000_000.0);
    }

    double hitRate() {
        long h = hits.get();
        long total = h + misses.get();
        return total == 0 ? 1.0 : (double) h / total;
    }
}
