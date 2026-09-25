package com.urlshortener.analytics.consume;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AggregationLogicTest {

    @Test
    @DisplayName("s2.2/s12.2: a referrer is reduced to a lowercase HOST - paths, query strings (tokens, PII) and credentials are dropped")
    void referrerNormalisation() {
        assertThat(ReferrerNormalizer.normalize("https://News.Example.COM/story?id=9&token=secret#frag")).isEqualTo("news.example.com");
        assertThat(ReferrerNormalizer.normalize("https://user:pw@host.example:8443/x")).isEqualTo("host.example");
        assertThat(ReferrerNormalizer.normalize("android-app://com.google.android.gm/")).isEqualTo("com.google.android.gm");
        assertThat(ReferrerNormalizer.normalize(null)).isEqualTo("(direct)");
        assertThat(ReferrerNormalizer.normalize("   ")).isEqualTo("(direct)");
        assertThat(ReferrerNormalizer.normalize("not a url at all")).isEqualTo("(unknown)");
        assertThat(ReferrerNormalizer.normalize("mailto:someone@example.com")).isEqualTo("(unknown)");
        assertThat(ReferrerNormalizer.normalize("https://")).isEqualTo("(unknown)");
    }

    @Test
    @DisplayName("s5.3: top-N referrers keeps the N highest counts and never evicts the key just incremented")
    void topNKeepsTheHighest() {
        Map<String, Long> counts = new HashMap<>();
        for (int i = 1; i <= 5; i++) {
            counts.put("r" + i, (long) i * 10);                      // r1=10 ... r5=50
        }
        Map<String, Long> next = AggregationService.bump(counts, "new", 5);
        assertThat(next).hasSize(5).containsKey("new").doesNotContainKey("r1");     // the lowest was evicted, not "new"
        assertThat(next.get("new")).isEqualTo(1L);
        assertThat(AggregationService.bump(next, "r5", 5).get("r5")).isEqualTo(51L);
    }

    @Test
    @DisplayName("bump returns a NEW map (the JSON column is only re-written if Hibernate sees a changed value) and increments existing keys")
    void bumpIsNonMutating() {
        Map<String, Long> original = new HashMap<>(Map.of("a", 1L));
        Map<String, Long> bumped = AggregationService.bump(original, "a", 20);
        assertThat(bumped).containsEntry("a", 2L);
        assertThat(original).as("input untouched").containsEntry("a", 1L);
        assertThat(AggregationService.bump(Map.of(), "x", Integer.MAX_VALUE)).containsEntry("x", 1L);
    }
}
