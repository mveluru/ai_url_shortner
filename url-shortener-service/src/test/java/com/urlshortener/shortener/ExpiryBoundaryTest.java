package com.urlshortener.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.redirect.cache.CachedUrl;
import com.urlshortener.shortener.domain.UrlEntity;
import com.urlshortener.shortener.domain.UrlStatus;
import com.urlshortener.shortener.domain.UrlView;
import com.urlshortener.testsupport.Covers;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Expiry semantics at the boundary (design doc F12, F13): no sub-second semantics, evaluated at read time. */
class ExpiryBoundaryTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    @Test
    @Covers({"E14", "F12", "F13"})
    @DisplayName("F13: expiry is exclusive at the exact instant, and tolerant of sub-second skew by construction (whole-second comparison)")
    void boundary() {
        assertThat(new UrlView("c", "u", NOW, true, 1).isServable(NOW)).as("expires_at == now is expired").isFalse();
        assertThat(new UrlView("c", "u", NOW.plusSeconds(1), true, 1).isServable(NOW)).isTrue();
        assertThat(new UrlView("c", "u", NOW.minusMillis(1), true, 1).isServable(NOW)).isFalse();
        assertThat(new CachedUrl("u", NOW, true, 1).isServable(NOW)).isFalse();
        assertThat(new CachedUrl("u", NOW.plusSeconds(1), true, 1).isServable(NOW)).isTrue();
    }

    @Test
    @Covers({"E14", "E15"})
    @DisplayName("E14/E15: a link with no expiry never expires; an inactive one is never servable, expiry or not")
    void neverExpiresAndInactive() {
        assertThat(new UrlView("c", "u", null, true, 1).isServable(NOW.plusSeconds(10L * 365 * 86_400))).isTrue();
        assertThat(new UrlView("c", "u", null, false, 1).isServable(NOW)).isFalse();
        assertThat(new CachedUrl("u", null, false, 1).isServable(NOW)).isFalse();
    }

    @Test
    @Covers({"F12"})
    @DisplayName("F12: status is computed from expires_at at read time; EXPIRED wins even if the sweep has not run (or already did)")
    void statusComputedAtReadTime() {
        UrlEntity live = UrlEntity.create(1, "code001", "https://x.test", false, "k", null, NOW.plusSeconds(60));
        assertThat(live.statusAt(NOW)).isEqualTo(UrlStatus.ACTIVE);
        assertThat(live.statusAt(NOW.plusSeconds(61))).as("no sweep needed").isEqualTo(UrlStatus.EXPIRED);

        UrlEntity swept = UrlEntity.create(2, "code002", "https://x.test", false, "k", "fp", NOW.minusSeconds(5));
        swept.deactivate();                                           // what the sweep does
        assertThat(swept.statusAt(NOW)).as("still reported as EXPIRED, not DEACTIVATED").isEqualTo(UrlStatus.EXPIRED);

        UrlEntity deleted = UrlEntity.create(3, "code003", "https://x.test", false, "k", "fp", null);
        deleted.deactivate();
        assertThat(deleted.statusAt(NOW)).isEqualTo(UrlStatus.DEACTIVATED);
        assertThat(deleted.getIdempotencyFingerprint()).as("deactivation releases the idempotency claim").isNull();
    }
}
