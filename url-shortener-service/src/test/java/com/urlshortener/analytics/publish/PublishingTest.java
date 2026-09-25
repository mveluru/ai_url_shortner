package com.urlshortener.analytics.publish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.urlshortener.analytics.domain.ClickEvent;
import com.urlshortener.testsupport.Covers;
import com.urlshortener.testsupport.TestProps;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PublishingTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-25T10:00:00Z"), ZoneOffset.UTC);

    private static HttpServletRequest request(String referer, String userAgent, String ip) {
        HttpServletRequest r = mock(HttpServletRequest.class);
        when(r.getHeader("Referer")).thenReturn(referer);
        when(r.getHeader("User-Agent")).thenReturn(userAgent);
        when(r.getRemoteAddr()).thenReturn(ip);
        return r;
    }

    private static ClickEventFactory factory() {
        var props = TestProps.defaults();
        return new ClickEventFactory(new IpHasher(props, CLOCK), CLOCK, props);
    }

    private static String sha256Hex(String text) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    @Covers({"E24"})
    @DisplayName("E24: Referer and User-Agent longer than 512 are TRUNCATED, never rejected; shorter values are untouched")
    void headersAreTruncated() {
        ClickEvent e = factory().create("abc123", request("https://r.example/" + "x".repeat(10_000), "UA" + "y".repeat(10_000), "203.0.113.9"));
        assertThat(e.referrer()).hasSize(512).startsWith("https://r.example/");
        assertThat(e.userAgent()).hasSize(512).startsWith("UA");
        ClickEvent small = factory().create("abc123", request("https://r.example/", "Mozilla", "203.0.113.9"));
        assertThat(small.referrer()).isEqualTo("https://r.example/");
        assertThat(small.userAgent()).isEqualTo("Mozilla");
        ClickEvent none = factory().create("abc123", request(null, null, "203.0.113.9"));
        assertThat(none.referrer()).isNull();
        assertThat(none.userAgent()).isNull();
    }

    @Test
    @DisplayName("s5.2: every event has a unique event_id (the consumer's dedup key) and the raw IP appears nowhere in it")
    void eventShape() {
        ClickEventFactory f = factory();
        ClickEvent a = f.create("abc123", request(null, null, "203.0.113.9"));
        ClickEvent b = f.create("abc123", request(null, null, "203.0.113.9"));
        assertThat(a.eventId()).isNotEqualTo(b.eventId());
        assertThat(a.timestamp()).isEqualTo(CLOCK.instant());
        assertThat(a.toString()).doesNotContain("203.0.113.9");
        assertThat(a.ipHash()).matches("[0-9a-f]{64}").isEqualTo(b.ipHash());     // same visitor, same period -> same hash
    }

    @Test
    @DisplayName("s10.2: ip_hash is keyed - different IPs differ, it is not the plain hash of the IP, and it rotates with the period")
    void ipHash() throws Exception {
        var props = TestProps.defaults();
        IpHasher day1 = new IpHasher(props, CLOCK);
        IpHasher day3 = new IpHasher(props, Clock.fixed(CLOCK.instant().plusSeconds(2 * 86_400), ZoneOffset.UTC));
        assertThat(day1.hash("203.0.113.9")).isNotEqualTo(day1.hash("203.0.113.10"));
        assertThat(day1.hash("203.0.113.9")).as("not a bare, rainbow-table-able SHA-256 of the address")
                .isNotEqualTo(sha256Hex("203.0.113.9"));
        assertThat(day1.hash("203.0.113.9")).as("rotated key -> unlinkable across periods").isNotEqualTo(day3.hash("203.0.113.9"));
        assertThat(day1.hash(null)).isNotBlank();
        IpHasher otherSecret = new IpHasher(com.urlshortener.testsupport.TestProps.with(Map.of("app.analytics.ip-hash-secret", "another-secret-value")), CLOCK);
        assertThat(otherSecret.hash("203.0.113.9")).isNotEqualTo(day1.hash("203.0.113.9"));
    }

    @Test
    @Covers({"F5"})
    @DisplayName("F5: publish() never throws and never blocks the caller, whatever the broker does")
    void publishNeverThrows() throws Exception {
        ClickSender sender = mock(ClickSender.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);                       // a stalled broker
            return null;
        }).when(sender).send(any());
        ClickPublisher publisher = new ClickPublisher(factory(), sender, new SimpleMeterRegistry(), TestProps.defaults());

        long start = System.nanoTime();
        assertThatCode(() -> publisher.publish("abc123", request(null, null, "1.2.3.4"))).doesNotThrowAnyException();
        assertThat((System.nanoTime() - start) / 1_000_000).as("returned without waiting for the stalled send").isLessThan(500);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        publisher.shutdown();
    }

    @Test
    @Covers({"F5"})
    @DisplayName("F5: send failures and an open breaker drop the event and count click_publish_failed by reason - the caller never sees them")
    void failuresAreCountedNotPropagated() throws Exception {
        ClickSender sender = mock(ClickSender.class);
        var meters = new SimpleMeterRegistry();
        CircuitBreaker open = CircuitBreaker.ofDefaults("mq-publish");
        open.transitionToOpenState();
        doThrow(new RuntimeException("broker down")).doThrow(CallNotPermittedException.createCallNotPermittedException(open))
                .when(sender).send(any());
        ClickPublisher publisher = new ClickPublisher(factory(), sender, meters, TestProps.defaults());

        publisher.publish("abc123", request(null, null, "1.2.3.4"));
        publisher.publish("abc123", request(null, null, "1.2.3.4"));
        org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(meters.get("click_publish_failed").tag("reason", "send_failed").counter().count()).isEqualTo(1);
            assertThat(meters.get("click_publish_failed").tag("reason", "circuit_open").counter().count()).isEqualTo(1);
        });
    }

    @Test
    @Covers({"F5"})
    @DisplayName("F5: when the in-flight cap is exhausted new events are DROPPED, not queued in memory (a broker stall cannot grow the heap)")
    void saturationDropsInsteadOfBuffering() throws Exception {
        ClickSender sender = mock(ClickSender.class);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> {
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(sender).send(any());
        var meters = new SimpleMeterRegistry();
        ClickPublisher publisher = new ClickPublisher(factory(), sender, meters,
                TestProps.with(Map.of("app.analytics.max-publish-in-flight", "3")));

        for (int i = 0; i < 10; i++) {
            publisher.publish("abc123", request(null, null, "1.2.3.4"));
        }
        assertThat(meters.get("click_publish_failed").tag("reason", "saturated").counter().count()).isEqualTo(7);
        release.countDown();
        publisher.shutdown();
    }
}
