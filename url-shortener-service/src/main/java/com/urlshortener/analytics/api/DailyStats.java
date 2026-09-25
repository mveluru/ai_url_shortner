package com.urlshortener.analytics.api;

import java.time.LocalDate;
import java.util.Map;

public record DailyStats(LocalDate date, long clicks, Map<String, Long> topReferrers,
                         Map<String, Long> deviceBreakdown) {}
