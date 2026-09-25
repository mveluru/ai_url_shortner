package com.urlshortener.redirect.cache;

import com.urlshortener.shortener.domain.UrlView;
import java.time.Instant;

/**
 * What the redirect path needs, as stored in Redis (design doc F2): {@code short_code -> long_url (+ expiry, active
 * flag)}, plus the DB row's {@code version}. On a hit the redirect trusts these fields directly; correctness after a
 * mutation comes from the Shortener <em>invalidating</em> the key, not from comparing versions.
 */
public record CachedUrl(String longUrl, Instant expiresAt, boolean active, int version) {

    public static CachedUrl from(UrlView view) {
        return new CachedUrl(view.longUrl(), view.expiresAt(), view.active(), view.version());
    }

    /** Evaluated at read time, never assuming the cache TTL or sweep job removed a dead entry (E14, E15, F12). */
    public boolean isServable(Instant now) {
        return active && (expiresAt == null || expiresAt.isAfter(now));
    }
}
