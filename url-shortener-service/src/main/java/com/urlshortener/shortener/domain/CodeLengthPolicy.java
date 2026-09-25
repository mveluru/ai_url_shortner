package com.urlshortener.shortener.domain;

/**
 * Length policy (design doc section 4.1, step 4, E17): start at {@code initialLength} characters; once the counter
 * crosses {@code rolloverUtilization} (80%) of the current length's keyspace, <em>new</em> codes use one more
 * character. Existing shorter codes stay valid forever: length is a property of when a code was minted.
 *
 * <p>Codes of different lengths can never collide with each other (different string lengths), so rolling over is
 * safe without re-encoding anything.
 */
public final class CodeLengthPolicy {

    public static final int MAX_LENGTH = 10;

    private final int initialLength;
    private final double rolloverUtilization;

    public CodeLengthPolicy(int initialLength, double rolloverUtilization) {
        if (initialLength < 1 || initialLength >= MAX_LENGTH) {
            throw new IllegalArgumentException("initialLength must be in 1.." + (MAX_LENGTH - 1));
        }
        if (rolloverUtilization <= 0 || rolloverUtilization >= 1) {
            throw new IllegalArgumentException("rolloverUtilization must be in (0, 1)");
        }
        this.initialLength = initialLength;
        this.rolloverUtilization = rolloverUtilization;
    }

    /** Counter value at which codes of {@code length} stop being minted. */
    public long rolloverThreshold(int length) {
        return (long) (rolloverUtilization * Base62.pow(length));
    }

    public int lengthFor(long counter) {
        int length = initialLength;
        while (counter >= rolloverThreshold(length)) {
            length++;
            if (length > MAX_LENGTH) {
                throw new IllegalStateException("short-code keyspace exhausted at counter " + counter);
            }
        }
        return length;
    }
}
