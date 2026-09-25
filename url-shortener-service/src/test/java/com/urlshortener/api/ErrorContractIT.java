package com.urlshortener.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.testsupport.AbstractIntegrationTest;
import com.urlshortener.testsupport.TestApi;
import com.urlshortener.testsupport.TestApi.Response;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The error taxonomy (design doc section 9) observed over real HTTP: every body has one shape and a requestId. */
class ErrorContractIT extends AbstractIntegrationTest {

    private static final Map<String, String> JSON = Map.of("Content-Type", "application/json");

    @Test
    @DisplayName("s9.1: every error body has code, message, requestId, timestamp, path and fieldErrors; requestId == X-Request-Id")
    void errorShape() {
        Response r = api().get("/nope1234", Map.of());
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.json().fieldNames()).toIterable()
                .containsExactlyInAnyOrder("code", "message", "requestId", "timestamp", "path", "fieldErrors");
        assertThat(r.json().path("requestId").asText()).isNotBlank().isEqualTo(r.header("x-request-id"));
        assertThat(r.json().path("path").asText()).isEqualTo("/nope1234");
        assertThat(r.json().path("fieldErrors").isNull()).isTrue();
        assertThat(r.header("content-type")).startsWith("application/json");
    }

    @Test
    @DisplayName("s9.4: a well-formed incoming X-Request-Id is honoured (gateway-generated ids propagate); junk is replaced")
    void requestIdPropagation() {
        Response honoured = api().get("/nope1234", Map.of("X-Request-Id", "gateway-abc-123"));
        assertThat(honoured.json().path("requestId").asText()).isEqualTo("gateway-abc-123");
        Response replaced = api().get("/nope1234", Map.of("X-Request-Id", "bad id with spaces & symbols!"));
        assertThat(replaced.json().path("requestId").asText()).isNotEqualTo("bad id with spaces & symbols!").doesNotContain(" ");
    }

    @Test
    @DisplayName("MALFORMED_JSON: broken JSON, a non-object body, and wrong JSON types (number for a string field)")
    void malformedJson() {
        TestApi api = api();
        String key = api.newApiKey();
        for (String body : new String[] {"{not json", "[]", "\"just a string\"", "{\"longUrl\": 12345}",
                "{\"longUrl\":\"https://public.test/x\",\"expiresAt\": 1893456000}",
                "{\"longUrl\":\"https://public.test/x\",\"customAlias\": true}", ""}) {
            Response r = api.send("POST", "/api/v1/urls", key, body, JSON);
            assertThat(r.status()).as(body).isEqualTo(400);
            assertThat(r.code()).as(body).isEqualTo("MALFORMED_JSON");
        }
    }

    @Test
    @DisplayName("UNSUPPORTED_MEDIA_TYPE (415) for a non-JSON Content-Type")
    void unsupportedMediaType() {
        TestApi api = api();
        Response r = api.send("POST", "/api/v1/urls", api.newApiKey(), "longUrl=x", Map.of("Content-Type", "text/plain"));
        assertThat(r.status()).isEqualTo(415);
        assertThat(r.code()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    @DisplayName("NOT_ACCEPTABLE (406) when the client only accepts a non-JSON representation of an API resource")
    void notAcceptable() {
        TestApi api = api();
        Response r = api.send("POST", "/api/v1/urls", api.newApiKey(), "{\"longUrl\":\"https://public.test/x\"}",
                Map.of("Content-Type", "application/json", "Accept", "application/xml"));
        assertThat(r.status()).isEqualTo(406);
        assertThat(r.code()).isEqualTo("NOT_ACCEPTABLE");
    }

    @Test
    @DisplayName("METHOD_NOT_ALLOWED (405) with an Allow header, e.g. PATCH on a resource that has no update operation")
    void methodNotAllowed() {
        TestApi api = api();
        Response r = api.send("PATCH", "/api/v1/urls/abc123", api.newApiKey(), "{}", JSON);
        assertThat(r.status()).isEqualTo(405);
        assertThat(r.code()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(r.header("allow")).contains("GET");
    }

    @Test
    @DisplayName("NO_HANDLER (404) for a path that matches no route, still with the standard body and requestId (s20.4)")
    void noHandler() {
        TestApi api = api();
        Response r = api.get("/a/b/c/d", Map.of());
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("NO_HANDLER");
        assertThat(r.json().path("requestId").asText()).isNotBlank();

        Response authed = api.get("/api/v1/does-not-exist", api.newApiKey());
        assertThat(authed.status()).isEqualTo(404);
        assertThat(authed.code()).isEqualTo("NO_HANDLER");
    }

    @Test
    @DisplayName("INVALID_RANGE (400): from after to, and malformed dates")
    void invalidRange() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = api.create(key, "https://public.test/" + UUID.randomUUID()).json().path("shortCode").asText();
        for (String query : new String[] {"from=2026-02-01&to=2026-01-01", "from=not-a-date", "to=2026-13-45", "from=20260101"}) {
            Response r = api.get("/api/v1/urls/" + code + "/stats?" + query, key);
            assertThat(r.status()).as(query).isEqualTo(400);
            assertThat(r.code()).as(query).isEqualTo("INVALID_RANGE");
        }
    }

    @Test
    @DisplayName("s9.4: error bodies never contain stack traces, SQL or exception class names")
    void noInternalsLeak() {
        TestApi api = api();
        String key = api.newApiKey();
        for (Response r : new Response[] {api.get("/nope1234", Map.of()), api.send("POST", "/api/v1/urls", key, "{bad", JSON),
                api.get("/a/b/c", Map.of()), api.get("/api/v1/urls/x", "bad-key")}) {
            assertThat(r.body()).doesNotContain("Exception").doesNotContain("at com.").doesNotContain("org.springframework")
                    .doesNotContain("SELECT ").doesNotContain("java.");
        }
    }
}
