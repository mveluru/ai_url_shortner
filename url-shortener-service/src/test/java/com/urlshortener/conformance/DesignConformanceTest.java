package com.urlshortener.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * Pins {@code application.yml} to the numbers in the design doc, so the configuration cannot silently drift from the spec
 * (section 8.2.1's per-dependency table, section 11.3's rate limits). If someone tunes a breaker, this test fails and forces
 * the design doc to be updated in the same change - "a drifted design doc is a release blocker" (section 21.9).
 */
class DesignConformanceTest {

    private static Properties yml;

    @BeforeAll
    static void load() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        yml = factory.getObject();
    }

    private static String p(String key) {
        String v = yml.getProperty(key);
        assertThat(v).as("application.yml must define %s", key).isNotNull();
        return v;
    }

    private void breaker(String name, int window, int minCalls, String waitOpen, int halfOpenCalls) {
        String base = "resilience4j.circuitbreaker.instances." + name + ".";
        assertThat(p(base + "sliding-window-size")).as(name + " window").isEqualTo(String.valueOf(window));
        assertThat(p(base + "minimum-number-of-calls")).as(name + " min calls").isEqualTo(String.valueOf(minCalls));
        assertThat(p(base + "wait-duration-in-open-state")).as(name + " wait in open").isEqualTo(waitOpen);
        assertThat(p(base + "permitted-number-of-calls-in-half-open-state")).as(name + " half-open trials")
                .isEqualTo(String.valueOf(halfOpenCalls));
        assertThat(p("resilience4j.circuitbreaker.configs.default.failure-rate-threshold")).isEqualTo("50");
        assertThat(p("resilience4j.circuitbreaker.configs.default.sliding-window-type")).isEqualTo("COUNT_BASED");
    }

    @Test
    @DisplayName("s8.2.1: the five named circuit breakers match the design table (window, >=50% failure, wait-in-open, half-open trials)")
    void circuitBreakersMatchTheTable() {
        breaker("redis-cache", 20, 10, "5s", 3);
        breaker("mysql-read", 20, 10, "10s", 3);
        breaker("mysql-write", 10, 5, "15s", 2);
        breaker("mq-publish", 20, 10, "5s", 3);
        breaker("auth-lookup", 20, 10, "10s", 3);
    }

    @Test
    @DisplayName("s8.2.1: retry counts match the table - redis 1 retry, mysql-read 2, mysql-write 1 (idempotent read only), auth 1, mq-publish NONE")
    void retryCountsMatchTheTable() {
        assertThat(p("resilience4j.retry.instances.redis-cache.max-attempts")).isEqualTo("2");
        assertThat(p("resilience4j.retry.instances.mysql-read.max-attempts")).isEqualTo("3");
        assertThat(p("resilience4j.retry.instances.mysql-write.max-attempts")).isEqualTo("2");
        assertThat(p("resilience4j.retry.instances.auth-lookup.max-attempts")).isEqualTo("2");
        assertThat(yml.stringPropertyNames()).as("the queue publish must have ZERO retries: no retry instance may exist")
                .noneMatch(k -> k.startsWith("resilience4j.retry.instances.mq-publish"));
    }

    @Test
    @DisplayName("s8.2.1: full-jitter base/cap match the table: redis 10/50ms, mysql-read 25/200ms, mysql-write 25/200ms, auth 20/100ms")
    void jitterBoundsMatchTheTable() {
        assertBackoff("redis-cache", 10, 50);
        assertBackoff("mysql-read", 25, 200);
        assertBackoff("mysql-write", 25, 200);
        assertBackoff("auth-lookup", 20, 100);
    }

    private void assertBackoff(String name, long baseMs, long capMs) {
        assertThat(parse(p("app.retry-backoff." + name + ".base"))).isEqualTo(Duration.ofMillis(baseMs));
        assertThat(parse(p("app.retry-backoff." + name + ".cap"))).isEqualTo(Duration.ofMillis(capMs));
    }

    private static Duration parse(String text) {
        return text.endsWith("ms") ? Duration.ofMillis(Long.parseLong(text.replace("ms", ""))) : Duration.parse(text);
    }

    @Test
    @DisplayName("s8.2: circuit breaker is the OUTER decorator and retry the INNER one (retry only while the breaker permits the call)")
    void aspectOrder() {
        int cb = Integer.parseInt(p("resilience4j.circuitbreaker.circuitBreakerAspectOrder"));
        int retry = Integer.parseInt(p("resilience4j.retry.retryAspectOrder"));
        assertThat(cb).as("lower order = outer").isLessThan(retry);
    }

    @Test
    @DisplayName("s11.3: rate limit defaults - create 100/min burst 20 per key; redirect 1,000/min burst 200 per IP")
    void rateLimitDefaults() {
        assertThat(p("app.rate-limit.create-per-key.requests-per-minute")).isEqualTo("100");
        assertThat(p("app.rate-limit.create-per-key.burst")).isEqualTo("20");
        assertThat(p("app.rate-limit.redirect-per-ip.requests-per-minute")).isEqualTo("1000");
        assertThat(p("app.rate-limit.redirect-per-ip.burst")).isEqualTo("200");
    }

    @Test
    @DisplayName("s20.4: NO_HANDLER needs BOTH throw-exception-if-no-handler-found=true and add-mappings=false")
    void noHandlerPrerequisites() {
        assertThat(p("spring.mvc.throw-exception-if-no-handler-found")).isEqualTo("true");
        assertThat(p("spring.web.resources.add-mappings")).isEqualTo("false");
    }

    @Test
    @DisplayName("s20.3: virtual threads are enabled; s5.1: JDBC session pinned to UTC; s20.1: Flyway (not ddl-auto) owns the schema")
    void platformSettings() {
        assertThat(p("spring.threads.virtual.enabled")).isEqualTo("true");
        assertThat(p("spring.datasource.url")).contains("connectionTimeZone=UTC").contains("forceConnectionTimeZoneToSession=true");
        assertThat(p("spring.jpa.properties.hibernate.jdbc.time_zone")).isEqualTo("UTC");
        assertThat(p("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(p("spring.flyway.enabled")).isEqualTo("true");
    }

    @Test
    @DisplayName("s22.3: Swagger UI config points at the static contract, and the ui is on in the default profile")
    void swaggerConfig() {
        assertThat(p("springdoc.swagger-ui.url")).isEqualTo("/v3/api-docs.yaml");
        assertThat(p("springdoc.swagger-ui.enabled")).isEqualTo("true");
        assertThat(p("springdoc.packages-to-scan")).isEqualTo("none");
    }

    @Test
    @DisplayName("s22.4: production disables the API explorer and the docs, and moves actuator off the public port")
    void prodProfile() {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource("application-prod.yml"));
        Properties prod = factory.getObject();
        assertThat(prod.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
        assertThat(prod.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
        assertThat(prod.getProperty("management.server.port")).isNotBlank();
        assertThat(prod.getProperty("app.code.feistel-key")).as("no default: a missing secret must fail startup").doesNotContain(":");
        assertThat(prod.getProperty("app.analytics.ip-hash-secret")).doesNotContain(":");
        assertThat(prod.getProperty("spring.datasource.password")).doesNotContain(":");
    }
}
