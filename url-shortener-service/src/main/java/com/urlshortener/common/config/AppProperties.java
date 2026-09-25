package com.urlshortener.common.config;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All tunables in one typed place. Limits and windows are configuration, not code, so they can change per
 * environment without a deploy (design doc section 11.3).
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue("http://localhost:8080") String publicBaseUrl,
        @DefaultValue("short.ly") List<String> selfHosts,
        @DefaultValue Url url,
        @DefaultValue Alias alias,
        @DefaultValue Code code,
        @DefaultValue Idempotency idempotency,
        @DefaultValue Cache cache,
        @DefaultValue RateLimit rateLimit,
        @DefaultValue Analytics analytics,
        @DefaultValue ExpirySweep expirySweep,
        @DefaultValue Features features,
        @DefaultValue Http http,
        @DefaultValue Map<String, Backoff> retryBackoff) {

    /** Full-jitter retry bounds for one named dependency (section 8.2.1). */
    public record Backoff(Duration base, Duration cap) {}

    /** longUrl constraints (E6) and SSRF DNS resolution budget (section 10.3). */
    public record Url(@DefaultValue("2048") int maxLength, @DefaultValue("2s") Duration dnsTimeout) {}

    /** Custom alias rules (section 4.3). Reserved words are data, not code. */
    public record Alias(
            @DefaultValue("3") int minLength,
            @DefaultValue("20") int maxLength,
            @DefaultValue("classpath:reserved-aliases.txt") String reservedWordsLocation,
            @DefaultValue List<String> additionalReservedWords) {}

    /** Short-code generation (section 4.1). */
    public record Code(
            @DefaultValue("6") int initialLength,
            @DefaultValue("0.8") double rolloverUtilization,
            @DefaultValue("dev-only-feistel-key-change-me-in-prod") String feistelKey,
            @DefaultValue("3") int aliasClashMaxAttempts,
            @DefaultValue("10000") int idBlockSize) {}

    /** Idempotent-create window (section 6.3). */
    public record Idempotency(@DefaultValue("24h") Duration window) {}

    /** Redis cache-aside + stampede guard (F1, F2, F7). */
    public record Cache(
            @DefaultValue("10m") Duration ttl,
            @DefaultValue("24h") Duration staleTtl,
            @DefaultValue("2s") Duration lockTtl,
            @DefaultValue("200ms") Duration lockWait,
            @DefaultValue("10ms") Duration lockPollInterval) {}

    /** Sustained rate + burst per limiter (section 11.3). */
    public record RateLimit(Limit createPerKey, Limit redirectPerIp, Limit apiPerIp) {
        /** {@code burst} requests are allowed per {@code burst / requestsPerMinute} minutes, i.e. the sustained rate holds. */
        public record Limit(int requestsPerMinute, int burst) {}

        public RateLimit {
            // Defaults differ per limiter (section 11.3), so they are applied here rather than via @DefaultValue.
            createPerKey = createPerKey != null ? createPerKey : new Limit(100, 20);
            redirectPerIp = redirectPerIp != null ? redirectPerIp : new Limit(1000, 200);
            apiPerIp = apiPerIp != null ? apiPerIp : new Limit(600, 100);
        }
    }

    /** Click pipeline (sections 5.2, 5.3, F5, F6). */
    public record Analytics(
            @DefaultValue("url-shortener.clicks") String exchange,
            @DefaultValue("url-shortener.clicks.events") String queue,
            @DefaultValue("url-shortener.clicks.dlx") String deadLetterExchange,
            @DefaultValue("url-shortener.clicks.events.dlq") String deadLetterQueue,
            @DefaultValue("72h") Duration queueRetention,
            @DefaultValue("5") int deliveryLimit,
            @DefaultValue("1000") int maxPublishInFlight,
            @DefaultValue("512") int headerMaxLength,
            @DefaultValue("20") int topReferrers,
            @DefaultValue("90") int retentionDays,
            @DefaultValue("dev-only-ip-hash-secret-change-me") String ipHashSecret,
            @DefaultValue("1d") Duration ipHashKeyRotation,
            @DefaultValue("15s") Duration queueDepthPollInterval) {}

    /** Expiry sweep is storage hygiene only; correctness is enforced at read time (F12). */
    public record ExpirySweep(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("60s") Duration interval,
            @DefaultValue("1000") int batchSize) {}

    /** Config-driven feature flags gating new API fields without a redeploy (section 13). */
    public record Features(@DefaultValue("true") boolean statsDeviceBreakdown) {}

    /** Header values sent on dependency-failure responses. */
    public record Http(@DefaultValue("5") int retryAfterSeconds) {}
}
