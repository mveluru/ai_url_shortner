package com.urlshortener.analytics.stats;

import com.urlshortener.analytics.domain.ClickAggregateEntity;
import com.urlshortener.analytics.domain.ClickAggregateRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Read-side access to the analytics store behind the {@code mysql-read} breaker (section 8.2.1). */
@Component
class AggregateReader {

    private final ClickAggregateRepository aggregates;
    private final JdbcTemplate jdbc;

    AggregateReader(ClickAggregateRepository aggregates, JdbcTemplate jdbc) {
        this.aggregates = aggregates;
        this.jdbc = jdbc;
    }

    @CircuitBreaker(name = "mysql-read")
    @Retry(name = "mysql-read")
    @Transactional(readOnly = true)
    List<ClickAggregateEntity> range(String shortCode, LocalDate from, LocalDate to) {
        return aggregates.findRange(shortCode, from, to);
    }

    @CircuitBreaker(name = "mysql-read")
    @Retry(name = "mysql-read")
    @Transactional(readOnly = true)
    Optional<Instant> lastAggregation() {
        List<LocalDateTime> rows = jdbc.queryForList(
                "SELECT last_aggregation_at FROM analytics_state WHERE id = 1 AND last_aggregation_at IS NOT NULL",
                LocalDateTime.class);
        return rows.stream().findFirst().map(t -> t.toInstant(ZoneOffset.UTC));
    }
}
