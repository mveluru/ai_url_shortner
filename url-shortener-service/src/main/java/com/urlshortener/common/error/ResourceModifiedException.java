package com.urlshortener.common.error;

public final class ResourceModifiedException extends UrlShortenerException {

    public ResourceModifiedException(String message) {
        super(ErrorCode.RESOURCE_MODIFIED, message);
    }
}
