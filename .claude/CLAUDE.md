# URL Shortener — project context (read first)

Java 21 · Spring Boot 3.3 · MySQL 8.4 (`utf8mb4_bin` codes) · Redis 7 · RabbitMQ 3.13 · Resilience4j · Flyway. Single deployable, three packages
(`shortener`, `redirect`, `analytics`) — design §20.7. Source of truth: `urldesign/url-shortener-comprehensive-design.md`; contract: `docs/openapi.yaml`.

## Layout
`url-shortener-service/src/main/java/com/urlshortener/{shortener,redirect,analytics,common,api,jobs,internal}` · migrations `src/main/resources/db/migration` ·
tests `src/test` (`*Test` = unit, `*IT` = Docker) · `docs/` · `ops/` · `scripts/verify-design-coverage.py`.

## Non-negotiable invariants (each cites its design section)
1. **The redirect path depends only on Redis-or-MySQL** — never on the queue, analytics store or auth (§8.1, F11).
2. **302, never 301, with `Cache-Control: no-store`** (§6.4).
3. **Cache: invalidate on mutation, never update** (F2); expiry/active are evaluated at read time, never trusted to TTL or the sweep (F12).
4. **Uniqueness is decided by the DB constraint**, never pre-checked (E13, F9). The constraint *name* tells which rule fired (`UniqueConstraint`).
5. **One place chooses HTTP statuses: `GlobalExceptionHandler`** — filters delegate via `HandlerExceptionResolver` (§9.3).
6. **No PII in logs or errors**: no IPs, API keys, query strings, long URLs, stack traces in bodies (§9.4, §10.2, §12.2).
7. **SSRF guard runs before persist** on every URL-accepting path; fail closed (§10.3).
8. **Writes are never auto-retried; reads are** (§8.2.3). Retry is the INNER decorator, breaker the OUTER. One named breaker per dependency.
9. **Short codes are case-sensitive**: every `short_code` column is `utf8mb4_bin` (§20.4a).
10. **Expand/contract migrations only**; never edit an applied migration (§13).
11. **Never silently narrow a documented guarantee** — change the design doc in the same PR (§21.9).
12. **Redirect hot-path changes need explicit engineer sign-off** (§16).

## AI traceability
Tag every AI-produced change `[generated]`, `[edited]` or `[rejected]` + one-line rationale in the PR/commit (`docs/ai-traceability-log.md`).

## Where to look
| Task | Read |
|---|---|
| create/delete/metadata, aliases, idempotency | `components/shortener-service` |
| redirect, cache, stampede, breaker fallback | `components/redirect-service` |
| clicks, queue, aggregation, stats | `components/analytics-service` |
| schema, migrations, id allocation, replica | `components/data-layer` |
| auth, SSRF, rate limits | `components/security-auth` |
| metrics, logs, tracing, alerts | `components/observability` |
| cross-cutting workflows | `plugins/` |

## Definition of done
`./mvnw verify` green · `python3 scripts/verify-design-coverage.py` green · `openapi.yaml` and DTOs agree (`OpenApiContractTest`) · design doc and these
files updated if a guarantee changed · sign-off recorded if a high-impact path was touched.
