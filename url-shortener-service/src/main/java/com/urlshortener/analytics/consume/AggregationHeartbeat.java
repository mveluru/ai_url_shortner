package com.urlshortener.analytics.consume;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Records "aggregation has run" in {@code analytics_state}, surfaced to clients as {@code updatedAt} so eventual
 * consistency is disclosed instead of hidden (design doc sections 5.4, 6.5).
 *
 * <p>Writes are throttled per instance to at most one per second. The alternative - updating one hot row inside every
 * event's transaction - would serialise every consumer on that row's lock.
 */
@Component
public class AggregationHeartbeat {

    private static final long MIN_INTERVAL_NANOS = 1_000_000_000L;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AtomicLong lastWrite = new AtomicLong(System.nanoTime() - 2 * MIN_INTERVAL_NANOS);

    public AggregationHeartbeat(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Called after an event is aggregated and committed. */
    public void aggregated() {
        long now = System.nanoTime();
        long previous = lastWrite.get();
        if (now - previous >= MIN_INTERVAL_NANOS && lastWrite.compareAndSet(previous, now)) {
            write();
        }
    }

    /** Called when the queue is empty: aggregation is fully caught up as of now, even if no click arrived recently. */
    public void caughtUp() {
        lastWrite.set(System.nanoTime());
        write();
    }

    private void write() {
        jdbc.update("UPDATE analytics_state SET last_aggregation_at = ? WHERE id = 1",
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
}
