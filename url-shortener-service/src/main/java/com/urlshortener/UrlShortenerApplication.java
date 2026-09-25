package com.urlshortener;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Single deployable hosting the three logical services as packages: {@code shortener} (write path),
 * {@code redirect} (read path) and {@code analytics} (async consumer + stats API). Design doc section 20.7.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class UrlShortenerApplication {

    public static void main(String[] args) {
        SpringApplication.run(UrlShortenerApplication.class, args);
    }
}
