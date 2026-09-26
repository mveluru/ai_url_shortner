# Design ↔ Implementation Verification Report

Design document: `urldesign/url-shortener-comprehensive-design.md` v2.5. Assignment brief: `urldesign/010 - Assignment…pdf`.
Implementation: `url-shortener-service/` (Java 21, Spring Boot 3.3.13, MySQL 8.4, Redis 7, RabbitMQ 3.13).

How this was checked (mechanically, not by reading):

| Check | Command | What it proves |
|---|---|---|
| Unit tests (no Docker) | `./mvnw test` | pure logic, mapping tables, config ↔ design conformance, OpenAPI ↔ DTO drift |
| Integration + failure injection | `./mvnw verify` (needs Docker) | real MySQL/Redis/RabbitMQ; faults injected at network level via Toxiproxy |
| Design-row coverage | `python3 scripts/verify-design-coverage.py` | every E1–E24 and F1–F13 has a test tagged `@Covers` |

## 1. Coverage matrix

| Design section | Implemented in | Verified by |
|---|---|---|
| §2, §6 API, versioning, idempotency | `docs/openapi.yaml`, generated `UrlsApi`/`RedirectApi`, `UrlService` | `OpenApiContractTest`, `CreateUrlIT`, `SwaggerIT` |
| §3 architecture (3 services as packages, §20.7) | packages `shortener`, `redirect`, `analytics` | whole suite |
| §4 code generation (counter → Feistel → Base62, 80 % rollover) | `IdBlockAllocator`, `FeistelPermutation`, `Base62`, `CodeLengthPolicy` | `FeistelPermutationTest` (exhaustive bijection), `CodeLengthPolicyTest`, `IdAllocationIT` |
| §5 data model, MySQL `utf8mb4_bin`, UTC | `db/migration/V1–V3`, entities | `MigrationIT` (collation asserted, `abc123`+`ABC123`) |
| §7 E1–E24 | validators, `UrlLookupService`, `AggregationService`, advice | see `verify-design-coverage.py` output |
| §8 F1–F13, §8.2 breakers/retry/jitter | `ResilienceConfig`, `RedisUrlCache`, `UrlReader/Writer`, `ClickPublisher` | `FailureInjectionIT`, `ReplicaFallbackIT`, `CollisionIT`, `DesignConformanceTest` |
| §9 error taxonomy (20 codes, sealed hierarchy, one advice) | `common.error.*` | `GlobalExceptionHandlerTest` (every row), `ErrorContractIT` |
| §10 security (API key/Argon2, ownership 403, SSRF, rate limits, ip_hash) | `common.security`, `SsrfGuard`, `RateLimits`, `IpHasher` | `AuthIT`, `UrlValidatorTest`, `IpClassifierTest`, `RateLimitIT`, `IpRateLimitIT` |
| §11 rate-limit defaults | `application.yml` | `DesignConformanceTest`, `RateLimitsTest` |
| §12 metrics/logging/tracing/alerts | Micrometer, `logback-spring.xml`, `ops/prometheus-alerts.yml` | `ObservabilityIT` (names exist; alert metrics are real; logs leak nothing) |
| §13 deploy/rollback, feature flags, expand/contract | profiles, `features.stats-device-breakdown`, Flyway, `Dockerfile` | `ProdProfileIT`; process items documented in `.claude/plugins/release-checklist.md` |
| §14 DR | topology only | docs (`architecture-diagrams.md`) — not code |
| §15/§16 scenarios, AI traceability | Design §15 *As built* blocks; `package-info.java` in `redirect`, `shortener`, `analytics`; `docs/engineering-summary.md`; `docs/ai-traceability-log.md` | documents; see V-20 for what the plan text claims that the repository does not evidence |
| §17 test strategy | test tree | unit / IT / failure-injection / security tests all present |
| §18 setup | `docker-compose.yml`, `mvnw`, `/internal/api-keys` (local/test only) | `README.md` steps executed in `ProdProfileIT`/ITs |
| §20 stack mapping | `pom.xml`, generator, Testcontainers | build |
| §21 `.claude/` tooling, README, diagrams | `.claude/**`, `README.md`, `docs/architecture-diagrams.md` | present |
| §22 Swagger UI serving the checked-in contract | `OpenApiContractController`, `HiddenRoutesFilter` | `SwaggerIT` (byte-identical; generated spec unreachable) |

