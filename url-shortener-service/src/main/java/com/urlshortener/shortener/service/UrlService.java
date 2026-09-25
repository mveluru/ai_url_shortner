package com.urlshortener.shortener.service;

import com.urlshortener.common.config.AppProperties;
import com.urlshortener.common.error.AliasTakenException;
import com.urlshortener.common.error.ForbiddenException;
import com.urlshortener.common.error.ResourceNotFoundException;
import com.urlshortener.common.error.UniqueConstraint;
import com.urlshortener.common.ratelimit.RateLimits;
import com.urlshortener.redirect.cache.BestEffortCache;
import com.urlshortener.redirect.cache.CachedUrl;
import com.urlshortener.shortener.api.CreateUrlRequest;
import com.urlshortener.shortener.api.UrlResource;
import com.urlshortener.shortener.domain.UrlEntity;
import com.urlshortener.shortener.validation.AliasValidator;
import com.urlshortener.shortener.validation.ExpiryValidator;
import com.urlshortener.shortener.validation.UrlValidator;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The Shortener (write path): validate -> idempotency -> allocate/insert -> write-through (design doc section 3.2).
 *
 * <p>Uniqueness is <em>never</em> pre-checked. A check-then-insert would itself race (E13), so the database's UNIQUE
 * constraint is the sole arbiter and the loser's insert failure is translated here, where the attempted code and the
 * caller's intent (custom vs auto) are known.
 */
@Service
public class UrlService {

    private static final Logger log = LoggerFactory.getLogger(UrlService.class);

    private final UrlValidator urlValidator;
    private final AliasValidator aliasValidator;
    private final ExpiryValidator expiryValidator;
    private final RateLimits rateLimits;
    private final UrlWriter writer;
    private final UrlReader reader;
    private final BestEffortCache cache;
    private final MeterRegistry meters;
    private final Clock clock;
    private final String baseUrl;
    private final Duration idempotencyWindow;
    private final int aliasClashMaxAttempts;

    public UrlService(UrlValidator urlValidator, AliasValidator aliasValidator, ExpiryValidator expiryValidator,
                      RateLimits rateLimits, UrlWriter writer, UrlReader reader, BestEffortCache cache,
                      MeterRegistry meters, Clock clock, AppProperties props) {
        this.urlValidator = urlValidator;
        this.aliasValidator = aliasValidator;
        this.expiryValidator = expiryValidator;
        this.rateLimits = rateLimits;
        this.writer = writer;
        this.reader = reader;
        this.cache = cache;
        this.meters = meters;
        this.clock = clock;
        this.baseUrl = props.publicBaseUrl().replaceAll("/+$", "");
        this.idempotencyWindow = props.idempotency().window();
        this.aliasClashMaxAttempts = props.code().aliasClashMaxAttempts();
        meters.counter("alias_collision", "outcome", "rejected");
        meters.counter("alias_collision", "outcome", "auto_code_skipped");
        meters.counter("short_code_collision");
    }

    public CreateResult create(String ownerKeyId, CreateUrlRequest request) {
        long started = System.nanoTime();
        String outcome = "error";
        try {
            CreateResult result = doCreate(ownerKeyId, request);
            outcome = result.created() ? "created" : "replayed";
            return result;
        } finally {
            DistributionSummary.builder("create_latency_ms").tag("outcome", outcome)
                    .publishPercentileHistogram().register(meters)
                    .record((System.nanoTime() - started) / 1_000_000.0);
        }
    }

    private CreateResult doCreate(String ownerKeyId, CreateUrlRequest request) {
        rateLimits.checkCreate(ownerKeyId);                                          // E19 (per key)

        String longUrl = urlValidator.validate(request.longUrl());                   // E1-E6
        String alias = request.customAlias() == null ? null : aliasValidator.validate(request.customAlias()); // E9, E10
        Instant expiresAt = expiryValidator.validate(request.expiresAt());           // E11, E12
        Instant now = clock.instant();
        String fingerprint = IdempotencyKey.fingerprint(ownerKeyId, alias != null, longUrl);

        Optional<UrlEntity> replay = liveClaim(fingerprint, now);
        if (replay.isPresent()) {
            return new CreateResult(toResource(replay.get(), now), false);            // section 6.3: first request's parameters win
        }
        return alias != null
                ? createCustom(longUrl, alias, ownerKeyId, fingerprint, expiresAt, now)
                : createAuto(longUrl, ownerKeyId, fingerprint, expiresAt, now);
    }

