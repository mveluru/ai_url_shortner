package com.urlshortener.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    /** All application time comes from here (UTC), so expiry logic is testable and consistent (F13). */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
