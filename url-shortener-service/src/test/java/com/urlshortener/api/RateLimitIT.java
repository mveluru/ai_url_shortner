package com.urlshortener.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.urlshortener.testsupport.AbstractIntegrationTest;
import com.urlshortener.testsupport.Covers;
import com.urlshortener.testsupport.TestApi;
import com.urlshortener.testsupport.TestApi.Response;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Per-key create and per-IP redirect limits with REAL (small) values, in their own application context (design doc
 * sections 10.4, 11.3, E19). The per-IP API limit is left huge here on purpose: it is one shared bucket for every test
 * calling from 127.0.0.1, so it is exercised separately in {@link IpRateLimitIT}.
 * "60 requests/minute with burst 3" means 3 permits every 3 seconds: the sustained rate is exact and no window can ever
 * start with more than the burst.
 */
@TestPropertySource(properties = {
        "app.rate-limit.create-per-key.requests-per-minute=60",
        "app.rate-limit.create-per-key.burst=3",
        "app.rate-limit.redirect-per-ip.requests-per-minute=60",
        "app.rate-limit.redirect-per-ip.burst=5",
        "app.rate-limit.api-per-ip.requests-per-minute=6000000",
        "app.rate-limit.api-per-ip.burst=1000000"
})
class RateLimitIT extends AbstractIntegrationTest {

    private String unique() {
        return "https://public.test/" + UUID.randomUUID();
    }

    @Test
    @Covers({"E19"})
    @DisplayName("E19: beyond its burst a key gets 429 RATE_LIMITED with Retry-After; other keys are unaffected; it recovers on refill")
    void perKeyCreateLimit() {
        TestApi api = api();
        String key = api.newApiKey();
        for (int i = 0; i < 3; i++) {
            assertThat(api.create(key, unique()).status()).as("within burst #%d", i).isEqualTo(201);
        }
        Response limited = api.create(key, unique());
        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(limited.header("retry-after"))).isBetween(1L, 5L);
        assertThat(limited.json().path("requestId").asText()).isNotBlank();

        assertThat(api.create(api.newApiKey(), unique()).status()).as("a different key has its own bucket").isEqualTo(201);

        // The limit is per key on CREATE only: reading is not throttled by it.
        String code = api.create(api.newApiKey(), unique()).json().path("shortCode").asText();
        assertThat(api.get("/api/v1/urls/" + code, key).status()).isIn(200, 403);

        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(api.create(key, unique()).status()).as("refilled after the period").isEqualTo(201));
    }

    @Test
    @Covers({"E19"})
    @DisplayName("s10.4: a rate-limited create is rejected BEFORE any work is done - no row is created for it")
    void rejectedRequestsDoNoWork() {
        TestApi api = api();
        String key = api.newApiKey();
        for (int i = 0; i < 3; i++) {
            api.create(key, unique());
        }
        String url = unique();
        assertThat(api.create(key, url).status()).isEqualTo(429);
        // Once it refills, the very same URL is created fresh (201), proving the rejected attempt left nothing behind
        // that would make it an idempotent replay.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                assertThat(api.create(key, url).status()).isEqualTo(201));
    }

    @Test
    @DisplayName("s10.4: the public redirect is capped per IP (much higher than creation, but not unlimited)")
    void perIpRedirectLimit() {
        TestApi api = api();
        String code = api.create(api.newApiKey(), unique()).json().path("shortCode").asText();
        int ok = 0;
        Response limited = null;
        for (int i = 0; i < 12 && limited == null; i++) {
            Response r = api.get("/" + code, Map.of());
            if (r.status() == 429) {
                limited = r;
            } else {
                assertThat(r.status()).isEqualTo(302);
                ok++;
            }
        }
        assertThat(ok).as("requests allowed before throttling (the burst)").isEqualTo(5);
        assertThat(limited).isNotNull();
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(limited.header("retry-after")).isNotBlank();
    }
}
