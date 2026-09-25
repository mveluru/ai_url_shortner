package com.urlshortener.shortener.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The idempotency fingerprint (design doc section 6.3, 15.2): SHA-256 over {@code (owner key, kind, longUrl)}.
 *
 * <p>{@code kind} ({@code auto} vs {@code custom}) is what reconciles two statements that otherwise contradict:
 * section 6.3 says a retry with a <em>different</em> alias returns the original resource (both are {@code custom}, so
 * they share a fingerprint), while section 15.2 says an alias request must never be treated as a duplicate of an
 * auto-generated code for the same URL (different kinds, different fingerprints).
 */
final class IdempotencyKey {

    private IdempotencyKey() {}

    static String fingerprint(String ownerKeyId, boolean customAlias, String longUrl) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ownerKeyId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update((customAlias ? "custom" : "auto").getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(longUrl.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
