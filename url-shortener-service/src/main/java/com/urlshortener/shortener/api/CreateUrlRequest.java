package com.urlshortener.shortener.api;

/**
 * Wire DTO for {@code POST /api/v1/urls}. Deliberately carries no bean-validation constraints on its three fields:
 * each has a dedicated error code (INVALID_URL, INVALID_ALIAS/ALIAS_RESERVED, INVALID_EXPIRY per edge cases
 * E1-E11), so validation lives in the domain validators. {@code expiresAt} is a string so an unparseable value is
 * INVALID_EXPIRY while a non-string JSON type is MALFORMED_JSON (section 9.2).
 */
public record CreateUrlRequest(String longUrl, String customAlias, String expiresAt) {}
