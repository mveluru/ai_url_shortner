package com.urlshortener.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.common.error.InvalidExpiryException;
import com.urlshortener.shortener.validation.ExpiryValidator;
import com.urlshortener.testsupport.Covers;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ExpiryValidatorTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-25T10:00:00Z"), ZoneOffset.UTC);
    private final ExpiryValidator validator = new ExpiryValidator(clock);

    @Test
    @Covers("E12")
    @DisplayName("E12: absent expiresAt means the link never expires")
    void absentMeansNever() {
        assertThat(validator.validate(null)).isNull();
    }

    @Test
    @Covers("E11")
    @DisplayName("E11: a future expiry is accepted; offsets are normalised to UTC")
    void future() {
        assertThat(validator.validate("2026-09-25T10:00:01Z")).isEqualTo(Instant.parse("2026-09-25T10:00:01Z"));
        assertThat(validator.validate("2026-09-25T13:00:00+02:00")).isEqualTo(Instant.parse("2026-09-25T11:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-09-25T10:00:00Z", "2026-09-25T09:59:59Z", "2020-01-01T00:00:00Z",
            "2026-09-25T13:00:00+05:00"})
    @Covers("E11")
    @DisplayName("E11: an expiry in the past, or exactly now, -> INVALID_EXPIRY")
    void pastOrNow(String input) {
        assertThatThrownBy(() -> validator.validate(input))
                .isInstanceOf(InvalidExpiryException.class).hasMessageContaining("future");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "tomorrow", "2026-09-26", "2026-09-26T10:00:00", "not-a-date", "2026-13-40T00:00:00Z"})
    @Covers("E11")
    @DisplayName("s9.2: an unparseable (or offset-less, thus ambiguous) expiresAt -> INVALID_EXPIRY")
    void unparseable(String input) {
        assertThatThrownBy(() -> validator.validate(input)).isInstanceOf(InvalidExpiryException.class);
    }

    @Test
    @DisplayName("an expiry beyond DATETIME's year-9999 limit is a clean 400, not a database error")
    void beyondColumnRange() {
        assertThatThrownBy(() -> validator.validate("+10000-01-01T00:00:00Z"))
                .isInstanceOf(InvalidExpiryException.class);
    }
}
