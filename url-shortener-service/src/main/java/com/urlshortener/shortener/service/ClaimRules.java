package com.urlshortener.shortener.service;

import com.urlshortener.shortener.domain.UrlEntity;
import com.urlshortener.shortener.domain.UrlStatus;
import java.time.Duration;
import java.time.Instant;

/** When does an existing row still count as "the same create request" for idempotency (section 6.3)? */
final class ClaimRules {

    private ClaimRules() {}

    /** Inside the 24h window, and still an ACTIVE link: replaying a deactivated or expired resource would be wrong. */
    static boolean isLive(UrlEntity entity, Instant now, Duration window) {
        return entity.getCreatedAt() != null
                && entity.getCreatedAt().plus(window).isAfter(now)
                && entity.statusAt(now) == UrlStatus.ACTIVE;
    }
}
