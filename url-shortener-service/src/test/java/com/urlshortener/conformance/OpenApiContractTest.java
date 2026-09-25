package com.urlshortener.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.analytics.api.DailyStats;
import com.urlshortener.analytics.api.UrlStats;
import com.urlshortener.common.error.ErrorCode;
import com.urlshortener.common.error.ErrorResponse;
import com.urlshortener.common.error.FieldError;
import com.urlshortener.shortener.api.CreateUrlRequest;
import com.urlshortener.shortener.api.UrlResource;
import com.urlshortener.shortener.domain.UrlStatus;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Keeps {@code docs/openapi.yaml} and the hand-written Java DTOs from drifting apart (design doc sections 20.3, 20.6). The
 * controller <em>interfaces</em> are generated from the spec, so a controller cannot drift; the DTOs are hand-written records
 * (section 20.3), so this test is what makes their agreement with the contract mechanical instead of hoped-for.
 */
class OpenApiContractTest {

    private static Map<String, Object> spec;

    @BeforeAll
    @SuppressWarnings("unchecked")
    static void load() throws IOException {
        spec = new Yaml().load(Files.readString(Path.of("..", "docs", "openapi.yaml")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> at(Object node, String... path) {
        Object current = node;
        for (String key : path) {
            current = ((Map<String, Object>) current).get(key);
            assertThat(current).as("missing %s in openapi.yaml", String.join(".", path)).isNotNull();
        }
        return (Map<String, Object>) current;
    }

    private static Set<String> components(Class<? extends Record> type) {
        Set<String> names = new TreeSet<>();
        for (RecordComponent c : type.getRecordComponents()) {
            names.add(c.getName());
        }
        return names;
    }

    private static Set<String> schemaProperties(String schema) {
        return new TreeSet<>(at(spec, "components", "schemas", schema, "properties").keySet());
    }

    @Test
    @DisplayName("s20.6: every wire DTO record has exactly the properties its OpenAPI schema declares")
    void dtoRecordsMatchSchemas() {
        Map<String, Class<? extends Record>> dtos = Map.of(
                "CreateUrlRequest", CreateUrlRequest.class,
                "UrlResource", UrlResource.class,
                "UrlStats", UrlStats.class,
                "DailyStats", DailyStats.class,
                "ErrorResponse", ErrorResponse.class,
                "FieldError", FieldError.class);
        dtos.forEach((schema, type) -> assertThat(components(type))
                .as("record %s vs schema %s", type.getSimpleName(), schema).isEqualTo(schemaProperties(schema)));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("s9.2: the ErrorResponse.code enum in the spec lists exactly the ErrorCode values the server can emit")
    void errorCodesMatch() {
        List<String> declared = (List<String>) at(spec, "components", "schemas", "ErrorResponse", "properties", "code").get("enum");
        assertThat(declared).containsExactlyInAnyOrderElementsOf(Arrays.stream(ErrorCode.values()).map(Enum::name).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("UrlResource.status enum in the spec equals UrlStatus")
    void statusEnumMatches() {
        List<String> declared = (List<String>) at(spec, "components", "schemas", "UrlResource", "properties", "status").get("enum");
        assertThat(declared).containsExactlyInAnyOrderElementsOf(Arrays.stream(UrlStatus.values()).map(Enum::name).toList());
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("s22.5: ApiKeyAuth is an X-API-Key header scheme; management operations require it; the redirect carries NO security requirement")
    void securityPerOperation() {
        Map<String, Object> scheme = at(spec, "components", "securitySchemes", "ApiKeyAuth");
        assertThat(scheme.get("type")).isEqualTo("apiKey");
        assertThat(scheme.get("in")).isEqualTo("header");
        assertThat(scheme.get("name")).isEqualTo("X-API-Key");

        Map<String, Object> paths = at(spec, "paths");
        for (var entry : paths.entrySet()) {
            Map<String, Object> ops = (Map<String, Object>) entry.getValue();
            for (String verb : List.of("get", "post", "delete")) {
                if (!ops.containsKey(verb)) {
                    continue;
                }
                List<Object> security = (List<Object>) ((Map<String, Object>) ops.get(verb)).get("security");
                if (entry.getKey().startsWith("/api/v1/")) {
                    assertThat(security).as("%s %s must require the API key", verb, entry.getKey()).isNotEmpty();
                } else {
                    assertThat(security).as("%s %s is public: no lock icon (section 22.5)", verb, entry.getKey()).isEmpty();
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("s6.2: the spec defines exactly the five endpoints of the design's summary table")
    void endpointSet() {
        Map<String, Object> paths = at(spec, "paths");
        Set<String> operations = new TreeSet<>();
        paths.forEach((path, ops) -> ((Map<String, Object>) ops).keySet().stream()
                .filter(k -> List.of("get", "post", "delete", "put", "patch").contains(k))
                .forEach(verb -> operations.add(verb.toUpperCase() + " " + path)));
        assertThat(operations).containsExactlyInAnyOrder(
                "POST /api/v1/urls", "GET /api/v1/urls/{shortCode}", "DELETE /api/v1/urls/{shortCode}",
                "GET /api/v1/urls/{shortCode}/stats", "GET /{shortCode}");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("s6.4: the redirect documents 302 (not 301) with Location and Cache-Control headers")
    void redirectDocumentsFound() {
        Map<String, Object> responses = at(spec, "paths", "/{shortCode}", "get", "responses");
        assertThat(responses).containsKey("302").doesNotContainKey("301");
        Map<String, Object> headers = (Map<String, Object>) ((Map<String, Object>) responses.get("302")).get("headers");
        assertThat(headers).containsKeys("Location", "Cache-Control");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("s6.3: POST documents 201 (created) and 200 with Idempotent-Replay (replay)")
    void createDocumentsIdempotentReplay() {
        Map<String, Object> responses = at(spec, "paths", "/api/v1/urls", "post", "responses");
        assertThat(responses).containsKeys("200", "201");
        Map<String, Object> headers = (Map<String, Object>) ((Map<String, Object>) responses.get("200")).get("headers");
        assertThat(headers).containsKey("Idempotent-Replay");
    }

    @Test
    @DisplayName("s6.1: the alias pattern and bounds in the spec are the design's: start alphanumeric, [A-Za-z0-9_-], 3-20")
    void aliasSchema() {
        Map<String, Object> alias = at(spec, "components", "schemas", "CreateUrlRequest", "properties", "customAlias");
        assertThat(alias.get("pattern")).isEqualTo("^[A-Za-z0-9][A-Za-z0-9_-]{2,19}$");
        assertThat(alias.get("minLength")).isEqualTo(3);
        assertThat(alias.get("maxLength")).isEqualTo(20);
        assertThat(at(spec, "components", "schemas", "CreateUrlRequest", "properties", "longUrl").get("maxLength")).isEqualTo(2048);
    }
}
