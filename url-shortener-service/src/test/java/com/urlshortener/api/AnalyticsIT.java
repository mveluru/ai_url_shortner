package com.urlshortener.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urlshortener.analytics.domain.ClickEvent;
import com.urlshortener.common.config.AppProperties;
import com.urlshortener.testsupport.AbstractIntegrationTest;
import com.urlshortener.testsupport.Covers;
import com.urlshortener.testsupport.TestApi;
import com.urlshortener.testsupport.TestApi.Response;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The click pipeline end to end over real RabbitMQ + MySQL (design doc sections 5.2-5.4, 6.5, E20-E22, E24, F5, F6). */
class AnalyticsIT extends AbstractIntegrationTest {

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36";
    private static final String MOBILE_UA =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148";

    @Autowired JdbcTemplate jdbc;
    @Autowired RabbitTemplate rabbit;
    @Autowired AmqpAdmin admin;
    @Autowired AppProperties props;
    @Autowired ObjectMapper mapper;

    private String unique() {
        return "https://public.test/" + UUID.randomUUID();
    }

    private JsonNode awaitTotal(TestApi api, String key, String code, long expected) {
        JsonNode[] last = new JsonNode[1];
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            Response r = api.get("/api/v1/urls/" + code + "/stats", key);
            assertThat(r.status()).isEqualTo(200);
            last[0] = r.json();
            assertThat(last[0].path("totalClicks").asLong()).isEqualTo(expected);
        });
        return last[0];
    }

    @Test
    @DisplayName("create -> redirect -> click queued -> consumer aggregates -> stats reflect it (eventually consistent, converges)")
    void endToEnd() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = api.create(key, unique()).json().path("shortCode").asText();

        for (int i = 0; i < 3; i++) {
            api.get("/" + code, Map.of("Referer", "https://News.Example/story?id=" + i + "&token=secret",
                    "User-Agent", DESKTOP_UA));
        }
        for (int i = 0; i < 2; i++) {
            api.get("/" + code, Map.of("User-Agent", MOBILE_UA));
        }

        JsonNode stats = awaitTotal(api, key, code, 5);
        assertThat(stats.path("shortCode").asText()).isEqualTo(code);
        // Referrers are reduced to a lowercase host: the path and query (which can carry tokens/PII) are never retained.
        assertThat(stats.path("topReferrers").path("news.example").asLong()).isEqualTo(3);
        assertThat(stats.path("topReferrers").path("(direct)").asLong()).isEqualTo(2);
        assertThat(stats.path("topReferrers").toString()).doesNotContain("token").doesNotContain("secret");
        assertThat(stats.path("deviceBreakdown").path("desktop").asLong()).isEqualTo(3);
        assertThat(stats.path("deviceBreakdown").path("mobile").asLong()).isEqualTo(2);
        assertThat(stats.path("daily")).hasSize(1);
        assertThat(stats.path("daily").get(0).path("date").asText()).isEqualTo(LocalDate.now(ZoneOffset.UTC).toString());
        assertThat(stats.path("daily").get(0).path("clicks").asLong()).isEqualTo(5);
    }

    @Test
    @DisplayName("s6.5: stats disclose updatedAt (last aggregation), so 'zero clicks' is distinguishable from 'not caught up'")
    void updatedAtIsDisclosed() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = api.create(key, unique()).json().path("shortCode").asText();
        api.get("/" + code, Map.of());
        JsonNode stats = awaitTotal(api, key, code, 1);
        assertThat(stats.path("updatedAt").isNull()).as("updatedAt must be present once aggregation has run").isFalse();
        assertThat(Instant.parse(stats.path("updatedAt").asText()))
                .isCloseTo(Instant.now(), org.assertj.core.api.Assertions.within(60, ChronoUnit.SECONDS));

        String fresh = api.create(key, unique()).json().path("shortCode").asText();
        Response zero = api.get("/api/v1/urls/" + fresh + "/stats", key);
        assertThat(zero.json().path("totalClicks").asLong()).isZero();
        assertThat(zero.json().path("daily")).isEmpty();
    }

    @Test
    @Covers({"E24"})
    @DisplayName("s5.2/E24/s10.2: the queued event has an HMAC ip_hash and NO raw IP; oversized headers are truncated to 512, not rejected")
    void eventOnTheWire() throws Exception {
        TestApi api = api();
        String code = api.create(api.newApiKey(), unique()).json().path("shortCode").asText();

        String captureQueue = "test-capture-" + UUID.randomUUID();
        Queue capture = new Queue(captureQueue, false, true, true);
        admin.declareQueue(capture);
        admin.declareBinding(BindingBuilder.bind(capture).to(new DirectExchange(props.analytics().exchange())).with("click"));

        Response r = api.get("/" + code, Map.of("Referer", "https://ref.example/" + "r".repeat(20_000),
                "User-Agent", "Mozilla/5.0 " + "u".repeat(20_000)));
        assertThat(r.status()).isEqualTo(302);

        Message message = rabbit.receive(captureQueue, 15_000);
        assertThat(message).as("click event published").isNotNull();
        String body = new String(message.getBody());
        JsonNode event = mapper.readTree(body);
        assertThat(event.path("shortCode").asText()).isEqualTo(code);
        assertThat(UUID.fromString(event.path("eventId").asText())).isNotNull();
        assertThat(Instant.parse(event.path("timestamp").asText())).isNotNull();
        assertThat(event.path("referrer").asText()).hasSize(512).startsWith("https://ref.example/");
        assertThat(event.path("userAgent").asText()).hasSize(512).startsWith("Mozilla/5.0 ");
        assertThat(event.path("ipHash").asText()).matches("[0-9a-f]{64}");
        assertThat(body).doesNotContain("127.0.0.1").doesNotContain("0:0:0:0:0:0:0:1").doesNotContain("\"ip\"");
    }

    @Test
    @Covers({"E21"})
    @DisplayName("E21: a click event delivered three times (at-least-once) is aggregated ONCE, deduplicated by event_id")
    void duplicateDeliveryIsDeduplicated() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = api.create(key, unique()).json().path("shortCode").asText();
        ClickEvent event = new ClickEvent(code, UUID.randomUUID().toString(), Instant.now(), null, DESKTOP_UA, "h".repeat(64));

        rabbit.convertAndSend(props.analytics().exchange(), "click", event);
        rabbit.convertAndSend(props.analytics().exchange(), "click", event);
        rabbit.convertAndSend(props.analytics().exchange(), "click", event);

        awaitTotal(api, key, code, 1);
        // Give any (wrongly) non-deduplicated redelivery time to land, then re-check it is still exactly one.
        await().pollDelay(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(api.get("/api/v1/urls/" + code + "/stats", key).json().path("totalClicks").asLong()).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class,
                event.eventId())).isEqualTo(1);
    }

    @Test
    @Covers({"E20"})
    @DisplayName("E20: clicks recorded before deactivation are kept historically, but stats for a deactivated code are 404")
    void statsForDeactivatedAre404() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = api.create(key, unique()).json().path("shortCode").asText();
        api.get("/" + code, Map.of());
        awaitTotal(api, key, code, 1);

        api.delete("/api/v1/urls/" + code, key);
        Response r = api.get("/api/v1/urls/" + code + "/stats", key);
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.code()).isEqualTo("NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT SUM(click_count) FROM click_aggregates WHERE short_code = ?", Long.class, code))
                .as("the historical click is still recorded").isEqualTo(1L);
    }

    @Test
    @Covers({"E22"})
    @DisplayName("E22: from after to -> 400 INVALID_RANGE; valid ranges filter by UTC day and default to the last 30 days")
    void rangeHandling() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = api.create(key, unique()).json().path("shortCode").asText();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        seedAggregate(code, today.minusDays(3), 4);
        seedAggregate(code, today.minusDays(10), 6);
        seedAggregate(code, today.minusDays(45), 9);        // outside the default 30-day window, inside retention

        Response inverted = api.get("/api/v1/urls/" + code + "/stats?from=" + today + "&to=" + today.minusDays(1), key);
        assertThat(inverted.status()).isEqualTo(400);
        assertThat(inverted.code()).isEqualTo("INVALID_RANGE");

        JsonNode def = api.get("/api/v1/urls/" + code + "/stats", key).json();
        assertThat(def.path("totalClicks").asLong()).isEqualTo(10);                         // 4 + 6, not the 45-day-old 9
        assertThat(def.path("daily")).hasSize(2);
        assertThat(def.path("daily").get(0).path("date").asText()).isEqualTo(today.minusDays(10).toString());   // ascending

        JsonNode wide = api.get("/api/v1/urls/" + code + "/stats?from=" + today.minusDays(60) + "&to=" + today, key).json();
        assertThat(wide.path("totalClicks").asLong()).isEqualTo(19);

        JsonNode narrow = api.get("/api/v1/urls/" + code + "/stats?from=" + today.minusDays(5) + "&to="
                + today.minusDays(1), key).json();
        assertThat(narrow.path("totalClicks").asLong()).isEqualTo(4);
    }

    @Test
    @DisplayName("s15.3: data older than the 90-day retention is never served, even when a wider range is requested")
    void retentionWindow() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = api.create(key, unique()).json().path("shortCode").asText();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        seedAggregate(code, today.minusDays(200), 50);
        seedAggregate(code, today.minusDays(20), 3);
        JsonNode stats = api.get("/api/v1/urls/" + code + "/stats?from=2000-01-01&to=" + today, key).json();
        assertThat(stats.path("totalClicks").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("s20.4a: 'aggXXXX' and 'AGGXXXX' are different links with separate analytics (case-sensitive, end to end)")
    void caseSensitiveAggregation() {
        TestApi api = api();
        String key = api.newApiKey();
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String lower = "agg" + suffix;
        String upper = "AGG" + suffix;
        api.create(key, unique(), lower);
        api.create(key, unique(), upper);
        api.get("/" + lower, Map.of());
        api.get("/" + lower, Map.of());
        api.get("/" + upper, Map.of());
        awaitTotal(api, key, lower, 2);
        awaitTotal(api, key, upper, 1);
    }

    @Test
    @DisplayName("stats for an EXPIRED (but not deactivated) code still work - it is the owner's historical data")
    void statsForExpiredStillServed() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = api.create(key, unique()).json().path("shortCode").asText();
        seedAggregate(code, LocalDate.now(ZoneOffset.UTC), 2);
        jdbc.update("UPDATE urls SET expires_at = CURRENT_TIMESTAMP(6) - INTERVAL 1 HOUR WHERE short_code = ?", code);
        assertThat(api.get("/api/v1/urls/" + code + "/stats", key).json().path("totalClicks").asLong()).isEqualTo(2);
    }

    @Test
    @Covers({"F6"})
    @DisplayName("F6: an unparseable message is dead-lettered by the broker, not retried forever, and the consumer keeps working")
    void poisonMessageGoesToDeadLetterQueue() {
        MessageProperties p = new MessageProperties();
        p.setContentType("application/json");
        String marker = UUID.randomUUID().toString();
        rabbit.send(props.analytics().exchange(), "click", new Message(("{ this is not json " + marker).getBytes(), p));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Message dead = rabbit.receive(props.analytics().deadLetterQueue(), 1_000);
            assertThat(dead).as("dead-lettered message").isNotNull();
            assertThat(new String(dead.getBody())).contains(marker);
        });
    }

    @Test
    @Covers({"F6"})
    @DisplayName("F6: a message that keeps failing is redelivered by the broker up to the delivery limit, then dead-lettered")
    void repeatedFailureHitsDeliveryLimit() {
        // A 30-char short code cannot exist (E18 blocks it) and cannot be aggregated: every attempt fails and rolls back.
        String eventId = UUID.randomUUID().toString();
        ClickEvent poison = new ClickEvent("x".repeat(30), eventId, Instant.now(), null, null, "h".repeat(64));
        rabbit.convertAndSend(props.analytics().exchange(), "click", poison);

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            Message dead = rabbit.receive(props.analytics().deadLetterQueue(), 1_000);
            assertThat(dead).as("dead-lettered after the delivery limit").isNotNull();
            assertThat(new String(dead.getBody())).contains(eventId);
        });
        // Every failed attempt rolled back its dedup marker, so nothing was half-applied.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId))
                .isZero();
    }

    private void seedAggregate(String code, LocalDate day, long clicks) {
        jdbc.update("INSERT INTO click_aggregates (short_code, date, click_count, top_referrers, device_breakdown, updated_at) "
                + "VALUES (?, ?, ?, '{}', '{}', CURRENT_TIMESTAMP(6))", code, day, clicks);
    }
}
