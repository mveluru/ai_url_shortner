package com.urlshortener.shortener.domain;

/** Derived lifecycle state, only ever disclosed on the authenticated metadata endpoint (section 7, E14 rationale). */
public enum UrlStatus {
    ACTIVE,
    EXPIRED,
    DEACTIVATED
}
