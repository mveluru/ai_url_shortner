package com.urlshortener.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.common.config.AppProperties;
import com.urlshortener.common.error.UniqueConstraint;
import com.urlshortener.shortener.domain.IdBlockAllocator;
import com.urlshortener.shortener.domain.UrlEntity;
import com.urlshortener.shortener.domain.UrlRepository;
import com.urlshortener.testsupport.AbstractIntegrationTest;
import com.urlshortener.testsupport.Covers;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** F8 (ID pre-allocation) and the unique-constraint discrimination F9 depends on, against real MySQL. */
class IdAllocationIT extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired UrlRepository repository;

    private IdBlockAllocator allocator(int blockSize) {
        AppProperties props = new AppProperties(null, null, null, null,
                new AppProperties.Code(6, 0.8, "k", 3, blockSize), null, null, null, null, null, null, null, null);
        return new IdBlockAllocator(jdbc, txManager, props);
    }

    private long nextVal() {
        return jdbc.queryForObject("SELECT next_val FROM url_id_seq", Long.class);
    }

    @Test
    @Covers({"F8"})
    @DisplayName("F8: two 'instances' allocating concurrently never hand out the same id, and touch the shared row once per BLOCK, not per id")
    void blocksAreDisjointAndClaimsAreRare() throws Exception {
        int blockSize = 50;
        IdBlockAllocator a = allocator(blockSize);
        IdBlockAllocator b = allocator(blockSize);
        long before = nextVal();

        Set<Long> ids = ConcurrentHashMap.newKeySet();
        int perThread = 100;
        List<Callable<Void>> work = new ArrayList<>();
        for (IdBlockAllocator alloc : List.of(a, a, a, a, b, b, b, b)) {
            work.add(() -> {
                for (int i = 0; i < perThread; i++) {
                    assertThat(ids.add(alloc.nextId())).as("an id was handed out twice").isTrue();
                }
                return null;
            });
        }
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (Future<Void> f : pool.invokeAll(work)) {
                f.get();
            }
        }

        assertThat(ids).hasSize(800);
        long advanced = nextVal() - before;
        assertThat(advanced % blockSize).as("the shared counter only ever moves in whole blocks").isZero();
        long claims = advanced / blockSize;
        assertThat(claims).as("800 ids cost ~16 claims on the shared row, not 800").isBetween(16L, 18L);
    }

    @Test
    @Covers({"F8"})
    @DisplayName("F8: ids from a block are handed out from memory - a single instance issues a whole block with ONE claim")
    void oneClaimPerBlock() {
        IdBlockAllocator alloc = allocator(1_000);
        long before = nextVal();
        for (int i = 0; i < 1_000; i++) {
            alloc.nextId();
        }
        assertThat(nextVal() - before).isEqualTo(1_000);        // exactly one block claimed for 1,000 ids
        alloc.nextId();                                          // the 1,001st id triggers the next claim
        assertThat(nextVal() - before).isEqualTo(2_000);
    }

    @Test
    @Covers({"F9"})
    @DisplayName("F9/s20.4: the constraint that fired is identified by NAME on real MySQL - short_code vs idempotency fingerprint")
    void uniqueConstraintsAreDistinguishedByName() {
        long base = 8_000_000_000L + System.nanoTime() % 1_000_000;
        repository.saveAndFlush(UrlEntity.create(base, "dupcodeA", "https://x.test/1", false, "k", "fp-" + base, null));

        DataIntegrityViolationException sameCode = catchIntegrity(
                UrlEntity.create(base + 1, "dupcodeA", "https://x.test/2", false, "k", "fp-" + (base + 1), null));
        assertThat(UniqueConstraint.of(sameCode)).isEqualTo(UniqueConstraint.SHORT_CODE);

        DataIntegrityViolationException sameFingerprint = catchIntegrity(
                UrlEntity.create(base + 2, "dupcodeB", "https://x.test/3", false, "k", "fp-" + base, null));
        assertThat(UniqueConstraint.of(sameFingerprint)).isEqualTo(UniqueConstraint.IDEMPOTENCY_FINGERPRINT);
    }

    @Test
    @Covers({"F9", "E7"})
    @DisplayName("F9: a duplicate short_code can never silently overwrite: the insert fails and the original row is untouched")
    void duplicateNeverOverwrites() {
        long base = 8_100_000_000L + System.nanoTime() % 1_000_000;
        repository.saveAndFlush(UrlEntity.create(base, "keepme01", "https://original.test/", false, "k", null, null));
        assertThatThrownBy(() -> repository.saveAndFlush(
                UrlEntity.create(base + 1, "keepme01", "https://attacker.test/", false, "k2", null, null)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT long_url FROM urls WHERE short_code = 'keepme01'", String.class))
                .isEqualTo("https://original.test/");
    }

    private DataIntegrityViolationException catchIntegrity(UrlEntity entity) {
        try {
            repository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            return e;
        }
        throw new AssertionError("expected a unique-constraint violation");
    }
}
