package com.urlshortener.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.common.config.AppProperties;
import com.urlshortener.common.error.InvalidUrlException;
import com.urlshortener.common.error.ServiceUnavailableException;
import com.urlshortener.shortener.validation.HostResolver;
import com.urlshortener.shortener.validation.SsrfGuard;
import com.urlshortener.shortener.validation.UrlValidator;
import com.urlshortener.testsupport.Covers;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

    /** Fake DNS: public.example -> 93.184.216.34; everything else is looked up in the table or is NXDOMAIN. */
    private static HostResolver dns(Map<String, String[]> table) {
        return host -> {
            String[] ips = table.get(host);
            if (ips == null) {
                // Numeric literals resolve without a network round trip.
                if (host.matches("[0-9a-fA-Fx.:]+")) {
                    return InetAddress.getAllByName(host);
                }
                throw new UnknownHostException(host);
            }
            InetAddress[] out = new InetAddress[ips.length];
            for (int i = 0; i < ips.length; i++) {
                out[i] = InetAddress.getByName(ips[i]);
            }
            return out;
        };
    }

    private static final Map<String, String[]> TABLE = Map.of(
            "public.example", new String[] {"93.184.216.34"},
            "www.public.example", new String[] {"93.184.216.34"},
            "deadbeef.public.example", new String[] {"93.184.216.34"},
            "internal.example", new String[] {"10.1.2.3"},
            "mixed.example", new String[] {"93.184.216.34", "192.168.0.10"},
            "v6mapped.example", new String[] {"::ffff:127.0.0.1"},
            "metadata.example", new String[] {"169.254.169.254"},
            "localhost", new String[] {"127.0.0.1"});

    private final UrlValidator validator = validatorWith(dns(TABLE), Duration.ofSeconds(2));

    private static UrlValidator validatorWith(HostResolver resolver, Duration timeout) {
        AppProperties props = new AppProperties("https://short.ly", java.util.List.of("short.ly", "go.example.com"),
                new AppProperties.Url(2048, timeout), null, null, null, null, null, null, null, null, null, null);
        return new UrlValidator(props, new SsrfGuard(resolver, props));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    @Covers("E1")
    @DisplayName("E1: missing, empty or blank longUrl -> INVALID_URL")
    void missingOrEmpty(String input) {
        assertThatThrownBy(() -> validator.validate(input)).isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "//example.com/path", "http://", "http:///nohost", "http://exa mple.com",
            "not a url", "://missing-scheme", "http://my_host.example", "/relative/path"})
    @Covers("E2")
    @DisplayName("E2: malformed or non-absolute URLs -> INVALID_URL")
    void malformed(String input) {
        assertThatThrownBy(() -> validator.validate(input)).isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "JaVaScRiPt:alert(1)", "file:///etc/passwd", "data:text/html,<script>",
            "ftp://public.example/file", "mailto:a@b.c", "gopher://public.example", "ws://public.example", "vbscript:x"})
    @Covers("E3")
    @DisplayName("E3: only http/https are allowed - an allowlist, so dangerous and unknown schemes all fail")
    void disallowedSchemes(String input) {
        assertThatThrownBy(() -> validator.validate(input)).isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @Covers("E3")
    @DisplayName("E3: http and https (any case) are accepted")
    void allowedSchemes() {
        assertThat(validator.validate("http://public.example/a")).isEqualTo("http://public.example/a");
        assertThat(validator.validate("HTTPS://public.example/a?b=c#d")).isEqualTo("HTTPS://public.example/a?b=c#d");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/", "http://127.1/", "http://localhost/", "http://localhost./", "http://[::1]/",
            "http://10.0.0.1/", "http://172.16.0.1/", "http://172.31.255.255/", "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data/", "http://0.0.0.0/", "http://100.64.0.1/",
            "http://[fd00:ec2::254]/", "http://[fe80::1]/", "http://[::ffff:127.0.0.1]/",
            "http://[64:ff9b::7f00:1]/", "http://[2002:7f00:1::]/",
            "http://public.example@127.0.0.1/", "http://127.0.0.1#@public.example/",
            "http://internal.example/", "http://metadata.example/", "http://v6mapped.example/"})
    @Covers("E4")
    @DisplayName("E4: URLs that resolve to private/loopback/link-local addresses (incl. obfuscated encodings) are refused")
    void ssrf(String input) {
        assertThatThrownBy(() -> validator.validate(input)).isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://internal.example/", "http://metadata.example/", "http://v6mapped.example/",
            "http://10.0.0.1/", "http://[::1]/", "http://localhost./"})
    @Covers("E4")
    @DisplayName("E4: the IP-range check itself is what rejects these (not an earlier syntax error)")
    void rejectedByTheRangeCheck(String input) {
        assertThatThrownBy(() -> validator.validate(input))
                .isInstanceOf(InvalidUrlException.class).hasMessageContaining("private or internal");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://2130706433/", "http://127.1/", "http://0x7f.0.0.1/", "http://0177.0.0.1/",
            "http://017700000001/", "http://0x7f000001/", "http://1.2.3/", "http://1.2.3.4.5/", "http://127.0.0.01/",
            "http://256.1.1.1/", "http://0177.0.0.1./"})
    @Covers("E4")
    @DisplayName("E4: numeric hosts that are not a canonical dotted-quad are refused - parsers disagree on them "
            + "(JDK reads 0177.0.0.1 as decimal 177.0.0.1; browsers as octal 127.0.0.1)")
    void ambiguousIpNotation(String input) {
        assertThatThrownBy(() -> validator.validate(input)).isInstanceOf(InvalidUrlException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://2130706433/", "http://0177.0.0.1/", "http://017700000001/", "http://127.0.0.01/"})
    @Covers("E4")
    @DisplayName("E4: the ambiguous-notation rule is what stops the forms that java.net.URI itself accepts as hosts "
            + "(this is the octal bypass the JDK would have resolved to a public address)")
    void ambiguousNotationRuleFires(String input) {
        assertThatThrownBy(() -> validator.validate(input))
                .isInstanceOf(InvalidUrlException.class).hasMessageContaining("ambiguous IP");
    }

    @Test
    @Covers("E4")
    @DisplayName("E4: a canonical public dotted-quad is fine, and real hostnames that merely look hex-ish are not caught")
    void canonicalIpAndHexishHostnamesAllowed() {
        assertThat(validator.validate("http://93.184.216.34/x")).isNotNull();
        assertThat(validator.validate("http://deadbeef.public.example/")).isNotNull().isNotEmpty();
    }

    @Test
    @Covers("E4")
    @DisplayName("E4: a multi-record DNS answer is refused if ANY address is internal (no hiding among public ones)")
    void anyInternalAddressWins() {
        assertThatThrownBy(() -> validator.validate("http://mixed.example/"))
                .isInstanceOf(InvalidUrlException.class);
    }

    @Test
    @Covers("E4")
    @DisplayName("E4: an unresolvable host fails closed (rejected), never waved through")
    void unresolvableFailsClosed() {
        assertThatThrownBy(() -> validator.validate("http://does-not-exist.example/"))
                .isInstanceOf(InvalidUrlException.class).hasMessageContaining("could not be resolved");
    }

    @Test
    @Covers("E4")
    @DisplayName("E4/s8.2.1: a slow resolver surfaces as 503 (cannot verify), not as a bypassed check")
    void slowResolverIs503() {
        HostResolver hang = host -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new InetAddress[0];
        };
        UrlValidator slow = validatorWith(hang, Duration.ofMillis(100));
        assertThatThrownBy(() -> slow.validate("http://public.example/"))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://short.ly/abc", "http://SHORT.LY/abc", "https://short.ly./abc",
            "https://go.example.com/x", "https://short.ly:8443/x", "https://user@short.ly/x"})
    @Covers("E5")
    @DisplayName("E5: URLs pointing at the shortener's own domain are refused (no chains / redirect loops)")
    void selfReferential(String input) {
        assertThatThrownBy(() -> validator.validate(input))
                .isInstanceOf(InvalidUrlException.class).hasMessageContaining("this service");
    }

    @Test
    @Covers("E5")
    @DisplayName("E5: look-alike hosts are not falsely rejected")
    void lookalikeHostsAccepted() {
        assertThat(validator.validate("https://www.public.example/short.ly")).isNotNull();
    }

    @Test
    @Covers("E6")
    @DisplayName("E6: longUrl over 2048 characters is refused; exactly 2048 is accepted")
    void tooLong() {
        String prefix = "http://public.example/";
        String ok = prefix + "a".repeat(2048 - prefix.length());
        String tooLong = ok + "a";
        assertThat(ok).hasSize(2048);
        assertThat(validator.validate(ok)).isEqualTo(ok);
        assertThatThrownBy(() -> validator.validate(tooLong))
                .isInstanceOf(InvalidUrlException.class).hasMessageContaining("2048");
    }

    @Test
    @DisplayName("surrounding whitespace is trimmed rather than rejected")
    void trims() {
        assertThat(validator.validate("  http://public.example/x  ")).isEqualTo("http://public.example/x");
    }
}
