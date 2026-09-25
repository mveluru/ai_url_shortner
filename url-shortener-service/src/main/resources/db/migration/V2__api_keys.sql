-- Design doc section 10.1: keys are opaque, hashed at rest (Argon2), rotated by re-issue + revoke.
CREATE TABLE api_keys (
    key_id     VARCHAR(64)  NOT NULL,
    key_hash   VARCHAR(255) NOT NULL,
    name       VARCHAR(100) NULL,
    created_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    revoked_at DATETIME(6)  NULL,
    PRIMARY KEY (key_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
