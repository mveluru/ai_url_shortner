package com.urlshortener.shortener.service;

import com.urlshortener.shortener.domain.UrlEntity;
import com.urlshortener.shortener.domain.UrlRepository;
import com.urlshortener.shortener.domain.UrlView;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-side data access behind the {@code mysql-read} breaker with two full-jitter retries (design doc section 8.2.1, F4).
 * This is the redirect path's last line of defense (section 8.1). Retrying a SELECT is always safe (section 8.2.3).
 * {@link #findView} is a read-only transaction, which is what a read-replica router keys off; {@link #findEntity} is a
 * primary read so an owner always sees their own writes.
 */
@Component
public class UrlReader {

    private final UrlRepository repository;

    public UrlReader(UrlRepository repository) {
        this.repository = repository;
    }

    /** The redirect fallback read: read-only, so with a replica configured it is served by the replica (F4). */
    @CircuitBreaker(name = "mysql-read")
    @Retry(name = "mysql-read")
    @Transactional(readOnly = true)
    public Optional<UrlView> findView(String shortCode) {
        return repository.findViewByShortCode(shortCode);
    }

    /**
     * Management/ownership read. Deliberately NOT read-only, so it always uses the primary: an owner must read their own
     * writes immediately, which a lagging replica cannot promise (design doc F4 accepts replica lag only for redirects).
     */
    @CircuitBreaker(name = "mysql-read")
    @Retry(name = "mysql-read")
    @Transactional
    public Optional<UrlEntity> findEntity(String shortCode) {
        return repository.findByShortCode(shortCode);
    }
}
