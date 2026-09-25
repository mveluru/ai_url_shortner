package com.urlshortener.analytics.publish;

import com.urlshortener.common.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * {@code ip_hash = HMAC-SHA256(rotating key, ip)} (design doc sections 5.2, 10.2). The key is derived from a server-side
 * master secret and the current rotation period, so hashes are stable within a period (unique-visitor counting still
 * works) but cannot be linked across periods or reversed by brute-forcing the small IPv4 space without the secret.
 * The raw IP is used transiently here and never stored or logged.
 */
@Component
public class IpHasher {

    private static final String HMAC = "HmacSHA256";

    private final byte[] masterSecret;
    private final long rotationSeconds;
    private final Clock clock;

    public IpHasher(AppProperties props, Clock clock) {
        this.masterSecret = props.analytics().ipHashSecret().getBytes(StandardCharsets.UTF_8);
        this.rotationSeconds = Math.max(1, props.analytics().ipHashKeyRotation().toSeconds());
        this.clock = clock;
    }

    public String hash(String ip) {
        long period = clock.instant().getEpochSecond() / rotationSeconds;
        byte[] periodKey = hmac(masterSecret, "period:" + period);
        return HexFormat.of().formatHex(hmac(periodKey, ip == null ? "" : ip));
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
