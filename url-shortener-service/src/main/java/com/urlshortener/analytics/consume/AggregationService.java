package com.urlshortener.analytics.consume;

import com.urlshortener.analytics.domain.ClickAggregateEntity;
import com.urlshortener.analytics.domain.ClickAggregateId;
import com.urlshortener.analytics.domain.ClickAggregateRepository;
import com.urlshortener.analytics.domain.ClickEvent;
import com.urlshortener.analytics.publish.DeviceClassifier;
import com.urlshortener.common.config.AppProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Folds one click event into the daily aggregate (design doc sections 5.3, 5.4, E21).
 *
 * <p>Runs in a single transaction: the dedup marker and the aggregate update commit together or not at all, so a
 * redelivered event can never be double-counted and a crash between the two can never lose or duplicate a click.
 */
@Service
public class AggregationService {

    private final JdbcTemplate jdbc;
    private final ClickAggregateRepository aggregates;
    private final Clock clock;
    private final int topReferrers;

    public AggregationService(JdbcTemplate jdbc, ClickAggregateRepository aggregates, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.aggregates = aggregates;
        this.clock = clock;
        this.topReferrers = props.analytics().topReferrers();
    }

    /**
     * @return false if this event id was already processed (at-least-once redelivery, E21) and was therefore skipped
     */
    @Transactional
    public boolean process(ClickEvent event) {
        // INSERT IGNORE, not insert-and-catch: a duplicate-key exception inside a JPA transaction would mark it
        // rollback-only. The event_id primary key is the dedup arbiter; 0 rows affected means "seen before".
        int fresh = jdbc.update("INSERT IGNORE INTO processed_events (event_id, processed_at) VALUES (?, ?)",
                event.eventId(), utcNow());
        if (fresh == 0) {
            return false;
        }

        LocalDate day = event.timestamp().atZone(ZoneOffset.UTC).toLocalDate();           // daily buckets are UTC
        ClickAggregateId id = new ClickAggregateId(event.shortCode(), day);

        // Upsert the (possibly new) row, then lock it: two consumers can never both "create" it, and neither can
        // read-modify-write the same JSON concurrently.
        jdbc.update("INSERT IGNORE INTO click_aggregates (short_code, date, click_count, top_referrers, "
                + "device_breakdown, updated_at) VALUES (?, ?, 0, '{}', '{}', ?)", event.shortCode(), day, utcNow());
        ClickAggregateEntity row = aggregates.lockById(id).orElseThrow();

        row.setClickCount(row.getClickCount() + 1);
        row.setTopReferrers(bump(row.getTopReferrers(), ReferrerNormalizer.normalize(event.referrer()), topReferrers));
        row.setDeviceBreakdown(bump(row.getDeviceBreakdown(), DeviceClassifier.classify(event.userAgent()), Integer.MAX_VALUE));
        row.setUpdatedAt(clock.instant());
        return true;
    }

    /** Increments {@code key}, keeping only the {@code limit} highest counts (never evicting the key just bumped). */
    static Map<String, Long> bump(Map<String, Long> current, String key, int limit) {
        Map<String, Long> next = new HashMap<>(current);      // a fresh map, so JSON dirty-checking always sees the change
        next.merge(key, 1L, Long::sum);
        while (next.size() > limit) {
            next.entrySet().stream()
                    .filter(e -> !e.getKey().equals(key))
                    .min(Map.Entry.comparingByValue())
                    .ifPresent(e -> next.remove(e.getKey()));
        }
        return next;
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
