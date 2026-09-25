package com.urlshortener.common.security;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** An API key. Only the Argon2 hash is stored; the plaintext exists once, in the response that issued it. */
@Entity
@Table(name = "api_keys")
public class ApiKeyEntity {

    @Id
    @Column(name = "key_id", length = 64)
    private String keyId;

    @Column(name = "key_hash", nullable = false)
    private String keyHash;

    @Column(name = "name", length = 100)
    private String name;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ApiKeyEntity() {
        // JPA
    }

    public ApiKeyEntity(String keyId, String keyHash, String name) {
        this.keyId = keyId;
        this.keyHash = keyHash;
        this.name = name;
    }

    public void revoke(Instant at) {
        this.revokedAt = at;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public String getKeyId() { return keyId; }
    public String getKeyHash() { return keyHash; }
    public String getName() { return name; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getRevokedAt() { return revokedAt; }
}
