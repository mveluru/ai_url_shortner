package com.urlshortener.common.error;

/** One entry of {@code fieldErrors} for {@code VALIDATION_FAILED} (design doc section 9.2a). */
public record FieldError(String field, Object rejectedValue, String constraint, String message) {}
