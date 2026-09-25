package com.urlshortener.shortener.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UrlRepository extends JpaRepository<UrlEntity, Long> {

    Optional<UrlEntity> findByShortCode(String shortCode);

    Optional<UrlEntity> findByIdempotencyFingerprint(String fingerprint);

    @Query("""
            select new com.urlshortener.shortener.domain.UrlView(u.shortCode, u.longUrl, u.expiresAt, u.active, u.version)
            from UrlEntity u where u.shortCode = :shortCode""")
    Optional<UrlView> findViewByShortCode(@Param("shortCode") String shortCode);

    /** Candidates for the expiry sweep (F12): storage hygiene only, never relied on for correctness. */
    @Query("select u from UrlEntity u where u.active = true and u.expiresAt is not null and u.expiresAt < :now order by u.id")
    List<UrlEntity> findExpiredActive(@Param("now") Instant now, Pageable page);

    /** Hard delete: the admin path that frees a soft-deleted alias (E8). */
    @Modifying
    @Query("delete from UrlEntity u where u.shortCode = :shortCode")
    int hardDeleteByShortCode(@Param("shortCode") String shortCode);
}
