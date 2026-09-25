package com.urlshortener.shortener.service;

/**
 * F9: an auto-generated code collided with another <em>auto-generated</em> code. The counter + bijective permutation
 * make this impossible unless there is a bug, a migration error or a manual DB edit, so it is a data-integrity incident
 * to page on, never a normal-path retry. Deliberately outside the {@code UrlShortenerException} hierarchy: it surfaces
 * as a generic 500 INTERNAL_ERROR with the full detail logged server-side.
 */
public class ShortCodeCollisionException extends IllegalStateException {

    public ShortCodeCollisionException(String code) {
        super("Auto-generated short code collided with another auto-generated code (data-integrity bug): " + code);
    }
}
