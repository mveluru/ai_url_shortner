package com.urlshortener.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.common.config.AppProperties;
import com.urlshortener.common.error.AliasReservedException;
import com.urlshortener.common.error.InvalidAliasException;
import com.urlshortener.shortener.validation.AliasValidator;
import com.urlshortener.shortener.validation.ReservedAliases;
import com.urlshortener.testsupport.Covers;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.DefaultResourceLoader;

class AliasValidatorTest {

    private final AliasValidator validator = new AliasValidator(new ReservedAliases(
            new AppProperties(null, null, null,
                    new AppProperties.Alias(3, 20, "classpath:reserved-aliases.txt", List.of("Extra-Word")),
                    null, null, null, null, null, null, null, null, null),
            new DefaultResourceLoader()));

    @ParameterizedTest
    @ValueSource(strings = {"abc", "my-launch", "Promo_2026", "a1b", "ABC", "aBc", "0start", "xxxxxxxxxxxxxxxxxxxx"})
    @Covers("E10")
    @DisplayName("E10: valid aliases are accepted and returned unchanged (case-sensitive, stored as-is)")
    void valid(String alias) {
        assertThat(validator.validate(alias)).isEqualTo(alias);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "ab", "xxxxxxxxxxxxxxxxxxxxx", "-ab", "_ab", "ab cd", "ab/cd", "ab.cd", "ab%2e", "../etc",
            "ab\n", "ab\ncd", "caféx", "١٢٣", "a\u0000bc", "<script>", "a;b;c"})
    @Covers("E10")
    @DisplayName("E10: bad charset/length/leading-character -> INVALID_ALIAS (incl. leading hyphen, path chars, unicode)")
    void invalid(String alias) {
        assertThatThrownBy(() -> validator.validate(alias)).isInstanceOf(InvalidAliasException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "admin", "health", "metrics", "static", "actuator", "swagger-ui", "internal"})
    @Covers("E9")
    @DisplayName("E9: reserved words are refused with ALIAS_RESERVED")
    void reserved(String alias) {
        assertThatThrownBy(() -> validator.validate(alias)).isInstanceOf(AliasReservedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"API", "Admin", "hEaLtH", "ACTUATOR", "Swagger-UI", "extra-word", "EXTRA-WORD"})
    @Covers("E9")
    @DisplayName("E9: reserved-word matching is case-insensitive, so case tricks cannot bypass it; config additions apply")
    void reservedBypassAttempts(String alias) {
        assertThatThrownBy(() -> validator.validate(alias)).isInstanceOf(AliasReservedException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"favicon.ico", "robots.txt", "FAVICON.ICO"})
    @Covers("E9")
    @DisplayName("E9: dotted reserved names report ALIAS_RESERVED (not a charset error)")
    void reservedWithDots(String alias) {
        assertThatThrownBy(() -> validator.validate(alias)).isInstanceOf(AliasReservedException.class);
    }

    @Test
    @DisplayName("words that merely contain a reserved word are fine")
    void containsButNotEqual() {
        assertThat(validator.validate("api-docs")).isEqualTo("api-docs");
        assertThat(validator.validate("myadmin")).isEqualTo("myadmin");
    }
}
