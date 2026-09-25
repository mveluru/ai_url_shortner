package com.urlshortener.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.urlshortener.analytics.consume.QueueMonitor;
import com.urlshortener.redirect.cache.UrlCache;
import com.urlshortener.testsupport.AbstractFaultInjectionIT;
import com.urlshortener.testsupport.Covers;
import com.urlshortener.testsupport.TestApi;
import com.urlshortener.testsupport.TestApi.Response;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * One test per failure mode of design doc section 8 (F1-F7, F11), each run against REAL Redis / MySQL / RabbitMQ with the
 * fault injected at the network level, asserting the mitigated behavior the design defines.
 */
@TestPropertySource(properties = {
        // Spring caches several application contexts per JVM and each runs a consumer. On a shared queue another context
        // would drain the backlog while this one's consumer is stopped (F6), so this context gets its own pipeline.
        "app.analytics.exchange=fi.clicks", "app.analytics.queue=fi.clicks.events",
        "app.analytics.dead-letter-exchange=fi.clicks.dlx", "app.analytics.dead-letter-queue=fi.clicks.events.dlq"
})
class FailureInjectionIT extends AbstractFaultInjectionIT {

    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;
    @Autowired RetryRegistry retries;
    @Autowired UrlCache urlCache;
    @Autowired EntityManagerFactory emf;
    @Autowired RabbitListenerEndpointRegistry listeners;
    @Autowired QueueMonitor queueMonitor;

    private String unique() {
        return "https://public.test/" + UUID.randomUUID();
    }

    private String create(TestApi api, String key) {
        return api.create(key, unique()).json().path("shortCode").asText();
    }

    private CircuitBreaker breaker(String name) {
        return breakers.circuitBreaker(name);
    }

    private double gauge(String name, String tag) {
        return meters.get(name).tag("name", tag).gauge().value();
    }

