package com.urlshortener.redirect.cache;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.resource.Delay;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.data.redis.ClientResourcesBuilderCustomizer;
import org.springframework.boot.autoconfigure.data.redis.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class RedisConfig {

    /**
     * Fail fast when Redis is down (F1). Lettuce's default is to <em>queue</em> commands while disconnected and let each
     * one wait out its timeout, which would make every redirect pay that timeout even after the breaker should have
     * tripped. REJECT_COMMANDS errors immediately so the redirect goes straight to the database fallback.
     */
    /**
     * Lettuce backs reconnect attempts off exponentially up to 30 seconds by default, so after Redis returns the service
     * could keep bypassing it for that long. Capping the delay at 2s keeps recovery prompt (F1: "until a health probe
     * succeeds again") without hammering a Redis that is genuinely down.
     */
    @Bean
    ClientResourcesBuilderCustomizer boundedReconnectDelay() {
        return builder -> builder.reconnectDelay(
                Delay.exponential(Duration.ofMillis(100), Duration.ofSeconds(2), 2, TimeUnit.MILLISECONDS));
    }

    @Bean
    LettuceClientConfigurationBuilderCustomizer failFastLettuce() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(200)).build())
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofMillis(200)))
                .build());
    }
}
