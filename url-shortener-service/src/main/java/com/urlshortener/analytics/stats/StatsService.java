package com.urlshortener.analytics.stats;

import com.urlshortener.analytics.api.DailyStats;
import com.urlshortener.analytics.api.UrlStats;
import com.urlshortener.analytics.domain.ClickAggregateEntity;
import com.urlshortener.common.config.AppProperties;
import com.urlshortener.common.error.InvalidRangeException;
import com.urlshortener.common.error.ResourceNotFoundException;
import com.urlshortener.shortener.domain.UrlEntity;
import com.urlshortener.shortener.service.UrlService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** The {@code stats} read API (design doc sections 6.2, 6.5, E20, E22). */
@Service
public class StatsService {

    private static final int DEFAULT_WINDOW_DAYS = 30;

    private final UrlService urls;
    private final AggregateReader reader;
    private final Clock clock;
    private final int retentionDays;
    private final int topReferrers;
    private final boolean deviceBreakdown;

    public StatsService(UrlService urls, AggregateReader reader, Clock clock, AppProperties props) {
        this.urls = urls;
        this.reader = reader;
        this.clock = clock;
        this.retentionDays = props.analytics().retentionDays();
        this.topReferrers = props.analytics().topReferrers();
        this.deviceBreakdown = props.features().statsDeviceBreakdown();
    }

    public UrlStats stats(String ownerKeyId, String shortCode, LocalDate fromParam, LocalDate toParam) {
        UrlEntity url = urls.owned(ownerKeyId, shortCode);                             // 404 unknown, 403 foreign
        if (!url.isActive()) {
            // E20: historical clicks are still aggregated, but a deactivated code's stats are not served.
            throw new ResourceNotFoundException("No such short URL.");
        }

        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate to = toParam != null ? toParam : today;
        LocalDate from = fromParam != null ? fromParam : to.minusDays(DEFAULT_WINDOW_DAYS - 1L);
        if (from.isAfter(to)) {
            throw new InvalidRangeException("'from' must not be after 'to'.");                // E22
        }
        // Rows older than the retention window are purged, so never query for (or imply) data that cannot exist.
        LocalDate earliest = today.minusDays(retentionDays);
        LocalDate effectiveFrom = from.isBefore(earliest) ? earliest : from;

        List<ClickAggregateEntity> rows = effectiveFrom.isAfter(to) ? List.of() : reader.range(shortCode, effectiveFrom, to);

        long total = 0;
        Map<String, Long> referrers = new HashMap<>();
        Map<String, Long> devices = new HashMap<>();
        List<DailyStats> daily = rows.stream().map(r -> new DailyStats(r.getId().day(), r.getClickCount(),
                r.getTopReferrers(), deviceBreakdown ? r.getDeviceBreakdown() : Map.of())).toList();
        for (ClickAggregateEntity r : rows) {
            total += r.getClickCount();
            r.getTopReferrers().forEach((k, v) -> referrers.merge(k, v, Long::sum));
            r.getDeviceBreakdown().forEach((k, v) -> devices.merge(k, v, Long::sum));
        }
        return new UrlStats(shortCode, from, to, total, reader.lastAggregation().orElse(null),
                top(referrers, topReferrers), deviceBreakdown ? devices : Map.of(), daily);
    }

    private static Map<String, Long> top(Map<String, Long> counts, int limit) {
        Map<String, Long> out = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(limit).forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }
}
