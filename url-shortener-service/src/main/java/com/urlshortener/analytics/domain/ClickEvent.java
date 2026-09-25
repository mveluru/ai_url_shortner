package com.urlshortener.analytics.domain;

import java.time.Instant;

/**
 * The queue payload for one redirect (design doc section 5.2). Not persisted raw beyond the queue's bounded retention.
 * The raw IP is never present: only {@code ipHash}, an HMAC under a rotating server-side key (section 10.2).
 */
public record ClickEvent(String shortCode, String eventId, Instant timestamp, String referrer, String userAgent,
                         String ipHash) {}
