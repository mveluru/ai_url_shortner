package com.urlshortener.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.testsupport.AbstractIntegrationTest;
import com.urlshortener.testsupport.TestApi.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Design doc section 22: the Swagger UI serves the checked-in contract, not a second, generated one. */
class SwaggerIT extends AbstractIntegrationTest {

    private static final Path CONTRACT = Path.of("..", "docs", "openapi.yaml");

    @Test
    @DisplayName("s22.7: GET /v3/api-docs.yaml is byte-for-byte identical to docs/openapi.yaml (one source of truth, mechanically enforced)")
    void servedContractIsTheRepositoryFile() throws Exception {
        Response r = api().get("/v3/api-docs.yaml", Map.of());
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.header("content-type")).startsWith("application/yaml");
        assertThat(r.body()).isEqualTo(Files.readString(CONTRACT));
    }

    @Test
    @DisplayName("s22.1: the Swagger UI is reachable and configured to load the served static contract")
    void swaggerUiLoadsTheStaticContract() {
        Response page = api().get("/swagger-ui/index.html", Map.of());
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.body()).containsIgnoringCase("swagger");

        Response config = api().get("/v3/generated-api-docs/swagger-config", Map.of());
        assertThat(config.status()).isEqualTo(200);
        assertThat(config.body()).contains("/v3/api-docs.yaml");
    }

    @Test
    @DisplayName("s22.1: springdoc's own annotation-generated spec is unreachable - the checked-in file is the ONLY contract")
    void generatedSpecIsNotExposed() {
        for (String path : new String[] {"/v3/generated-api-docs", "/v3/generated-api-docs.yaml"}) {
            Response r = api().get(path, Map.of());
            assertThat(r.status()).as(path).isEqualTo(404);
            assertThat(r.code()).as(path).isEqualTo("NO_HANDLER");
        }
        // ...but the UI's own configuration endpoint is still served, or the UI could not start.
        assertThat(api().get("/v3/generated-api-docs/swagger-config", Map.of()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("s22.3: /swagger-ui.html entry point works and sends the browser to the UI")
    void entryPoint() {
        Response r = api().get("/swagger-ui.html", Map.of());
        assertThat(r.status()).isIn(200, 302);
        if (r.status() == 302) {
            assertThat(r.header("location")).contains("swagger-ui");
        }
    }
}
