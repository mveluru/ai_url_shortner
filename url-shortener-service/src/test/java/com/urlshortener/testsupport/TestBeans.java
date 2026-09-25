package com.urlshortener.testsupport;

import com.urlshortener.shortener.validation.HostResolver;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Deterministic DNS for integration tests: no test depends on the outside internet. {@code public*} hosts resolve to a
 * public address, {@code internal*} to an RFC 1918 one, and IP literals resolve to themselves.
 */
@TestConfiguration
public class TestBeans {

    @Bean
    @Primary
    HostResolver testHostResolver() {
        return host -> {
            if (host.startsWith("public") || host.endsWith(".public.test")) {
                return new InetAddress[] {InetAddress.getByName("93.184.216.34")};
            }
            if (host.startsWith("internal")) {
                return new InetAddress[] {InetAddress.getByName("10.0.0.5")};
            }
            if (host.matches("[0-9.]+") || host.contains(":") || host.equals("localhost")) {
                return InetAddress.getAllByName(host);
            }
            throw new UnknownHostException(host);
        };
    }
}
