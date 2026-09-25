package com.urlshortener.shortener.validation;

import com.urlshortener.common.error.InvalidExpiryException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/** {@code expiresAt} handling (E11, E12). */
@Component
public class ExpiryValidator {

    /** DATETIME(6) tops out at year 9999; anything later would fail at the database as a 500 instead of a clean 400. */
    private static final Instant MAX = Instant.parse("9999-12-31T23:59:59Z");

    private final Clock clock;

    public ExpiryValidator(Clock clock) {
        this.clock = clock;
    }

    /**
     * @param raw ISO-8601 date-time with an offset (e.g. {@code 2030-01-01T00:00:00Z}), or null
     * @return the expiry instant, or null meaning "never expires" (E12)
     */
    public Instant validate(String raw) {
        if (raw == null) {
            return null;                                                                             // E12
        }
        Instant expiresAt;
        try {
            expiresAt = OffsetDateTime.parse(raw.strip(), DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeException e) {
            throw new InvalidExpiryException("expiresAt must be an ISO-8601 date-time with an offset, e.g. 2030-01-01T00:00:00Z.");
        }
        if (!expiresAt.isAfter(clock.instant())) {
            throw new InvalidExpiryException("expiresAt must be in the future.");                    // E11
        }
        if (expiresAt.isAfter(MAX)) {
            throw new InvalidExpiryException("expiresAt is too far in the future.");
        }
        return expiresAt;
    }
}
