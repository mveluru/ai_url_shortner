package com.urlshortener.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.jobs.ExpirySweepJob;
import com.urlshortener.testsupport.AbstractIntegrationTest;
import com.urlshortener.testsupport.Covers;
import com.urlshortener.testsupport.TestApi;
import com.urlshortener.testsupport.TestApi.Response;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** GET /{shortCode}: the redirect path's semantics and its cache-aside behaviour (design doc sections 6.4, 7, 8, F2, F12). */
class RedirectIT extends AbstractIntegrationTest {

    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;
    @Autowired ExpirySweepJob sweep;

    private String create(TestApi api, String key, String url) {
        return api.create(key, url).json().path("shortCode").asText();
    }

    private String unique() {
        return "https://public.test/" + UUID.randomUUID();
    }

    private double count(String cacheTag) {
        var s = meters.find("redirect_latency_ms").tag("cache", cacheTag).tag("result", "found").summary();
        return s == null ? 0 : s.count();
    }

    // ---- s6.4 ----------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("s6.4: 302 Found (never 301) with Location, and Cache-Control exactly 'no-store'")
    void redirectSemantics() {
        TestApi api = api();
        String url = "https://public.test/some/path?x=1&y=%20z#frag";
        String code = create(api, api.newApiKey(), url);
        Response r = api.get("/" + code, Map.of());
        assertThat(r.status()).isEqualTo(302);
        assertThat(r.header("location")).isEqualTo(url);
        assertThat(r.header("cache-control")).isEqualTo("no-store");
        assertThat(r.body()).isEmpty();
    }

    @Test
    @DisplayName("s6.4: the redirect works for any Accept header (a browser sends text/html), unlike the JSON API")
    void anyAcceptHeader() {
        TestApi api = api();
        String code = create(api, api.newApiKey(), unique());
        for (String accept : List.of("text/html,application/xhtml+xml;q=0.9", "application/xml", "text/plain", "*/*")) {
            assertThat(api.get("/" + code, Map.of("Accept", accept)).status()).as(accept).isEqualTo(302);
        }
    }

    // ---- E14-E16, E18 ----------------------------------------------------------------------------------------------

    @Test
    @Covers({"E16"})
    @DisplayName("E16: a code that never existed -> 404 NOT_FOUND with the standard body")
    void neverExisted() {
        Response r = api().get("/NeverThere99", Map.of());
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("NOT_FOUND");
        assertThat(r.json().path("requestId").asText()).isNotBlank();
    }

