package com.urlshortener.shortener.domain;

/** Base62 over {@code [0-9A-Za-z]} (design doc section 4.1). Case-sensitive: 'a' and 'A' are different digits. */
public final class Base62 {

    public static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    public static final int RADIX = 62;

    /** 62^0 .. 62^10. 62^10 (~8.4e17) is the largest power that still fits comfortably in a signed long. */
    private static final long[] POWERS = new long[11];

    static {
        POWERS[0] = 1;
        for (int i = 1; i < POWERS.length; i++) {
            POWERS[i] = POWERS[i - 1] * RADIX;
        }
    }

    private Base62() {}

    /** @return 62^exponent, for exponent in 0..10 */
    public static long pow(int exponent) {
        if (exponent < 0 || exponent >= POWERS.length) {
            throw new IllegalArgumentException("exponent out of range 0.." + (POWERS.length - 1) + ": " + exponent);
        }
        return POWERS[exponent];
    }

    /** Encodes {@code value}, left-padded with '0' to exactly {@code length} characters. */
    public static String encode(long value, int length) {
        if (value < 0) {
            throw new IllegalArgumentException("value must be non-negative: " + value);
        }
        if (value >= pow(length)) {
            throw new IllegalArgumentException("value " + value + " does not fit in " + length + " base62 characters");
        }
        char[] out = new char[length];
        long remaining = value;
        for (int i = length - 1; i >= 0; i--) {
            out[i] = ALPHABET.charAt((int) (remaining % RADIX));
            remaining /= RADIX;
        }
        return new String(out);
    }

    public static long decode(String text) {
        long value = 0;
        for (int i = 0; i < text.length(); i++) {
            int digit = ALPHABET.indexOf(text.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("not a base62 string: " + text);
            }
            value = value * RADIX + digit;
        }
        return value;
    }
}
