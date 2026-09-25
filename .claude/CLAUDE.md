# URL Shortener — project context (read first)

Java 21 · Spring Boot 3.3 · MySQL 8.4 (`utf8mb4_bin` codes) · Redis 7 · RabbitMQ 3.13 · Resilience4j · Flyway. Single deployable, three packages
(`shortener`, `redirect`, `analytics`) — design §20.7. Source of truth: `urldesign/url-shortener-comprehensive-design.md`; contract: `docs/openapi.yaml`.

## Layout
`url-shortener-service/src/main/java/com/urlshortener/{shortener,redirect,analytics,common,api,jobs,internal}` · migrations `src/main/resources/db/migration` ·
tests `src/test` (`*Test` = unit, `*IT` = Docker) · `docs/` · `ops/` · `scripts/verify-design-coverage.py`.

## Run locally (full guide: `README.md` → Setup guide / Starting the application / Manual testing with curl)
`docker compose up -d` → `./mvnw -pl url-shortener-service spring-boot:run` (default profile `local`; Flyway migrates on startup) → `curl localhost:8080/actuator/health`.
- **JDK 21 required**; the machine default may be newer — set `JAVA_HOME` before running Maven.
- **Windows / a teammate with nothing installed:** `run-local.bat` (start) and `stop-local.bat [reset]` in the repo root. They check Docker + JDK 21, reuse running containers or pick free ports, export `DB_URL`/`REDIS_PORT`/`RABBIT_PORT`/`SERVER_PORT`/`PUBLIC_BASE_URL`, then run `mvnw.cmd ... spring-boot:run`. **Not yet executed on Windows** (README limitation L23); say so rather than claiming they work. Never run `stop-local.*` to "clean up" a test: it stops the developer's shared containers.
- **Keep them in sync:** if `docker-compose.yml` ports/services or the env vars in `application.yml` change, update `run-local.bat` **and** `run-local.sh`, the README config table and this section together. Host ports in compose are `${VAR:-default}` on purpose (`MYSQL_PORT`, `REDIS_PORT`, `RABBIT_PORT`, `RABBIT_MGMT_PORT`); `REDIS_PORT`/`RABBIT_PORT` are also the names the app reads.
- `.bat`/`.cmd` files must stay **CRLF** (`.gitattributes` enforces it) and **ASCII**; LF-only labels break `goto`/`call :label`.
- **macOS/Linux:** `./run-local.sh [--no-open]` and `./stop-local.sh [reset]` do the same as the .bat pair (tested on macOS only; README limitation L24). Manual fallback: `MYSQL_PORT=3307 docker compose up -d` **and** export `DB_URL` for the same port (both, or the app cannot reach MySQL).
- **One RabbitMQ vhost per database.** Click events share one queue (competing consumers): a second app (e.g. the Docker image) with a different DB on the same vhost steals ~half the clicks into the wrong DB (README L25). Give test containers their own `SPRING_RABBITMQ_VIRTUAL_HOST` and schema, and delete what a test writes.
- **Postman collection** (`postman/`): run with `npx newman run postman/url-shortener.postman_collection.json` against a `local` instance. Keep it in step with `docs/openapi.yaml`: a new endpoint, a changed status, or a new `ErrorCode` (rule A5) needs a request/assertion there too. It is hand-maintained JSON (no generator in the repo). Create is rate limited per key (burst 20; rejected creates count), so a run needs several keys: spread new create requests across them. Newman leaves revoked-key and click-aggregate rows behind (~14 keys and ~50 aggregate rows for a few runs); clean only rows you created.
- **Check `:8080` before starting** — an instance may already be running (`lsof -iTCP:8080 -sTCP:LISTEN`); drive it with curl instead of starting a second.
- `/internal/**` (issue/revoke API keys, hard delete) exists only in `local`/`test` (S8). Curl-driven checks must clean up the URLs they create.
- `prod` profile has no defaults and needs every secret/endpoint from the environment (`ProdSecretsGuard`); actuator moves to `MANAGEMENT_PORT` (8081).

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
| black-box API check (Postman/Newman) | `postman/url-shortener.postman_collection.json` |
| set up, start, or manually exercise the app | `README.md`, `run-local.{bat,sh}`, `stop-local.{bat,sh}` |

## Definition of done
`./mvnw verify` green · `python3 scripts/verify-design-coverage.py` green · `openapi.yaml` and DTOs agree (`OpenApiContractTest`) · design doc and these
files updated if a guarantee changed · sign-off recorded if a high-impact path was touched.