Infrastructure-only (no code to test): **F10** multi-AZ / replica promotion, **§14** region DR, **§13** canary mechanics.

## 2. Findings: contradictions inside the design, and how each was resolved

| # | Design says | Conflict | Resolution in code |
|---|---|---|---|
| V-1 | E1/E10: blank `longUrl`, bad alias pattern → `INVALID_URL`/`INVALID_ALIAS`. §9.2 lists the same codes. | The §9.2a *example* shows the same inputs as `VALIDATION_FAILED`. | Dedicated codes win (two normative rows vs one illustrative example). `VALIDATION_FAILED` is used for remaining bean-validation / type failures. **Please confirm; the §9.2a example should change.** |
| V-2 | §6.3: a different `customAlias` on retry returns the original. §15.2: an alias request must not be a duplicate of an auto code. | Direct contradiction. | Fingerprint = SHA-256(owner, **kind**, url), kind ∈ {auto, custom}. Satisfies both. |
| V-3 | §5.1: fingerprint index non-unique. §20.1: unique index + catch violation. | A plain unique index forbids re-creating a URL after the 24 h window. | Unique **nullable** column; released on deactivate/expiry/window lapse. Makes the concurrent-duplicate race safe. |
| V-4 | §5.1 `id` "identity"; §4.1/§20.1 Hibernate `@SequenceGenerator` block allocation. | The code is derived *from* the id, but Hibernate assigns ids inside `persist()` after the INSERT state is captured → INSERT(null) then UPDATE → `NOT NULL` violation (found by test). | Explicit `IdBlockAllocator` (same `url_id_seq` table, 10,000/claim, allocated **outside** any transaction to avoid pool-exhaustion deadlock). Behaviour identical to F8. |
| V-5 | F9: any `short_code` collision is a paging bug. §4.1: aliases share the namespace. | An alias can legitimately equal a future generated code. | Conflict with a **custom** alias → skip to next id (bounded, metered). Conflict with an **auto** code → F9: 500 + `short_code_collision` metric, never overwrites. |
| V-6 | §9.2 `MALFORMED_JSON` for a wrong JSON type (e.g. number for `expiresAt`). | Jackson coerces number→String by default; `ALLOW_COERCION_OF_SCALARS` does not cover it. | Explicit `CoercionConfig` for textual targets (`JacksonConfig`). |
| V-7 | §10.3 SSRF guard resolves the host. | **Real bypass found by test:** `http://0177.0.0.1/` — the JDK reads it as decimal 177.0.0.1 (public), browsers as octal 127.0.0.1. | Non-canonical numeric hosts are rejected (`rejectAmbiguousIpNotation`). |
| V-8 | §20.4a requires `utf8mb4_bin` for `short_code` (`urls`). | `click_aggregates.short_code` has the same problem: `abc`/`ABC` analytics would merge. | Same collation on `click_aggregates.short_code`; asserted in `MigrationIT`, exercised in `AnalyticsIT`. |
| V-9 | F4: fallback reads use the replica. | The design has no replica routing; Spring Data reads are `readOnly` so *auth lookups* would also go to a lagging replica (found by `ReplicaFallbackIT`: 401). | `ReplicaRoutingConfig` (optional, `app.datasource.replica.url`); redirect fallback + stats reads → replica; auth, ownership/metadata and all writes → primary. |
| V-10 | F2: invalidate on mutation. | If Redis is down at delete time the invalidation is lost and a deleted link keeps redirecting until TTL. | Failed invalidations are queued and retried every 5 s; `cache_invalidation_pending` metric + alert. TTL remains the hard bound. |
| V-11 | §22.3: enable springdoc **and** serve a static controller at `/v3/api-docs.yaml`. | Two handlers on one path; content negotiation (not intent) picked springdoc's *generated* spec — the exact two-sources-of-truth failure §22.1 warns about. | springdoc's generator relocated to `/v3/generated-api-docs` and hidden (`HiddenRoutesFilter`, 404); only its UI config sub-path stays reachable. |
| V-12 | E18: illegal path characters → 404. | Spring Security's firewall and Tomcat reject some paths before MVC, with HTML/empty bodies and no `requestId`. | Firewall rejections → 404 via the advice; container rejections → standard JSON body via `JsonErrorReportValve`. **Residual:** container-level rejections (invalid `%` escape, `%2F`) are `400`, not `404`. |
| V-13 | §8.2.1 "opens at ≥ 50 % over the last 20 calls". | Resilience4j's default `minimumNumberOfCalls` is 100. | Set to 10 (window 20) / 5 (window 10) so the breaker can actually open. |
| V-14 | §20.1 "`IntervalFunction.ofExponentialRandomBackoff` implements full jitter". | It is jittered but not *full* jitter. | `FullJitterIntervalFunction` = `random(0, min(cap, base·2^n))`, tested statistically. |
| V-15 | §20.1 `@Scheduled` durations. | Spring 6.1 rejects `15s`-style `fixedDelayString`. | Scheduled from the typed `Duration` properties. |
| V-16 | E14 note: reason visible only on the authenticated metadata endpoint; E23/E20: deactivated → 404. | Unclear what metadata returns for a deactivated code. | Metadata → `200` with `status: DEACTIVATED|EXPIRED|ACTIVE`; `DELETE` twice → 404; stats on deactivated → 404. |
| V-17 | §20.1 typo "Lettoce"; §5.1 `version` "DEFAULT 1". | Hibernate seeds `@Version` at 0. | Cosmetic; DB default retained. |
| V-18 | F1 "stops paying the connection-timeout tax". | Lettuce queues commands while disconnected and reconnects with up-to-30 s backoff. | `REJECT_COMMANDS` + reconnect delay capped at 2 s (`RedisConfig`). |
| V-19 | F3 "fail fast". | A **black-holed** primary is only detected by the JDBC socket timeout (was 10 s). | Default `socketTimeout=3000`; both "refused" and "black-hole" outages are tested. |
| V-20 | §15.1–15.3 describe validation and process steps: a stampede load test, an analytics-vs-redirect load test, a "reconciliation test" against raw click events, an added migration rollback script, and an engineer-corrected first-draft cache-miss race. | **None of these is evidenced in the repository.** No load tests were run (L1 in the README). Raw events are deliberately not retained (§5.2), so reconciliation against them is impossible as written. Migrations `V1`–`V3` are expand-only with no rollback script. The traceability log has no entry for the race. (The guard, the alias regex and the queue-based design that these steps refer to *are* implemented and tested.) | §15 keeps the plan text and adds an **As built in this repository** block per scenario (decomposition → what delivered it, execution, validation as run, what was not done); §19 gains the load-test limitation; the three scenario packages carry the same account as Javadoc (`package-info.java`). **Engineer decision needed:** supply the missing evidence or state it happened outside this repository, or downgrade the plan text. |

