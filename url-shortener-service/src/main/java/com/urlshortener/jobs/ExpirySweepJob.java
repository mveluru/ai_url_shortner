package com.urlshortener.jobs;

import com.urlshortener.common.config.AppProperties;
import com.urlshortener.redirect.cache.BestEffortCache;
import com.urlshortener.shortener.service.UrlWriter;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/**
 * Storage hygiene, NOT correctness (design doc F12): reclaims past-expiry rows so the {@code (is_active, expires_at)} index
 * stays small. The redirect and metadata paths evaluate {@code expires_at} at read time and are correct whether or not
 * this job ever runs. Idempotent, so it is safe for several instances to run it concurrently.
 */
@Component
@ConditionalOnProperty(name = "app.expiry-sweep.enabled", havingValue = "true", matchIfMissing = true)
public class ExpirySweepJob implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ExpirySweepJob.class);

    private final UrlWriter writer;
    private final BestEffortCache cache;
    private final Clock clock;
    private final int batchSize;
    private final Duration interval;

    public ExpirySweepJob(UrlWriter writer, BestEffortCache cache, Clock clock, AppProperties props) {
        this.writer = writer;
        this.cache = cache;
        this.clock = clock;
        this.batchSize = props.expirySweep().batchSize();
        this.interval = props.expirySweep().interval();
    }

    /** Scheduled from the typed {@code Duration} property, so "60s" and "PT60S" both work. */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(this::run, interval);
    }

    /** @return how many links were swept, looping until a batch comes back short */
    public int run() {
        int total = 0;
        try {
            List<String> swept;
            do {
                swept = writer.sweepExpired(clock.instant(), batchSize);
                swept.forEach(cache::invalidateQuietly);
                total += swept.size();
            } while (swept.size() == batchSize);
        } catch (RuntimeException e) {
            log.warn("Expiry sweep failed; it is only hygiene and will retry next interval: {}", e.toString());
        }
        if (total > 0) {
            log.info("Expiry sweep deactivated {} expired links", total);
        }
        return total;
    }
}
