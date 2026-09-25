package com.urlshortener.common.error;

import java.time.Instant;
import java.util.List;

/**
 * The single error body shape for every endpoint (section 9.1). {@code requestId} is always present and is the
 * only handle needed to find the server-side detail; {@code fieldErrors} is null except for VALIDATION_FAILED.
 */
public record ErrorResponse(
        ErrorCode code,
        String message,
        String requestId,
        Instant timestamp,
        String path,
        List<FieldError> fieldErrors) {}
