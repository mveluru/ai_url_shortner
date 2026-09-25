package com.urlshortener.testsupport;

import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real MySQL / Redis / RabbitMQ (design doc section 17, 20.1): failure-injection tests only mean something against real
 * infrastructure that can actually be cut off, not mocks. Containers are singletons started once per JVM and shared by
 * every integration test, on one Docker network so a fault-injection proxy can sit in front of them.
 *
 * <p>Deliberately registers NO application properties. {@link AbstractIntegrationTest} points the app straight at the
 * containers; {@link AbstractFaultInjectionIT} points it at Toxiproxy instead. They are siblings, not parent and child:
 * when one inherited the other's {@code @DynamicPropertySource}, the parent's direct endpoints silently overwrote the
 * proxied ones and every injected fault landed on a proxy nothing was using.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability          // Boot disables metrics export in tests by default; the metrics ARE part of the design (section 12)
@ActiveProfiles("test")
@Import(TestBeans.class)
public abstract class Containers {

    protected static final Network NETWORK = Network.newNetwork();

    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withNetwork(NETWORK).withNetworkAliases("mysql")
            .withDatabaseName("urlshortener")
            .withUsername("urlshortener")
            .withPassword("urlshortener")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true")
            .withUrlParam("connectTimeout", "2000")
            .withUrlParam("socketTimeout", "5000")
            .withCommand("--character-set-server=utf8mb4");

    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withNetwork(NETWORK).withNetworkAliases("redis")
            .withExposedPorts(6379);

    protected static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"))
                    .withNetwork(NETWORK).withNetworkAliases("rabbit");

    static {
        MYSQL.start();
        REDIS.start();
        RABBIT.start();
    }

    @LocalServerPort
    protected int port;

    protected TestApi api() {
        return new TestApi(port);
    }
}
