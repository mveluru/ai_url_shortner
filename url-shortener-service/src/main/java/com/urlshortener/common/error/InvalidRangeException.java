package com.urlshortener.common.error;

public final class InvalidRangeException extends UrlShortenerException {

    public InvalidRangeException(String message) {
        super(ErrorCode.INVALID_RANGE, message);
    }
}
