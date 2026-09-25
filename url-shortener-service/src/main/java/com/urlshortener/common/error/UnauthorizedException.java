package com.urlshortener.common.error;

public final class UnauthorizedException extends UrlShortenerException {

    public UnauthorizedException(String message) {
        super(ErrorCode.UNAUTHORIZED, message);
    }
}
