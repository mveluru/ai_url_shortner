package com.urlshortener.analytics.consume;

import java.net.URI;
import java.util.Locale;

/**
 * Reduces a raw {@code Referer} header to its host for aggregation. The full URL is deliberately NOT retained: referrer
 * paths and query strings routinely carry PII or tokens, and the design's "top referrers" needs only the source.
 */
final class ReferrerNormalizer {

    static final String DIRECT = "(direct)";
    static final String UNKNOWN = "(unknown)";

    private ReferrerNormalizer() {}

    static String normalize(String referrer) {
        if (referrer == null || referrer.isBlank()) {
            return DIRECT;
        }
        try {
            String host = URI.create(referrer.strip()).getHost();
            return host == null || host.isBlank() ? UNKNOWN : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
