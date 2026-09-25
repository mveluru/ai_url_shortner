package com.urlshortener.common.error;

public final class ForbiddenException extends UrlShortenerException {

    public ForbiddenException(String message) {
        super(ErrorCode.FORBIDDEN, message);
    }
}
