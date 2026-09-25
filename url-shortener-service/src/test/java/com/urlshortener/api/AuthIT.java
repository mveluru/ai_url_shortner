package com.urlshortener.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.testsupport.AbstractIntegrationTest;
import com.urlshortener.testsupport.Covers;
import com.urlshortener.testsupport.TestApi;
import com.urlshortener.testsupport.TestApi.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** API-key authentication (design doc section 10.1) and the public/authenticated split (section 8.1). */
class AuthIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("401 UNAUTHORIZED, in the standard error shape, for a missing key")
    void missingKey() {
        Response r = api().send("POST", "/api/v1/urls", null, "{\"longUrl\":\"https://public.test/x\"}",
                Map.of("Content-Type", "application/json"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.code()).isEqualTo("UNAUTHORIZED");
        assertThat(r.json().path("requestId").asText()).isNotBlank().isEqualTo(r.header("x-request-id"));
        assertThat(r.json().path("path").asText()).isEqualTo("/api/v1/urls");
    }

    @Test
    @DisplayName("401 for garbage, wrong-secret, unknown-id and wrong-prefix keys - one uniform error, no hint which part was wrong")
    void invalidKeys() {
        TestApi api = api();
        String real = api.newApiKey();
        String keyId = real.substring("usk_".length(), real.indexOf('.'));
        List<String> bad = List.of("garbage", "usk_", "usk_nodot", "usk_" + keyId + ".wrongsecret",
                "usk_ffffffffffffffffffffffff.whatever", "Bearer " + real, real.substring(1));
        for (String key : bad) {
            Response r = api.get("/api/v1/urls/whatever1", key);
            assertThat(r.status()).as(key).isEqualTo(401);
            assertThat(r.code()).as(key).isEqualTo("UNAUTHORIZED");
            assertThat(r.json().path("message").asText()).as(key).isEqualTo("Missing or invalid API key.");
        }
    }

    @Test
    @DisplayName("s10.1: only an Argon2 hash is stored - the plaintext key and secret never touch the database")
    void hashedAtRest() {
        String key = api().newApiKey();
        String secret = key.substring(key.indexOf('.') + 1);
        String keyId = key.substring("usk_".length(), key.indexOf('.'));
        String stored = jdbc.queryForObject("SELECT key_hash FROM api_keys WHERE key_id = ?", String.class, keyId);
        assertThat(stored).startsWith("$argon2").doesNotContain(secret);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_keys WHERE key_hash LIKE ?", Integer.class, "%" + secret + "%"))
                .isZero();
    }

    @Test
    @DisplayName("s10.1: rotation = issue a new key and revoke the old one; the revoked key stops working, the new one works")
    void rotation() {
        TestApi api = api();
        String oldKey = api.newApiKey();
        String oldId = oldKey.substring("usk_".length(), oldKey.indexOf('.'));
        assertThat(api.create(oldKey, "https://public.test/" + UUID.randomUUID()).status()).isEqualTo(201);

        String newKey = api.newApiKey();
        assertThat(api.send("DELETE", "/internal/api-keys/" + oldId, null, null, Map.of()).status()).isEqualTo(204);

        assertThat(api.create(oldKey, "https://public.test/" + UUID.randomUUID()).status()).isEqualTo(401);
        assertThat(api.create(newKey, "https://public.test/" + UUID.randomUUID()).status()).isEqualTo(201);
    }

    @Test
    @Covers({"F11"})
    @DisplayName("s8.1/s10.1: the redirect endpoint is public - it needs no key and never consults the auth store")
    void redirectIsPublic() {
        TestApi api = api();
        String code = api.create(api.newApiKey(), "https://public.test/" + UUID.randomUUID()).json().path("shortCode").asText();
        assertThat(api.get("/" + code, Map.of()).status()).isEqualTo(302);            // no X-API-Key header at all
        assertThat(api.get("/" + code, "usk_totally.invalid").status()).isEqualTo(302); // a bad key on a public route is irrelevant
    }

    @Test
    @DisplayName("management routes stay protected even for paths that do not exist: 401 before any routing detail leaks")
    void unknownManagementPathStillNeedsAuth() {
        assertThat(api().get("/api/v1/does-not-exist", (String) null).status()).isEqualTo(401);
    }
}
