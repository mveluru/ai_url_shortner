package com.urlshortener.shortener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.shortener.domain.Base62;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Base62Test {

    @Test
    @DisplayName("alphabet is 0-9A-Za-z, case-sensitive: 'a' and 'A' are different digits")
    void alphabet() {
        assertThat(Base62.ALPHABET).hasSize(62).startsWith("0123456789ABC").endsWith("xyz");
        assertThat(Base62.decode("a")).isNotEqualTo(Base62.decode("A"));
        assertThat(Base62.encode(10, 1)).isEqualTo("A");
        assertThat(Base62.encode(36, 1)).isEqualTo("a");
        assertThat(Base62.encode(61, 1)).isEqualTo("z");
    }

    @Test
    @DisplayName("encode is left-padded to the requested length and round-trips")
    void roundTrip() {
        assertThat(Base62.encode(0, 6)).isEqualTo("000000");
        assertThat(Base62.encode(62, 6)).isEqualTo("000010");
        for (long v : new long[] {0, 1, 61, 62, 12345, 56_800_235_583L, Base62.pow(6) - 1}) {
            assertThat(Base62.decode(Base62.encode(v, 6))).isEqualTo(v);
        }
    }

    @Test
    @DisplayName("a value that does not fit is rejected rather than truncated")
    void overflow() {
        assertThatThrownBy(() -> Base62.encode(Base62.pow(6), 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62.encode(-1, 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Base62.decode("ab-c")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("62^6 is the ~56.8 billion keyspace quoted in design doc section 4.1")
    void keyspace() {
        assertThat(Base62.pow(6)).isEqualTo(56_800_235_584L);
    }
}
