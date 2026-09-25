package com.urlshortener.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.urlshortener.testsupport.AbstractIntegrationTest;
import com.urlshortener.testsupport.Covers;
import com.urlshortener.testsupport.TestApi;
import com.urlshortener.testsupport.TestApi.Response;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/** POST /api/v1/urls end to end: validation edge cases, aliases, the alias race, and idempotency (sections 6.3, 7). */
class CreateUrlIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    private String unique() {
        return "https://public.test/" + UUID.randomUUID();
    }

    private String alias() {
        return "a" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    // ---- happy path & response shape ---------------------------------------------------------------------------

    @Test
    @DisplayName("201 Created with Location, a 6-char Base62 code, shortUrl and status ACTIVE")
    void createsAutoCode() {
        TestApi api = api();
        Response r = api.create(api.newApiKey(), "https://public.test/a?b=c#d");
        assertThat(r.status()).isEqualTo(201);
        JsonNode json = r.json();
        String code = json.path("shortCode").asText();
        assertThat(code).matches("[0-9A-Za-z]{6}");
        assertThat(r.header("location")).isEqualTo("/api/v1/urls/" + code);
        assertThat(json.path("shortUrl").asText()).endsWith("/" + code);
        assertThat(json.path("longUrl").asText()).isEqualTo("https://public.test/a?b=c#d");
        assertThat(json.path("customAlias").asBoolean()).isFalse();
        assertThat(json.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(json.path("expiresAt").isNull()).isTrue();
        assertThat(Instant.parse(json.path("createdAt").asText())).isCloseTo(Instant.now(), org.assertj.core.api.Assertions.within(30, ChronoUnit.SECONDS));
    }

    // ---- E1-E6: longUrl ----------------------------------------------------------------------------------------

    @Test
    @Covers({"E1", "E2", "E3", "E4", "E5", "E6"})
    @DisplayName("E1-E6: every longUrl rule is enforced through the API and reported as 400 INVALID_URL")
    void invalidUrls() {
        TestApi api = api();
        String key = api.newApiKey();
        List<String> bad = List.of("", "   ", "not a url", "example.com", "javascript:alert(1)", "file:///etc/passwd",
                "ftp://public.test/x", "http://127.0.0.1/", "http://localhost/", "http://internal.test/",
                "http://169.254.169.254/latest/meta-data/", "http://[::1]/", "http://0177.0.0.1/", "http://2130706433/",
                "https://short.ly/abc", "http://localhost:8080/abc",
                "http://public.test/" + "a".repeat(2100));
        for (String url : bad) {
            Response r = api.create(key, url);
            assertThat(r.status()).as("status for %s", url.length() > 60 ? url.substring(0, 60) + "..." : url).isEqualTo(400);
            assertThat(r.code()).as("code for %s", url.length() > 60 ? url.substring(0, 60) : url).isEqualTo("INVALID_URL");
        }
        Response missing = api.post("/api/v1/urls", key, Map.of("customAlias", "nourl123"));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.code()).isEqualTo("INVALID_URL");                        // E1: longUrl missing entirely
        Response nullUrl = api.post("/api/v1/urls", key, "{\"longUrl\":null}");
        assertThat(nullUrl.code()).isEqualTo("INVALID_URL");
    }

    // ---- E9, E10: alias rules; E7, E8, E13 ----------------------------------------------------------------------

    @Test
    @Covers({"E9", "E10"})
    @DisplayName("E9/E10: reserved words -> ALIAS_RESERVED, bad charset/length/leading hyphen -> INVALID_ALIAS")
    void aliasRules() {
        TestApi api = api();
        String key = api.newApiKey();
        for (String reserved : List.of("api", "admin", "API", "Admin", "swagger-ui", "actuator")) {
            Response r = api.create(key, unique(), reserved);
            assertThat(r.status()).as(reserved).isEqualTo(400);
            assertThat(r.code()).as(reserved).isEqualTo("ALIAS_RESERVED");
        }
        for (String invalid : List.of("ab", "-abc", "_abc", "a b c", "ab/cd", "../x", "toolongtoolongtoolong1", "caféx", "")) {
            Response r = api.create(key, unique(), invalid);
            assertThat(r.status()).as("alias '%s'", invalid).isEqualTo(400);
            assertThat(r.code()).as("alias '%s'", invalid).isEqualTo("INVALID_ALIAS");
        }
    }

    @Test
    @Covers({"E7", "E10"})
    @DisplayName("E7: a custom alias is created case-sensitively; a second use is 409 ALIAS_TAKEN; a different case is free")
    void aliasTakenAndCaseSensitive() {
        TestApi api = api();
        String key = api.newApiKey();
        String alias = "Promo" + UUID.randomUUID().toString().substring(0, 6);
        Response first = api.create(key, unique(), alias);
        assertThat(first.status()).isEqualTo(201);
        assertThat(first.json().path("shortCode").asText()).isEqualTo(alias);      // stored as-is
        assertThat(first.json().path("customAlias").asBoolean()).isTrue();

        Response taken = api.create(api.newApiKey(), unique(), alias);
        assertThat(taken.status()).isEqualTo(409);
        assertThat(taken.code()).isEqualTo("ALIAS_TAKEN");

        // 'PROMO...' and 'Promo...' are different links (utf8mb4_bin collation, section 20.4a)
        Response otherCase = api.create(key, unique(), alias.toUpperCase());
        assertThat(otherCase.status()).isEqualTo(201);
        assertThat(otherCase.json().path("shortCode").asText()).isEqualTo(alias.toUpperCase());
    }

    @Test
    @Covers({"E8"})
    @DisplayName("E8: an alias whose code was DEACTIVATED is still 409 - never silently recycled")
    void softDeletedAliasIsNotRecycled() {
        TestApi api = api();
        String key = api.newApiKey();
        String alias = alias();
        assertThat(api.create(key, unique(), alias).status()).isEqualTo(201);
        assertThat(api.delete("/api/v1/urls/" + alias, key).status()).isEqualTo(204);

        Response again = api.create(api.newApiKey(), unique(), alias);
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.code()).isEqualTo("ALIAS_TAKEN");
    }

    @Test
    @Covers({"E13", "E7"})
    @DisplayName("E13: many simultaneous requests for one alias -> exactly one 201, all others 409, decided by the DB constraint")
    void aliasRace() throws Exception {
        TestApi api = api();
        String alias = alias();
        int contenders = 12;
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            keys.add(api.newApiKey());
        }
        List<Callable<Response>> calls = new ArrayList<>();
        for (String k : keys) {
            calls.add(() -> api.create(k, unique(), alias));
        }
        List<Response> results = runConcurrently(calls);

        List<Integer> statuses = results.stream().map(Response::status).sorted().collect(Collectors.toList());
        assertThat(statuses.stream().filter(s -> s == 201).count()).as("statuses %s", statuses).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).as("statuses %s", statuses).isEqualTo(contenders - 1);
        assertThat(results.stream().filter(r -> r.status() == 409).map(Response::code).distinct()).containsExactly("ALIAS_TAKEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM urls WHERE short_code = ?", Integer.class, alias)).isEqualTo(1);
    }

    // ---- E11, E12: expiry --------------------------------------------------------------------------------------

    @Test
    @Covers({"E11", "E12"})
    @DisplayName("E11/E12: past or unparseable expiresAt -> INVALID_EXPIRY; absent -> never expires; future is stored")
    void expiry() {
        TestApi api = api();
        String key = api.newApiKey();
        for (String bad : List.of("2020-01-01T00:00:00Z", "yesterday", "2999-01-01", "")) {
            Response r = api.post("/api/v1/urls", key, Map.of("longUrl", unique(), "expiresAt", bad));
            assertThat(r.status()).as(bad).isEqualTo(400);
            assertThat(r.code()).as(bad).isEqualTo("INVALID_EXPIRY");
        }
        Response never = api.create(key, unique());
        assertThat(never.json().path("expiresAt").isNull()).isTrue();

        String future = Instant.now().plus(2, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        Response ok = api.post("/api/v1/urls", key, Map.of("longUrl", unique(), "expiresAt", future));
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.json().path("expiresAt").asText()).isEqualTo(future);
    }

    // ---- section 6.3 / 15.2: idempotency -----------------------------------------------------------------------

    @Test
    @DisplayName("s6.3: the same (key, longUrl) again returns 200 + Idempotent-Replay with the SAME code, not a duplicate")
    void idempotentReplay() {
        TestApi api = api();
        String key = api.newApiKey();
        String url = unique();
        Response first = api.create(key, url);
        Response second = api.create(key, url);
        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.header("idempotent-replay")).isEqualTo("true");
        assertThat(second.json().path("shortCode").asText()).isEqualTo(first.json().path("shortCode").asText());
        assertThat(first.header("idempotent-replay")).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM urls WHERE long_url = ?", Integer.class, url)).isEqualTo(1);
    }

    @Test
    @DisplayName("s6.3: a retry with a different expiresAt does not create or update anything - the first request's parameters win")
    void firstRequestParametersWin() {
        TestApi api = api();
        String key = api.newApiKey();
        String url = unique();
        String firstExpiry = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        String laterExpiry = Instant.now().plus(9, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
        Response first = api.post("/api/v1/urls", key, Map.of("longUrl", url, "expiresAt", firstExpiry));
        Response retry = api.post("/api/v1/urls", key, Map.of("longUrl", url, "expiresAt", laterExpiry));
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.json().path("shortCode").asText()).isEqualTo(first.json().path("shortCode").asText());
        assertThat(retry.json().path("expiresAt").asText()).isEqualTo(firstExpiry);
    }

    @Test
    @DisplayName("s6.3: a retry naming a DIFFERENT customAlias for the same URL returns the original custom resource")
    void differentAliasOnRetryReturnsOriginal() {
        TestApi api = api();
        String key = api.newApiKey();
        String url = unique();
        String first = alias();
        Response created = api.create(key, url, first);
        Response retry = api.create(key, url, alias());
        assertThat(created.status()).isEqualTo(201);
        assertThat(retry.status()).isEqualTo(200);
        assertThat(retry.json().path("shortCode").asText()).isEqualTo(first);
    }

    @Test
    @DisplayName("s15.2: a custom-alias request is NOT a duplicate of an auto-generated code for the same URL (and vice versa)")
    void aliasRequestIsNotADuplicateOfAutoCode() {
        TestApi api = api();
        String key = api.newApiKey();
        String url = unique();
        Response auto = api.create(key, url);
        String customAlias = alias();
        Response custom = api.create(key, url, customAlias);
        assertThat(auto.status()).isEqualTo(201);
        assertThat(custom.status()).isEqualTo(201);                                 // a NEW resource, not a 200 replay
        assertThat(custom.json().path("shortCode").asText()).isEqualTo(customAlias).isNotEqualTo(auto.json().path("shortCode").asText());

        // ...and the other direction, with a fresh URL
        String url2 = unique();
        Response custom2 = api.create(key, url2, alias());
        Response auto2 = api.create(key, url2);
        assertThat(custom2.status()).isEqualTo(201);
        assertThat(auto2.status()).isEqualTo(201);
        assertThat(auto2.json().path("customAlias").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("s6.3: idempotency is per API key - another key shortening the same URL gets its own code")
    void idempotencyIsPerKey() {
        TestApi api = api();
        String url = unique();
        Response a = api.create(api.newApiKey(), url);
        Response b = api.create(api.newApiKey(), url);
        assertThat(a.status()).isEqualTo(201);
        assertThat(b.status()).isEqualTo(201);
        assertThat(a.json().path("shortCode").asText()).isNotEqualTo(b.json().path("shortCode").asText());
    }

    @Test
    @DisplayName("s6.3: after DELETE the URL can be shortened again immediately (a deactivated link is not replayed)")
    void recreateAfterDeactivate() {
        TestApi api = api();
        String key = api.newApiKey();
        String url = unique();
        Response first = api.create(key, url);
        assertThat(api.delete("/api/v1/urls/" + first.json().path("shortCode").asText(), key).status()).isEqualTo(204);
        Response again = api.create(key, url);
        assertThat(again.status()).isEqualTo(201);
        assertThat(again.json().path("shortCode").asText()).isNotEqualTo(first.json().path("shortCode").asText());
    }

    @Test
    @DisplayName("s6.3: once the 24h window has passed the claim is released and a new code is minted")
    void windowExpiry() {
        TestApi api = api();
        String key = api.newApiKey();
        String url = unique();
        Response first = api.create(key, url);
        jdbc.update("UPDATE urls SET created_at = created_at - INTERVAL 25 HOUR WHERE short_code = ?",
                first.json().path("shortCode").asText());
        Response later = api.create(key, url);
        assertThat(later.status()).isEqualTo(201);
        assertThat(later.json().path("shortCode").asText()).isNotEqualTo(first.json().path("shortCode").asText());
    }

    @Test
    @DisplayName("s6.3/s20.1: identical requests racing each other yield ONE resource: one 201, the rest 200 with the same code")
    void idempotencyRace() throws Exception {
        TestApi api = api();
        String key = api.newApiKey();
        String url = unique();
        // Stay below the per-key create limit (burst 20) so this measures idempotency, not rate limiting.
        List<Callable<Response>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(() -> api.create(key, url));
        }
        List<Response> results = runConcurrently(calls);

        assertThat(results.stream().map(Response::status).collect(Collectors.toList()))
                .as("all succeed").allMatch(s -> s == 200 || s == 201);
        assertThat(results.stream().filter(r -> r.status() == 201).count()).isEqualTo(1);
        assertThat(results.stream().map(r -> r.json().path("shortCode").asText()).distinct().count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM urls WHERE long_url = ?", Integer.class, url)).isEqualTo(1);
    }

    // ---- codes are unique and not guessable ---------------------------------------------------------------------

    @Test
    @DisplayName("s4: codes are unique across many creations and do not look sequential")
    void codesUniqueAndNotSequential() {
        TestApi api = api();
        String key = api.newApiKey();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 15; i++) {                                              // below the per-key limit
            codes.add(api.create(key, unique()).json().path("shortCode").asText());
        }
        assertThat(codes).doesNotHaveDuplicates().allMatch(c -> c.matches("[0-9A-Za-z]{6}"));
        assertThat(codes).isNotEqualTo(codes.stream().sorted().collect(Collectors.toList()));
    }

    private <T> List<T> runConcurrently(List<Callable<T>> calls) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(calls.size())) {
            List<Future<T>> futures = pool.invokeAll(calls);
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        }
    }
}