## 3. Interpretations / additions the design did not specify

- `GET /stats` defaults to the last 30 days; referrers are reduced to a lowercase **host** (paths/queries can carry tokens); device breakdown is behind `app.features.stats-device-breakdown`.
- `updatedAt` = last aggregation **or** "queue drained" heartbeat, so "caught up" is distinguishable from "idle".
- `/internal/urls/{code}` hard-delete (the E8 "admin path") and `/internal/api-keys` exist only in `local`/`test` profiles.
- `prod` profile refuses to start with dev secrets (`ProdSecretsGuard`), serves actuator on another port, disables Swagger.

## 4. Known limitations (honest list — extends design §19/§20.8)

1. Rate limiters are per-instance (design §20.8): the effective global limit scales with instance count.
2. F2: the pending-invalidation queue is in memory per instance; a crash during a Redis outage falls back to TTL (default 10 min).
3. Replica lag can briefly serve a deactivated link on a cache miss (accepted by F4, but a real staleness window).
4. F5 events are dropped during broker outages (by design); analytics under-count by that amount.
5. SSRF is creation-time only (design §19). Redirect targets are not re-validated.
6. Container-level path rejections return 400 (V-12).
7. `updatedAt` staleness while the consumer is down is disclosed but not alerted per-code.
8. Load tests (§17 p99 targets at 5,000 rps) were **not run**; only functional and fault behaviour is verified here.
9. Tracing exporter (OTLP) is not wired; spans and trace ids exist (requestId == trace id), export is a deployment setting.
10. Argon2 verification is cached per instance for 30 s, so a revoked key may work that long on an instance that already saw it.
11. Checkstyle/SpotBugs/coverage gates from the design's "quality gates" are not configured; compiler + tests are the only gates.
