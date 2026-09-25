package com.urlshortener.shortener.domain;

import com.urlshortener.common.config.AppProperties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Block-preallocated counter (design doc section 4.1 step 1, F8). Each instance claims {@code blockSize} ids (default
 * 10,000) from the single-row {@code url_id_seq} table in one short transaction and then hands them out from memory, so
 * most ID allocations are a local, lock-free compare-and-set and the shared row is touched once per block.
 *
 * <p><b>Why not Hibernate's {@code @SequenceGenerator} (section 20.1's suggestion)?</b> The short code is derived
 * <em>from</em> the id, so the id must exist before the entity does. Hibernate only assigns ids inside {@code persist()},
 * after which its INSERT state is already captured: setting {@code short_code} afterwards yields INSERT (null) then
 * UPDATE, which violates {@code NOT NULL}. This class keeps the identical behaviour and the same {@code url_id_seq}
 * table (MySQL has no native SEQUENCE anyway) - only the mechanism differs.
 *
 * <p>The claim runs in its own {@code REQUIRES_NEW} transaction and callers must invoke {@link #nextId()} <em>outside</em>
 * any open transaction: allocating while holding a pooled connection would deadlock once every connection is held by a
 * request waiting for another. Uses a {@link ReentrantLock}, not {@code synchronized}, to stay virtual-thread friendly
 * (section 20.4). Ids lost when an instance restarts leave harmless gaps.
 */
@Component
public class IdBlockAllocator {

    private record Block(long next, long end) {
        boolean hasNext() {
            return next < end;
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate claimTx;
    private final long blockSize;
    private final AtomicReference<Block> block = new AtomicReference<>();
    private final ReentrantLock refillLock = new ReentrantLock();

    public IdBlockAllocator(JdbcTemplate jdbc, PlatformTransactionManager txManager, AppProperties props) {
        this.jdbc = jdbc;
        this.claimTx = new TransactionTemplate(txManager);
        this.claimTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.blockSize = props.code().idBlockSize();
    }

    /** @return the next unused id (>= 1). Never returns the same value twice across instances. */
    public long nextId() {
        for (;;) {
            Block current = block.get();
            if (current != null && current.hasNext()) {
                if (block.compareAndSet(current, new Block(current.next() + 1, current.end()))) {
                    return current.next();
                }
                continue;
            }
            refill();
        }
    }

    private void refill() {
        refillLock.lock();
        try {
            Block current = block.get();
            if (current != null && current.hasNext()) {
                return;                                     // another thread refilled while we waited for the lock
            }
            long start = claim();
            block.set(new Block(start, start + blockSize));
        } finally {
            refillLock.unlock();
        }
    }

    /** Atomically reserves {@code blockSize} ids: read-and-advance the shared row under a row lock. */
    private long claim() {
        Long start = claimTx.execute(status -> {
            Long next = jdbc.queryForObject("SELECT next_val FROM url_id_seq FOR UPDATE", Long.class);
            jdbc.update("UPDATE url_id_seq SET next_val = ?", next + blockSize);
            return next;
        });
        if (start == null) {
            throw new IllegalStateException("Could not claim an id block from url_id_seq");
        }
        return start;
    }
}