    private CreateResult createCustom(String longUrl, String alias, String owner, String fingerprint,
                                      Instant expiresAt, Instant now) {
        try {
            return created(writer.insert(longUrl, alias, owner, fingerprint, expiresAt, now, idempotencyWindow), now);
        } catch (ShortCodeConflictException e) {
            // E7, E8, E13: taken by an active OR soft-deleted code, or lost a simultaneous race. One answer for all.
            meters.counter("alias_collision", "outcome", "rejected").increment();
            throw new AliasTakenException("The requested alias is already in use.");
        } catch (DataIntegrityViolationException e) {
            return replayAfterRace(e, fingerprint, now);
        }
    }

    private CreateResult createAuto(String longUrl, String owner, String fingerprint, Instant expiresAt, Instant now) {
        for (int attempt = 1; ; attempt++) {
            try {
                return created(writer.insert(longUrl, null, owner, fingerprint, expiresAt, now, idempotencyWindow), now);
            } catch (ShortCodeConflictException e) {
                if (isCustomAlias(e.attemptedCode()) && attempt < aliasClashMaxAttempts) {
                    // Not a bug: a user-chosen alias happens to equal the next generated code. Take the next counter
                    // value. This is a deterministic, known conflict, not the blind retry section 8.2.3 forbids.
                    meters.counter("alias_collision", "outcome", "auto_code_skipped").increment();
                    continue;
                }
                if (isCustomAlias(e.attemptedCode())) {
                    throw new ShortCodeCollisionException(e.attemptedCode());       // clashed repeatedly: treat as anomalous
                }
                // F9: two AUTO codes collided. The design makes this impossible; page on it.
                meters.counter("short_code_collision").increment();
                log.error("F9 DATA-INTEGRITY ALERT: auto-generated short code collided with an auto-generated code: {}",
                        e.attemptedCode());
                throw new ShortCodeCollisionException(e.attemptedCode());
            } catch (DataIntegrityViolationException e) {
                return replayAfterRace(e, fingerprint, now);
            }
        }
    }

    /** Two identical requests raced: the loser's insert hit the fingerprint constraint, so return the winner's resource. */
    private CreateResult replayAfterRace(DataIntegrityViolationException e, String fingerprint, Instant now) {
        if (UniqueConstraint.of(e) == UniqueConstraint.IDEMPOTENCY_FINGERPRINT) {
            Optional<UrlEntity> winner = liveClaim(fingerprint, now);
            if (winner.isPresent()) {
                return new CreateResult(toResource(winner.get(), now), false);
            }
        }
        throw e;
    }

    private boolean isCustomAlias(String code) {
        return reader.findEntity(code).map(UrlEntity::isCustomAlias).orElse(false);
    }

    private Optional<UrlEntity> liveClaim(String fingerprint, Instant now) {
        return writer.findByFingerprint(fingerprint).filter(e -> ClaimRules.isLive(e, now, idempotencyWindow));
    }

    private CreateResult created(UrlEntity entity, Instant now) {
        cache.putQuietly(entity.getShortCode(), new CachedUrl(entity.getLongUrl(), entity.getExpiresAt(),
                entity.isActive(), entity.getVersion()));                             // write-through, best-effort (F2)
        return new CreateResult(toResource(entity, now), true);
    }

    /** Metadata. The only endpoint that discloses WHY a link is unusable (E14 rationale). Foreign codes -> 403. */
    public UrlResource get(String ownerKeyId, String shortCode) {
        UrlEntity entity = owned(ownerKeyId, shortCode);
        return toResource(entity, clock.instant());
    }

    /** E23: the second DELETE of the same code is a 404. */
    public void deactivate(String ownerKeyId, String shortCode) {
        writer.deactivate(shortCode, ownerKeyId);
        cache.invalidateQuietly(shortCode);                                          // invalidate, never update (F2)
    }

    /** Loads a code and enforces ownership; also used by the stats endpoint. */
    public UrlEntity owned(String ownerKeyId, String shortCode) {
        UrlEntity entity = reader.findEntity(shortCode)
                .orElseThrow(() -> new ResourceNotFoundException("No such short URL."));
        if (!entity.getOwnerKeyId().equals(ownerKeyId)) {
            throw new ForbiddenException("This short URL belongs to a different API key.");
        }
        return entity;
    }

    UrlResource toResource(UrlEntity entity, Instant now) {
        return new UrlResource(entity.getShortCode(), baseUrl + "/" + entity.getShortCode(), entity.getLongUrl(),
                entity.isCustomAlias(), entity.statusAt(now), entity.getCreatedAt(), entity.getExpiresAt());
    }
}
