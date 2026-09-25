package com.urlshortener.common.error;

public final class AliasReservedException extends UrlShortenerException {

    public AliasReservedException(String message) {
        super(ErrorCode.ALIAS_RESERVED, message);
    }
}