    @Test
    @Covers({"E14", "F12"})
    @DisplayName("E14/F12: an expired code is 404 whether or not the sweep job has run (read-time check, DB path)")
    void expiredIsNotFoundWithoutSweep() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = create(api, key, unique());
        jdbc.update("UPDATE urls SET expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE short_code = ?", code);
        redis.delete(List.of("url:" + code, "url:stale:" + code));
        // is_active is still 1: nothing has swept it. Correctness must not depend on the sweep.
        assertThat(jdbc.queryForObject("SELECT is_active FROM urls WHERE short_code = ?", Boolean.class, code)).isTrue();
        assertThat(api.get("/" + code, Map.of()).status()).isEqualTo(404);
    }

    @Test
    @Covers({"E14", "F12"})
    @DisplayName("E14/F12: a cached entry whose expiry has passed is refused at read time (cache path) - never trust TTL alone")
    void expiredCachedEntryIsRefused() {
        TestApi api = api();
        String code = create(api, api.newApiKey(), unique());
        redis.opsForValue().set("url:" + code,
                "{\"longUrl\":\"https://public.test/stale\",\"expiresAt\":\"2020-01-01T00:00:00Z\",\"active\":true,\"version\":1}");
        Response r = api.get("/" + code, Map.of());
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("NOT_FOUND");
    }

    @Test
    @Covers({"F12"})
    @DisplayName("F12: the sweep only soft-deletes for storage hygiene; the metadata endpoint still reports EXPIRED, not DEACTIVATED")
    void sweepIsHygieneOnly() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = create(api, key, unique());
        jdbc.update("UPDATE urls SET expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE short_code = ?", code);

        assertThat(sweep.run()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT is_active FROM urls WHERE short_code = ?", Boolean.class, code)).isFalse();
        assertThat(redis.hasKey("url:" + code)).isFalse();                                   // cache invalidated too
        assertThat(api.get("/api/v1/urls/" + code, key).json().path("status").asText()).isEqualTo("EXPIRED");
        assertThat(api.get("/" + code, Map.of()).status()).isEqualTo(404);
        assertThat(sweep.run()).isZero();                                                     // idempotent
    }

    @Test
    @Covers({"E15"})
    @DisplayName("E15: a deactivated code is 404 on the public surface")
    void deactivated() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = create(api, key, unique());
        api.delete("/api/v1/urls/" + code, key);
        assertThat(api.get("/" + code, Map.of()).status()).isEqualTo(404);
    }

    @Test
    @Covers({"E18"})
    @DisplayName("E18: characters outside the code charset -> 404 NOT_FOUND (not a 400), in the standard body")
    void invalidCharacters() {
        TestApi api = api();
        for (String path : List.of("/ab", "/a.b.c", "/ab$cd", "/ab~cd", "/ab,cd", "/ab'cd", "/%E2%82%AC%E2%82%AC%E2%82%AC",
                "/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "/-leading-hyphen", "/ab%20cd", "/ab%3Bcd", "/select%201")) {
            Response r = api.get(path, Map.of());
            assertThat(r.status()).as(path).isEqualTo(404);
            assertThat(r.code()).as(path).isEqualTo("NOT_FOUND");
            assertThat(r.json().path("requestId").asText()).as(path).isNotBlank();
        }
    }

    @Test
    @Covers({"E18"})
    @DisplayName("E18: paths the firewall/container refuse outright (encoded slash, semicolon, NUL, bad escape) are also a plain 404 JSON")
    void firewalledPaths() {
        TestApi api = api();
        for (String line : List.of("GET /ab%2Fcd HTTP/1.1", "GET /abc;jsessionid=1 HTTP/1.1", "GET /abc%00 HTTP/1.1",
                "GET /%zz HTTP/1.1", "GET /abc%5Cdef HTTP/1.1", "GET /abc%2e%2e/x HTTP/1.1")) {
            Response r = api.raw(line, Map.of());
            assertThat(r.status()).as(line).isIn(400, 404);
            assertThat(r.header("content-type")).as(line).startsWith("application/json");
            assertThat(r.json().path("requestId").asText()).as(line).isNotBlank();
            assertThat(r.json().fieldNames()).toIterable().as(line)
                    .contains("code", "message", "requestId", "timestamp", "path");
        }
    }

    // ---- E24 -----------------------------------------------------------------------------------------------------

    @Test
    @Covers({"E24"})
    @DisplayName("E24: enormous Referer and User-Agent headers never break the redirect")
    void hugeHeaders() {
        TestApi api = api();
        String code = create(api, api.newApiKey(), unique());
        Response r = api.get("/" + code, Map.of("Referer", "https://ref.example/" + "r".repeat(20_000),
                "User-Agent", "Mozilla/5.0 " + "u".repeat(20_000)));
        assertThat(r.status()).isEqualTo(302);
    }

    // ---- cache-aside behaviour (F2) ---------------------------------------------------------------------------------

    @Test
    @Covers({"F2"})
    @DisplayName("F2: create writes through to Redis; DELETE invalidates (deletes) both the entry and its stale copy")
    void writeThroughAndInvalidate() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = create(api, key, unique());
        assertThat(redis.hasKey("url:" + code)).isTrue();
        assertThat(redis.hasKey("url:stale:" + code)).isTrue();

        api.delete("/api/v1/urls/" + code, key);
        assertThat(redis.hasKey("url:" + code)).isFalse();
        assertThat(redis.hasKey("url:stale:" + code)).isFalse();
    }

    @Test
    @Covers({"F1", "F2"})
    @DisplayName("cache-aside: a miss reads MySQL and repopulates Redis; the next request is a hit")
    void missThenHit() {
        TestApi api = api();
        String code = create(api, api.newApiKey(), unique());
        redis.delete(List.of("url:" + code, "url:stale:" + code));

        double misses = count("miss");
        double hits = count("hit");
        assertThat(api.get("/" + code, Map.of()).status()).isEqualTo(302);
        assertThat(redis.hasKey("url:" + code)).as("repopulated by the miss").isTrue();
        assertThat(api.get("/" + code, Map.of()).status()).isEqualTo(302);

        assertThat(count("miss")).isEqualTo(misses + 1);
        assertThat(count("hit")).isEqualTo(hits + 1);
    }

    @Test
    @DisplayName("a cache entry never outlives the link's own expiry (TTL is capped at time-to-expiry)")
    void ttlCappedByExpiry() {
        TestApi api = api();
        String key = api.newApiKey();
        String expiresAt = Instant.now().plus(45, ChronoUnit.SECONDS).truncatedTo(ChronoUnit.SECONDS).toString();
        String code = api.post("/api/v1/urls", key, Map.of("longUrl", unique(), "expiresAt", expiresAt))
                .json().path("shortCode").asText();
        Long ttl = redis.getExpire("url:" + code, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isBetween(1L, 45L);                     // default TTL is ~10 minutes; expiry wins
        Long staleTtl = redis.getExpire("url:stale:" + code, TimeUnit.SECONDS);
        assertThat(staleTtl).isNotNull().isBetween(1L, 45L);
    }

    @Test
    @DisplayName("a corrupt cache entry behaves like a miss (the DB is the source of truth) and is repaired")
    void corruptEntry() {
        TestApi api = api();
        String url = unique();
        String code = create(api, api.newApiKey(), url);
        redis.opsForValue().set("url:" + code, "{{{ not json");
        assertThat(api.get("/" + code, Map.of()).header("location")).isEqualTo(url);
        assertThat(redis.opsForValue().get("url:" + code)).contains(url);
    }

    @Test
    @DisplayName("short codes are case-sensitive end to end: 'aBc123' and 'AbC123' redirect to different targets")
    void caseSensitiveCodes() {
        TestApi api = api();
        String key = api.newApiKey();
        String suffix = UUID.randomUUID().toString().substring(0, 5);
        String lower = "case" + suffix.toLowerCase();
        String upper = "CASE" + suffix.toLowerCase();
        String urlA = unique();
        String urlB = unique();
        assertThat(api.create(key, urlA, lower).status()).isEqualTo(201);
        assertThat(api.create(key, urlB, upper).status()).isEqualTo(201);
        assertThat(api.get("/" + lower, Map.of()).header("location")).isEqualTo(urlA);
        assertThat(api.get("/" + upper, Map.of()).header("location")).isEqualTo(urlB);
        redis.delete(List.of("url:" + lower, "url:" + upper, "url:stale:" + lower, "url:stale:" + upper));
        assertThat(api.get("/" + lower, Map.of()).header("location")).as("DB path").isEqualTo(urlA);
        assertThat(api.get("/" + upper, Map.of()).header("location")).as("DB path").isEqualTo(urlB);
    }

    @Test
    @DisplayName("HEAD works like GET for links (link previews / crawlers), and POST to a code is 405")
    void headAndMethods() {
        TestApi api = api();
        String code = create(api, api.newApiKey(), unique());
        assertThat(api.send("HEAD", "/" + code, null, null, Map.of()).status()).isEqualTo(302);
        Response post = api.send("POST", "/" + code, null, "{}", Map.of("Content-Type", "application/json"));
        assertThat(post.status()).isEqualTo(405);
        assertThat(post.code()).isEqualTo("METHOD_NOT_ALLOWED");
    }
}
