package com.urlshortener.redirect.cache;

import java.util.Optional;

/**
 * Cache-aside store for redirect lookups. Implementations <em>throw</em> on infrastructure failure so the circuit
 * breaker and retry around them observe it; callers translate that into a {@link CacheLookupResult}.
 */
public interface UrlCache {

    Optional<CachedUrl> get(String shortCode);

    /** A longer-lived copy served only when a stampede lock is lost and the primary key has not been repopulated (F7). */
    Optional<CachedUrl> getStale(String shortCode);

    void put(String shortCode, CachedUrl value);

    /** Deletes (never updates) the entry and its stale copy: mutations force a fresh DB read (F2). */
    void invalidate(String shortCode);

    /** {@code SET key token NX PX ttl}: true if this caller now owns the per-key repopulation lock (F7). */
    boolean tryLock(String shortCode, String token);

    /** Releases the lock only if {@code token} still owns it (compare-and-delete, so a slow owner cannot free another's lock). */
    void unlock(String shortCode, String token);
}
