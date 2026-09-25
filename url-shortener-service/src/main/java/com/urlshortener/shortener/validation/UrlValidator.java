package com.urlshortener.shortener.validation;

import com.urlshortener.common.config.AppProperties;
import com.urlshortener.common.error.InvalidUrlException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@code longUrl} validation, edge cases E1-E6 (design doc section 7), all reported as INVALID_URL.
 * Order matters: cheap structural checks first, self-reference before any DNS lookup, SSRF last.
 */
@Component
public class UrlValidator {

    /** Explicit allowlist, not a blocklist (E3): anything not listed - javascript:, file:, data:, ftp: - is refused. */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** A dot-separated label that is a decimal number or a 0x-prefixed hex number. */
    private static final Pattern NUMERIC_LABEL = Pattern.compile("[0-9]+|0[xX][0-9a-fA-F]*");

    private final int maxLength;
    private final Set<String> selfHosts;
    private final SsrfGuard ssrfGuard;

    public UrlValidator(AppProperties props, SsrfGuard ssrfGuard) {
        this.maxLength = props.url().maxLength();
        this.ssrfGuard = ssrfGuard;
        Set<String> hosts = new HashSet<>();
        props.selfHosts().forEach(h -> hosts.add(normalizeHost(h)));
        String baseHost = URI.create(props.publicBaseUrl()).getHost();
        if (baseHost != null) {
            hosts.add(normalizeHost(baseHost));
        }
        this.selfHosts = Set.copyOf(hosts);
    }

    /** @return the trimmed, validated URL */
    public String validate(String rawLongUrl) {
        if (rawLongUrl == null || rawLongUrl.isBlank()) {
            throw new InvalidUrlException("longUrl is required.");                                   // E1
        }
        String longUrl = rawLongUrl.strip();
        if (longUrl.length() > maxLength) {
            throw new InvalidUrlException("longUrl must be at most " + maxLength + " characters.");  // E6
        }
        URI uri;
        try {
            uri = new URI(longUrl);
        } catch (URISyntaxException e) {
            throw new InvalidUrlException("longUrl is not a valid absolute URL.");                   // E2
        }
        if (uri.getScheme() == null) {
            throw new InvalidUrlException("longUrl must be an absolute URL with a scheme.");         // E2
        }
        if (!ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
            throw new InvalidUrlException("longUrl scheme must be http or https.");                  // E3
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidUrlException("longUrl must include a valid host.");                     // E2
        }
        rejectAmbiguousIpNotation(host);                                                             // E4
        if (selfHosts.contains(normalizeHost(host))) {
            throw new InvalidUrlException("longUrl must not point at this service.");                // E5
        }
        ssrfGuard.assertPublic(host);                                                                // E4
        return longUrl;
    }

    /**
     * Refuses numeric hosts that are not a canonical dotted-quad ({@code 2130706433}, {@code 127.1}, {@code 0x7f.0.0.1},
     * {@code 0177.0.0.1}). Parsers disagree on these: the JDK reads {@code 0177.0.0.1} as decimal 177.0.0.1 (a public
     * address, so it would pass the SSRF check) while browsers and libc read it as octal 127.0.0.1 - exactly the gap an
     * attacker uses. Real hostnames always contain a non-numeric label, so nothing legitimate is lost.
     */
    private static void rejectAmbiguousIpNotation(String host) {
        if (host.startsWith("[")) {
            return;                                   // IPv6 literal: classified by IpClassifier after parsing
        }
        String bare = host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
        String[] labels = bare.split("\\.", -1);
        boolean allNumeric = true;
        for (String label : labels) {
            if (!NUMERIC_LABEL.matcher(label).matches()) {
                allNumeric = false;
                break;
            }
        }
        if (!allNumeric) {
            return;                                   // an ordinary hostname
        }
        boolean canonical = labels.length == 4;
        for (String label : labels) {
            canonical = canonical && label.matches("0|[1-9][0-9]{0,2}") && Integer.parseInt(label) <= 255;
        }
        if (!canonical) {
            throw new InvalidUrlException("longUrl host uses an ambiguous IP address notation.");
        }
    }

    private static String normalizeHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }
}
