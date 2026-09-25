package com.urlshortener.common.error;

public final class RateLimitedException extends UrlShortenerException {

    private final long retryAfterSeconds;

    public RateLimitedException(String message, long retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED, message);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
