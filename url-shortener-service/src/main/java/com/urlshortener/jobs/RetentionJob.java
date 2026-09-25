package com.urlshortener.jobs;

import com.urlshortener.analytics.domain.ClickAggregateRepository;
import com.urlshortener.common.config.AppProperties;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces the analytics retention window (design doc section 15.3: 90 days, an assumption flagged for confirmation) and
 * trims the consumer-side dedup table. Dedup entries only need to outlive the queue's redelivery window.
 */
@Component
public class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);
    private static final int DEDUP_RETENTION_DAYS = 7;

    private final ClickAggregateRepository aggregates;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int retentionDays;

    public RetentionJob(ClickAggregateRepository aggregates, JdbcTemplate jdbc, Clock clock, AppProperties props) {
        this.aggregates = aggregates;
        this.jdbc = jdbc;
        this.clock = clock;
        this.retentionDays = props.analytics().retentionDays();
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "UTC")
    @Transactional
    public void purge() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        int aggregatesDeleted = aggregates.deleteOlderThan(today.minusDays(retentionDays));
        int eventsDeleted = jdbc.update("DELETE FROM processed_events WHERE processed_at < ?",
                LocalDateTime.ofInstant(clock.instant().minusSeconds(DEDUP_RETENTION_DAYS * 86_400L), ZoneOffset.UTC));
        log.info("Retention purge removed {} aggregate rows and {} dedup markers", aggregatesDeleted, eventsDeleted);
    }
}
