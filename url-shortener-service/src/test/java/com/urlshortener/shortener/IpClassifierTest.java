package com.urlshortener.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.shortener.validation.IpClassifier;
import com.urlshortener.testsupport.Covers;
import java.net.InetAddress;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;

class IpClassifierTest {

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "127.255.255.254", "10.0.0.1", "10.255.255.255", "172.16.0.1", "172.31.255.255", "192.168.0.1",
            "169.254.169.254", "169.254.0.1", "0.0.0.0", "0.1.2.3", "100.64.0.1", "100.127.255.255", "192.0.0.5", "198.18.0.1",
            "198.19.255.255", "224.0.0.1", "239.255.255.255", "240.0.0.1", "255.255.255.255",
            "::1", "::", "fe80::1", "fc00::1", "fd12:3456:789a::1", "fd00:ec2::254", "ff02::1", "::ffff:127.0.0.1", "::ffff:10.1.1.1",
            "64:ff9b::7f00:1", "64:ff9b::a00:1", "2002:7f00:1::", "2002:c0a8:101::"})
    @Covers({"E4"})
    @DisplayName("E4: private, loopback, link-local, CGNAT, reserved, multicast and IPv6-embedded-private addresses are blocked")
    void blocked(String literal) throws Exception {
        assertThat(IpClassifier.isBlocked(InetAddress.getByName(literal))).as(literal).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"93.184.216.34", "8.8.8.8", "1.1.1.1", "172.15.255.255", "172.32.0.1", "100.63.255.255", "100.128.0.1",
            "198.17.255.255", "198.20.0.1", "223.255.255.255", "2606:4700:4700::1111", "2001:4860:4860::8888",
            "64:ff9b::808:808", "2002:808:808::"})
    @Covers({"E4"})
    @DisplayName("E4: ordinary public addresses (including range neighbours and public NAT64/6to4 embeds) are NOT blocked")
    void allowed(String literal) throws Exception {
        assertThat(IpClassifier.isBlocked(InetAddress.getByName(literal))).as(literal).isFalse();
    }
}
