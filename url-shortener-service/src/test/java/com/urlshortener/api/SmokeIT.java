package com.urlshortener.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.testsupport.AbstractIntegrationTest;
import com.urlshortener.testsupport.TestApi;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SmokeIT extends AbstractIntegrationTest {

    @Test
    void createRedirectAndStats() {
        TestApi api = api();
        String key = api.newApiKey();
        var created = api.post("/api/v1/urls", key, Map.of("longUrl", "https://public.test/some/path?x=1"));
        System.out.println("CREATE -> " + created.status() + " " + created.body());
        assertThat(created.status()).isEqualTo(201);
        String code = created.json().path("shortCode").asText();

        var redirect = api.get("/" + code, Map.of());
        System.out.println("REDIRECT -> " + redirect.status() + " " + redirect.headers());
        assertThat(redirect.status()).isEqualTo(302);
        assertThat(redirect.header("location")).isEqualTo("https://public.test/some/path?x=1");
    }
}
