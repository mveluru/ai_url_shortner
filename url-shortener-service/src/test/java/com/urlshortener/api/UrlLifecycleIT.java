package com.urlshortener.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.redirect.cache.UrlCache;
import com.urlshortener.testsupport.AbstractIntegrationTest;
import com.urlshortener.testsupport.Covers;
import com.urlshortener.testsupport.TestApi;
import com.urlshortener.testsupport.TestApi.Response;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Metadata, deactivation and ownership (design doc sections 6.2, 7 E14-E16/E23, 10.1). */
class UrlLifecycleIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UrlCache cache;

    private String unique() {
        return "https://public.test/" + UUID.randomUUID();
    }

    private String createCode(TestApi api, String key) {
        return api.create(key, unique()).json().path("shortCode").asText();
    }

    @Test
    @DisplayName("GET metadata returns the resource with status ACTIVE")
    void metadata() {
        TestApi api = api();
        String key = api.newApiKey();
        String url = unique();
        String code = api.create(key, url).json().path("shortCode").asText();
        Response r = api.get("/api/v1/urls/" + code, key);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().path("shortCode").asText()).isEqualTo(code);
        assertThat(r.json().path("longUrl").asText()).isEqualTo(url);
        assertThat(r.json().path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @Covers({"E16"})
    @DisplayName("metadata for a code that never existed -> 404 NOT_FOUND")
    void unknownCode() {
        TestApi api = api();
        Response r = api.get("/api/v1/urls/doesnotexist1", api.newApiKey());
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("NOT_FOUND");
    }

    @Test
    @Covers({"E14", "E15"})
    @DisplayName("E14 rationale: the PUBLIC redirect says only 404, the authenticated metadata says WHY (EXPIRED / DEACTIVATED)")
    void reasonOnlyOnAuthenticatedEndpoint() {
        TestApi api = api();
        String key = api.newApiKey();
        String expired = createCode(api, key);
        String deactivated = createCode(api, key);
        // Expiry is normally fixed at creation (and the cache TTL is capped by it). Back-dating the row directly is a
        // test shortcut, so drop the write-through cache entry the way a real expiry would have.
        jdbc.update("UPDATE urls SET expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 HOUR WHERE short_code = ?", expired);
        cache.invalidate(expired);
        api.delete("/api/v1/urls/" + deactivated, key);

        assertThat(api.get("/api/v1/urls/" + expired, key).json().path("status").asText()).isEqualTo("EXPIRED");
        assertThat(api.get("/api/v1/urls/" + deactivated, key).json().path("status").asText()).isEqualTo("DEACTIVATED");

        // Public surface: indistinguishable from "never existed" - no 410, no lifetime leak.
        Response expiredRedirect = api.get("/" + expired, java.util.Map.of());
        Response deactivatedRedirect = api.get("/" + deactivated, java.util.Map.of());
        Response neverExisted = api.get("/zzzNeverExisted9", java.util.Map.of());
        for (Response r : List.of(expiredRedirect, deactivatedRedirect, neverExisted)) {
            assertThat(r.status()).isEqualTo(404);
            assertThat(r.code()).isEqualTo("NOT_FOUND");
            assertThat(r.json().path("message").asText()).isEqualTo("No such short URL.");
        }
    }

    @Test
    @Covers({"E23"})
    @DisplayName("E23: DELETE -> 204, the second DELETE -> 404 (already inactive), the row is soft-deleted not removed")
    void deleteTwice() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = createCode(api, key);
        assertThat(api.delete("/api/v1/urls/" + code, key).status()).isEqualTo(204);
        Response second = api.delete("/api/v1/urls/" + code, key);
        assertThat(second.status()).isEqualTo(404);
        assertThat(second.code()).isEqualTo("NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT is_active FROM urls WHERE short_code = ?", Boolean.class, code)).isFalse();
    }

    @Test
    @Covers({"E23", "E13"})
    @DisplayName("Two simultaneous DELETEs: exactly one 204; the loser is 404 or 409 RESOURCE_MODIFIED (optimistic lock), never a second success")
    void concurrentDelete() throws Exception {
        TestApi api = api();
        String key = api.newApiKey();
        String code = createCode(api, key);
        List<Response> results;
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            List<Callable<Response>> calls = java.util.stream.IntStream.range(0, 6)
                    .<Callable<Response>>mapToObj(i -> () -> api.delete("/api/v1/urls/" + code, key)).toList();
            results = pool.invokeAll(calls).stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }).collect(Collectors.toList());
        }
        assertThat(results.stream().filter(r -> r.status() == 204).count()).isEqualTo(1);
        assertThat(results.stream().filter(r -> r.status() != 204).map(r -> r.status() + ":" + r.code()).distinct())
                .isSubsetOf("404:NOT_FOUND", "409:RESOURCE_MODIFIED");
        assertThat(api.delete("/api/v1/urls/" + code, key).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("s10.1: another key's code -> 403 FORBIDDEN for metadata, delete and stats (distinguishable from 404, unlike the public surface)")
    void ownership() {
        TestApi api = api();
        String owner = api.newApiKey();
        String intruder = api.newApiKey();
        String code = createCode(api, owner);
        for (Response r : List.of(api.get("/api/v1/urls/" + code, intruder), api.delete("/api/v1/urls/" + code, intruder),
                api.get("/api/v1/urls/" + code + "/stats", intruder))) {
            assertThat(r.status()).isEqualTo(403);
            assertThat(r.code()).isEqualTo("FORBIDDEN");
        }
        assertThat(api.get("/api/v1/urls/" + code, owner).status()).isEqualTo(200);   // the owner is unaffected
        assertThat(api.get("/" + code, java.util.Map.of()).status()).isEqualTo(302);  // and the link still works
    }

    @Test
    @DisplayName("E23/E15: deactivating makes the redirect 404 immediately (cache invalidated, not left stale)")
    void deactivationTakesEffectImmediately() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = createCode(api, key);
        assertThat(api.get("/" + code, java.util.Map.of()).status()).isEqualTo(302);   // warms/uses the cache
        api.delete("/api/v1/urls/" + code, key);
        assertThat(api.get("/" + code, java.util.Map.of()).status()).isEqualTo(404);
    }
}
