package com.urlshortener.shortener.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.shortener.domain.UrlEntity;
import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClaimAndFingerprintTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(24);

    private static UrlEntity entity(Instant createdAt, Instant expiresAt, boolean deactivated) throws Exception {
        UrlEntity e = UrlEntity.create(1, "abc123", "https://x.test", false, "k", "fp", expiresAt);
        Field f = UrlEntity.class.getDeclaredField("createdAt");
        f.setAccessible(true);
        f.set(e, createdAt);
        if (deactivated) {
            e.deactivate();
        }
        return e;
    }

    @Test
    @DisplayName("s6.3: a claim is live only inside the 24h window AND while the link is ACTIVE")
    void claimLiveness() throws Exception {
        assertThat(ClaimRules.isLive(entity(NOW.minus(Duration.ofHours(23)), null, false), NOW, WINDOW)).isTrue();
        assertThat(ClaimRules.isLive(entity(NOW.minus(Duration.ofHours(24)), null, false), NOW, WINDOW)).as("exactly 24h old").isFalse();
        assertThat(ClaimRules.isLive(entity(NOW.minus(Duration.ofHours(25)), null, false), NOW, WINDOW)).isFalse();
        assertThat(ClaimRules.isLive(entity(NOW.minusSeconds(10), null, true), NOW, WINDOW)).as("deactivated").isFalse();
        assertThat(ClaimRules.isLive(entity(NOW.minusSeconds(10), NOW.minusSeconds(1), false), NOW, WINDOW)).as("expired").isFalse();
        assertThat(ClaimRules.isLive(entity(null, null, false), NOW, WINDOW)).as("not yet read back from the DB").isFalse();
    }

    @Test
    @DisplayName("s6.3/s15.2: the fingerprint separates owner, kind (auto vs custom) and URL, and is a stable 64-char SHA-256 hex")
    void fingerprint() {
        String base = IdempotencyKey.fingerprint("key1", false, "https://x.test/a");
        assertThat(base).matches("[0-9a-f]{64}").isEqualTo(IdempotencyKey.fingerprint("key1", false, "https://x.test/a"));
        assertThat(IdempotencyKey.fingerprint("key2", false, "https://x.test/a")).as("owner").isNotEqualTo(base);
        assertThat(IdempotencyKey.fingerprint("key1", true, "https://x.test/a")).as("custom is not a duplicate of auto (s15.2)").isNotEqualTo(base);
        assertThat(IdempotencyKey.fingerprint("key1", false, "https://x.test/b")).as("url").isNotEqualTo(base);
        // Field boundaries are delimited: ("k","ey1x") must not collide with ("key1","x").
        assertThat(IdempotencyKey.fingerprint("k", false, "ey1https://x.test/a")).isNotEqualTo(base);
        assertThat(IdempotencyKey.fingerprint("key1", true, "https://x.test/a"))
                .as("two custom requests for one URL share a fingerprint (s6.3: a different alias on retry replays the original)")
                .isEqualTo(IdempotencyKey.fingerprint("key1", true, "https://x.test/a"));
    }
}
