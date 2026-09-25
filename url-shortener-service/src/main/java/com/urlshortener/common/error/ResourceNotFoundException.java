package com.urlshortener.common.error;

public final class ResourceNotFoundException extends UrlShortenerException {

    public ResourceNotFoundException(String message) {
        super(ErrorCode.NOT_FOUND, message);
    }
}
