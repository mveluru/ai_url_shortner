package com.urlshortener.shortener.domain;

import com.urlshortener.common.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Counter -> permutation -> Base62 (design doc section 4.1). O(1), no database round trip and no collision-retry
 * loop, so write latency does not degrade as the keyspace fills (section 4.2).
 *
 * <p>Custom aliases never come through here (section 4.1, step 5). Pure functions only: the ShortCodeGenerator is
 * safe to call from any number of threads (no {@code synchronized}, section 20.4).
 */
@Component
public class ShortCodeGenerator {

    private final byte[] key;
    private final CodeLengthPolicy lengthPolicy;
    private final ConcurrentMap<Integer, FeistelPermutation> permutations = new ConcurrentHashMap<>();

    @Autowired
    public ShortCodeGenerator(AppProperties props) {
        this(props.code().feistelKey(),
                new CodeLengthPolicy(props.code().initialLength(), props.code().rolloverUtilization()));
    }

    public ShortCodeGenerator(String secret, CodeLengthPolicy lengthPolicy) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("app.code.feistel-key must be set");
        }
        this.key = sha256(secret);
        this.lengthPolicy = lengthPolicy;
    }

    /** @param counter the allocated id (always >= 1 in practice) */
    public String generate(long counter) {
        int length = lengthPolicy.lengthFor(counter);
        FeistelPermutation permutation = permutations.computeIfAbsent(length,
                len -> new FeistelPermutation(key, Base62.pow(len)));
        return Base62.encode(permutation.permute(counter), length);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
