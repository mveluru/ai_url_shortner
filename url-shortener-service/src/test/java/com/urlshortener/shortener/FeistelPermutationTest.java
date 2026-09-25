package com.urlshortener.shortener;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.shortener.domain.Base62;
import com.urlshortener.shortener.domain.FeistelPermutation;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FeistelPermutationTest {

    private static final byte[] KEY = "test-key".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("exhaustive bijection on 62^2 (a domain that is NOT a power of two: cycle walking is exercised)")
    void bijectionOnBase62Squared() {
        assertBijection(new FeistelPermutation(KEY, Base62.pow(2)));
    }

    @Test
    @DisplayName("exhaustive bijection on awkward domain sizes, including tiny and odd ones")
    void bijectionOnAwkwardDomains() {
        for (long n : new long[] {2, 3, 5, 100, 1000, 4095, 4097, 65_537}) {
            assertBijection(new FeistelPermutation(KEY, n));
        }
    }

    @Test
    @DisplayName("invert(permute(x)) == x: the permutation is reversible")
    void reversible() {
        FeistelPermutation p = new FeistelPermutation(KEY, Base62.pow(6));
        for (long x : new long[] {0, 1, 2, 999_999, 12_345_678_901L, Base62.pow(6) - 1}) {
            assertThat(p.invert(p.permute(x))).isEqualTo(x);
        }
    }

    @Test
    @DisplayName("output always stays inside the domain (never overflows the target code length)")
    void staysInDomain() {
        FeistelPermutation p = new FeistelPermutation(KEY, Base62.pow(6));
        for (long x = 0; x < 200_000; x++) {
            assertThat(p.permute(x)).isBetween(0L, Base62.pow(6) - 1);
        }
    }

    @Test
    @DisplayName("200k consecutive counters yield 200k distinct values on the 62^6 domain")
    void noCollisionsOnLargeDomain() {
        FeistelPermutation p = new FeistelPermutation(KEY, Base62.pow(6));
        Set<Long> seen = new HashSet<>();
        for (long x = 1; x <= 200_000; x++) {
            assertThat(seen.add(p.permute(x))).as("collision at %d", x).isTrue();
        }
    }

    @Test
    @DisplayName("a different key gives a different permutation (the mapping is secret-dependent)")
    void keyMatters() {
        FeistelPermutation a = new FeistelPermutation(KEY, Base62.pow(6));
        FeistelPermutation b = new FeistelPermutation("other-key".getBytes(StandardCharsets.UTF_8), Base62.pow(6));
        int differing = 0;
        for (long x = 1; x <= 1000; x++) {
            if (a.permute(x) != b.permute(x)) {
                differing++;
            }
        }
        assertThat(differing).isGreaterThan(990);
    }

    @Test
    @DisplayName("the largest supported domain (62^10) works and stays in range")
    void largestDomain() {
        FeistelPermutation p = new FeistelPermutation(KEY, Base62.pow(10));
        long v = p.permute(Base62.pow(10) - 1);
        assertThat(v).isBetween(0L, Base62.pow(10) - 1);
        assertThat(p.invert(v)).isEqualTo(Base62.pow(10) - 1);
    }

    private static void assertBijection(FeistelPermutation p) {
        int n = (int) p.domainSize();
        BitSet hit = new BitSet(n);
        for (int x = 0; x < n; x++) {
            long y = p.permute(x);
            assertThat(y).isBetween(0L, (long) n - 1);
            assertThat(hit.get((int) y)).as("domain %d: %d already produced", n, y).isFalse();
            hit.set((int) y);
        }
        assertThat(hit.cardinality()).isEqualTo(n);
    }
}
