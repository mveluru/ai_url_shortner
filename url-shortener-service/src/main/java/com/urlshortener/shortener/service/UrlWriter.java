package com.urlshortener.shortener.service;

import com.urlshortener.common.error.ForbiddenException;
import com.urlshortener.common.error.ResourceNotFoundException;
import com.urlshortener.common.error.UniqueConstraint;
import com.urlshortener.shortener.domain.IdBlockAllocator;
import com.urlshortener.shortener.domain.ShortCodeGenerator;
import com.urlshortener.shortener.domain.UrlEntity;
import com.urlshortener.shortener.domain.UrlRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Write-side data access behind the {@code mysql-write} breaker (design doc section 8.2.1, F3).
 *
 * <p><b>Retry rule (section 8.2.3):</b> the only method with a retry is the idempotency-fingerprint <em>read</em>.
 * Every INSERT/UPDATE/DELETE has the breaker but zero retries - a blindly retried write is not proven safe, so a failed
 * mutation surfaces immediately as 503 rather than being silently repeated.
 *
 * <p>Writes use a programmatic {@link TransactionTemplate} so a unique-constraint violation surfaces <em>here</em>,
 * where the attempted code is known, instead of at an opaque proxy commit.
 */
@Component
public class UrlWriter {

    private final UrlRepository repository;
    private final ShortCodeGenerator generator;
    private final IdBlockAllocator idAllocator;
    private final TransactionTemplate tx;

    public UrlWriter(UrlRepository repository, ShortCodeGenerator generator, IdBlockAllocator idAllocator,
                     TransactionTemplate tx) {
        this.repository = repository;
        this.generator = generator;
        this.idAllocator = idAllocator;
        this.tx = tx;
    }

    /** The one retry-safe read on the write path. */
    @CircuitBreaker(name = "mysql-write")
    @Retry(name = "mysql-write")
    public Optional<UrlEntity> findByFingerprint(String fingerprint) {
        return tx.execute(s -> repository.findByIdempotencyFingerprint(fingerprint));
    }

    /** @param window how long an existing fingerprint claim stays valid; older claims are released before inserting */
    @CircuitBreaker(name = "mysql-write")
    public UrlEntity insert(String longUrl, String customAlias, String ownerKeyId, String fingerprint,
                            Instant expiresAt, Instant now, Duration window) {
        // Allocate the id and derive the code BEFORE opening a transaction: the code is a pure function of the id, and
        // allocation must never hold a pooled connection while waiting for another (see IdBlockAllocator).
        long id = idAllocator.nextId();
        String code = customAlias != null ? customAlias : generator.generate(id);
        UrlEntity entity = UrlEntity.create(id, code, longUrl, customAlias != null, ownerKeyId, fingerprint, expiresAt);
        try {
            return tx.execute(status -> {
                releaseStaleClaim(fingerprint, now, window);
                repository.save(entity);
                repository.flush();                                       // surface a unique violation inside this method
                return entity;
            });
        } catch (DataIntegrityViolationException e) {
            if (UniqueConstraint.of(e) == UniqueConstraint.SHORT_CODE) {
                throw new ShortCodeConflictException(code, e);
            }
            throw e;
        }
    }

    /**
     * Soft-deletes a code owned by {@code ownerKeyId}. Runs in one transaction with an optimistic-lock check, so two
     * concurrent deactivations cannot both succeed: the loser gets RESOURCE_MODIFIED (409), retries, and sees 404 (E23).
     */
    @CircuitBreaker(name = "mysql-write")
    public void deactivate(String shortCode, String ownerKeyId) {
        tx.executeWithoutResult(status -> {
            UrlEntity entity = repository.findByShortCode(shortCode).orElseThrow(
                    () -> new ResourceNotFoundException("No such short URL."));
            if (!entity.getOwnerKeyId().equals(ownerKeyId)) {
                throw new ForbiddenException("This short URL belongs to a different API key.");
            }
            if (!entity.isActive()) {
                throw new ResourceNotFoundException("No such short URL.");   // E23: the second DELETE reports it is gone
            }
            entity.deactivate();
        });
    }

    /**
     * Expiry sweep (F12): storage hygiene only. Marks a bounded batch of past-expiry rows inactive and releases their
     * fingerprints. Correctness never depends on this having run - expiry is enforced at read time.
     *
     * @return the codes swept, so the caller can drop their cache entries
     */
    @CircuitBreaker(name = "mysql-write")
    public List<String> sweepExpired(Instant now, int batchSize) {
        return tx.execute(status -> {
            List<UrlEntity> batch = repository.findExpiredActive(now, PageRequest.of(0, batchSize));
            batch.forEach(UrlEntity::deactivate);
            return batch.stream().map(UrlEntity::getShortCode).toList();
        });
    }

    /** The admin path that frees a soft-deleted alias (E8). */
    @CircuitBreaker(name = "mysql-write")
    public boolean hardDelete(String shortCode) {
        Integer deleted = tx.execute(status -> repository.hardDeleteByShortCode(shortCode));
        return deleted != null && deleted > 0;
    }

    /**
     * If an earlier row still holds this fingerprint but its claim no longer applies (window lapsed, deactivated or
     * expired), release it so the unique index does not block a legitimate new creation.
     */
    private void releaseStaleClaim(String fingerprint, Instant now, Duration window) {
        repository.findByIdempotencyFingerprint(fingerprint).ifPresent(existing -> {
            if (!ClaimRules.isLive(existing, now, window)) {
                existing.releaseFingerprint();
                repository.flush();
            }
        });
    }
}
