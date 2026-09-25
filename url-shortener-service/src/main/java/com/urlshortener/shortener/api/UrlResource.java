package com.urlshortener.shortener.api;

import com.urlshortener.shortener.domain.UrlStatus;
import java.time.Instant;

public record UrlResource(
        String shortCode,
        String shortUrl,
        String longUrl,
        boolean customAlias,
        UrlStatus status,
        Instant createdAt,
        Instant expiresAt) {}
