package com.urlshortener.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.io.IOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.ToxiproxyContainer.ContainerProxy;

/**
 * Base for failure-injection tests (design doc section 17: "each row of section 8's Failure Mode Catalog gets a
 * corresponding test... asserting the <em>defined</em> mitigated behavior, not just 'doesn't crash'").
 *
 * <p>The application talks to MySQL, Redis and RabbitMQ <em>through Toxiproxy</em>, so a test can inject the three shapes
 * real outages take and then undo them:
 * <ul>
 *   <li>{@link #down} - the dependency is gone: connections are reset and new ones are refused (fails fast);</li>
 *   <li>{@link #blackhole} - connections stay open but nothing is ever answered (the nastiest: only timeouts save you);</li>
 *   <li>{@link #slow} - the dependency answers, late.</li>
 * </ul>
 * Every fault is undone around each test and every circuit breaker is reset, so tests cannot leak state into each other.
 */
@TestPropertySource(properties = {
        // Same policy as production, only with shorter waits so breaker recovery is observable within a test.
        "resilience4j.circuitbreaker.instances.redis-cache.wait-duration-in-open-state=1s",
        "resilience4j.circuitbreaker.instances.mysql-read.wait-duration-in-open-state=1s",
        "resilience4j.circuitbreaker.instances.mysql-write.wait-duration-in-open-state=1s",
        "resilience4j.circuitbreaker.instances.mq-publish.wait-duration-in-open-state=1s",
        "resilience4j.circuitbreaker.instances.auth-lookup.wait-duration-in-open-state=1s",
        "spring.datasource.hikari.connection-timeout=500",
        "spring.datasource.hikari.maximum-pool-size=10",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.com.rabbitmq=ERROR"
})
public abstract class AbstractFaultInjectionIT extends Containers {

    /** The JDBC socket timeout used here; a black-holed MySQL cannot be detected faster than this. */
    protected static final int JDBC_SOCKET_TIMEOUT_MS = 3_000;

    private static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0")
            .withNetwork(NETWORK);

    protected static final ContainerProxy MYSQL_PROXY;
    protected static final ContainerProxy REDIS_PROXY;
    protected static final ContainerProxy RABBIT_PROXY;

    private static final ToxiproxyClient CONTROL;

    static {
        TOXIPROXY.start();
        MYSQL_PROXY = TOXIPROXY.getProxy(MYSQL, 3306);
        REDIS_PROXY = TOXIPROXY.getProxy(REDIS, 6379);
        RABBIT_PROXY = TOXIPROXY.getProxy(RABBIT, 5672);
        CONTROL = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
    }

    @Autowired
    protected CircuitBreakerRegistry breakers;

    @Autowired
    private Environment environment;

    /** The ONLY property registration for this hierarchy: everything goes through the proxies (see {@link Containers}). */
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:mysql://" + MYSQL_PROXY.getContainerIpAddress() + ":"
                + MYSQL_PROXY.getProxyPort() + "/urlshortener?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
                + "&connectTimeout=1000&socketTimeout=" + JDBC_SOCKET_TIMEOUT_MS);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS_PROXY::getContainerIpAddress);
        registry.add("spring.data.redis.port", REDIS_PROXY::getProxyPort);
        registry.add("spring.rabbitmq.host", RABBIT_PROXY::getContainerIpAddress);
        registry.add("spring.rabbitmq.port", RABBIT_PROXY::getProxyPort);
        registry.add("spring.rabbitmq.username", () -> "guest");
        registry.add("spring.rabbitmq.password", () -> "guest");
    }

    /**
     * A fault injected into a proxy that nothing is connected to proves nothing, and fails silently as "the system
     * tolerated the fault". So verify, before every test, that the application really resolved the PROXY endpoints.
     */
    @BeforeEach
    void applicationIsConnectedThroughTheProxies() {
        assertThat(environment.getProperty("spring.datasource.url"))
                .as("MySQL must be reached through Toxiproxy").contains(":" + MYSQL_PROXY.getProxyPort() + "/");
        assertThat(environment.getProperty("spring.data.redis.port"))
                .as("Redis must be reached through Toxiproxy").isEqualTo(String.valueOf(REDIS_PROXY.getProxyPort()));
        assertThat(environment.getProperty("spring.rabbitmq.port"))
                .as("RabbitMQ must be reached through Toxiproxy").isEqualTo(String.valueOf(RABBIT_PROXY.getProxyPort()));
    }

    @BeforeEach
    @AfterEach
    void restoreEverything() throws IOException {
        for (Proxy proxy : CONTROL.getProxies()) {
            proxy.enable();
            for (var toxic : proxy.toxics().getAll()) {
                toxic.remove();
            }
        }
        breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    /** The dependency is DOWN: existing connections are reset and new ones are refused, so callers fail immediately. */
    protected static void down(ContainerProxy proxy) {
        try {
            proxyFor(proxy).disable();
        } catch (IOException e) {
            throw new IllegalStateException("Toxiproxy refused to take the dependency down", e);
        }
    }

    protected static void up(ContainerProxy proxy) {
        try {
            proxyFor(proxy).enable();
        } catch (IOException e) {
            throw new IllegalStateException("Toxiproxy refused to bring the dependency back", e);
        }
    }

    /** The dependency is a BLACK HOLE: connections stay open but nothing is ever answered. Only timeouts detect this. */
    protected static void blackhole(ContainerProxy proxy) throws IOException {
        proxy.toxics().timeout("blackhole", ToxicDirection.DOWNSTREAM, 0);
        proxy.toxics().timeout("blackhole-up", ToxicDirection.UPSTREAM, 0);
    }

    /** The dependency is up but SLOW. */
    protected static void slow(ContainerProxy proxy, long latencyMillis) throws IOException {
        proxy.toxics().latency("slow", ToxicDirection.DOWNSTREAM, latencyMillis);
    }

    private static Proxy proxyFor(ContainerProxy proxy) {
        try {
            for (Proxy p : CONTROL.getProxies()) {
                if (p.getListen().endsWith(":" + proxy.getOriginalProxyPort())) {
                    return p;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot reach Toxiproxy", e);
        }
        throw new IllegalStateException("No Toxiproxy proxy listens on " + proxy.getOriginalProxyPort());
    }
}
