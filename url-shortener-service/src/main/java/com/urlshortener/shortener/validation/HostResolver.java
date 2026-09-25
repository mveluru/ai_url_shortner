package com.urlshortener.shortener.validation;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** Port over DNS so the SSRF guard can be tested with hostile resolutions and no network. */
@FunctionalInterface
public interface HostResolver {

    /** @return every address the host resolves to (A and AAAA) */
    InetAddress[] resolve(String host) throws UnknownHostException;
}
