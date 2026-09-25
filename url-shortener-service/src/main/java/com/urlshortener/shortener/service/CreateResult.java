package com.urlshortener.shortener.service;

import com.urlshortener.shortener.api.UrlResource;

/** {@code created == false} means an idempotent replay of an existing resource (HTTP 200, not 201). */
public record CreateResult(UrlResource resource, boolean created) {}
