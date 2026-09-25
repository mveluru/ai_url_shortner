package com.urlshortener.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.urlshortener.testsupport.AbstractIntegrationTest;
import com.urlshortener.testsupport.TestApi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Design doc section 12: the minimum metric set exists under its exact names, and the alerts point at metrics that are real. */
class ObservabilityIT extends AbstractIntegrationTest {

    /** Metrics that come from outside this service (exporters), so they cannot appear in its own scrape. */
    private static final Set<String> EXTERNAL = Set.of("mysql_slave_status_seconds_behind_source");

    private String scrape() {
        return api().get("/actuator/prometheus", Map.of()).body();
    }

    private void generateTraffic() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = api.create(key, "https://public.test/" + UUID.randomUUID()).json().path("shortCode").asText();
        api.get("/" + code, Map.of());
        api.get("/" + code, Map.of());                                    // a cache hit
        api.get("/definitelyNotThere", Map.of());                         // a 404 -> http_errors
        api.create(key, "not a url");                                     // a 400 -> http_errors
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(api.get("/api/v1/urls/" + code + "/stats", key).json().path("totalClicks").asLong()).isEqualTo(2));
    }

    @Test
    @DisplayName("s12.1: every metric of the design's minimum set is exported under its exact name")
    void minimumMetricSetExists() {
        generateTraffic();
        String metrics = scrape();
        assertThat(metrics)
                .as("redirect_latency_ms, tagged by cache outcome")
                .contains("redirect_latency_ms_bucket{cache=\"hit\"").contains("redirect_latency_ms_bucket{cache=\"miss\"")
                .contains("redirect_latency_ms_count")
                .as("redirect_cache_hit_rate").contains("redirect_cache_hit_rate")
                .as("create_latency_ms").contains("create_latency_ms_count")
                .as("click_publish_failed_total (F5)").contains("click_publish_failed_total{reason=\"send_failed\"")
                .as("queue_depth / consumer_lag (F6)").contains("queue_depth").contains("consumer_lag")
                .as("alias_collision_total (E7/E13)").contains("alias_collision_total{outcome=\"rejected\"")
                .as("F9 collision counter").contains("short_code_collision_total")
                .as("error rate by code and endpoint (s9.2)").contains("http_errors_total{code=\"NOT_FOUND\"")
                .contains("http_errors_total{code=\"INVALID_URL\"");
    }

    @Test
    @DisplayName("s8.2.2/s12.1: one cache_circuit_breaker_state gauge per NAMED breaker, and retry outcomes by kind per named retry")
    void breakerAndRetryMetrics() {
        generateTraffic();
        String metrics = scrape();
        for (String name : new String[] {"redis-cache", "mysql-read", "mysql-write", "mq-publish", "auth-lookup"}) {
            assertThat(metrics).as("breaker gauge for " + name).contains("cache_circuit_breaker_state{name=\"" + name + "\"");
        }
        assertThat(metrics).contains("resilience4j_retry_calls_total{").contains("kind=\"successful_without_retry\"");
        assertThat(metrics).contains("resilience4j_circuitbreaker_state");
    }

    @Test
    @DisplayName("s12.1: the redirect latency histogram has SLO-aligned buckets around the 50ms hit / 150ms miss targets")
    void latencyBuckets() {
        generateTraffic();
        String metrics = scrape();
        for (String le : new String[] {"25.0", "50.0", "100.0", "150.0", "250.0"}) {
            assertThat(metrics).contains("redirect_latency_ms_bucket{cache=\"hit\",result=\"found\",le=\"" + le + "\"");
        }
    }

    @Test
    @DisplayName("s12.4: every application metric referenced by an alert rule is actually exported (a rename would otherwise silence the alert)")
    void alertRulesReferenceRealMetrics() throws Exception {
        generateTraffic();
        String metrics = scrape();
        String rules = Files.readString(Path.of("..", "ops", "prometheus-alerts.yml"));

        Set<String> referenced = new TreeSet<>();
        for (String line : rules.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("#") || trimmed.startsWith("summary") || trimmed.startsWith("runbook")) {
                continue;
            }
            Matcher m = Pattern.compile("\\b([a-z][a-z0-9_]*(?:_bucket|_total|_count|_state|_pending|_depth|_lag|_rate|_source))\\b").matcher(line);
            while (m.find()) {
                referenced.add(m.group(1));
            }
        }
        assertThat(referenced).as("the alert file should reference metrics").isNotEmpty();
        for (String metric : referenced) {
            if (EXTERNAL.contains(metric) || metric.equals("http_server_requests_seconds_count")) {
                continue;
            }
            assertThat(metrics).as("alert references metric '%s' which the service does not export", metric).contains(metric);
        }
        assertThat(metrics).contains("http_server_requests_seconds_count");
    }

    @Test
    @DisplayName("s8.1: readiness reports only what must pull an instance out of rotation (the database) - Redis/RabbitMQ degrade, they do not fail readiness")
    void readinessExcludesDegradableDependencies() {
        var readiness = api().get("/actuator/health/readiness", Map.of());
        assertThat(readiness.status()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"UP\"");
        assertThat(readiness.body()).doesNotContain("redis").doesNotContain("rabbit");
        assertThat(api().get("/actuator/health/liveness", Map.of()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("s12.2/s9.4: the access log carries requestId on every line and NEVER the query string, API key, Referer, client IP or long URL")
    void accessLogsDoNotLeak() {
        TestApi api = api();
        String key = api.newApiKey();
        String longUrl = "https://public.test/very/private/path-" + UUID.randomUUID();
        String code = api.create(key, longUrl).json().path("shortCode").asText();

        ch.qos.logback.classic.Logger access = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("access");
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> captured =
                new ch.qos.logback.core.read.ListAppender<>();
        captured.start();
        access.addAppender(captured);
        try {
            api.get("/" + code + "?token=SUPERSECRET123&email=alice@example.com",
                    Map.of("Referer", "https://leaky.example/?session=REFERERSECRET", "X-API-Key", key, "X-Request-Id", "trace-abc-123"));
            api.get("/nope1234?debug=1", Map.of());
            api.create("not-a-real-key-SECRETKEY456", longUrl);
        } finally {
            access.detachAppender(captured);
        }

        assertThat(captured.list).as("one access line per request").hasSizeGreaterThanOrEqualTo(3);
        for (var event : captured.list) {
            String line = event.getFormattedMessage();
            assertThat(line).doesNotContain("SUPERSECRET123").doesNotContain("alice@example.com").doesNotContain("token=")
                    .doesNotContain("REFERERSECRET").doesNotContain(key).doesNotContain("SECRETKEY456")
                    .doesNotContain("127.0.0.1").doesNotContain("0:0:0:0:0:0:0:1").doesNotContain("very/private").doesNotContain("?");
            assertThat(event.getMDCPropertyMap()).as("requestId is on every log line").containsKey("requestId");
            assertThat(event.getMDCPropertyMap().get("requestId")).isNotBlank();
        }
        assertThat(captured.list).anySatisfy(e -> {
            assertThat(e.getFormattedMessage()).contains("GET /" + code + " -> 302");
            assertThat(e.getMDCPropertyMap().get("requestId")).isEqualTo("trace-abc-123");   // the gateway's id propagated
        });
        assertThat(captured.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("GET /nope1234 -> 404").contains("code=NOT_FOUND"));
        assertThat(captured.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("POST /api/v1/urls -> 401").contains("code=UNAUTHORIZED"));
    }
}
