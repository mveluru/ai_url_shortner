package com.urlshortener.common.error;

/** A dependency call exceeded its timeout: "slow", as opposed to {@link ServiceUnavailableException}'s "down". */
public final class UpstreamTimeoutException extends UrlShortenerException {

    public UpstreamTimeoutException(String message, Throwable cause) {
        super(ErrorCode.UPSTREAM_TIMEOUT, message, cause);
    }
}
