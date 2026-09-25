package com.urlshortener.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.testsupport.TestProps;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProdSecretsGuardTest {

    private static final String GOOD = "0123456789abcdef0123456789abcdef";

    private static ProdSecretsGuard guard(String feistel, String hmac, String baseUrl) {
        return new ProdSecretsGuard(TestProps.with(Map.of("app.code.feistel-key", feistel,
                "app.analytics.ip-hash-secret", hmac, "app.public-base-url", baseUrl)));
    }

    @Test
    @DisplayName("s10.2: production accepts real secrets (>= 32 chars) and an https public URL")
    void accepts() {
        assertThatCode(() -> guard(GOOD, GOOD + "x", "https://short.example").afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("s10.2: production REFUSES TO START with a development default, a too-short secret, or a non-TLS public URL")
    void refuses() {
        assertThatThrownBy(() -> guard("dev-only-feistel-key-change-me-in-prod-padded", GOOD, "https://s.example").afterPropertiesSet())
                .hasMessageContaining("app.code.feistel-key");
        assertThatThrownBy(() -> guard(GOOD, "dev-only-ip-hash-secret-change-me-padded-out", "https://s.example").afterPropertiesSet())
                .hasMessageContaining("app.analytics.ip-hash-secret");
        assertThatThrownBy(() -> guard("short", GOOD, "https://s.example").afterPropertiesSet())
                .hasMessageContaining("at least 32");
        assertThatThrownBy(() -> guard(GOOD, GOOD, "http://s.example").afterPropertiesSet())
                .hasMessageContaining("https://");
    }
}
