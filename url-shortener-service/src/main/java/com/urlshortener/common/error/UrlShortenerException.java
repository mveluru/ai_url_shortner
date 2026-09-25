package com.urlshortener.common.error;

/**
 * Root of the domain exception hierarchy (section 9.3). Each subtype maps to exactly one {@link ErrorCode}, and
 * {@code GlobalExceptionHandler} is the only place HTTP statuses are chosen.
 */
public abstract sealed class UrlShortenerException extends RuntimeException
        permits InvalidUrlException, InvalidAliasException, AliasReservedException, InvalidExpiryException,
                InvalidRangeException, UnauthorizedException, ForbiddenException, ResourceNotFoundException,
                AliasTakenException, ResourceModifiedException, RateLimitedException, UpstreamTimeoutException,
                ServiceUnavailableException {

    private final ErrorCode errorCode;

    protected UrlShortenerException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected UrlShortenerException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
