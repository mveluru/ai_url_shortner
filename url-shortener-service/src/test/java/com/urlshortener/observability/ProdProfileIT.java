package com.urlshortener.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.common.security.ApiKeyService;
import com.urlshortener.testsupport.AbstractIntegrationTest;
import com.urlshortener.testsupport.TestApi;
import com.urlshortener.testsupport.TestApi.Response;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.TestSocketUtils;

/**
 * The {@code prod} profile end to end (design doc sections 10.2, 12, 22.4): what must NOT be reachable in production, and
 * what must be. Secrets come from the environment (here, test properties standing in for it).
 */
@ActiveProfiles(profiles = "prod", inheritProfiles = false)
@TestPropertySource(properties = {
        "APP_CODE_FEISTEL_KEY=prod-feistel-key-0123456789abcdef0123456789",
        "APP_IP_HASH_SECRET=prod-ip-hash-secret-0123456789abcdef012345",
        "PUBLIC_BASE_URL=https://short.example",
        "app.expiry-sweep.enabled=false",
        "app.rate-limit.api-per-ip.requests-per-minute=6000000",
        "app.rate-limit.api-per-ip.burst=1000000"
})
class ProdProfileIT extends AbstractIntegrationTest {

    /**
     * An explicit free port: with server.port=0 AND management.server.port=0 Boot treats the two as the SAME port, which
     * would defeat the point of the test.
     */
    private static final int MANAGEMENT_PORT = TestSocketUtils.findAvailableTcpPort();

    @DynamicPropertySource
    static void managementPort(DynamicPropertyRegistry registry) {
        registry.add("management.server.port", () -> MANAGEMENT_PORT);   // the test bootstrap otherwise forces a random one
    }

    @Autowired ApiKeyService keys;

    private Response get(String path) {
        return api().get(path, Map.of());
    }

    @Test
    @DisplayName("s22.4: in production the Swagger UI and every docs route are gone (a plain 404 in the standard shape)")
    void swaggerIsNotServed() {
        for (String path : new String[] {"/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs.yaml", "/v3/api-docs",
                "/v3/generated-api-docs", "/v3/api-docs/swagger-config"}) {
            Response r = get(path);
            assertThat(r.status()).as(path).isEqualTo(404);
            assertThat(r.json().path("requestId").asText()).as(path).isNotBlank();
        }
    }

    @Test
    @DisplayName("s18/E8: the dev-only /internal endpoints do not exist in production (no route at all, so not even a 401)")
    void internalEndpointsAreAbsent() {
        TestApi api = api();
        Response issue = api.send("POST", "/internal/api-keys", null, "{}", Map.of("Content-Type", "application/json"));
        assertThat(issue.status()).isEqualTo(404);
        assertThat(issue.code()).isEqualTo("NO_HANDLER");
        assertThat(api.send("DELETE", "/internal/urls/whatever1", null, null, Map.of()).code()).isEqualTo("NO_HANDLER");
    }

    @Test
    @DisplayName("s12: actuator is NOT on the public port; it is served on a separate management port")
    void actuatorIsOnAnotherPort() throws Exception {
        assertThat(get("/actuator/health").status()).isEqualTo(404);
        assertThat(get("/actuator/prometheus").status()).isEqualTo(404);

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> health = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + MANAGEMENT_PORT + "/actuator/health")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).as("no component detail in production").doesNotContain("mysql").doesNotContain("jdbc");
        HttpResponse<String> prometheus = http.send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + MANAGEMENT_PORT + "/actuator/prometheus")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(prometheus.statusCode()).isEqualTo(200);
        assertThat(prometheus.body()).contains("cache_circuit_breaker_state");
    }

    @Test
    @DisplayName("the production profile serves the real API: create -> redirect works with production settings")
    void coreFlowWorks() {
        TestApi api = api();
        String key = keys.issue("prod-test").apiKey();
        Response created = api.create(key, "https://public.test/" + UUID.randomUUID());
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.json().path("shortUrl").asText()).startsWith("https://short.example/");
        assertThat(api.get("/" + created.json().path("shortCode").asText(), Map.of()).status()).isEqualTo(302);
    }
}
