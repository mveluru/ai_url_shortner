package com.urlshortener.common.error;

public final class AliasTakenException extends UrlShortenerException {

    public AliasTakenException(String message) {
        super(ErrorCode.ALIAS_TAKEN, message);
    }
}
