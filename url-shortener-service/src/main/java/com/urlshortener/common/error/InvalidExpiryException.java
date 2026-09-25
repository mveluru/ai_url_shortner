package com.urlshortener.common.error;

public final class InvalidExpiryException extends UrlShortenerException {

    public InvalidExpiryException(String message) {
        super(ErrorCode.INVALID_EXPIRY, message);
    }
}
