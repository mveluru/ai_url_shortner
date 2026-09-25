package com.urlshortener.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.shortener.domain.CodeLengthPolicy;
import com.urlshortener.shortener.domain.ShortCodeGenerator;
import com.urlshortener.testsupport.Covers;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator("unit-test-secret", new CodeLengthPolicy(6, 0.8));

    @Test
    @DisplayName("s4.1: codes are 6 Base62 characters and deterministic for a given counter")
    void shape() {
        String code = generator.generate(1);
        assertThat(code).hasSize(6).matches("[0-9A-Za-z]{6}");
        assertThat(generator.generate(1)).isEqualTo(code);
    }

    @Test
    @DisplayName("s4.1: sequentially issued ids do NOT produce sequential codes (enumeration resistance)")
    void notSequential() {
        int ascending = 0;
        int total = 20_000;
        String previous = generator.generate(1);
        for (long id = 2; id <= total; id++) {
            String current = generator.generate(id);
            if (current.compareTo(previous) > 0) {
                ascending++;
            }
            previous = current;
        }
        // Sequential codes would be ~100% ascending. A good permutation is a coin flip.
        assertThat((double) ascending / total).isBetween(0.45, 0.55);
        // ...and consecutive ids should not share a long prefix.
        assertThat(generator.generate(1000).substring(0, 3)).isNotEqualTo(generator.generate(1001).substring(0, 3));
    }

    @Test
    @Covers("F8")
    @DisplayName("F8/s17: codes stay unique when many threads generate concurrently from disjoint id blocks")
    void uniqueUnderConcurrency() throws Exception {
        Set<String> codes = ConcurrentHashMap.newKeySet();
        int threads = 16;
        int perThread = 20_000;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                long start = 1L + (long) t * perThread;           // each "instance" owns a pre-allocated id block
                futures.add(pool.submit(() -> {
                    for (long id = start; id < start + perThread; id++) {
                        codes.add(generator.generate(id));
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }
        assertThat(codes).hasSize(threads * perThread);
    }

    @Test
    @DisplayName("a blank secret is refused at construction (no silent insecure default)")
    void blankSecretRefused() {
        assertThatThrownBy(() -> new ShortCodeGenerator(" ", new CodeLengthPolicy(6, 0.8)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
