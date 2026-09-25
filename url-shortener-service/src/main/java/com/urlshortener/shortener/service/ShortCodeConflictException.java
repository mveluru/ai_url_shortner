package com.urlshortener.shortener.service;

import org.springframework.dao.DataIntegrityViolationException;

/**
 * A unique-constraint violation on {@code short_code}, carrying the code that was attempted. It extends
 * {@link DataIntegrityViolationException} on purpose: circuit breakers already ignore that type (a business conflict is
 * not a dependency failure) and the advice's generic mapping still applies if it ever escapes.
 */
class ShortCodeConflictException extends DataIntegrityViolationException {

    private final String attemptedCode;

    ShortCodeConflictException(String attemptedCode, DataIntegrityViolationException cause) {
        super("short_code already exists: " + attemptedCode, cause);
        this.attemptedCode = attemptedCode;
    }

    String attemptedCode() {
        return attemptedCode;
    }
}
