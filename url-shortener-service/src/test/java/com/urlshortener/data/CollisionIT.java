package com.urlshortener.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.urlshortener.common.config.AppProperties;
import com.urlshortener.shortener.domain.IdBlockAllocator;
import com.urlshortener.shortener.domain.ShortCodeGenerator;
import com.urlshortener.shortener.domain.UrlEntity;
import com.urlshortener.shortener.domain.UrlRepository;
import com.urlshortener.testsupport.AbstractIntegrationTest;
import com.urlshortener.testsupport.Covers;
import com.urlshortener.testsupport.TestApi;
import com.urlshortener.testsupport.TestApi.Response;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * F9 and its subtle sibling (design doc section 8, F9 + section 4.1 step 5).
 *
 * <p>Ids are normally allocated by counter, so an auto-generated code can never collide with another auto-generated code -
 * unless there is a bug, a migration error or a manual DB edit. That is F9: it must never overwrite, and it must page. But
 * a user-chosen <em>custom alias</em> may legitimately equal a code the counter later generates: that is not a bug, and
 * failing the unlucky user's create with a 500 would be wrong. Here the id sequence is controlled so both can be forced.
 */
@Import(CollisionIT.ControlledIds.class)
class CollisionIT extends AbstractIntegrationTest {

    static final Deque<Long> NEXT_IDS = new ArrayDeque<>();

    @TestConfiguration
    static class ControlledIds {
        @Bean
        @Primary
        IdBlockAllocator controlledAllocator(JdbcTemplate jdbc, PlatformTransactionManager tm, AppProperties props) {
            return new IdBlockAllocator(jdbc, tm, props) {
                @Override
                public long nextId() {
                    Long forced = NEXT_IDS.pollFirst();
                    return forced != null ? forced : super.nextId();
                }
            };
        }
    }

    @Autowired UrlRepository repository;
    @Autowired ShortCodeGenerator generator;
    @Autowired MeterRegistry meters;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clear() {
        NEXT_IDS.clear();
    }

    private static long freshBase() {
        return 6_000_000_000L + (System.nanoTime() % 900_000_000L) * 10;
    }

    private double counter(String name, String... tags) {
        var c = meters.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @Covers({"F9"})
    @DisplayName("F9: an auto-generated code colliding with another AUTO code is a data-integrity incident: 500, alert metric, and NO overwrite")
    void autoVersusAutoIsAnIncident() {
        TestApi api = api();
        long base = freshBase();
        String code = generator.generate(base + 1);
        repository.saveAndFlush(UrlEntity.create(base, code, "https://original.test/keep", false, "someone", null, null));
        NEXT_IDS.add(base + 1);                                            // the counter is about to hand out this id

        double before = counter("short_code_collision");
        String newUrl = "https://public.test/" + UUID.randomUUID();
        Response r = api.create(api.newApiKey(), newUrl);

        assertThat(r.status()).isEqualTo(500);
        assertThat(r.code()).isEqualTo("INTERNAL_ERROR");
        assertThat(r.body()).doesNotContain(code).doesNotContain("Duplicate").doesNotContain("SQL");
        assertThat(counter("short_code_collision")).as("the page-worthy metric").isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT long_url FROM urls WHERE short_code = ?", String.class, code))
                .as("the existing mapping must never be overwritten").isEqualTo("https://original.test/keep");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM urls WHERE long_url = ?", Integer.class, newUrl)).isZero();
    }

    @Test
    @Covers({"F9", "E7"})
    @DisplayName("s4.1: an auto code that equals someone's CUSTOM alias is NOT an incident - it is skipped and the next counter value is used")
    void autoVersusCustomAliasIsSkipped() {
        TestApi api = api();
        long base = freshBase();
        String clashing = generator.generate(base + 1);
        String expected = generator.generate(base + 2);
        repository.saveAndFlush(UrlEntity.create(base, clashing, "https://alias-owner.test/", true, "someone", null, null));
        NEXT_IDS.add(base + 1);
        NEXT_IDS.add(base + 2);

        double skipped = counter("alias_collision", "outcome", "auto_code_skipped");
        double incidents = counter("short_code_collision");
        Response r = api.create(api.newApiKey(), "https://public.test/" + UUID.randomUUID());

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.json().path("shortCode").asText()).isEqualTo(expected);
        assertThat(counter("alias_collision", "outcome", "auto_code_skipped")).isEqualTo(skipped + 1);
        assertThat(counter("short_code_collision")).as("not an incident").isEqualTo(incidents);
        assertThat(jdbc.queryForObject("SELECT long_url FROM urls WHERE short_code = ?", String.class, clashing))
                .as("the alias owner's mapping is untouched").isEqualTo("https://alias-owner.test/");
    }

    @Test
    @Covers({"F9"})
    @DisplayName("s4.1: skipping is bounded - repeated clashes stop retrying and surface as a server error rather than looping")
    void skippingIsBounded() {
        TestApi api = api();
        long base = freshBase();
        for (int i = 1; i <= 3; i++) {
            repository.saveAndFlush(UrlEntity.create(base + 100 + i, generator.generate(base + i), "https://alias-owner.test/" + i,
                    true, "someone", null, null));
            NEXT_IDS.add(base + i);
        }
        Response r = api.create(api.newApiKey(), "https://public.test/" + UUID.randomUUID());
        assertThat(r.status()).isEqualTo(500);
        assertThat(r.code()).isEqualTo("INTERNAL_ERROR");
    }
}
