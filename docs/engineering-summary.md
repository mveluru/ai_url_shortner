# Final engineering summary (assignment §4.8)

## Plan and rationale
Design-first, contract-first: `docs/openapi.yaml` → generated controller interfaces → domain → resilience → tests against real infrastructure.
Order actually followed: contract/schema → code generation → validation/SSRF → security → write path → read path → analytics →
observability → Swagger → failure injection → docs/tooling. Each step ended with a passing build before the next began.

## Artifacts
Working prototype (`url-shortener-service/`), OpenAPI contract, Flyway schema, 278 unit + 117 integration/failure-injection tests (all passing, 2026-09-25),
Prometheus alerts, docker-compose, Dockerfile, architecture diagrams, verification report, traceability log, `.claude/` tooling.

## The three required scenarios (mapped to this repository)
| Scenario | Decomposition → execution → validation |
|---|---|
| **Greenfield — redirect service** | contract → data model → cache-aside read path → F1/F7 handling → error handling → tests. `UrlLookupService`, `RedisUrlCache`. Validated by `RedirectIT` and the F1/F7 tests (60 concurrent misses ⇒ ≤ 3 DB reads). |
| **Brownfield — custom aliases on create** | impacted: request schema, unique constraint, validators, reserved list, **idempotency** (an alias request must not be a duplicate of an auto code). `AliasValidator`, `IdempotencyKey` (kind). Validated by regression of the auto flow + E7–E10/E13 tests. *Note: implemented together with the base create flow in this build, not as a separate retrofit; the impact analysis is what the scenario contributed.* |
| **Ambiguous — "add analytics"** | clarified into: click + referrer + coarse device, daily UTC buckets, eventually consistent, 90-day retention (assumption). Queue-based, never inline. Validated by `AnalyticsIT` (convergence, dedup, DLQ) and `FailureInjectionIT.f5/f6`. |

**Where the full account is.** For each scenario, design §15 has an **As built in this repository** block (decomposition → what delivered it, execution, validation as run, and what was *not* done), and the packages `com.urlshortener.redirect`, `.shortener` and `.analytics` carry the same summary as Javadoc (`package-info.java`). Two things the plan text in §15 describes are not in this repository: load tests (none were run) and a migration rollback script; a raw-event reconciliation test is impossible as written because raw events are not retained. See V-20 in `docs/design-verification-report.md`.

## Risks / trade-offs / validation
See `docs/design-verification-report.md` §2 (design contradictions) and §4 (limitations). Highest residual risks: no load test run;
replica-lag staleness window; per-instance rate limits.

## Assumptions
90-day retention; rate limits and breaker thresholds are starting points; single region; API keys are the only auth; referrer stored as host only.

## Limitations
Listed in the verification report. Human sign-off on high-impact paths (§16) is **pending**.
