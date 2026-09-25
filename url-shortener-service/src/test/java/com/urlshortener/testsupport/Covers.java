package com.urlshortener.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares which design-doc rows a test verifies: edge cases {@code E1..E24} (section 7) and failure modes
 * {@code F1..F13} (section 8). {@code scripts/verify-design-coverage.py} fails the build if any row has no test,
 * turning "every row of the catalog gets a test" (section 17) into a mechanical check instead of a promise.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Covers {
    String[] value();
}
