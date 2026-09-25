package com.urlshortener.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.common.config.AppProperties;
import com.urlshortener.shortener.domain.Base62;
import com.urlshortener.shortener.domain.CodeLengthPolicy;
import com.urlshortener.shortener.domain.ShortCodeGenerator;
import com.urlshortener.testsupport.Covers;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CodeLengthPolicyTest {

    private final CodeLengthPolicy policy = new CodeLengthPolicy(6, 0.8);

    @Test
    @Covers("E17")
    @DisplayName("E17: 6 chars until 80% of 62^6 is consumed, then 7; 7 until 80% of 62^7, then 8")
    void rollsOverAtEightyPercent() {
        long six = policy.rolloverThreshold(6);
        assertThat(six).isEqualTo((long) (0.8 * Base62.pow(6)));
        assertThat(policy.lengthFor(1)).isEqualTo(6);
        assertThat(policy.lengthFor(six - 1)).isEqualTo(6);
        assertThat(policy.lengthFor(six)).isEqualTo(7);
        assertThat(policy.lengthFor(policy.rolloverThreshold(7) - 1)).isEqualTo(7);
        assertThat(policy.lengthFor(policy.rolloverThreshold(7))).isEqualTo(8);
    }

    @Test
    @Covers("E17")
    @DisplayName("E17: codes minted across the boundary are all unique; earlier 6-char codes stay 6 chars")
    void codesAcrossTheBoundaryStayUniqueAndValid() {
        AppProperties props = new AppProperties(null, null, null, null,
                new AppProperties.Code(6, 0.8, "k", 3, 10_000), null, null, null, null, null, null, null, null);
        ShortCodeGenerator generator = new ShortCodeGenerator(props);
        long boundary = policy.rolloverThreshold(6);

        Set<String> codes = new HashSet<>();
        for (long id = boundary - 5_000; id < boundary + 5_000; id++) {
            String code = generator.generate(id);
            assertThat(code).hasSize(id < boundary ? 6 : 7).matches("[0-9A-Za-z]+");
            assertThat(codes.add(code)).as("duplicate at id %d", id).isTrue();
        }
        assertThat(generator.generate(1)).hasSize(6);      // existing shorter codes are unaffected
    }

    @Test
    @DisplayName("exhausting the whole keyspace fails loudly instead of wrapping")
    void exhaustion() {
        assertThatThrownBy(() -> policy.lengthFor(Long.MAX_VALUE)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("invalid policy parameters are rejected")
    void invalidParams() {
        assertThatThrownBy(() -> new CodeLengthPolicy(0, 0.8)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CodeLengthPolicy(6, 1.0)).isInstanceOf(IllegalArgumentException.class);
    }
}
