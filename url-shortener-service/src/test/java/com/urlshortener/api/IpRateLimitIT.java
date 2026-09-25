package com.urlshortener.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.testsupport.AbstractIntegrationTest;
import com.urlshortener.testsupport.TestApi;
import com.urlshortener.testsupport.TestApi.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** The coarse pre-authentication per-IP limit on /api/** (design doc section 10.4). Alone in its context: it is one shared bucket. */
@TestPropertySource(properties = {
        "app.rate-limit.api-per-ip.requests-per-minute=60",
        "app.rate-limit.api-per-ip.burst=10",
        "app.rate-limit.redirect-per-ip.requests-per-minute=6000000",
        "app.rate-limit.redirect-per-ip.burst=1000000"
})
class IpRateLimitIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("s10.4: unauthenticated flooding of /api/** is throttled per IP BEFORE authentication (429, not 401)")
    void preAuthApiLimit() {
        TestApi api = api();
        int unauthorized = 0;
        Response limited = null;
        for (int i = 0; i < 40 && limited == null; i++) {
            Response r = api.get("/api/v1/urls/whatever1", (String) null);
            if (r.status() == 429) {
                limited = r;
            } else {
                assertThat(r.status()).isEqualTo(401);
                unauthorized++;
            }
        }
        assertThat(unauthorized).as("401s served before the coarse per-IP limit kicked in (the burst)").isEqualTo(10);
        assertThat(limited).isNotNull();
        assertThat(limited.code()).isEqualTo("RATE_LIMITED");
        assertThat(limited.header("retry-after")).isNotBlank();
        assertThat(limited.json().path("requestId").asText()).isNotBlank();
    }
}
