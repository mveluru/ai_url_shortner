package com.urlshortener.common.error;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Identifies <em>which</em> unique constraint fired on a {@link DataIntegrityViolationException} (design doc
 * section 20.4, F9). The same exception type covers the alias race (E13, 409), the idempotency race (section 6.3)
 * and an unexpected auto-code collision (F9, page on it), so the distinction must come from the constraint name,
 * not the exception type.
 */
public enum UniqueConstraint {
    SHORT_CODE("uk_urls_short_code"),
    IDEMPOTENCY_FINGERPRINT("uk_urls_idempotency_fingerprint"),
    UNKNOWN("");

    private final String constraintName;

    UniqueConstraint(String constraintName) {
        this.constraintName = constraintName;
    }

    public static UniqueConstraint of(DataIntegrityViolationException ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ConstraintViolationException cve && cve.getConstraintName() != null) {
                UniqueConstraint match = match(cve.getConstraintName());
                if (match != UNKNOWN) {
                    return match;
                }
            }
            // MySQL: "Duplicate entry 'x' for key 'urls.uk_urls_short_code'"; fall back to the raw message.
            if (t.getMessage() != null) {
                UniqueConstraint match = match(t.getMessage());
                if (match != UNKNOWN) {
                    return match;
                }
            }
        }
        return UNKNOWN;
    }

    private static UniqueConstraint match(String text) {
        for (UniqueConstraint c : values()) {
            if (c != UNKNOWN && text.contains(c.constraintName)) {
                return c;
            }
        }
        return UNKNOWN;
    }
}
