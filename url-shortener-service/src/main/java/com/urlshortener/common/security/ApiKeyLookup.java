package com.urlshortener.common.security;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only outbound call of the auth path, isolated in its own bean so the {@code auth-lookup} breaker and
 * retry-with-jitter (design doc section 8.2.1) apply through the Spring proxy. It guards the management API only;
 * the redirect path has no dependency on it (F11, section 8.1).
 */
@Component
class ApiKeyLookup {

    private final ApiKeyRepository repository;

    ApiKeyLookup(ApiKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * Deliberately a read-WRITE transaction: Spring Data's repository reads are read-only by default, which a read-replica
     * router sends to the replica. Authentication and revocation must always be checked against the primary - a key issued
     * a moment ago, or revoked a moment ago, must not depend on replication lag.
     */
    @CircuitBreaker(name = "auth-lookup")
    @Retry(name = "auth-lookup")
    @Transactional
    Optional<ApiKeyEntity> find(String keyId) {
        return repository.findById(keyId);
    }
}
