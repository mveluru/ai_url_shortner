package com.urlshortener.redirect.cache;

/**
 * Outcome of a cache lookup (design doc section 20.3). Sealed so every call site is forced, by the compiler, to decide
 * what to do when the cache is unavailable rather than relying on convention.
 */
public sealed interface CacheLookupResult {

    /** The key was present. */
    record Hit(CachedUrl value) implements CacheLookupResult {}

    /** Redis answered and the key is absent: a normal cache miss. */
    record Miss() implements CacheLookupResult {}

    /**
     * The cache could not be consulted - the {@code redis-cache} breaker is open, or Redis errored/timed out after its
     * one short retry (F1). The caller goes straight to the database and must not try to repopulate the cache.
     */
    record CircuitOpen(String reason) implements CacheLookupResult {}
}
