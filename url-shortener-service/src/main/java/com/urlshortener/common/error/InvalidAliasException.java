package com.urlshortener.common.error;

public final class InvalidAliasException extends UrlShortenerException {

    public InvalidAliasException(String message) {
        super(ErrorCode.INVALID_ALIAS, message);
    }
}
