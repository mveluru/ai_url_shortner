# AI traceability log (design doc §16)

Format: `[generated|edited|rejected] <what> — <one-line rationale>`. This log records what actually happened while building
this repository with Claude Code; the engineer reviews and owns every line. Entries marked ⚠ were caught by a test, not by review.

## Decisions caught by tests or review (the interesting ones)

- [edited] ⚠ `SsrfGuard`/`UrlValidator` — first draft passed `http://0177.0.0.1/` (JDK parses octal-looking hosts as decimal, browsers as octal); added canonical-dotted-quad rule. Found by `UrlValidatorTest`.
- [edited] ⚠ code generation — first draft used a Hibernate `@SequenceGenerator` and set `short_code` after `persist()`; Hibernate INSERTs the persist-time state → `NOT NULL` violation. Replaced by `IdBlockAllocator`. Found by the first end-to-end run.
- [edited] ⚠ `ApiKeyLookup` — repository reads are `readOnly`, so with a replica configured auth would read the lagging replica (401s). Forced primary. Found by `ReplicaFallbackIT`.
- [edited] ⚠ `JacksonConfig` — the `allow-coercion-of-scalars: false` YAML setting did nothing for number→String; replaced with explicit coercion config. Found by `ErrorContractIT`.
- [edited] ⚠ Swagger wiring — design §22.3 config let springdoc's generated spec answer at the contract path. Relocated + hidden. Found by `SwaggerIT` (Content-Type `application/vnd.oai.openapi`).
- [edited] ⚠ `RabbitConfig` — a `#` binding key on a *direct* exchange (topic syntax) would have routed nothing; fixed to a fixed routing key. Caught in review before any test ran.
- [edited] ⚠ `BestEffortCache` — invalidation failures were only counted; added retry queue after reasoning through F2 with Redis down. Confirmed by `FailureInjectionIT.f2_*`.
- [edited] ⚠ fault-injection base classes — a subclass `@DynamicPropertySource` was silently overridden by its parent, so faults were injected into an unused proxy and tests passed vacuously. Added a wiring guard (`applicationIsConnectedThroughTheProxies`) and made the bases siblings.
- [rejected] a test asserting on a string literal defined inside the test (`accessLogsDoNotLeak`, first draft) — vacuous; replaced by real log capture.
- [rejected] an `ip_hash` assertion against an arbitrary constant — vacuous; replaced by comparison with the true SHA-256.
- [rejected] `denyAll()` for springdoc's generated spec — answered `401` (implies a credential helps); replaced by a 404 filter.
- [rejected] `410 Gone` for expired links — leaks lifetime on an unauthenticated endpoint (design E14).
- [rejected] synchronous analytics write in the redirect path — violates §8.1.
- [rejected] Mockito/H2 for failure-injection tests — a mock cannot prove a breaker opens against a black-holed dependency; real containers + Toxiproxy used.

## Generated, accepted after review

- [generated] Base62, `FeistelPermutation` (cycle-walking), `CodeLengthPolicy` — exhaustive bijection tests added by hand-chosen awkward domains.
- [generated] `GlobalExceptionHandler`, sealed exception hierarchy — one test row per §9.2/§9.3 line.
- [generated] Flyway migrations, entities, repositories — verified by `MigrationIT` on real MySQL.
- [generated] `RedisUrlCache`, `UrlLookupService` (sealed `CacheLookupResult`, stampede guard).
- [generated] analytics pipeline (publisher, consumer, aggregation, stats) — verified on real RabbitMQ incl. DLQ.
- [generated] all documentation in `docs/`, `.claude/`, `README.md`.

## Design-doc contradictions surfaced (decisions for the engineer)

See `docs/design-verification-report.md` §2 (V-1 … V-19). **V-1 needs an explicit human decision.**

## Human sign-off status

Not yet given. Per §16, the redirect hot path (`UrlLookupService`, `RedisUrlCache`, `RedirectController`), the SSRF guard, the migrations
and `SecurityConfig` require explicit engineer approval before merge.
