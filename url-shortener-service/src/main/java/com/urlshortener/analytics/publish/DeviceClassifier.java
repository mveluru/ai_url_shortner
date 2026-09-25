package com.urlshortener.analytics.publish;

import java.util.Locale;

/**
 * Coarse device class from the User-Agent: {@code mobile}, {@code desktop} or {@code other} (design doc section 2.2).
 * Deliberately coarse and library-free: it is a breakdown for analytics, not fingerprinting, so nothing finer than
 * three buckets is retained.
 */
public final class DeviceClassifier {

    private DeviceClassifier() {}

    public static String classify(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "other";
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (containsAny(ua, "bot", "crawler", "spider", "curl/", "wget/", "python-requests", "httpclient", "okhttp",
                "go-http-client", "java/")) {
            return "other";
        }
        if (containsAny(ua, "mobi", "android", "iphone", "ipad", "ipod", "windows phone")) {
            return "mobile";
        }
        if (containsAny(ua, "windows nt", "macintosh", "x11", "cros", "linux")) {
            return "desktop";
        }
        return "other";
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
