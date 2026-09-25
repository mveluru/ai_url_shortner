package com.urlshortener.common.error;

public final class InvalidUrlException extends UrlShortenerException {

    public InvalidUrlException(String message) {
        super(ErrorCode.INVALID_URL, message);
    }
}
