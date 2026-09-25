package com.urlshortener.common.error;

import org.springframework.http.HttpStatus;

/** Every {@code code} a client can see, with its HTTP status (design doc section 9.2). One row per code. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    INVALID_URL(HttpStatus.BAD_REQUEST),
    INVALID_ALIAS(HttpStatus.BAD_REQUEST),
    ALIAS_RESERVED(HttpStatus.BAD_REQUEST),
    INVALID_EXPIRY(HttpStatus.BAD_REQUEST),
    INVALID_RANGE(HttpStatus.BAD_REQUEST),
    MALFORMED_JSON(HttpStatus.BAD_REQUEST),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),
    FORBIDDEN(HttpStatus.FORBIDDEN),
    NOT_FOUND(HttpStatus.NOT_FOUND),
    NO_HANDLER(HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE),
    ALIAS_TAKEN(HttpStatus.CONFLICT),
    RESOURCE_MODIFIED(HttpStatus.CONFLICT),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    UPSTREAM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT);

    private final HttpStatus status;

    ErrorCode(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
