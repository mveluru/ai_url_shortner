package com.urlshortener.shortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/** The {@code urls} table: source of truth for every mapping (design doc section 5.1). */
@Entity
@Table(name = "urls")
public class UrlEntity {

    /** Assigned by {@link IdBlockAllocator} (block-preallocated, F8) BEFORE the entity exists, because the code derives from it. */
    @Id
    private Long id;

    @Column(name = "short_code", nullable = false, length = 20)
    private String shortCode;

    @Column(name = "long_url", nullable = false, length = 2048)
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String longUrl;

    @Column(name = "is_custom_alias", nullable = false)
    private boolean customAlias;

    @Column(name = "owner_key_id", nullable = false, length = 64)
    private String ownerKeyId;

    @Column(name = "idempotency_fingerprint", length = 64)
    private String idempotencyFingerprint;

    /** DB-generated (F13: creation defaults use DB server time), read back after insert. */
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /** Optimistic-concurrency token (section 5.1); a lost race surfaces as RESOURCE_MODIFIED. */
    @Version
    @Column(nullable = false)
    private Integer version;

    protected UrlEntity() {
        // JPA
    }

    public static UrlEntity create(long id, String shortCode, String longUrl, boolean customAlias, String ownerKeyId,
                                   String fingerprint, Instant expiresAt) {
        UrlEntity e = new UrlEntity();
        e.id = id;
        e.shortCode = shortCode;
        e.longUrl = longUrl;
        e.customAlias = customAlias;
        e.ownerKeyId = ownerKeyId;
        e.idempotencyFingerprint = fingerprint;
        e.expiresAt = expiresAt;
        return e;
    }

    /**
     * Soft-delete. The fingerprint is released so the same URL can be shortened again immediately; the short code
     * itself is retained forever (E8: deactivated aliases are never recycled automatically).
     */
    public void deactivate() {
        this.active = false;
        this.idempotencyFingerprint = null;
    }

    /** Drops the idempotency claim once its window has lapsed, so the fingerprint can be reused. */
    public void releaseFingerprint() {
        this.idempotencyFingerprint = null;
    }

    /** Expiry is evaluated against the supplied clock at read time, never trusting the sweep job (F12). */
    public UrlStatus statusAt(Instant now) {
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            return UrlStatus.EXPIRED;
        }
        return active ? UrlStatus.ACTIVE : UrlStatus.DEACTIVATED;
    }

    public Long getId() { return id; }
    public String getShortCode() { return shortCode; }
    public String getLongUrl() { return longUrl; }
    public boolean isCustomAlias() { return customAlias; }
    public String getOwnerKeyId() { return ownerKeyId; }
    public String getIdempotencyFingerprint() { return idempotencyFingerprint; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isActive() { return active; }
    public Integer getVersion() { return version; }
}
