package com.urlshortener.analytics.domain;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ClickAggregateRepository extends JpaRepository<ClickAggregateEntity, ClickAggregateId> {

    /** {@code SELECT ... FOR UPDATE}: concurrent consumers serialise per (code, day) so no increment is lost. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ClickAggregateEntity a where a.id = :id")
    Optional<ClickAggregateEntity> lockById(@Param("id") ClickAggregateId id);

    @Query("select a from ClickAggregateEntity a where a.id.shortCode = :code and a.id.day between :from and :to order by a.id.day")
    List<ClickAggregateEntity> findRange(@Param("code") String code, @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Modifying
    @Query("delete from ClickAggregateEntity a where a.id.day < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDate cutoff);
}
