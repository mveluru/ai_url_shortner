package com.urlshortener.redirect;

/** A successful lookup. {@code cache} says how it was served (hit / miss / stale / bypass), for tests and metrics. */
public record Resolution(String longUrl, String cache) {}
