package com.urlshortener.common.resilience;

import com.urlshortener.common.config.AppProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.common.retry.configuration.RetryConfigCustomizer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * Wires the retry-with-jitter policy (section 8.2.1) and exports one breaker-state gauge per named breaker
 * (section 8.2.2, 12.1). Each dependency has its own named instance; a struggling queue must never trip the Redis
 * breaker or vice versa.
 */
@Configuration
public class ResilienceConfig {

    public static final String REDIS = "redis-cache";
    public static final String MYSQL_READ = "mysql-read";
    public static final String MYSQL_WRITE = "mysql-write";
    public static final String MQ_PUBLISH = "mq-publish";
    public static final String AUTH_LOOKUP = "auth-lookup";

    static final List<String> BREAKERS = List.of(REDIS, MYSQL_READ, MYSQL_WRITE, MQ_PUBLISH, AUTH_LOOKUP);

    @Bean
    RetryConfigCustomizer redisRetryCustomizer(AppProperties props) {
        return customizer(REDIS, props);
    }

    @Bean
    RetryConfigCustomizer mysqlReadRetryCustomizer(AppProperties props) {
        return customizer(MYSQL_READ, props);
    }

    @Bean
    RetryConfigCustomizer mysqlWriteRetryCustomizer(AppProperties props) {
        return customizer(MYSQL_WRITE, props);
    }

    @Bean
    RetryConfigCustomizer authRetryCustomizer(AppProperties props) {
        return customizer(AUTH_LOOKUP, props);
    }

    private static RetryConfigCustomizer customizer(String name, AppProperties props) {
        AppProperties.Backoff b = props.retryBackoff().get(name);
        if (b == null) {
            throw new IllegalStateException("Missing app.retry-backoff." + name + " (design doc section 8.2.1)");
        }
        return RetryConfigCustomizer.of(name,
                builder -> builder.intervalFunction(
                        new FullJitterIntervalFunction(b.base(), b.cap())));
    }

    /** {@code cache_circuit_breaker_state{name=...}}: 0 CLOSED, 1 HALF_OPEN, 2 OPEN, 3 other (DISABLED/FORCED). */
    @EventListener(ApplicationReadyEvent.class)
    void registerBreakerGauges(ApplicationReadyEvent event) {
        CircuitBreakerRegistry registry = event.getApplicationContext().getBean(CircuitBreakerRegistry.class);
        MeterRegistry meters = event.getApplicationContext().getBean(MeterRegistry.class);
        for (String name : BREAKERS) {
            CircuitBreaker breaker = registry.circuitBreaker(name);
            Gauge.builder("cache_circuit_breaker_state", breaker, ResilienceConfig::stateValue)
                    .tag("name", name)
                    .description("0=CLOSED 1=HALF_OPEN 2=OPEN")
                    .register(meters);
        }
    }

    static double stateValue(CircuitBreaker breaker) {
        return switch (breaker.getState()) {
            case CLOSED -> 0;
            case HALF_OPEN -> 1;
            case OPEN -> 2;
            default -> 3;
        };
    }
}
