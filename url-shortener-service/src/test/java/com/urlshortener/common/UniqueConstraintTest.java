package com.urlshortener.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.common.error.UniqueConstraint;
import com.urlshortener.testsupport.Covers;
import java.sql.SQLIntegrityConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class UniqueConstraintTest {

    private static DataIntegrityViolationException viaHibernate(String constraintName, String mysqlMessage) {
        return new DataIntegrityViolationException("could not execute statement",
                new org.hibernate.exception.ConstraintViolationException("could not execute statement",
                        new SQLIntegrityConstraintViolationException(mysqlMessage, "23000", 1062), constraintName));
    }

    @Test
    @Covers({"F9"})
    @DisplayName("s20.4: the fired constraint is identified from Hibernate's constraint name")
    void byConstraintName() {
        assertThat(UniqueConstraint.of(viaHibernate("urls.uk_urls_short_code", "x"))).isEqualTo(UniqueConstraint.SHORT_CODE);
        assertThat(UniqueConstraint.of(viaHibernate("urls.uk_urls_idempotency_fingerprint", "x")))
                .isEqualTo(UniqueConstraint.IDEMPOTENCY_FINGERPRINT);
    }

    @Test
    @Covers({"F9"})
    @DisplayName("s20.4: ...and falls back to MySQL's raw 'for key' message when no constraint name is extracted")
    void byMessage() {
        assertThat(UniqueConstraint.of(new DataIntegrityViolationException("dup",
                new SQLIntegrityConstraintViolationException("Duplicate entry 'abc' for key 'urls.uk_urls_short_code'"))))
                .isEqualTo(UniqueConstraint.SHORT_CODE);
        assertThat(UniqueConstraint.of(new DataIntegrityViolationException(
                "Duplicate entry 'ff' for key 'urls.uk_urls_idempotency_fingerprint'")))
                .isEqualTo(UniqueConstraint.IDEMPOTENCY_FINGERPRINT);
    }

    @Test
    @Covers({"F9"})
    @DisplayName("s20.4: an unrelated integrity violation (PK, NOT NULL, another unique key) is UNKNOWN - never mis-labelled as an alias clash")
    void unknown() {
        assertThat(UniqueConstraint.of(viaHibernate("urls.PRIMARY", "Duplicate entry '1' for key 'urls.PRIMARY'")))
                .isEqualTo(UniqueConstraint.UNKNOWN);
        assertThat(UniqueConstraint.of(new DataIntegrityViolationException("Column 'short_code' cannot be null")))
                .isEqualTo(UniqueConstraint.UNKNOWN);
    }
}
