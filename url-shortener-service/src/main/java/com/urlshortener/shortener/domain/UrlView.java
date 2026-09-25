package com.urlshortener.shortener.domain;

import java.time.Instant;

/** Read-only projection for the redirect hot path: only the columns a redirect needs. */
public record UrlView(String shortCode, String longUrl, Instant expiresAt, boolean active, int version) {

    /** True when this mapping may be served right now. Evaluated at read time (E14, E15, F12). */
    public boolean isServable(Instant now) {
        return active && (expiresAt == null || expiresAt.isAfter(now));
    }
}
