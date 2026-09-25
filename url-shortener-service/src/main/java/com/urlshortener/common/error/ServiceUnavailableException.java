package com.urlshortener.common.error;

public final class ServiceUnavailableException extends UrlShortenerException {

    public ServiceUnavailableException(String message) {
        super(ErrorCode.SERVICE_UNAVAILABLE, message);
    }

    public ServiceUnavailableException(String message, Throwable cause) {
        super(ErrorCode.SERVICE_UNAVAILABLE, message, cause);
    }
}
