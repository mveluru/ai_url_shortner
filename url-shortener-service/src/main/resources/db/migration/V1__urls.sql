-- Design doc section 5.1 / 20.4a. MySQL 8.4, InnoDB, utf8mb4.
--
-- id is allocated from a block-preallocated counter (Hibernate SequenceStyleGenerator, pooled-lo optimizer,
-- allocationSize=10000). MySQL has no native SEQUENCE, so Hibernate emulates it with this single-row table
-- (section 4.1, F8). The row holds the NEXT unallocated value.
CREATE TABLE url_id_seq (
    next_val BIGINT NOT NULL
) ENGINE = InnoDB;
INSERT INTO url_id_seq (next_val) VALUES (1);

CREATE TABLE urls (
    id                      BIGINT       NOT NULL,
    -- CORRECTNESS-CRITICAL (section 20.4a): short codes are case-sensitive. MySQL's default collations are
    -- case-insensitive and would make 'aZ3kP1' and 'AZ3Kp1' collide on the UNIQUE index. utf8mb4_bin is set
    -- explicitly on the column, never inherited from a schema/table default.
    short_code              VARCHAR(20)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    long_url                TEXT         NOT NULL,
    is_custom_alias         TINYINT(1)   NOT NULL DEFAULT 0,
    owner_key_id            VARCHAR(64)  NOT NULL,
    -- SHA-256 hex of (owner_key_id, kind, long_url). NULL once the 24h idempotency window no longer applies
    -- (deactivated / expired / stale), which frees the value; UNIQUE permits many NULLs. See IdempotencyKey.
    idempotency_fingerprint VARCHAR(64)  NULL,
    -- DATETIME(6), not TIMESTAMP (2038 limit). UTC is enforced by the connection (connectionTimeZone=UTC).
    created_at              DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at              DATETIME(6)  NULL,
    is_active               TINYINT(1)   NOT NULL DEFAULT 1,
    version                 INT          NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    -- Constraint names are load-bearing: UniqueConstraint maps them to ALIAS_TAKEN / idempotent replay / F9.
    CONSTRAINT uk_urls_short_code UNIQUE (short_code),
    CONSTRAINT uk_urls_idempotency_fingerprint UNIQUE (idempotency_fingerprint),
    KEY ix_urls_owner_key_id (owner_key_id),
    -- No partial indexes in MySQL: the expiry sweep's "WHERE is_active = 1" is served by is_active leading.
    KEY ix_urls_active_expires (is_active, expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
