package com.urlshortener.shortener.domain;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * A keyed, reversible bijection over {@code [0, domainSize)} (design doc section 4.1, step 2).
 *
 * <p>Sequential ids must not yield sequential (enumerable) codes, but the mapping must stay collision-free, so a
 * permutation is used rather than hashing. A balanced Feistel network is a bijection over {@code 2^k} values for any
 * round function; because the target domain is {@code 62^L} (not a power of two), <em>cycle walking</em> re-applies
 * the network until the value lands inside the domain, which preserves bijectivity on the smaller set. Domain is at
 * most ~1.3x the network size, so it costs about 1.2 iterations on average.
 *
 * <p>The round function is HMAC-SHA256 under a secret key, so the permutation cannot be inverted or extrapolated
 * without the key. Pure and thread-safe: no shared mutable state, no {@code synchronized} (section 20.4).
 */
public final class FeistelPermutation {

    private static final int ROUNDS = 6;
    private static final String HMAC = "HmacSHA256";

    private final long domainSize;
    private final int halfBits;
    private final long halfMask;
    private final SecretKeySpec key;

    public FeistelPermutation(byte[] key, long domainSize) {
        if (domainSize < 2) {
            throw new IllegalArgumentException("domainSize must be >= 2");
        }
        if (key == null || key.length == 0) {
            throw new IllegalArgumentException("key must not be empty");
        }
        this.domainSize = domainSize;
        int bits = 64 - Long.numberOfLeadingZeros(domainSize - 1);
        bits = Math.max(2, bits + (bits % 2));            // even, so both halves are the same width
        this.halfBits = bits / 2;
        this.halfMask = (1L << halfBits) - 1;
        this.key = new SecretKeySpec(key, HMAC);
    }

    public long domainSize() {
        return domainSize;
    }

    public long permute(long value) {
        checkInDomain(value);
        long x = value;
        do {
            x = encrypt(x);
        } while (x >= domainSize);                        // cycle walk back into [0, domainSize)
        return x;
    }

    public long invert(long value) {
        checkInDomain(value);
        long x = value;
        do {
            x = decrypt(x);
        } while (x >= domainSize);
        return x;
    }

    private long encrypt(long x) {
        long left = (x >>> halfBits) & halfMask;
        long right = x & halfMask;
        Mac mac = newMac();
        for (int round = 0; round < ROUNDS; round++) {
            long next = left ^ roundFunction(mac, round, right);
            left = right;
            right = next;
        }
        return (left << halfBits) | right;
    }

    private long decrypt(long x) {
        long left = (x >>> halfBits) & halfMask;
        long right = x & halfMask;
        Mac mac = newMac();
        for (int round = ROUNDS - 1; round >= 0; round--) {
            long previous = right ^ roundFunction(mac, round, left);
            right = left;
            left = previous;
        }
        return (left << halfBits) | right;
    }

    private long roundFunction(Mac mac, int round, long half) {
        byte[] digest = mac.doFinal(ByteBuffer.allocate(12).putInt(round).putLong(half).array());
        return ByteBuffer.wrap(digest).getLong() & halfMask;
    }

    private Mac newMac() {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return mac;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(HMAC + " is required on every Java platform", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("invalid Feistel key", e);
        }
    }

    private void checkInDomain(long value) {
        if (value < 0 || value >= domainSize) {
            throw new IllegalArgumentException("value " + value + " outside [0, " + domainSize + ")");
        }
    }
}