    private double counter(String name, String... tags) {
        var c = meters.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    private Statistics dbStats() {
        return emf.unwrap(SessionFactory.class).getStatistics();
    }

    private long timed(Runnable r) {
        long t = System.nanoTime();
        r.run();
        return (System.nanoTime() - t) / 1_000_000;
    }

    // ================================================================================================================
    // F1 - Redis unreachable
    // ================================================================================================================

    @Test
    @Covers({"F1", "F4"})
    @DisplayName("F1: Redis CUT -> every redirect still succeeds via MySQL; the redis-cache breaker opens; recovery closes it and repopulates")
    void f1_redisDown() {
        TestApi api = api();
        String code = create(api, api.newApiKey());
        assertThat(redis.hasKey("url:" + code)).isTrue();

        down(REDIS_PROXY);

        // No request may fail while Redis is down, before or after the breaker trips.
        for (int i = 0; i < 20; i++) {
            Response r = api.get("/" + code, Map.of());
            assertThat(r.status()).as("redirect #%d with Redis down", i).isEqualTo(302);
        }
        assertThat(breaker("redis-cache").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(gauge("cache_circuit_breaker_state", "redis-cache")).isEqualTo(2.0);

        // Breaker open: no per-request timeout tax - requests go straight to the database, so they are fast.
        List<Long> latencies = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            latencies.add(timed(() -> assertThat(api.get("/" + code, Map.of()).status()).isEqualTo(302)));
        }
        assertThat(latencies.stream().sorted().toList().get(latencies.size() / 2)).as("median latency, breaker open").isLessThan(250);

        up(REDIS_PROXY);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() -> {
            assertThat(api.get("/" + code, Map.of()).status()).isEqualTo(302);
            assertThat(breaker("redis-cache").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            assertThat(redis.hasKey("url:" + code)).as("cache repopulated after recovery").isTrue();
        });
    }

    @Test
    @Covers({"F1"})
    @DisplayName("F1: Redis BLACK-HOLED (connected but silent) -> redirects still succeed within the timeout budget; breaker opens")
    void f1_redisBlackhole() throws Exception {
        TestApi api = api();
        String code = create(api, api.newApiKey());

        blackhole(REDIS_PROXY);

        long slowest = 0;
        for (int i = 0; i < 15; i++) {
            long ms = timed(() -> assertThat(api.get("/" + code, Map.of()).status()).isEqualTo(302));
            slowest = Math.max(slowest, ms);
        }
        assertThat(slowest).as("worst request while Redis hangs (200ms command timeout + one short retry)").isLessThan(3_000);
        assertThat(breaker("redis-cache").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        long afterOpen = timed(() -> assertThat(api.get("/" + code, Map.of()).status()).isEqualTo(302));
        assertThat(afterOpen).as("once open, the hung dependency costs nothing").isLessThan(300);
    }

    @Test
    @Covers({"F1"})
    @DisplayName("s8.2: the retry sits INSIDE the breaker - the breaker counts one failure per call (not per attempt) and a "
            + "retry is never attempted against an already-open breaker")
    void f1_retryInsideCircuitBreaker() {
        Retry retry = retries.retry("redis-cache");
        CircuitBreaker cb = breaker("redis-cache");
        down(REDIS_PROXY);
        // Retry metrics accumulate over the shared context's lifetime, so measure deltas.
        long withRetryBefore = retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt();

        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> urlCache.get("anything")).isNotInstanceOf(CallNotPermittedException.class);
        }
        // 10 calls, each made 2 attempts (1 retry): the retry saw 10 exhausted calls, the breaker recorded 10 failures, not 20.
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt() - withRetryBefore).isEqualTo(10);
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(10);
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        long consultedBefore = retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()
                + retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
                + retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()
                + retry.getMetrics().getNumberOfSuccessfulCallsWithoutRetryAttempt();
        assertThatThrownBy(() -> urlCache.get("anything")).isInstanceOf(CallNotPermittedException.class);
        long consultedAfter = retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()
                + retry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
                + retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()
                + retry.getMetrics().getNumberOfSuccessfulCallsWithoutRetryAttempt();
        assertThat(consultedAfter).as("an OPEN breaker rejects before the retry is even consulted").isEqualTo(consultedBefore);
    }

    // ================================================================================================================
    // F2 - cache/DB desync
    // ================================================================================================================

    @Test
    @Covers({"F2"})
    @DisplayName("F2: a DELETE while Redis is down still succeeds (the DB is truth); the failed invalidation is retried until Redis is back, "
            + "so a deleted link stops redirecting")
    void f2_invalidationSurvivesRedisOutage() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = create(api, key);
        assertThat(api.get("/" + code, Map.of()).status()).isEqualTo(302);

        down(REDIS_PROXY);
        assertThat(api.delete("/api/v1/urls/" + code, key).status()).isEqualTo(204);
        assertThat(counter("cache_write_failed", "op", "invalidate")).isGreaterThanOrEqualTo(1);
        assertThat(meters.get("cache_invalidation_pending").gauge().value()).isGreaterThanOrEqualTo(1);

        up(REDIS_PROXY);
        // The stale entry is still in Redis at this instant; the background retry must remove it.
        await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
            assertThat(api.get("/" + code, Map.of()).status()).as("deleted link must stop redirecting").isEqualTo(404);
            assertThat(meters.get("cache_invalidation_pending").gauge().value()).isZero();
        });
    }

    // ================================================================================================================
    // F3 - MySQL primary unreachable (write path)
    // ================================================================================================================

    @Test
    @Covers({"F3", "F11"})
    @DisplayName("F3: MySQL DOWN -> create fails FAST with 503 + Retry-After and is NOT silently queued; cached redirects keep working")
    void f3_mysqlDown() {
        TestApi api = api();
        String key = api.newApiKey();
        api.create(key, unique());                                      // warm the API-key cache before the outage
        String cached = create(api, key);
        String failedUrl = unique();

        down(MYSQL_PROXY);

        Response[] holder = new Response[1];
        long elapsed = timed(() -> holder[0] = api.create(key, failedUrl));
        Response failed = holder[0];
        assertThat(failed.status()).isEqualTo(503);
        assertThat(failed.code()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(failed.header("retry-after")).isNotBlank();
        assertThat(elapsed).as("a refused connection fails fast").isLessThan(4_000);
        assertThat(failed.body()).doesNotContain("Exception").doesNotContain("jdbc").doesNotContain("mysql");

        // The redirect path does not need MySQL when the cache has the entry (and needs no auth service at all - F11).
        assertThat(api.get("/" + cached, Map.of()).status()).isEqualTo(302);

        up(MYSQL_PROXY);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(api.create(key, unique()).status()).isEqualTo(201));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM urls WHERE long_url = ?", Integer.class, failedUrl))
                .as("a failed create is honest: nothing was queued or half-written").isZero();
    }

    @Test
    @Covers({"F3"})
    @DisplayName("F3: MySQL BLACK-HOLED -> create still ends in a 503, bounded by the JDBC socket timeout, never a hang; cached redirects unaffected")
    void f3_mysqlBlackholedIsBoundedBySocketTimeout() throws Exception {
        TestApi api = api();
        String key = api.newApiKey();
        api.create(key, unique());                                    // warm the API-key cache
        String cached = create(api, key);

        blackhole(MYSQL_PROXY);

        Response[] holder = new Response[1];
        long elapsed = timed(() -> holder[0] = api.create(key, unique()));
        assertThat(holder[0].status()).isEqualTo(503);
        assertThat(holder[0].code()).isEqualTo("SERVICE_UNAVAILABLE");
        // Worst case: the idempotency read is tried twice (one jittered retry), each bounded by the socket timeout.
        assertThat(elapsed).as("bounded by 2 x socketTimeout, not by a TCP-level hang").isLessThan(2L * JDBC_SOCKET_TIMEOUT_MS + 4_000);
        assertThat(api.get("/" + cached, Map.of()).status()).isEqualTo(302);
    }

    @Test
    @Covers({"F3"})
    @DisplayName("F3/s8.2.3: the mysql-write breaker opens after repeated failures; writes are NEVER auto-retried (only the idempotent read is)")
    void f3_writesAreNeverRetried() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = create(api, key);
        Retry writeRetry = retries.retry("mysql-write");

        down(MYSQL_PROXY);

        long callsBefore = writeRetry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
                + writeRetry.getMetrics().getNumberOfFailedCallsWithRetryAttempt();
        // DELETE is a write: exactly one attempt, then an immediate 503 - the retry instance must not be involved at all.
        Response deleted = api.delete("/api/v1/urls/" + code, key);
        assertThat(deleted.status()).isEqualTo(503);
        assertThat(writeRetry.getMetrics().getNumberOfFailedCallsWithoutRetryAttempt()
                + writeRetry.getMetrics().getNumberOfFailedCallsWithRetryAttempt())
                .as("a mutation must never be retried").isEqualTo(callsBefore);

        // Repeated failing creates trip the breaker; then they are rejected instantly. Check the state right after each
        // failure so we observe it while OPEN (the test-only open-wait is short).
        for (int i = 0; i < 20 && breaker("mysql-write").getState() != CircuitBreaker.State.OPEN; i++) {
            api.create(key, unique());
        }
        assertThat(breaker("mysql-write").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        long fast = timed(() -> assertThat(api.create(key, unique()).status()).isEqualTo(503));
        assertThat(fast).as("an OPEN breaker rejects without touching the dependency").isLessThan(500);

        up(MYSQL_PROXY);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(api.get("/api/v1/urls/" + code, key).status()).isEqualTo(200));
        assertThat(api.get("/api/v1/urls/" + code, key).json().path("status").asText())
                .as("the failed DELETE was not silently applied later").isEqualTo("ACTIVE");
    }

    @Test
    @Covers({"F3"})
    @DisplayName("F3: an uncached redirect during a MySQL outage is an honest 503 (not a 500, not a hang) and never leaks internals")
    void f3_uncachedRedirectDuringOutage() {
        TestApi api = api();
        String code = create(api, api.newApiKey());
        redis.delete(List.of("url:" + code, "url:stale:" + code));

        down(MYSQL_PROXY);
        Response r = api.get("/" + code, Map.of());
        assertThat(r.status()).isEqualTo(503);
        assertThat(r.code()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(r.header("retry-after")).isNotBlank();
        assertThat(r.body()).doesNotContain("Exception").doesNotContain("jdbc");
    }

    @Test
    @Covers({"F11"})
    @DisplayName("F11: with the auth store (MySQL) down the management API answers 503 (not 500/401) while the public redirect is unaffected")
    void f11_authStoreDown() {
        TestApi api = api();
        String freshKey = api.newApiKey();                             // never used yet -> not in the verified-key cache
        String code = create(api, api.newApiKey());

        down(MYSQL_PROXY);

        Response mgmt = api.get("/api/v1/urls/" + code, freshKey);
        assertThat(mgmt.status()).isEqualTo(503);
        assertThat(mgmt.code()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(api.get("/" + code, Map.of()).status()).as("redirect has no dependency on the auth service").isEqualTo(302);
        assertThat(api.get("/" + code, "not-even-a-key").status()).isEqualTo(302);
    }

    // ================================================================================================================
    // F5 - message queue unreachable
    // ================================================================================================================

    @Test
    @Covers({"F5"})
    @DisplayName("F5: RabbitMQ CUT -> redirects are never delayed or failed; events are dropped and counted; the breaker opens; recovery resumes analytics")
    void f5_queueDown() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = create(api, key);

        down(RABBIT_PROXY);

        long slowest = 0;
        for (int i = 0; i < 25; i++) {
            long[] status = new long[1];
            long ms = timed(() -> status[0] = api.get("/" + code, Map.of()).status());
            assertThat(status[0]).as("redirect #%d with the queue down", i).isEqualTo(302);
            slowest = Math.max(slowest, ms);
        }
        assertThat(slowest).as("the queue outage must not add latency to any redirect").isLessThan(800);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(counter("click_publish_failed", "reason", "send_failed")
                    + counter("click_publish_failed", "reason", "circuit_open")).as("dropped events counted").isGreaterThanOrEqualTo(10);
            assertThat(breaker("mq-publish").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        });
        assertThat(api.get("/api/v1/urls/" + code + "/stats", key).status()).as("stats never depend on the broker").isEqualTo(200);

        up(RABBIT_PROXY);
        // Events dropped during the outage are gone (accepted, F5). New clicks must flow again once the breaker recovers.
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(700)).untilAsserted(() -> {
            api.get("/" + code, Map.of());
            assertThat(api.get("/api/v1/urls/" + code + "/stats", key).json().path("totalClicks").asLong()).isGreaterThanOrEqualTo(1);
        });
    }

    @Test
    @Covers({"F5"})
    @DisplayName("F5: RabbitMQ BLACK-HOLED (accepts the connection, never answers the handshake) -> still no added redirect latency")
    void f5_queueBlackholed() throws Exception {
        TestApi api = api();
        String code = create(api, api.newApiKey());
        blackhole(RABBIT_PROXY);
        // Force the publisher to need a fresh connection: cut then black-hole.
        long slowest = 0;
        for (int i = 0; i < 15; i++) {
            long[] status = new long[1];
            long ms = timed(() -> status[0] = api.get("/" + code, Map.of()).status());
            assertThat(status[0]).isEqualTo(302);
            slowest = Math.max(slowest, ms);
        }
        assertThat(slowest).isLessThan(800);
    }

    // ================================================================================================================
    // F6 - analytics consumer down / behind
    // ================================================================================================================

    @Test
    @Covers({"F6"})
    @DisplayName("F6: consumer stopped -> events queue up (queue_depth rises), redirects unaffected, stats stay stale but honest; restart converges")
    void f6_consumerDown() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = create(api, key);
        api.get("/" + code, Map.of());
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(api.get("/api/v1/urls/" + code + "/stats", key).json().path("totalClicks").asLong()).isEqualTo(1));

        listeners.stop();
        try {
            for (int i = 0; i < 6; i++) {
                assertThat(api.get("/" + code, Map.of()).status()).isEqualTo(302);
            }
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                queueMonitor.poll();
                assertThat(meters.get("queue_depth").gauge().value()).as("backlog visible as queue_depth").isGreaterThanOrEqualTo(6);
                assertThat(meters.get("consumer_lag").gauge().value()).isGreaterThanOrEqualTo(6);
            });
            assertThat(api.get("/api/v1/urls/" + code + "/stats", key).json().path("totalClicks").asLong())
                    .as("stats are stale, and updatedAt lets the client see that").isEqualTo(1);
        } finally {
            listeners.start();
        }
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(api.get("/api/v1/urls/" + code + "/stats", key).json().path("totalClicks").asLong()).isEqualTo(7));
    }

    // ================================================================================================================
    // F7 - cache-miss stampede
    // ================================================================================================================

    @Test
    @Covers({"F7"})
    @DisplayName("F7: 60 simultaneous requests for a suddenly-hot, uncached code reach MySQL ONCE, not 60 times")
    void f7_stampedeIsCoalesced() throws Exception {
        TestApi api = api();
        String code = create(api, api.newApiKey());
        redis.delete(List.of("url:" + code, "url:stale:" + code));
        long queriesBefore = dbStats().getQueryExecutionCount();

        int concurrent = 60;
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < concurrent; i++) {
            calls.add(() -> api.get("/" + code, Map.of()).status());
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(concurrent)) {
            for (var f : pool.invokeAll(calls)) {
                statuses.add(f.get());
            }
        }
        long dbReads = dbStats().getQueryExecutionCount() - queriesBefore;

        assertThat(statuses).allMatch(s -> s == 302);
        assertThat(dbReads).as("MySQL reads for %d concurrent misses on one key", concurrent).isLessThanOrEqualTo(3);
        assertThat(redis.hasKey("url:" + code)).isTrue();
    }

    @Test
    @Covers({"F7"})
    @DisplayName("F7: when another instance holds the lock and a stale copy exists, it is served - with no MySQL read at all")
    void f7_staleWhileRevalidate() {
        TestApi api = api();
        String url = unique();
        String code = api.create(api.newApiKey(), url).json().path("shortCode").asText();
        redis.delete("url:" + code);                                    // primary gone, stale copy remains
        redis.opsForValue().set("url:lock:" + code, "another-instance", Duration.ofSeconds(5));
        long queriesBefore = dbStats().getQueryExecutionCount();

        Response r = api.get("/" + code, Map.of());

        assertThat(r.status()).isEqualTo(302);
        assertThat(r.header("location")).isEqualTo(url);
        assertThat(dbStats().getQueryExecutionCount()).as("served from the stale copy, MySQL untouched").isEqualTo(queriesBefore);
    }

    @Test
    @Covers({"F7"})
    @DisplayName("F7: a lock holder that never finishes cannot hang requests - after the bounded wait they read MySQL themselves")
    void f7_boundedWait() {
        TestApi api = api();
        String url = unique();
        String code = api.create(api.newApiKey(), url).json().path("shortCode").asText();
        redis.delete(List.of("url:" + code, "url:stale:" + code));       // no primary, no stale copy
        redis.opsForValue().set("url:lock:" + code, "dead-instance", Duration.ofSeconds(30));
        long queriesBefore = dbStats().getQueryExecutionCount();

        long[] ms = new long[1];
        Response[] r = new Response[1];
        ms[0] = timed(() -> r[0] = api.get("/" + code, Map.of()));

        assertThat(r[0].status()).isEqualTo(302);
        assertThat(r[0].header("location")).isEqualTo(url);
        assertThat(dbStats().getQueryExecutionCount()).isEqualTo(queriesBefore + 1);
        assertThat(ms[0]).as("waited only the bounded lock window, not the lock's 30s TTL").isLessThan(3_000);
    }

    @Test
    @Covers({"F7"})
    @DisplayName("F7: waiters pick up the value the lock holder populates instead of reading MySQL")
    void f7_waiterUsesPopulatedCache() throws Exception {
        TestApi api = api();
        String url = unique();
        String code = api.create(api.newApiKey(), url).json().path("shortCode").asText();
        redis.delete(List.of("url:" + code, "url:stale:" + code));
        redis.opsForValue().set("url:lock:" + code, "other-instance", Duration.ofSeconds(5));
        long queriesBefore = dbStats().getQueryExecutionCount();

        // The "other instance" finishes populating shortly after our request starts waiting.
        Thread populator = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            redis.opsForValue().set("url:" + code,
                    "{\"longUrl\":\"" + url + "\",\"expiresAt\":null,\"active\":true,\"version\":1}", Duration.ofMinutes(1));
        });
        Response r = api.get("/" + code, Map.of());
        populator.join();

        assertThat(r.status()).isEqualTo(302);
        assertThat(dbStats().getQueryExecutionCount()).as("coalesced onto the populated entry").isEqualTo(queriesBefore);
    }
}
