package com.urlshortener.shortener.validation;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Decides whether an address is one an SSRF guard must refuse (design doc section 10.3): RFC 1918, loopback,
 * link-local (including the 169.254.169.254 cloud-metadata address), unspecified, multicast, plus the ranges Java's
 * own predicates miss - carrier-grade NAT, IPv6 unique-local, benchmarking/reserved blocks - and IPv6 forms that
 * <em>embed</em> an IPv4 address (NAT64, 6to4), which would otherwise smuggle a private address past the check.
 */
public final class IpClassifier {

    private IpClassifier() {}

    public static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isBlockedV4(b);
        }
        if (address instanceof Inet6Address) {
            return isBlockedV6(b);
        }
        return true;   // unknown address family: cannot verify safety, so refuse
    }

    private static boolean isBlockedV4(byte[] b) {
        int a = b[0] & 0xFF;
        int c = b[1] & 0xFF;
        return a == 0                                        // 0.0.0.0/8 "this network"
                || (a == 100 && (c & 0xC0) == 64)            // 100.64.0.0/10 carrier-grade NAT
                || (a == 192 && c == 0 && (b[2] & 0xFF) == 0) // 192.0.0.0/24 IETF protocol assignments
                || (a == 198 && (c & 0xFE) == 18)            // 198.18.0.0/15 benchmarking
                || a >= 240;                                 // 240.0.0.0/4 reserved + 255.255.255.255 broadcast
    }

    private static boolean isBlockedV6(byte[] b) {
        if ((b[0] & 0xFE) == 0xFC) {
            return true;                                     // fc00::/7 unique-local (Java only knows fec0::/10)
        }
        // 64:ff9b::/96 NAT64 embeds an IPv4 address in the last 4 bytes.
        if (b[0] == 0x00 && b[1] == 0x64 && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B && allZero(b, 4, 12)) {
            return embeddedV4Blocked(b, 12);
        }
        // 2002::/16 6to4 embeds an IPv4 address in bytes 2..5.
        if (b[0] == 0x20 && b[1] == 0x02) {
            return embeddedV4Blocked(b, 2);
        }
        return false;
    }

    private static boolean embeddedV4Blocked(byte[] b, int offset) {
        try {
            byte[] v4 = {b[offset], b[offset + 1], b[offset + 2], b[offset + 3]};
            return isBlocked(InetAddress.getByAddress(v4));
        } catch (UnknownHostException e) {
            return true;
        }
    }

    private static boolean allZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }
}
