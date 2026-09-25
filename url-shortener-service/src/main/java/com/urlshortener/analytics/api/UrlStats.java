package com.urlshortener.analytics.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** {@code updatedAt} is the last successful aggregation run, disclosing eventual-consistency lag (section 6.5). */
public record UrlStats(
        String shortCode,
        LocalDate from,
        LocalDate to,
        long totalClicks,
        Instant updatedAt,
        Map<String, Long> topReferrers,
        Map<String, Long> deviceBreakdown,
        List<DailyStats> daily) {}
