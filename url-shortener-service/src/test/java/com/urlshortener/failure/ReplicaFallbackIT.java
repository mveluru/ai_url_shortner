package com.urlshortener.failure;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.testsupport.AbstractFaultInjectionIT;
import com.urlshortener.testsupport.Covers;
import com.urlshortener.testsupport.TestApi;
import com.urlshortener.testsupport.TestApi.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * F4: "MySQL primary unreachable, replica still up (read path)" (design doc section 8). A SECOND real MySQL plays the read
 * replica. It is not wired up with real replication - the test copies a row across (or deliberately does not, to model
 * replica lag) - because what is under test is the application's ROUTING and fallback, not MySQL's replication.
 */
class ReplicaFallbackIT extends AbstractFaultInjectionIT {

    private static final MySQLContainer<?> REPLICA = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withNetwork(NETWORK).withNetworkAliases("mysql-replica")
            .withDatabaseName("urlshortener").withUsername("urlshortener").withPassword("urlshortener")
            .withUrlParam("connectionTimeZone", "UTC").withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withCommand("--character-set-server=utf8mb4");

    static {
        REPLICA.start();
        // Same schema as the primary, so the replica is a faithful stand-in.
        Flyway.configure().dataSource(REPLICA.getJdbcUrl(), REPLICA.getUsername(), REPLICA.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    @DynamicPropertySource
    static void replica(DynamicPropertyRegistry registry) {
        registry.add("app.datasource.replica.url", REPLICA::getJdbcUrl);
        registry.add("app.datasource.replica.username", REPLICA::getUsername);
        registry.add("app.datasource.replica.password", REPLICA::getPassword);
    }

    @Autowired JdbcTemplate primary;
    @Autowired StringRedisTemplate redis;

    private final JdbcTemplate replica = new JdbcTemplate(
            new DriverManagerDataSource(REPLICA.getJdbcUrl(), REPLICA.getUsername(), REPLICA.getPassword()));

    private String unique() {
        return "https://public.test/" + UUID.randomUUID();
    }

    /** Models replication catching up: copies the row from the primary, optionally changing its target. */
    private void replicate(String code, String overrideLongUrl) {
        Map<String, Object> row = primary.queryForMap("SELECT * FROM urls WHERE short_code = ?", code);
        replica.update("INSERT INTO urls (id, short_code, long_url, is_custom_alias, owner_key_id, idempotency_fingerprint, "
                        + "created_at, expires_at, is_active, version) VALUES (?,?,?,?,?,?,?,?,?,?)",
                row.get("id"), row.get("short_code"), overrideLongUrl != null ? overrideLongUrl : row.get("long_url"),
                row.get("is_custom_alias"), row.get("owner_key_id"), row.get("idempotency_fingerprint"), row.get("created_at"),
                row.get("expires_at"), row.get("is_active"), row.get("version"));
    }

    private void forceCacheMiss(String code) {
        redis.delete(List.of("url:" + code, "url:stale:" + code));
    }

    @Test
    @Covers({"F4"})
    @DisplayName("F4: the redirect's DB fallback is served by the REPLICA, not the primary (proved by giving the replica a distinct target)")
    void redirectFallbackReadsTheReplica() {
        TestApi api = api();
        String code = api.create(api.newApiKey(), unique()).json().path("shortCode").asText();
        replicate(code, "https://served-by-replica.test/");
        forceCacheMiss(code);

        Response r = api.get("/" + code, Map.of());

        assertThat(r.status()).isEqualTo(302);
        assertThat(r.header("location")).as("came from the replica").isEqualTo("https://served-by-replica.test/");
    }

    @Test
    @Covers({"F4", "F3"})
    @DisplayName("F4: with the PRIMARY down and the cache empty, redirects are still served from the replica; primary-only reads fail honestly")
    void primaryDownReplicaServesRedirects() {
        TestApi api = api();
        String key = api.newApiKey();
        String url = unique();
        String code = api.create(key, url).json().path("shortCode").asText();
        api.get("/api/v1/urls/" + code, key);                                  // warm the API-key cache
        replicate(code, null);
        forceCacheMiss(code);

        down(MYSQL_PROXY);

        Response redirect = api.get("/" + code, Map.of());
        assertThat(redirect.status()).as("redirect survives a primary outage").isEqualTo(302);
        assertThat(redirect.header("location")).isEqualTo(url);

        // Ownership/metadata reads and every write need the primary: an honest 503, not stale data.
        assertThat(api.get("/api/v1/urls/" + code, key).status()).isEqualTo(503);
        assertThat(api.create(key, unique()).status()).isEqualTo(503);
    }

    @Test
    @Covers({"F4"})
    @DisplayName("F4: replica LAG is accepted for redirects - a code not yet replicated is a 404 until it arrives (the cache covers the gap)")
    void replicaLagIsAccepted() {
        TestApi api = api();
        String code = api.create(api.newApiKey(), unique()).json().path("shortCode").asText();
        assertThat(api.get("/" + code, Map.of()).status()).as("the write-through cache covers a brand-new code").isEqualTo(302);

        forceCacheMiss(code);                                                  // replica has not received the row yet
        assertThat(api.get("/" + code, Map.of()).status()).isEqualTo(404);

        replicate(code, null);                                                 // replication catches up
        assertThat(api.get("/" + code, Map.of()).status()).isEqualTo(302);
    }

    @Test
    @Covers({"F4"})
    @DisplayName("F4: an owner always reads their OWN writes immediately (metadata reads use the primary), even while the replica lags")
    void ownerReadsOwnWrites() {
        TestApi api = api();
        String key = api.newApiKey();
        String code = api.create(key, unique()).json().path("shortCode").asText();
        // The row exists ONLY on the primary at this moment.
        assertThat(api.get("/api/v1/urls/" + code, key).status()).isEqualTo(200);
        assertThat(api.delete("/api/v1/urls/" + code, key).status()).isEqualTo(204);
    }
}
