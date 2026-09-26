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
- [generated] `README.md` "Manual testing with curl" — every scenario was run against a live instance (create, replay, alias/expiry, redirect, metadata, stats, deactivate, 401/400/403/404/409 cases, dev helpers) and matched the documented statuses.
- [generated] `README.md` "Setup guide" / "Starting the application" — config table taken from `application.yml`/`application-prod.yml`; local-run steps checked against a running instance, the jar and container steps were not executed.
- [edited] `docker-compose.yml` MySQL host port → `${MYSQL_PORT:-3306}` — the developer machine already had 3306 in use; default unchanged.
- [generated] `README.md` "Requirement coverage report" and expanded "Known limitations" — built from a real `./mvnw verify` run (278 unit + 117 IT, 0 failed) and a hand check of each E/F row's test kind; `verify-design-coverage.py` only checks `@Covers` tags. [edited] the README's earlier "~300 / ~90" test counts were wrong and were replaced with the measured numbers.
- [generated] `run-local.bat` / `stop-local.bat` — one-step Windows start/stop: Docker + JDK 21 checks, reuse-or-free-port selection, env export, auto-open Swagger. **Not executed on Windows** (written on macOS); only the compose overrides, `docker compose port` output format, RabbitMQ readiness command and a `--dry-run up` with the computed ports were checked.
- [edited] `docker-compose.yml` Redis/RabbitMQ host ports → `${REDIS_PORT:-6379}`, `${RABBIT_PORT:-5672}`, `${RABBIT_MGMT_PORT:-15672}` — lets the script avoid clashes; the app already reads `REDIS_PORT`/`RABBIT_PORT`, so one variable configures both. Defaults unchanged.
- [generated] `.gitattributes` (`*.bat`/`*.cmd` → CRLF) — LF-only batch files break labels.
- [edited] README/`.claude` docs for the Windows flow, with the untested status recorded as limitation L23.
- [generated] `run-local.sh` / `stop-local.sh` — bash port of the Windows scripts. Run for real on macOS with the project's containers already up and :8080 busy: reused 3307/6379/5672/15672, chose :8081, app healthy, create + redirect OK, real Ctrl+C via a pty exits cleanly, containers untouched. Linux branches and an empty-machine first run not run.
- [edited] `run-local.sh` — first draft reported a killed JVM / Ctrl+C as `[ERROR] exited with code 1`; now traps INT/TERM and treats an interrupt as a normal stop. Found by running it, not by review.
- [generated] `README.md` "Trying the container next to the compose services" — from a real run: image built, `prod` container started (17 s, 3 Flyway migrations), prod-only routes hidden, create/redirect/stats, 401, SSRF 400. The commands are the tested ones (env file replaced by `-e` flags, secrets via `openssl rand`).
- [edited] ⚠ container trial found the shared-queue pitfall by running it: 4 redirects showed 2 clicks because the local app consumed the rest into the dev schema. Fixed the trial with a separate RabbitMQ vhost + schema, cleaned the 2 stray rows, documented as README L25 and in `.claude/CLAUDE.md`.
- [edited] `postman/` collection grew from 32 requests / 78 assertions to 71 / 165: added "4. Positive paths" (limit-valid inputs, byte-for-byte redirects, exact analytics with real Referer/User-Agent, stats ranges) after the review question "did you test positive test cases?" showed the happy path was shallow. Two of my own mistakes were caught by the first run: a missing API-key header (401) and >20 creates on one key (429 RATE_LIMITED, the limiter working); fixed with a third key. Mutation-checked (6 mutations, all caught) and run 3x green. Test rows left in the dev schema were removed.
- [generated] `postman/url-shortener.postman_collection.json` — 32 requests / 78 assertions covering every endpoint and error case. Run with Newman against the local app: passes twice; a mutated copy (wrong Cache-Control, wrong error code) failed as required (R6). Not opened in the Postman desktop app. Test rows left in the dev schema were removed afterwards.
- [generated] design §15 "As built in this repository" blocks (three scenarios), `package-info.java` Javadoc for `redirect`, `shortener`, `analytics`, V-20 in the verification report, design v2.7. Every class and test named was checked against the code (two attributions were wrong on the first draft and corrected: retention-at-read is `StatsService`, and the F1/F2 test descriptions).
- [edited] ⚠ V-20: §15's plan text claims load tests, a reconciliation test, a rollback script and an engineer-corrected cache race that the repository does not evidence. The plan text was kept (the engineer may have done these elsewhere) and contradicted only in the new as-built blocks; **the engineer must resolve this**.
- [generated] `.claude/CLAUDE.md` "Run locally" — JDK 21 / `MYSQL_PORT`+`DB_URL` / already-running-instance gotchas hit while running the app.

## Design-doc contradictions surfaced (decisions for the engineer)

See `docs/design-verification-report.md` §2 (V-1 … V-19). **V-1 needs an explicit human decision.**

## Human sign-off status

Not yet given. Per §16, the redirect hot path (`UrlLookupService`, `RedisUrlCache`, `RedirectController`), the SSRF guard, the migrations
and `SecurityConfig` require explicit engineer approval before merge.
