package com.urlshortener.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.testsupport.AbstractIntegrationTest;
import com.urlshortener.testsupport.Covers;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Migration-level assertions the design doc requires (section 20.4a): the collation is asserted, not assumed, and
 * the whole schema is validated against the Hibernate mapping (ddl-auto=validate) because the context booted.
 */
class MigrationIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("Flyway applied all migrations and Hibernate validate accepted the schema")
    void migrationsApplied() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank", String.class);
        assertThat(versions).containsExactly("1", "2", "3");
    }

    @Test
    @Covers({"E7", "F9"})
    @DisplayName("s20.4a: short_code is case-sensitive - 'abc123' and 'ABC123' are distinct, exact duplicates are not")
    void shortCodeIsCaseSensitive() {
        insertUrl(900_001L, "abc123");
        insertUrl(900_002L, "ABC123");   // would fail under utf8mb4_0900_ai_ci / general_ci
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM urls WHERE short_code IN ('abc123','ABC123')", Integer.class)).isEqualTo(2);
        assertThatThrownBy(() -> insertUrl(900_003L, "abc123")).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("s20.4a: click_aggregates.short_code is case-sensitive too, or 'abc' and 'ABC' would merge analytics")
    void aggregatesAreCaseSensitive() {
        for (String code : List.of("Zz9", "zZ9")) {
            jdbc.update("INSERT INTO click_aggregates (short_code, date, click_count, top_referrers, device_breakdown,"
                    + " updated_at) VALUES (?, CURRENT_DATE, 1, '{}', '{}', CURRENT_TIMESTAMP(6))", code);
        }
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM click_aggregates WHERE short_code IN ('Zz9','zZ9')", Integer.class))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("Collation is set explicitly on the column as utf8mb4_bin, not inherited")
    void collationIsExplicit() {
        for (String table : List.of("urls", "click_aggregates")) {
            String collation = jdbc.queryForObject("""
                    SELECT COLLATION_NAME FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'short_code'""",
                    String.class, table);
            assertThat(collation).as("collation of %s.short_code", table).isEqualTo("utf8mb4_bin");
        }
    }

    @Test
    @DisplayName("s5.1: the connection session is pinned to UTC so DATETIME(6) defaults are UTC")
    void sessionIsUtc() {
        String tz = jdbc.queryForObject("SELECT @@session.time_zone", String.class);
        assertThat(tz).isIn("+00:00", "UTC");
    }

    @Test
    @DisplayName("The idempotency fingerprint is UNIQUE but nullable: many NULLs are allowed")
    void fingerprintAllowsManyNulls() {
        insertUrl(900_010L, "nullfp1");
        insertUrl(900_011L, "nullfp2");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM urls WHERE short_code IN ('nullfp1','nullfp2') AND idempotency_fingerprint IS NULL",
                Integer.class)).isEqualTo(2);
    }

    private void insertUrl(long id, String code) {
        jdbc.update("INSERT INTO urls (id, short_code, long_url, owner_key_id) VALUES (?, ?, 'https://example.com', 'k')",
                id, code);
    }
}
