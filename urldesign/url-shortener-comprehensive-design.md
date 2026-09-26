# URL Shortener — Comprehensive End-to-End Design Document

**Document version:** 2.8
**Last updated:** 2026-09-25
**Status:** Implementation-ready
**Target runtime:** Java 21 (LTS), Spring Boot 3.3+
**Database:** MySQL 8.4 LTS (migrated from Postgres — see §20.4a)
**Companion artifacts:** `openapi.yaml` (OpenAPI 3.0.3, v1 contract), reference prototype (`url-shortener-service/`, Maven/Spring Boot)

**Change log**

| Version | Date | Change |
|---|---|---|
| 1.0 | 2026-09-25 | Initial design document |
| 1.1 | 2026-09-25 | Added API versioning strategy and OpenAPI specification |
| 2.0 | 2026-09-25 | Full rewrite: exhaustive edge cases, failure-mode catalog, error taxonomy, sequence diagrams, capacity plan, security model, observability, deployment/rollback, DR — promoted to implementation-ready |
| 2.1 | 2026-09-25 | Verified and annotated for Java 21 / Spring Boot 3+ technical compatibility (§20); expanded §9 with the full exception hierarchy, `@RestControllerAdvice` mapping, and field-level validation error shape used by the reference implementation |
| 2.2 | 2026-09-25 | Added §21: `.claude` AI-assistant tooling layer (root + component `CLAUDE.md`, project rules, per-component `SKILLS.md`, cross-cutting plugins), `README.md`, and Mermaid architecture diagrams (`docs/architecture-diagrams.md`) |
| 2.3 | 2026-09-25 | Migrated persistence layer from Postgres to MySQL 8.4 LTS throughout; added §20.4a documenting the required corrections — most critically the `utf8mb4_bin`/`_as_cs` collation requirement on `short_code` for case-sensitivity (a correctness-critical fix, not cosmetic), plus sequence emulation, `JSON` vs `JSONB`, online-DDL syntax, and driver/dialect changes |
| 2.4 | 2026-09-25 | Added §8.2: circuit breaker + retry-with-jitter policy — per-dependency Resilience4j config table (Redis, MySQL read/write, queue publish, auth lookup), full-jitter rationale, the circuit-breaker state machine, and the idempotency-gates-every-retry safety rule; extended §12.1/§12.4 with retry-specific metrics and alerts; extended §20.1's Resilience4j mapping to cover `@Retry` alongside `@CircuitBreaker` |
| 2.6 | 2026-09-25 | Implementation cross-verification: added §23 recording the contradictions found between sections and how the implementation resolved them (idempotency fingerprint, id allocation, `INVALID_*` vs `VALIDATION_FAILED`, SSRF octal bypass, springdoc path collision, replica routing, and others). Full detail: `docs/design-verification-report.md` |
| 2.5 | 2026-09-25 | Added §22: Swagger/OpenAPI UI implementation — `springdoc-openapi` serving the actual `docs/openapi.yaml` as a static resource (not annotation-generated, to avoid a second source of truth), per-environment exposure/security, "Try it out" API-key wiring via the spec's existing security scheme, Maven build-time packaging, a CI check asserting the served doc matches the repo file byte-for-byte, and a forward-looking versioned-UI (groups) plan for when `v2` ships |
| 2.7 | 2026-09-25 | §15: added an **As built in this repository** block to each of the three scenarios (decomposition → what delivered it, execution, validation as run, and what was *not* done), with a reading note. Recorded in §23 / V-20 that the plan text describes load tests, a raw-event reconciliation test and a migration rollback script that the repository does not contain. No guarantee changed; §19 gains one limitation (load tests not run).
| 2.8 | 2026-09-26 | §22: corrected the Swagger URLs to match the implementation. The UI's canonical URL is `/swagger-ui/index.html` (`/swagger-ui.html` redirects to it); the contract is `/v3/api-docs.yaml`; `/v3/api-docs` is **not** served. The §22.3 configuration snippet now shows the implemented springdoc settings (generator relocated to `/v3/generated-api-docs`, V-11) and a URL table was added. No guarantee changed.

---

## Table of Contents

1. Purpose & Scope
2. Requirement Understanding
3. System Architecture
4. Short-Code Generation Design
5. Data Model
6. API Design & Versioning
7. Edge Case Catalog
8. Failure Mode Catalog & Reliability Design
9. Error Handling Taxonomy
10. Security Model
11. Capacity Planning & Scaling
12. Observability
13. Deployment, Rollout & Rollback
14. Disaster Recovery
15. Three Required Scenarios (Greenfield / Brownfield / Ambiguous)
16. AI-Assisted Execution Approach & Traceability
17. Testing Strategy
18. Setup Instructions
19. Limitations, Assumptions & Open Questions
20. Technology Stack & Implementation Mapping (Java 21 / Spring Boot 3+ / MySQL)
21. Repository & AI-Assistant Tooling (`.claude/` layout, README, diagrams)
22. Swagger / OpenAPI UI Implementation
23. Implementation Cross-Verification: Resolved Contradictions & Corrections

---

## 1. Purpose & Scope

This document is the single source of truth for implementing the URL Shortener service. It is written to be handed directly to an engineer (or an AI coding agent under engineer supervision) and implemented without further architectural decisions needing to be made — every ambiguity in the original requirement has been resolved and recorded, every failure mode has a defined behavior, and every API has a machine-readable contract in the companion `openapi.yaml`.

**In scope:** short-code creation (auto + custom alias), redirection, click analytics, expiry, deactivation, versioned management API, reliability under partial failure.

**Out of scope (explicitly deferred, see §19):** multi-tenant user accounts/OAuth, multi-region active-active writes, bulk import, link-in-bio/landing-page features, malware/phishing URL scanning integration (stubbed as an extension point only).

---

## 2. Requirement Understanding

### 2.1 Restated problem

Build a URL shortener: given a long URL, generate a short, unique, hard-to-guess code that redirects to the original URL, with analytics on usage and reliability guarantees (availability, low-latency redirects, no data loss on collisions).

### 2.2 Ambiguities identified and resolutions taken

| Ambiguity | Resolution chosen | Rationale |
|---|---|---|
| Custom aliases allowed? | Yes, optional, user-supplied slug, 3–20 chars, validated | Common real-world feature; low cost to add |
| Expiring links? | Yes, optional `expiresAt`, default none | Needed for reliability/cleanup story |
| Auth required? | API-key based for management API; redirects are public | Realistic scope without full IAM build-out |
| Analytics granularity? | Click count, referrer, timestamp, coarse device/browser from UA | Real pipeline without PII overreach |
| Collision handling? | Base62 counter encoding, not pure random | Deterministic; avoids retry storms at scale |
| Persistence choice? | MySQL (source of truth) + Redis (hot-read cache) | Matches reliability + scale requirement |
| What happens to a redirect if analytics fails? | Redirect always succeeds; analytics write is best-effort async | Redirect latency/availability is the higher-priority SLA |
| What happens if two people race to claim the same custom alias? | DB unique constraint is the sole arbiter; loser gets `409` | Avoids distributed-lock complexity; DB already serializes this |
| Are short codes case-sensitive? | Yes (Base62 requires it: `a` ≠ `A`) | Case-insensitivity would shrink the keyspace by ~5x and complicate lookups |

### 2.3 Normalized engineering problem

Design and implement a service that: (a) creates short codes for long URLs with optional custom alias and expiry; (b) redirects short codes to original URLs with sub-50ms cache-hit latency and sub-150ms p99 cache-miss latency; (c) records click analytics asynchronously so writes never block the redirect path; (d) exposes read APIs for analytics; (e) degrades gracefully — and definedly — under cache, database, or analytics-pipeline failure; (f) supports safe, non-breaking evolution of its API surface over time.

---

## 3. System Architecture

### 3.1 High-level components

```
                         ┌────────────────────┐
                         │   Client / Browser  │
                         └─────────┬───────────┘
                                   │
                        ┌──────────▼───────────┐
                        │   API Gateway / LB    │
                        │  (TLS term, rate      │
                        │   limit, routing)     │
                        └──────────┬───────────┘
                                   │
                 ┌─────────────────┼──────────────────┐
                 │                 │                  │
        ┌────────▼───────┐ ┌───────▼────────┐ ┌───────▼────────┐
        │  Shortener Svc  │ │  Redirect Svc  │ │ Analytics Svc  │
        │  (write path)   │ │  (read path)   │ │ (async reader) │
        │  stateless,     │ │  stateless,    │ │  consumer +    │
        │  horizontally   │ │  horizontally  │ │  read API      │
        │  scaled         │ │  scaled        │ │                │
        └────────┬───────┘ └───────┬────────┘ └───────┬────────┘
                 │                 │                  │
        ┌────────▼─────────────────▼────────┐  ┌──────▼───────┐
        │         Redis Cache (hot codes)     │  │ Message Queue │
        │   cluster mode, replica per shard    │  │ (click events)│
        └────────┬────────────────────────────┘  └──────┬───────┘
                 │                                       │
        ┌────────▼────────┐                     ┌────────▼───────┐
        │ MySQL primary │                     │ Analytics store │
        │ + read replica(s)│                     │ (aggregates)    │
        │ + counter table  │                     └─────────────────┘
        └──────────────────┘
```

### 3.2 Component responsibilities

- **API Gateway / LB** — TLS termination, coarse-grained rate limiting (per-IP, pre-auth), routing `GET /{shortCode}` to the Redirect Service and `/api/v1/*` to the Shortener/Analytics services.
- **Shortener Service (write path)** — validates input, reserves/generates a short code, persists the mapping, writes-through to cache, returns the resource. Stateless; scales horizontally behind the gateway.
- **Redirect Service (read path)** — cache-aside lookup, DB fallback, issues 302, fire-and-forget publish of a click event, never blocks on cache/queue failures (see §8). Stateless.
- **Analytics Service** — consumes click events from the queue, aggregates into daily buckets, exposes the `stats` read API. Runs independently; its downtime does not affect redirects or creation.
- **Redis** — cache-aside for `short_code → long_url (+ expiry, active flag)`. Cluster mode with replicas so a single node loss doesn't blank the cache.
- **MySQL** — source of truth. Primary + at least one read replica; replica absorbs the (rare) DB-fallback read load from Redirect Service during cache misses/outages.
- **Message queue** — durable, at-least-once delivery, decouples redirect latency from analytics durability.

### 3.3 Key design decisions

1. **Base62 counter-based code generation instead of random hashing.** A monotonically increasing, sharded counter encoded in Base62 guarantees uniqueness without collision retries. Trade-off: sequential codes are guessable/enumerable — mitigated by permuting the counter through a fixed-key Feistel or XOR-based bijection before Base62 encoding, so codes look random externally while remaining collision-free internally (see §4).
2. **Redirect path never waits on analytics or on cache writes.** Click events are published to a queue with a short timeout and dropped (with a metric increment) rather than retried in-line if the queue is unreachable.
3. **Cache-aside, not write-behind**, for the URL mapping — reads dominate writes by orders of magnitude; cache-aside with TTL + explicit invalidation on delete/expiry is the simplest correct pattern.
4. **Idempotent creation.** Re-submitting the same `(apiKey, longUrl)` pair within a 24h idempotency window returns the existing short code (`200`, not `201`) instead of minting a duplicate.
5. **Services are stateless and independently scalable.** Shortener, Redirect, and Analytics scale on different axes (write QPS, read QPS, queue depth respectively) — deploying them separately avoids over-provisioning the write path to satisfy read-path load or vice versa.

---

## 4. Short-Code Generation Design

### 4.1 Algorithm

1. Reserve the next value from a sharded 64-bit counter. **MySQL has no native `SEQUENCE` object** (unlike Postgres/Oracle), so this is implemented as a Hibernate `SequenceStyleGenerator` emulated via a dedicated single-row counter table (`hibernate_sequence`/`id_generator`, `pooled-lo` optimizer), which Hibernate does automatically when the configured dialect doesn't support real sequences — see §20.1/§20.4. The per-node pre-allocated block behavior (e.g. claim 10,000 IDs at a time) is unchanged; only the underlying mechanism differs from a Postgres-native sequence.
2. Run the counter value through a reversible bijective permutation (fixed-key XOR-Feistel over the integer space) so sequentially issued IDs do not produce sequentially adjacent codes.
3. Encode the permuted value in Base62 (`[0-9A-Za-z]`), left-padded/truncated to a target length.
4. **Length policy:** start at 6 characters (Base62^6 ≈ 56.8 billion codes). When utilization crosses 80% of the current length's keyspace, new codes roll over to 7 characters. Existing 6-character codes remain valid forever — length is a property of when a code was minted, not a fixed schema constraint.
5. Custom aliases bypass the counter entirely and go straight to the uniqueness check against `short_code`.

### 4.2 Why not random + retry-on-collision

At low collision probability, random generation looks cheap, but as the keyspace fills, expected retries grow unboundedly (birthday-paradox tail), producing unpredictable write-path latency exactly when the system is under the most load. The counter approach makes write latency O(1) regardless of fill level, at the cost of the permutation step (cheap, O(1), no DB round-trip).

### 4.3 Custom alias validation

- Charset: `[A-Za-z0-9_-]`, must start with an alphanumeric character.
- Length: 3–20 characters.
- Reserved-word blocklist: `api`, `admin`, `health`, `metrics`, `static`, `favicon.ico`, `robots.txt`, and any existing route prefix — checked against a maintained list, not hardcoded inline, so it can be extended without a code change.
- Case-sensitive, stored and compared as-is.

---

## 5. Data Model

### 5.1 `urls` table (MySQL — source of truth)

| Column | Type | Constraints / Notes |
|---|---|---|
| `id` | bigint | PK, identity; also the counter source for auto-generated codes |
| `short_code` | varchar(20) | `UNIQUE NOT NULL`, indexed, **collation `utf8mb4_bin`** (case-sensitive — see §20.4a; MySQL's default collations are case-insensitive and would silently break Base62 uniqueness) |
| `long_url` | text | `NOT NULL`, validated absolute URL, max 2048 chars |
| `is_custom_alias` | boolean | `NOT NULL DEFAULT false` (MySQL `TINYINT(1)`) |
| `owner_key_id` | varchar(64) | `NOT NULL`, indexed (for idempotency + per-key listing) |
| `idempotency_fingerprint` | varchar(64) | hash of `(owner_key_id, long_url)`, indexed, used for the 24h idempotent-create window |
| `created_at` | datetime(6) | `NOT NULL DEFAULT CURRENT_TIMESTAMP(6)`; stored/interpreted as UTC by application convention (MySQL `DATETIME` carries no timezone; see note below) |
| `expires_at` | datetime(6) | nullable; `NULL` = never expires; same UTC convention |
| `is_active` | boolean | `NOT NULL DEFAULT true`; false = soft-deleted/deactivated |
| `version` | integer | `NOT NULL DEFAULT 1`; optimistic-concurrency token for concurrent metadata updates |

Indexes: `UNIQUE (short_code)` (on the `utf8mb4_bin` column, above), `INDEX (owner_key_id)`, `INDEX (idempotency_fingerprint)`, composite `INDEX (is_active, expires_at)` (for the expiry sweep job — MySQL has no Postgres-style partial/filtered index, so the sweep job's `WHERE is_active = true` filter is served by a regular composite index with `is_active` as the leading column rather than a `WHERE`-qualified partial index).

**Timezone note:** unlike Postgres's `timestamptz`, MySQL's `DATETIME` stores no timezone information — the application layer (Hibernate `JdbcTimeZone`/connection `serverTimezone=UTC`) is responsible for consistently writing and reading all timestamps as UTC. `TIMESTAMP` (MySQL's other temporal type) does auto-convert to/from the session timezone but is range-limited (max year 2038) and is deliberately not used here for that reason — `DATETIME(6)` with an enforced UTC convention at the application layer is the correct choice, and this convention must be set once in `application.yml`/the datasource config, not left to each developer's session default.

### 5.2 `click_events` (queue payload — not persisted raw beyond a short buffer window)

| Field | Type | Notes |
|---|---|---|
| `short_code` | string | |
| `event_id` | uuid | for consumer-side dedup (at-least-once delivery) |
| `timestamp` | ISO8601 | |
| `referrer` | string, nullable | from `Referer` header, truncated to 512 chars |
| `user_agent` | string, nullable | raw UA, truncated to 512 chars |
| `ip_hash` | string | HMAC-SHA256 of IP with a rotating server-side key; raw IP never stored |

### 5.3 `click_aggregates` (analytics store, read model)

| Field | Type | Notes |
|---|---|---|
| `short_code` | string | part of composite key |
| `date` | date | part of composite key, daily bucket (UTC) |
| `click_count` | bigint | |
| `top_referrers` | JSON | top-20 referrers for the day, `{referrer: count}` — MySQL native `JSON` type (no `JSONB`; MySQL stores `JSON` in an internally optimized binary form automatically, so there is no separate binary-JSON type to choose) |
| `device_breakdown` | JSON | coarse `{mobile, desktop, other}` counts from UA parsing |
| `updated_at` | datetime(6) | last aggregation write (UTC convention, see §5.1's timezone note), exposed so clients can gauge staleness |

### 5.4 Consistency model

`urls` is strongly consistent (MySQL, single-writer-per-row via `id`/`short_code` uniqueness). `click_aggregates` is eventually consistent, typically converging within seconds to low minutes of the queue consumer's batch interval — this lag is surfaced to API clients via `updated_at` rather than hidden (see §6.5).

---

## 6. API Design & Versioning

### 6.1 Versioning strategy

- **Scheme:** URI path versioning — `/api/v1/...` — chosen for discoverability, cache-friendliness, and simple gateway routing over header-based versioning.
- **The redirect endpoint (`GET /{shortCode}`) is unversioned and always will be.** It is embedded in every short link ever issued; versioning it would break previously distributed links. Only the management API is versioned.
- **Non-breaking (no version bump):** adding optional request fields; adding response fields; adding new endpoints.
- **Breaking (requires a new version, e.g. `v2`):** removing/renaming a field; changing a status code's meaning; changing the auth mechanism; changing pagination shape; changing a field's type.
- **Deprecation policy:** a version is supported ≥ 6 months after its successor ships. Deprecated versions return `Deprecation: true` and `Sunset: <date>` headers (RFC 8594). Deprecation is announced at least one full support window in advance.
- **Current version:** `v1`. Full machine-readable contract: `openapi.yaml`.

### 6.2 Endpoint summary

| Method | Path | Auth | Summary |
|---|---|---|---|
| POST | `/api/v1/urls` | API key | Create a short URL |
| GET | `/api/v1/urls/{shortCode}` | API key | Retrieve metadata |
| DELETE | `/api/v1/urls/{shortCode}` | API key | Deactivate |
| GET | `/api/v1/urls/{shortCode}/stats` | API key | Analytics |
| GET | `/{shortCode}` | none (public) | Redirect (unversioned) |

Full request/response schemas, including every field, type, and error response, are defined in `openapi.yaml` — this document does not duplicate them field-by-field; see §9 for the cross-cutting error model.

### 6.3 Create semantics (idempotency)

`POST /api/v1/urls` with a `(apiKey, longUrl)` pair seen within the last 24h returns the existing resource with `200 OK` (not `201 Created`), and a response header `Idempotent-Replay: true`. A different `customAlias` or `expiresAt` on the retry does **not** create a second resource nor update the first — the first request's parameters win, and a `200` with the original resource is returned. This is called out explicitly because it's the one place idempotency and "the client's new input" appear to conflict; the resolution favors idempotency-safety over silently accepting the second request's parameters.

### 6.4 Redirect semantics

- `GET /{shortCode}` → `302 Found` with `Location` header. `302`, not `301`, is used deliberately: `301` is heavily cached by browsers/CDNs, which would make expiry, deactivation, and analytics collection unreliable once a client's browser has cached the permanent redirect.
- Redirect response includes `Cache-Control: no-store` for the same reason.

### 6.5 Analytics staleness disclosure

`GET .../stats` responses include `updatedAt` (last successful aggregation run) so clients can distinguish "zero clicks" from "aggregation hasn't caught up yet." This is a deliberate design choice to avoid the eventual-consistency trade-off being silently misleading.

---

## 7. Edge Case Catalog

Each row: the case, expected behavior, and where it's enforced.

| # | Edge case | Expected behavior | Enforced at |
|---|---|---|---|
| E1 | `longUrl` missing or empty | `400 INVALID_URL` | Shortener Svc, request validation |
| E2 | `longUrl` not a valid absolute URL (missing scheme, malformed) | `400 INVALID_URL` | Shortener Svc |
| E3 | `longUrl` scheme is not `http`/`https` (e.g. `javascript:`, `file:`) | `400 INVALID_URL` | Shortener Svc — explicit allowlist, not a blocklist |
| E4 | `longUrl` resolves to a private/internal/loopback IP (SSRF vector) | `400 INVALID_URL` | Shortener Svc, SSRF guard (see §10.3) |
| E5 | `longUrl` points at the shortener's own domain (chained/self-referential shortening) | `400 INVALID_URL` — rejected to prevent redirect loops | Shortener Svc |
| E6 | `longUrl` exceeds 2048 characters | `400 INVALID_URL` | Shortener Svc |
| E7 | `customAlias` collides with an existing active code | `409 ALIAS_TAKEN` | DB unique constraint, surfaced as typed error |
| E8 | `customAlias` collides with a *soft-deleted* code | `409 ALIAS_TAKEN` — deactivated codes are not recycled automatically (prevents surprising redirect changes for anyone who cached the old mapping); a human/admin path exists to hard-delete and free the alias | Shortener Svc |
| E9 | `customAlias` matches the reserved-word blocklist | `400 ALIAS_RESERVED` | Shortener Svc |
| E10 | `customAlias` fails charset/length pattern | `400 INVALID_ALIAS` | Shortener Svc, regex validation |
| E11 | `expiresAt` is in the past | `400 INVALID_EXPIRY` | Shortener Svc |
| E12 | `expiresAt` is absent | Treated as "never expires" | Shortener Svc default |
| E13 | Two requests race to create the same `customAlias` simultaneously | Exactly one succeeds (`201`); the other gets `409 ALIAS_TAKEN` from the DB unique-constraint violation, not a race in application code | MySQL `UNIQUE` constraint is the sole arbiter |
| E14 | Redirect requested for an expired code | `404 NOT_FOUND` (not a distinct "410 Gone" — see rationale below) | Redirect Svc, checked at read time regardless of cleanup-job lag |
| E15 | Redirect requested for a deactivated code | `404 NOT_FOUND` | Redirect Svc |
| E16 | Redirect requested for a code that never existed | `404 NOT_FOUND` | Redirect Svc |
| E17 | Auto-generated code-space at current length is >80% full | Length auto-increments for new codes (see §4.1); existing shorter codes remain valid | Shortener Svc counter logic |
| E18 | `shortCode` in a path contains characters outside Base62/alias charset | `404 NOT_FOUND` (treated as "no such code" rather than a distinct validation error, to avoid leaking implementation details to the public redirect surface) | Redirect Svc |
| E19 | Same API key spams create requests | Rate-limited per key (see §11.3); `429 RATE_LIMITED` with `Retry-After` | Gateway + Shortener Svc |
| E20 | Click event arrives for a `short_code` that was deleted between click and aggregation | Aggregation still records the click (historical fact) but the `stats` endpoint on a deactivated code returns `404`, not the stale aggregate | Analytics Svc |
| E21 | Duplicate click events (queue at-least-once redelivery) | Deduped by `event_id` at the consumer before aggregation | Analytics Svc |
| E22 | Analytics `from`/`to` query range is invalid (`from` after `to`) | `400 INVALID_RANGE` | Analytics Svc |
| E23 | `DELETE` called twice on the same code | First call: `204`. Second call: `404` (already inactive) — deletion is not idempotent-to-200; the second call correctly reports the resource is gone | Shortener Svc |
| E24 | Extremely long `Referer` or `User-Agent` header on redirect | Truncated to 512 chars before being placed on the queue; never rejected (must not break the redirect for an oversized header) | Redirect Svc |

**Rationale note on E14:** `410 Gone` was considered for expired links to distinguish "used to exist" from "never existed," but rejected — it leaks the existence/lifetime of a code to anyone probing the redirect endpoint, which is an information-disclosure smell on a public, unauthenticated surface. `404` is used uniformly for "not usable right now," and the *reason* (expired vs. deactivated vs. never existed) is only distinguishable via the authenticated metadata endpoint.

---

## 8. Failure Mode Catalog & Reliability Design

Each row: the failure, its blast radius if unmitigated, and the defined mitigated behavior.

| # | Failure | Unmitigated blast radius | Defined behavior (mitigated) |
|---|---|---|---|
| F1 | Redis cluster fully unreachable | Every redirect would fail if Redis were load-bearing | Redirect Svc catches the cache error, falls back to MySQL read-replica directly; a circuit breaker opens after N consecutive Redis failures so the service stops paying the connection-timeout tax per request and goes straight to DB until a health probe succeeds again — one short jittered retry precedes the fallback, per §8.2.1 |
| F2 | Redis returns a stale/incorrect entry after a DB write (cache/DB desync) | Redirect to wrong or deleted URL | Cache entries carry a `version` matching the DB row; on cache hit, Redirect Svc trusts the cached `long_url`/`is_active`/`expires_at` directly (not the version) but the Shortener Svc **invalidates** (not updates) the cache key on any mutation (delete/deactivate), forcing a fresh DB read on next access rather than risking a stale write-through |
| F3 | MySQL primary unreachable (write path) | Create/delete requests fail entirely | Creates fail fast with `503 SERVICE_UNAVAILABLE` + `Retry-After`; this is an honest failure, not silently queued, because code allocation must be strongly consistent — the mutation itself is never auto-retried (§8.2.3) |
| F4 | MySQL primary unreachable, replica still up (read path) | N/A if replica absorbs reads | Redirect Svc's DB-fallback reads target the replica, not the primary, with a short jittered retry budget (§8.2.1) before failing; replica lag is acceptable here since a code, once created, is rarely mutated |
| F5 | Message queue unreachable | If publish blocked, would add latency/failure to every redirect | Publish is fire-and-forget with a short (e.g. 50ms) timeout and zero retries (§8.2.1 — a retry here would trade redirect latency for a best-effort signal); on timeout/error, the event is dropped and a `click_publish_failed` metric increments — the redirect response is never delayed or failed by this |
| F6 | Analytics consumer crashes / falls behind | Growing queue depth, staler `stats` | Queue has bounded retention (e.g. 72h) and consumer auto-scales on queue depth; `stats` responses' `updatedAt` field makes staleness visible rather than silently wrong; queue-depth alert fires well before the retention window is at risk |
| F7 | Cache-miss stampede on a suddenly-viral code (thundering herd) | Many concurrent requests all miss cache and hit DB simultaneously | First miss acquires a short-lived per-key lock (Redis `SET NX PX`) and populates the cache; concurrent misses on the same key wait briefly on the lock or serve a `stale-while-revalidate` cached copy if one exists, rather than all hitting MySQL |
| F8 | Counter/sequence exhaustion or contention at very high write QPS | Serialized writes on a single sequence bottleneck creation throughput | Pre-allocated ID blocks per Shortener Svc instance (e.g. claim 10,000 IDs at a time from the sequence) turn most ID allocation into a local, lock-free operation |
| F9 | Duplicate short-code collision despite counter design (bug, migration error, manual DB edit) | Silent overwrite of an existing mapping | `short_code` `UNIQUE` constraint at the DB level makes this impossible to silently succeed; the insert fails and is surfaced as a `500` with alerting — this is treated as a data-integrity bug to page on, not a normal-path retry |
| F10 | Region/availability-zone outage | Full service outage if single-AZ | Services deployed across ≥2 AZs behind the load balancer; MySQL primary + at least one replica in different AZs so a single-AZ loss triggers replica promotion rather than an outage (see §14) |
| F11 | API-key store/auth service unreachable | Management API entirely blocked; must not take redirects down with it | Auth check only guards management endpoints; the public redirect path has no dependency on the auth service at all |
| F12 | Expiry sweep job fails or is delayed | Expired links might appear "not yet expired" in a stats/metadata call if only relying on the sweep | Redirect Svc and metadata reads check `expires_at` against current time at read time, independent of the sweep job; the sweep job's only job is reclaiming storage/soft-deleting for query efficiency, not enforcing correctness |
| F13 | Clock skew between service instances | Inconsistent expiry evaluation across nodes | All expiry comparisons use DB server time (`now()` in the query) for creation defaults and rely on NTP-synced host clocks elsewhere; expiry checks are tolerant to sub-second skew by design (no sub-second expiry semantics are supported) |

### 8.1 Redirect-path reliability summary (the core SLA)

The redirect path's only hard dependencies are: the load balancer and at least one of {Redis, MySQL replica}. It has **no** hard dependency on the message queue, the analytics store, or the auth/API-key service. This is the central reliability design decision of the system and is enforced by the fire-and-forget/circuit-breaker patterns in F1 and F5 above.

### 8.2 Circuit Breaker & Retry-with-Jitter Policy

F1–F6's mitigations rely on two complementary resilience patterns applied consistently across every outbound dependency call, not implemented ad hoc per call site. **Retry-with-jitter** absorbs brief, transient blips (a single dropped packet, a momentary GC pause on the dependency) without amplifying load. **Circuit breaking** protects the system once a dependency is genuinely degraded, so the service stops spending latency/threads retrying a call that's very unlikely to succeed, and fails fast instead. Retry always sits *inside* the circuit breaker (attempted only while the breaker is `CLOSED`/`HALF_OPEN`), never the reverse — retrying against an already-`OPEN` breaker would defeat the point of tripping it.

**Why jitter, specifically:** plain exponential backoff, applied uniformly across many concurrent redirect/create requests that all hit a timeout at the same moment (e.g. a brief Redis blip during a traffic spike), causes every retry to bunch up at the same next instant — a self-inflicted thundering herd against a dependency that was only briefly struggling, potentially turning a transient blip into a real outage. Jitter (randomizing each retry's delay within a window) spreads the retries out in time so they don't all land together. This project uses **full jitter** (`delay = random(0, min(cap, base * 2^attempt))`), not equal-jitter or decorrelated-jitter, as the default — full jitter has the strongest de-synchronization effect and is the safest default absent a specific reason to prefer one of the alternatives for a given call site.

#### 8.2.1 Per-dependency policy

| Dependency / call | Circuit breaker | Retry + jitter | Rationale |
|---|---|---|---|
| Redis (Redirect Svc cache-aside read, F1) | Sliding window (count-based, last 20 calls), opens at ≥50% failure rate, 5s wait-in-open before `HALF_OPEN` probe, 3 trial calls in `HALF_OPEN` to close | **1 retry**, base 10ms, cap 50ms, full jitter — deliberately tiny, since the fallback (MySQL replica read) is already fast and safe; a slow/long retry against a struggling cache is worse than falling back immediately | The redirect-path latency budget (§1's sub-50ms/sub-150ms targets) has no room for a meaningfully long retry; one very short retry catches a single dropped packet without materially affecting p99 |
| Redis (Shortener Svc write-through on create) | Same breaker instance/config as above (shared per-instance breaker per dependency, not per call site) | **1 retry**, same as above | A missed write-through cache populate is not correctness-critical (F2's invalidate-not-update pattern and cache-aside's miss-then-populate cover it) — not worth a longer retry budget |
| MySQL (Redirect Svc fallback read, F4) | Sliding window (count-based, last 20 calls), opens at ≥50% failure rate, 10s wait-in-open, 3 trial calls | **2 retries**, base 25ms, cap 200ms, full jitter | This is the redirect path's last line of defense (§8.1) — worth a slightly larger retry budget than Redis before giving up, but still bounded well inside the p99 latency target |
| MySQL (Shortener Svc write path, F3) | Sliding window (count-based, last 10 calls — lower volume than the read path), opens at ≥50% failure rate, 15s wait-in-open, 2 trial calls | **1 retry only for genuinely idempotent-safe reads** (e.g. the idempotency-fingerprint lookup before insert); **zero retries on the `INSERT`/`UPDATE`/`DELETE` itself** — a retried write is not safe to blindly re-attempt without re-checking idempotency state first (§6.3), so a failed mutation surfaces immediately as `503`/`INTERNAL_ERROR` rather than being silently retried | Never auto-retry a non-idempotent write — this is a correctness rule, not a tuning choice; see §8.2.3 |
| Message queue publish (Redirect Svc, F5) | Sliding window (count-based, last 20 calls), opens at ≥50% failure rate, 5s wait-in-open, 3 trial calls | **0 retries** — a single attempt with a short (50ms) timeout, then drop | Retrying here would directly trade off against §8.1's redirect-latency guarantee for a best-effort analytics signal; F5's design already accepts the occasional dropped event, so a retry buys nothing worth the latency risk |
| Message queue consume/ack (Analytics Svc, F6) | Not circuit-broken (a consumer has no caller to fail fast to — it simply keeps consuming) | **Broker-native redelivery** (at-least-once) handles transient consumer-side failures; a message that fails processing after the broker's configured redelivery limit routes to the dead-letter queue (per `analytics-service/SKILLS.md`), not an application-level retry loop | The retry semantics here are the queue's redelivery mechanism, not Resilience4j's `Retry` module — different failure shape (async consumer, not a synchronous caller awaiting a response) |
| Auth/API-key lookup (Security-Auth, F11) | Sliding window (count-based, last 20 calls), opens at ≥50% failure rate, 10s wait-in-open, 3 trial calls | **1 retry**, base 20ms, cap 100ms, full jitter | Only guards the management API (§8.1) — a slightly more generous retry budget is acceptable here since it never touches the redirect path's latency SLA |
| SSRF hostname resolution (Security-Auth, create path) | Not circuit-broken — a resolution failure is treated as "cannot verify safety" and rejected (`.claude/rules/security-rules.md`), not retried past the DNS client's own default timeout | **No application-level retry** — retrying a security check that failed open would be exactly backwards; if DNS resolution is flaky, that surfaces as a `503`, not a bypassed check | See §10.3 — this call's failure mode is "reject," not "retry" |

#### 8.2.2 Circuit breaker state machine (all breakers)

Standard three-state model (Resilience4j default, §20.1):

- **`CLOSED`** — calls flow normally; failures are recorded against the sliding window.
- **`OPEN`** — calls fail immediately (`CallNotPermittedException`, mapped to `503 SERVICE_UNAVAILABLE` per §9.3) without attempting the dependency at all, for the configured wait-in-open duration.
- **`HALF_OPEN`** — after the wait duration, a small number of trial calls are let through; if they succeed at or above the configured threshold, the breaker returns to `CLOSED`; if not, it reopens and the wait timer restarts.

Every breaker above is registered as a named, independently configured instance (`redis-cache`, `mysql-read`, `mysql-write`, `mq-publish`, `auth-lookup`) — never one shared breaker across unrelated dependencies, since a struggling queue must not trip the Redis breaker or vice versa. Breaker state (`cache_circuit_breaker_state` and its siblings) is exported as a Micrometer gauge per §12.1, one time series per named instance.

#### 8.2.3 Retry safety rule — idempotency gates every retry

**A call is only retried automatically if it is safe to execute more than once.** Concretely: all `GET`/`SELECT` reads are retry-safe by nature. `POST /api/v1/urls` is retry-safe *at the HTTP-client level* only because §6.3's idempotency-fingerprint mechanism makes a duplicate submission a no-op server-side — but the retry policy in §8.2.1 still applies zero automatic retries to the underlying `INSERT` itself, so idempotency-safety is enforced at the request layer, not relied upon to paper over a blind retry at the persistence layer. `DELETE` is naturally idempotent (§7's E23: a second `DELETE` correctly returns `404`, not a duplicate side effect) but is still not auto-retried at the persistence layer for the same reason. This rule is deliberately conservative — the cost of an occasional avoidable `503` is much lower than the cost of a retry policy silently double-applying a write somewhere it wasn't proven safe.

#### 8.2.4 Client-facing retry guidance (unchanged, cross-referenced)

§9.4 already specifies client-side backoff-with-jitter guidance for `429`/`500`/`503`/`504` responses — §8.2's policy is the *server's own* internal resilience layer (calls the service makes to its dependencies) and is complementary to, not a substitute for, that client guidance: a client retrying a `503` it received is retrying on top of a request the server itself may already have retried once against MySQL/Redis before giving up and returning that `503`.

---

## 9. Error Handling Taxonomy

### 9.1 Error response shape (all endpoints)

```json
{
  "code": "INVALID_URL",
  "message": "The provided longUrl is not a valid absolute URL.",
  "requestId": "a1b2c3d4-...",
  "timestamp": "2026-09-25T10:00:00Z",
  "path": "/api/v1/urls",
  "fieldErrors": null
}
```

`requestId` is always present and correlates to server-side logs/traces — every error response must be debuggable from the client-reported ID alone without asking the client for more context. `fieldErrors` is `null`/omitted except for `VALIDATION_FAILED` (§9.2a), where it carries one entry per invalid field.

### 9.2 Status code → code mapping

| HTTP status | `code` | Meaning | Retryable by client? |
|---|---|---|---|
| 400 | `VALIDATION_FAILED` | One or more request fields fail bean-validation constraints (type, size, pattern, required) before any business rule runs | No — fix `fieldErrors` |
| 400 | `INVALID_URL` | Malformed, disallowed-scheme, self-referential, or SSRF-blocked URL | No — fix input |
| 400 | `INVALID_ALIAS` | Alias fails charset/length pattern | No |
| 400 | `ALIAS_RESERVED` | Alias on the reserved-word blocklist | No |
| 400 | `INVALID_EXPIRY` | `expiresAt` in the past or unparseable | No |
| 400 | `INVALID_RANGE` | Analytics `from`/`to` malformed or inverted | No |
| 400 | `MALFORMED_JSON` | Request body is not valid JSON, or a field has the wrong JSON type (e.g. `expiresAt` sent as a number) | No |
| 401 | `UNAUTHORIZED` | Missing/invalid API key | No — fix credentials |
| 403 | `FORBIDDEN` | API key valid but not authorized for this resource (e.g. deleting another key's URL) | No |
| 404 | `NOT_FOUND` | Code doesn't exist, is expired, or is deactivated (see E14–E16) | No |
| 404 | `NO_HANDLER` | Request path matches no route (Spring's `NoHandlerFoundException`, with `spring.mvc.throw-exception-if-no-handler-found=true` and static-resource handling disabled — see §20.4) | No |
| 405 | `METHOD_NOT_ALLOWED` | Verb not supported on an otherwise-valid path (e.g. `PATCH /api/v1/urls/{code}`) | No |
| 406 | `NOT_ACCEPTABLE` | `Accept` header requests a representation the API doesn't produce | No — request `application/json` |
| 409 | `ALIAS_TAKEN` | Custom alias already in use (active or soft-deleted); also the mapped outcome of a `DataIntegrityViolationException` on the `short_code` unique constraint (E13's race is resolved at the DB, not the app, layer) | No — choose another alias |
| 409 | `RESOURCE_MODIFIED` | Optimistic-lock failure — `version` column mismatch (`OptimisticLockingFailureException`) on a concurrent metadata update | Yes — re-fetch and retry |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | `Content-Type` other than `application/json` on a body-bearing request | No |
| 429 | `RATE_LIMITED` | Per-key or per-IP rate limit exceeded | Yes — after `Retry-After` |
| 500 | `INTERNAL_ERROR` | Unexpected server error (uncaught exception, mapping bug); paged/alerted internally | Yes, with backoff |
| 503 | `SERVICE_UNAVAILABLE` | A required dependency (e.g. MySQL primary for writes) is down, or the Redis circuit breaker (F1) is open and the DB fallback also fails | Yes, with backoff |
| 504 | `UPSTREAM_TIMEOUT` | A dependency call (DB, cache, queue publish) exceeded its configured timeout — distinguished from `503` so clients can tell "down" from "slow" | Yes, with backoff |

### 9.2a Field-level validation error shape

`VALIDATION_FAILED` populates `fieldErrors` as an array so a single request can report every violation at once, not one-at-a-time:

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Request validation failed.",
  "requestId": "a1b2c3d4-...",
  "timestamp": "2026-09-25T10:00:00Z",
  "path": "/api/v1/urls",
  "fieldErrors": [
    { "field": "longUrl", "rejectedValue": "", "constraint": "NotBlank", "message": "must not be blank" },
    { "field": "customAlias", "rejectedValue": "-ab", "constraint": "Pattern", "message": "must match ^[A-Za-z0-9][A-Za-z0-9_-]{2,19}$" }
  ]
}
```

### 9.3 Exception hierarchy → HTTP mapping (implementation contract)

The reference implementation defines a small sealed exception hierarchy so every thrown type maps to exactly one row of §9.2, with no ad-hoc `ResponseEntity` construction scattered through service code:

```
UrlShortenerException (abstract, carries an ErrorCode enum + HTTP status)
 ├─ InvalidUrlException          → 400 INVALID_URL
 ├─ InvalidAliasException        → 400 INVALID_ALIAS
 ├─ AliasReservedException       → 400 ALIAS_RESERVED
 ├─ InvalidExpiryException       → 400 INVALID_EXPIRY
 ├─ InvalidRangeException        → 400 INVALID_RANGE
 ├─ UnauthorizedException        → 401 UNAUTHORIZED
 ├─ ForbiddenException           → 403 FORBIDDEN
 ├─ ResourceNotFoundException    → 404 NOT_FOUND
 ├─ AliasTakenException          → 409 ALIAS_TAKEN
 ├─ ResourceModifiedException    → 409 RESOURCE_MODIFIED
 ├─ RateLimitedException         → 429 RATE_LIMITED (carries retryAfterSeconds)
 ├─ UpstreamTimeoutException     → 504 UPSTREAM_TIMEOUT
 └─ ServiceUnavailableException  → 503 SERVICE_UNAVAILABLE
```

A single `@RestControllerAdvice` (`GlobalExceptionHandler`) is the only place HTTP status codes are chosen. It handles, in addition to the hierarchy above:

| Spring/Jakarta exception | Mapped to |
|---|---|
| `MethodArgumentNotValidException` (`@Valid` body failures) | `VALIDATION_FAILED`, `fieldErrors` populated from `BindingResult` |
| `ConstraintViolationException` (`@Validated` param/path failures) | `VALIDATION_FAILED` |
| `HttpMessageNotReadableException` | `MALFORMED_JSON` |
| `HttpMediaTypeNotSupportedException` | `UNSUPPORTED_MEDIA_TYPE` |
| `HttpMediaTypeNotAcceptableException` | `NOT_ACCEPTABLE` |
| `HttpRequestMethodNotSupportedException` | `METHOD_NOT_ALLOWED` |
| `NoHandlerFoundException` | `NO_HANDLER` |
| `DataIntegrityViolationException` (unique-constraint violation on `short_code`) | `ALIAS_TAKEN` — this is how E13's create-race is actually resolved: both requests reach the DB, the loser's insert throws, the advice translates it rather than the app pre-checking-then-inserting (check-then-act would itself race) |
| `OptimisticLockingFailureException` | `RESOURCE_MODIFIED` |
| `CallNotPermittedException` (Resilience4j circuit breaker open, §20.3) | `SERVICE_UNAVAILABLE` |
| `TimeoutException` / `QueryTimeoutException` | `UPSTREAM_TIMEOUT` |
| `Exception` (catch-all) | `INTERNAL_ERROR`, full stack trace logged server-side with the `requestId`, response body never includes it |

### 9.4 Client guidance embedded in the contract

- Clients must treat all `4xx` except `429` as non-retryable without changing the request.
- Clients must implement exponential backoff with jitter for `429`, `500`, `503`, and `504`.
- `500`/`503`/`504` responses never include internal error details (stack traces, SQL, exception class names) in the body — only `requestId`, to avoid leaking implementation details; full detail is server-side-logged (SLF4J/Logback, structured JSON) and looked up via `requestId`, which is also propagated as the request's trace ID (§12.3/§20.5).
- `requestId` is generated at the gateway (or, if absent, at the first Spring service to see the request) and placed in the MDC for the lifetime of the request so every log line — including ones emitted before the exception is thrown — carries it.

---

## 10. Security Model

### 10.1 AuthN/AuthZ

- Management API requires `X-API-Key`. Keys are opaque tokens, hashed at rest (never stored plaintext), rotated by re-issuing and revoking the old key — no in-place key value changes.
- Each key owns the resources it creates (`owner_key_id`); `DELETE` and metadata reads on a resource owned by a different key return `403 FORBIDDEN`, not `404` (deliberately distinguishable from "not found" here since this is an authenticated endpoint, unlike the public redirect surface).
- The redirect endpoint is intentionally unauthenticated — it is a public link-resolution service by design.

### 10.2 Transport & storage

- TLS everywhere in transit; terminated at the gateway, re-encrypted or run over a private network to internal services.
- `ip_hash` uses HMAC-SHA256 with a server-side key rotated on a fixed schedule; raw client IPs are never persisted, only used transiently to compute the hash.
- API keys hashed with a slow hash (e.g. Argon2/bcrypt) at rest.

### 10.3 SSRF / open-redirect protections

- Scheme allowlist: only `http`/`https` accepted for `longUrl`.
- Hostname resolved at creation time; rejected if it resolves to a private/loopback/link-local range (RFC 1918, `127.0.0.0/8`, `169.254.0.0/16`, `::1`, etc.) — this is a best-effort creation-time check, not a redirect-time check, since DNS can change after creation; it substantially raises the bar without claiming to be airtight (documented as a limitation, not oversold as complete protection — see §19).
- Self-referential rejection (E5) prevents redirect loops through the shortener's own domain.

### 10.4 Abuse & rate limiting

- Per-IP rate limit at the gateway (coarse, pre-auth) protects against unauthenticated flooding of the create endpoint's validation logic.
- Per-API-key rate limit at the Shortener Svc (finer-grained, post-auth) protects backend capacity.
- Redirect endpoint has a much higher per-IP limit (it's meant to be hit at scale by legitimate traffic) but still capped to blunt basic denial-of-service attempts.

### 10.5 Input validation summary

Every field in every request is validated against the schema in `openapi.yaml` (type, length, pattern) before any business logic runs — schema validation is a request-entry gate, not scattered through handler code.

---

## 11. Capacity Planning & Scaling

### 11.1 Assumed baseline load (documented assumption, revisit with real traffic data)

- Read:write ratio ≈ 100:1 (typical for link shorteners).
- Target: 5,000 redirects/sec sustained, bursting to 20,000/sec; 50 creates/sec sustained.

### 11.2 Scaling axes

| Component | Scales on | Mechanism |
|---|---|---|
| Redirect Svc | Read QPS | Horizontal pod autoscaling on CPU + request rate |
| Shortener Svc | Write QPS | Horizontal autoscaling on CPU; ID pre-allocation (F8) keeps this from bottlenecking on the DB sequence |
| Redis | Cache hit-rate / memory | Cluster mode, shard by `short_code` hash; add shards before eviction rate crosses a threshold |
| MySQL | Read replica lag / connection saturation | Add read replicas for Redirect Svc's DB-fallback path; primary only takes writes, which are low-QPS by comparison |
| Analytics consumer | Queue depth | Autoscale consumer group size on queue depth/lag |

### 11.3 Rate limit defaults (tunable, not hardcoded as business logic)

- Per-API-key create: 100 requests/min, burst 20.
- Per-IP redirect: 1,000 requests/min, burst 200.
- Limits are config, not code, so they can be tuned per environment/customer tier without a deploy.

---

## 12. Observability

### 12.1 Metrics (minimum required set)

- `redirect_latency_ms` (histogram, tagged by cache hit/miss)
- `redirect_cache_hit_rate`
- `create_latency_ms`
- `click_publish_failed_total` (F5)
- `cache_circuit_breaker_state` (F1) and its siblings — one gauge per named breaker instance (`mysql-read`, `mysql-write`, `mq-publish`, `auth-lookup`), per §8.2.2
- `resilience4j_retry_calls_total` tagged `kind={successful_without_retry, successful_with_retry, failed_with_retry, failed_without_retry}`, per named retry instance (§8.2) — distinguishes "worked on the first try" from "only worked after a retry" from "exhausted retries," which `cache_circuit_breaker_state` alone can't tell you
- `queue_depth` / `consumer_lag` (F6)
- `alias_collision_total` (E7/E13, distinguishes normal contention from abuse patterns)
- Error rate by `code` (§9.2), by endpoint

### 12.2 Logging

- Every request logs `requestId`, endpoint, status, latency, and (for errors) the error `code`.
- No raw IPs, full URLs with potential PII in query strings, or API keys in logs — `long_url` is logged truncated/redacted if it contains a query string matching common PII-bearing patterns (best-effort, documented as a limitation).

### 12.3 Tracing

- Distributed tracing across gateway → service → cache/DB/queue for the redirect and create paths, so a `requestId` from an error response can be looked up end-to-end.

### 12.4 Alerting (minimum required set)

- Redirect p99 latency above SLA threshold.
- Any named circuit breaker open for > N minutes (§8.2.2) — not just the cache breaker; `mysql-read`, `mq-publish`, and `auth-lookup` each alert independently.
- Elevated `failed_with_retry`/`failed_without_retry` rate on any named retry instance (§8.2) — a rising retry-exhaustion rate is typically the leading indicator *before* a breaker trips, so this alert should fire earlier than the breaker-open alert above.
- Queue depth approaching retention-window risk (F6).
- MySQL replica lag above threshold.
- Elevated `5xx` rate on any service.

---

## 13. Deployment, Rollout & Rollback

- Services are independently deployable (Shortener, Redirect, Analytics) — a bad deploy of one does not require rolling back the others.
- Redirect Svc, being the highest-availability-requirement component, deploys via canary (small % of traffic) with automatic rollback on elevated error rate or latency regression before proceeding to full rollout.
- Database migrations follow expand/contract: add new columns/tables as nullable/additive first, deploy code that can handle both old and new shape, backfill, then (in a later release) drop old columns — never a single migration that is both schema-breaking and code-breaking in the same deploy.
- Feature flags gate new API fields so they can be enabled/disabled without a redeploy if an issue is found post-launch.

---

## 14. Disaster Recovery

| Scenario | RPO | RTO | Mechanism |
|---|---|---|---|
| MySQL primary AZ loss | < 1 min of writes | Minutes (automated failover) | Replica promotion, managed by the DB platform's automated failover |
| Redis cluster loss | 0 (cache is rebuildable from MySQL) | Seconds–minutes (cold cache, elevated DB load until refilled) | Redirect Svc's MySQL-fallback path (F1) keeps serving during cache rebuild |
| Full region loss | Depends on backup cadence (target: < 15 min) | Hours (out of scope for the prototype; documented as a future multi-region requirement) | Cross-region MySQL backups/snapshots; full active-active is explicitly deferred (see §19) |
| Queue data loss (bounded retention exceeded) | Up to the retention window's worth of click events | N/A (analytics gap, not a redirect-availability incident) | Accepted trade-off per F6; the redirect/create paths are unaffected |

---

## 15. Three Required Scenarios

> **How to read this section.** The bullets directly under each scenario are the design-stage plan and the process as reported. Each scenario also has an **As built in this repository** block recording only what the repository evidences (files, tests, traceability-log entries) and stating plainly what the plan called for but has not been done. Where the two differ, the *as built* block is authoritative; the differences are logged as V-20 in §23 and `docs/design-verification-report.md`. Tests are named so they can be found with `grep`; the coverage script `scripts/verify-design-coverage.py` ties the E/F rows to them.

### 15.1 Greenfield — building the redirect service from scratch

- **Decomposition:** API contract → data model → cache-aside read path → failure-mode handling (F1, F7) → error handling → tests.
- **AI-assisted execution:** AI scaffolded the service boilerplate and the initial cache-aside logic. The engineer reviewed and corrected a cache-miss race condition in AI's first draft — concurrent misses on the same key both hit the DB and both wrote to cache — resolved with the `SET NX PX` lock guard now documented as F7.
- **Validation:** unit tests for hit/miss/expired/not-found; load test specifically simulating the cache-miss stampede scenario to confirm the guard works under concurrency.

#### As built in this repository

**Decomposition → what delivered each step**

| Step | Delivered as |
|---|---|
| API contract | `GET /{shortCode}` in `docs/openapi.yaml` (unversioned, rule A2) → generated `RedirectApi`, implemented by `RedirectController` |
| Data model | `V1__urls.sql`: `short_code VARCHAR(20)` with `utf8mb4_bin` and `uk_urls_short_code` (case-sensitive; the database decides uniqueness) |
| Cache-aside read path | `UrlLookupService.resolve` → `RedisUrlCache`. A miss reads MySQL through `UrlReader` and repopulates Redis; the entry TTL is capped at time-to-expiry; a corrupt entry is treated as a miss and repaired. Outcomes are the sealed `CacheLookupResult` |
| F1, Redis unreachable | Breaker `redis-cache` with one jittered retry inside it; `REJECT_COMMANDS` and a 2 s reconnect cap so a dead Redis costs nothing per request (V-18); the request is served from MySQL |
| F7, stampede | Per-key Redis lock (`SET NX PX`, 30 s TTL). Losers wait a bounded time, are served a stale copy when one exists, or read MySQL themselves |
| Error handling | One uniform `404` for unknown, expired, deactivated and malformed codes (E14–E16, E18). Expiry is evaluated at read time (F12), never trusted to the TTL or the sweep |

**Execution** (as recorded in `docs/ai-traceability-log.md`). `[generated]` `RedisUrlCache` and `UrlLookupService` (sealed `CacheLookupResult`, stampede guard), accepted after review. Defects found by *running* the code and then fixed: a lost cache invalidation while Redis is down (V-10, now retried by `BestEffortCache`) and Lettuce queuing commands while disconnected (V-18). The plan above mentions an AI-drafted cache-miss race that the engineer corrected; that episode is **not** in the traceability log, so it is not claimed here. The guard it describes exists and is tested (below). This is the redirect hot path: every change to it needs explicit engineer sign-off (§16, rule `R2`). **Sign-off is pending.**

**Validation as run** (`./mvnw verify`, real MySQL, Redis and RabbitMQ; Redis faults injected with Toxiproxy):

- `RedirectIT` (16 tests): `302` never `301` with `Cache-Control: no-store`; unknown / expired / deactivated / malformed codes; cache-aside miss then hit; an entry never outlives the link's expiry; a corrupt entry acts as a miss; case-sensitive codes; `HEAD`; oversized headers do not break the redirect.
- `FailureInjectionIT`, F1: with Redis cut, and separately with Redis connected but silent (black-holed), redirects still return `302` from MySQL within the timeout budget, the `redis-cache` breaker opens, and recovery closes it and repopulates. The retry sits inside the breaker, which counts one failure per call, not per attempt. F2: a `DELETE` while Redis is down still succeeds (the database is the truth) and the failed invalidation is retried until Redis is back; `RedirectIT` shows a `DELETE` invalidates the entry and its stale copy.
- `FailureInjectionIT`, F7 (4 tests): 60 simultaneous requests for one uncached code all return `302` with at most 3 MySQL reads; a stale copy is served with zero MySQL reads while another instance holds the lock; a lock holder that never finishes cannot hang requests (they wait under 3 s, not the 30 s lock TTL, then read MySQL); waiters pick up the value the lock holder populates.
- Live: `curl` and the Postman/Newman collection (`302`, `Location`, `no-store`, `404` after deactivation).

**Not done.** The load test the plan calls for (stampede at target QPS). The F7 tests use 60 concurrent requests, which shows the guard coalesces reads, not what throughput or p99 latency it achieves; the §2.3 latency targets are unverified (README limitation L1).

### 15.2 Brownfield — adding custom aliases to an existing create endpoint

- **Codebase reasoning:** impacted areas identified — request schema, uniqueness constraint, validation layer, reserved-word blocklist, and the idempotency check (must not treat a custom-alias request as a duplicate of an auto-generated code for the same `longUrl`).
- **AI-assisted execution:** AI proposed the alias-validation regex and the DB migration. The engineer tightened the regex (AI's version allowed a leading hyphen, which broke path parsing downstream) and added the missing migration rollback script, which AI's draft omitted.
- **Validation:** regression tests on the existing auto-generated flow; new tests for E7–E10, E13 from the edge case catalog.

#### As built in this repository

**Codebase reasoning → what each impacted area became**

| Impacted area | Delivered as |
|---|---|
| Request schema | Optional `customAlias` and `expiresAt` on the create request in `docs/openapi.yaml`; optional request fields are a non-breaking change (rule A3), so `/api/v1` stays |
| Uniqueness | `uk_urls_short_code` is the sole arbiter (E7, E13). The violated constraint's *name* says which rule fired (`UniqueConstraint`). Aliases and generated codes share one namespace; an alias that equals a future generated code makes the generator skip to the next id (V-5) |
| Validation layer | `AliasValidator`: `[A-Za-z0-9][A-Za-z0-9_-]{2,19}` (3–20 characters, the first alphanumeric, so a leading hyphen is refused). The reserved-word check runs first and is case-insensitive (`ReservedAliases`, `reserved-aliases.txt`). `ExpiryValidator` for `expiresAt` |
| Reserved-word blocklist | `reserved-aliases.txt`, matched case-insensitively so `Admin` cannot bypass `admin` |
| Idempotency | `IdempotencyKey` fingerprint = SHA-256(owner, **kind**, url) with kind ∈ {auto, custom}, stored in the unique nullable `idempotency_fingerprint`. A custom-alias request is therefore never a duplicate of an auto-generated code for the same `longUrl` (V-2, V-3) |

**Execution.** Alias support was written together with the base create flow in this build; it was **not** retrofitted onto a pre-existing endpoint (`docs/engineering-summary.md` says the same). What the scenario contributed is the impact analysis above, which is why the idempotency interaction was designed up front (and the two design contradictions it exposed, V-2 and V-3, were resolved before code) instead of discovered later. The leading-hyphen rule is enforced in code and tested, but the traceability log does not record it as a discrete `[edited]` step. The plan says a missing migration rollback script was added: **no rollback script exists** in the repository. `V1`–`V3` are expand-only; the policy relied on is expand/contract (§13, rule `R2` for any migration), under which the previous application version keeps working against the newer schema.

**Validation as run:**

- New cases: `CreateUrlIT` covers E7 (created case-sensitively; a second use is `409 ALIAS_TAKEN`; a different case is free), E8 (a deactivated alias is still `409`, never recycled), E9 / E10 (reserved words, bad charset, length, leading hyphen), E11 / E12 (expiry), and E13 (many simultaneous requests for one alias produce exactly one `201`, decided by the DB constraint). `AliasValidatorTest` (45 cases), `UniqueConstraintTest`, `CollisionIT`, `IdAllocationIT` and `MigrationIT` cover the rule and the constraint against real MySQL.
- Idempotency interaction: `CreateUrlIT` proves a custom alias is not a duplicate of an auto code (and the reverse), replay is per API key, a retry with a different alias returns the original, and a deactivated link can be shortened again.
- Auto-generated flow: its tests (`201` with a 6-character Base62 code, codes unique across many creations, replay, uniqueness under concurrency) run in the same suite as the alias tests and pass together. `OpenApiContractTest` checks the spec and the DTOs still agree.
- Live: the Postman collection creates aliases of exactly 3 and 20 characters and two aliases that differ only by case, and checks each redirects to its own target.

**Not done.** There is no separate before/after regression run (the two flows were built together, so "regression" here means the auto-flow tests passing alongside the alias tests), and no migration rollback script.

### 15.3 Ambiguous — "add analytics"

- **Requirement clarification:** no granularity, retention, or consistency model was specified in the original ask. Normalized into: click count + referrer + coarse device breakdown, daily aggregation, eventually consistent, 90-day retention (documented assumption, flagged for stakeholder confirmation — see §19).
- **AI-assisted execution:** AI drafted the aggregation job and the query API. The engineer rejected AI's initial suggestion to aggregate synchronously inline in the redirect path — this directly violates the "redirect never blocks on analytics" reliability requirement (§8.1) — and redirected the design to the queue-based async approach now documented in §3 and F5/F6.
- **Validation:** isolated load test confirming redirect latency is unaffected by analytics/queue load; reconciliation test confirming aggregate counts match raw click-event counts within the expected lag window (accounting for F6's dedup-by-`event_id`).

#### As built in this repository

**Requirement clarification → decision → where it lives**

| Ambiguity in "add analytics" | Decision | Where |
|---|---|---|
| What is counted | Click count, referrer, coarse device | `ClickEventFactory` builds the event on the redirect path |
| Referrer detail | Host only, lowercased; `(direct)` when absent, `(unknown)` when unparseable. Paths and queries can carry tokens, so they are dropped | `ReferrerNormalizer` |
| Device detail | Three classes: `mobile`, `desktop`, `other` (bots and command-line clients are `other`) | `DeviceClassifier`, behind `app.features.stats-device-breakdown` |
| Personal data | No raw IP: an HMAC `ip_hash`; `Referer` / `User-Agent` truncated to 512 characters, never rejected (E24) | `IpHasher`, `ClickEventFactory` |
| Granularity | Daily UTC buckets, `(short_code, date)` | `click_aggregates` (V3), `AggregationService` |
| Consistency | Eventually consistent. The response carries `updatedAt` (last aggregation or a "queue drained" heartbeat), so "caught up" is distinguishable from "idle" (§6.5) | `AggregationHeartbeat` (writes it), `StatsService` (serves it) |
| Duplicates | Delivery is at-least-once, so events are deduplicated by `event_id`; the dedup marker and the aggregate update commit in one transaction | `AggregationService` (`processed_events`) |
| Retention | 90 days. An **assumption**, flagged for stakeholder confirmation (§19); enforced by the retention job and again when stats are read | `RetentionJob`, `StatsService` |
| Failure isolation | Analytics never blocks or fails a redirect | see Execution |

**Execution.** Queue-based and asynchronous. `ClickPublisher` is fire-and-forget: it never blocks and never throws, bounds in-flight sends with a semaphore, uses breaker `mq-publish` with **no retry** (a retry would trade redirect latency for a best-effort signal), and on any failure drops the event and increments `click_publish_failed` (F5). The queue is a RabbitMQ quorum queue with a delivery limit and a dead-letter exchange (F6); `ClickConsumer` acknowledges only after the aggregation transaction commits. Recorded in the traceability log: `[rejected]` a synchronous analytics write in the redirect path, because it violates §8.1; `[generated]` the analytics pipeline, verified on real RabbitMQ including the dead-letter path.

**Validation as run:**

- `AnalyticsIT` (11 tests, real RabbitMQ and MySQL): create → redirect → click queued → aggregated → stats reflect it (converges); `updatedAt` freshness; the queued event has an `ip_hash` and no raw IP; a click delivered three times is counted once (E21); clicks before deactivation are kept but stats for a deactivated code are `404` (E20); invalid and valid ranges (E22); data older than 90 days is never served; `abc` and `ABC` have separate analytics; an unparseable message is dead-lettered and the consumer keeps working, and a message that keeps failing is redelivered up to the limit and then dead-lettered (F6).
- `FailureInjectionIT`, F5 and F6: with RabbitMQ cut, and separately black-holed (accepts the connection, never answers), redirects are never delayed or failed, events are dropped and counted, the breaker opens, and analytics resume on recovery; with the consumer stopped, events queue up, redirects are unaffected, stats stay stale but honest, and a restart converges. `PublishingTest` covers the publisher: it never throws or blocks, failures and an open breaker drop the event, and an exhausted in-flight cap drops new events rather than growing the heap.
- Live (Postman/Newman, folder 4c): four clicks with different `Referer` and `User-Agent` values give exactly `totalClicks: 4`, referrers `news.example.org` ×2, `(direct)` ×1, `(unknown)` ×1, devices `mobile` 1, `desktop` 1, `other` 2, one UTC day bucket, and no token from the referrer URL in the response.

**Not done.** (1) The plan's isolated load test showing redirect latency is unaffected by queue load: not run (README L1). The functional F5 test shows the redirect survives a broker outage, not that its latency is flat under queue load. (2) The plan's "reconciliation test" against raw click events: **not possible as written**, because raw events are deliberately not retained (§5.2). The substitute actually run is exact counting: the number of stats clicks equals the number of redirects sent (`AnalyticsIT`, Postman 4c), plus the dedup test. (3) The 90-day retention itself is still an unconfirmed assumption.

---

## 16. AI-Assisted Execution Approach & Traceability

For each task: intent, constraints, and acceptance criteria were written before invoking AI. Every AI-generated diff is tagged **generated**, **edited**, or **rejected**, with a one-line rationale, in the PR description / commit trailer:

```
[generated] cache-aside read logic — accepted as-is
[generated] SSRF hostname-resolution guard — accepted as-is
[edited] alias validation regex — tightened to disallow leading hyphen
[edited] expiry check — moved from sweep-job-only to read-time (F12), sweep job's role narrowed to cleanup
[rejected] synchronous analytics write in redirect path — violates latency SLA (§8.1)
[rejected] 410 Gone for expired links — leaks code lifetime on an unauthenticated endpoint (E14 rationale)
```

**Quality gates applied to all AI-produced code before merge:** static analysis/linting, unit + integration tests, a manual security pass specifically covering SSRF/open-redirect/injection (§10.3), and mandatory human sign-off for any change touching the redirect hot path — this is the system's designated "high-impact change" category requiring explicit engineer approval regardless of how the change was authored.

---

## 17. Testing Strategy

- **Unit tests:** code generation/permutation correctness and uniqueness under concurrency, validation rules (every row of §7's Edge Case Catalog gets a corresponding test), expiry logic, cache-aside behavior.
- **Integration tests:** end-to-end create → redirect → click recorded → analytics reflects it (polling for eventual consistency, asserting convergence within the expected lag window).
- **Failure-injection tests:** each row of §8's Failure Mode Catalog gets a corresponding test — Redis unreachable, queue unreachable, MySQL primary down with replica up, duplicate queue delivery, etc. — asserting the *defined* mitigated behavior, not just "doesn't crash."
- **Load tests:** redirect-path latency under cache-hit and cache-miss conditions at target QPS (§11.1); cache-miss stampede simulation (F7); queue-outage simulation confirming redirects still succeed (F5).
- **Security tests:** SSRF payloads (internal IPs, DNS rebinding attempt), open-redirect/self-referential payloads (E5), alias-injection and reserved-word bypass attempts.

---

## 18. Setup Instructions (prototype)

Prerequisites: JDK 21 (LTS), Maven 3.9+, Docker (for MySQL/Redis/broker via Testcontainers or `docker-compose`).

1. `docker-compose up -d` — starts MySQL, Redis, and the message broker (see §20.2 for concrete images).
2. `./mvnw spring-boot:run -pl url-shortener-service` (or run the packaged jar: `./mvnw -pl url-shortener-service clean package && java -jar url-shortener-service/target/url-shortener-service-*.jar`) — Flyway migrations (§20.1) run automatically on startup against `urls`/`click_aggregates`.
3. Seed an API key: `curl -X POST localhost:8080/internal/api-keys` (dev-profile-only convenience endpoint; disabled outside `local`/`test` profiles).
4. Create a short URL:
   ```
   curl -X POST localhost:8080/api/v1/urls \
     -H "X-API-Key: <key>" \
     -H "Content-Type: application/json" \
     -d '{"longUrl":"https://example.com"}'
   ```
5. Visit the returned short URL in a browser to confirm redirect + async click recording.
6. Query stats: `curl localhost:8080/api/v1/urls/<code>/stats -H "X-API-Key: <key>"`.
7. Validate the contract against the running service: `./mvnw org.openapitools:openapi-generator-maven-plugin:validate` (or an equivalent linter) as part of CI — see §20.6.
8. Actuator health/metrics: `curl localhost:8080/actuator/health`, `curl localhost:8080/actuator/prometheus`.

---

## 19. Limitations, Assumptions & Open Questions

**Explicit limitations (documented, not hidden):**
- SSRF hostname check is creation-time, not redirect-time — a URL that resolves publicly at creation but is later re-pointed via DNS to an internal address is not caught (rare, but real; a periodic re-validation sweep is a natural extension, not built in v1).
- No malware/phishing scanning of submitted URLs — flagged as an integration point (§1) but not implemented.
- Single-region deployment; full multi-region active-active is deferred (§14).
- 90-day analytics retention is an *assumption*, not a stated requirement — needs stakeholder confirmation.
- Rate limit values (§11.3) are reasonable defaults, not derived from real traffic data — revisit after launch telemetry is available.
- Bulk/batch URL creation is out of scope; each creation is a single synchronous request.
- **Load tests have not been run** against the prototype (§17, §15.1, §15.3): the throughput and latency targets in §2.3 / §11.1 and the "redirect latency is unaffected by analytics load" check are unverified. Functional and fault-injection behaviour is verified (§15 *As built*).

**Open questions for stakeholders:**
- Should deactivated custom aliases ever be recyclable, and if so, after what retention period (E8)?
- Is 90-day analytics retention correct, or does compliance/product need longer?
- Is there a requirement for branded/custom domains beyond `short.ly`, and if so, how does that interact with the self-referential check (E5)?

---

## 20. Technology Stack & Implementation Mapping (Java 21 / Spring Boot 3+ / MySQL)

This section verifies that every architectural decision in §1–19 is implementable as-is on Java 21 / Spring Boot 3+, and pins the concrete library each design element maps to. No architectural decision made earlier in this document changes because of the stack choice — this section is a compatibility check and an implementation mapping, not a redesign.

### 20.1 Module → framework mapping

| Design element | Section | Java 21 / Spring Boot 3+ mapping |
|---|---|---|
| Shortener / Redirect / Analytics services | §3 | Three deployable Spring Boot 3.3+ apps (or one multi-profile app for the prototype, per §20.7) on `spring-boot-starter-web`, running on embedded Tomcat (or Netty via `spring-boot-starter-webflux` if a reactive redirect path is later desired — not required to hit the stated latency targets on a servlet stack) |
| `urls` table access | §5.1 | Spring Data JPA (`spring-boot-starter-data-jpa`) over Hibernate 6.x with `MySQLDialect`, MySQL via `com.mysql:mysql-connector-j`; schema managed by **Flyway** (`flyway-mysql`), not `ddl-auto`, so migrations are the expand/contract mechanism referenced in §13 |
| Optimistic concurrency (`version` column) | §5.1, §9.2 | JPA `@Version` on the entity; Hibernate throws `OptimisticLockingFailureException`, mapped per §9.3 |
| Sharded counter / ID pre-allocation | §4.1, F8 | Hibernate's `pooled`/`pooled-lo` `@SequenceGenerator` (`allocationSize=10000`) gives block pre-allocation for free without hand-rolled ID-block logic |
| Base62 encode + reversible permutation | §4.1 | Plain Java 21 utility class (`ShortCodeGenerator`), no framework dependency — pure functions are the right choice here, not a library |
| Redis cache-aside | §3.2, F1, F2 | `spring-boot-starter-data-redis` (Lettuce client); cache-aside implemented explicitly in `UrlLookupService` rather than `@Cacheable`, because §8's F1/F2 failure semantics (fallback + explicit invalidation-not-update) need code-level control that declarative caching would obscure |
| Circuit breaker + retry-with-jitter (§8.2) | §8, §8.2, F1/F3/F4/F5/F11 | Resilience4j (`resilience4j-spring-boot3`); `@CircuitBreaker` + `@Retry`, stacked via `@CircuitBreaker(name=...) @Retry(name=...)` (retry is the inner decorator, circuit breaker the outer — retry attempts only occur while the breaker permits the call, per §8.2). `Retry`'s `IntervalFunction.ofExponentialRandomBackoff(baseDelay, multiplier, randomizationFactor)` implements the full-jitter policy from §8.2; one named `CircuitBreaker`/`Retry` instance pair per dependency (`redis-cache`, `mysql-read`, `mysql-write`, `mq-publish`, `auth-lookup`), each independently configured per §8.2.1's table — never a shared instance across dependencies. `CallNotPermittedException` mapped per §9.3 |
| Cache-miss stampede guard (F7) | §8, F7 | `SET NX PX` via `RedisTemplate`/Lettoce directly (no higher-level library needed); short-TTL per-key lock as designed |
| Message queue (click events) | §3.2, F5, F6 | `spring-boot-starter-amqp` (RabbitMQ) or `spring-kafka` — either satisfies the durable/at-least-once requirement; RabbitMQ is the default assumption for the prototype (lighter local footprint), Kafka is a drop-in alternative at higher sustained throughput. Publish uses `AmqpTemplate.convertAndSend` with a bounded `replyTimeout`, wrapped so a timeout/error is caught and dropped per F5 — never propagated to the redirect response |
| Analytics consumer | §3.2, F6 | `@RabbitListener` (or `@KafkaListener`) consumer group, manual ack after successful aggregation write, dedup by `event_id` via a unique index on the consumer-side staging table |
| Bean validation (request DTOs) | §7, §9.1–9.2a | Jakarta Bean Validation (`spring-boot-starter-validation`) — `@NotBlank`, `@Pattern`, `@Size`, `@Future`/custom validator for `expiresAt`; Java 21 **records** used for request/response DTOs (`CreateUrlRequest`, `UrlResource`, etc.) since they're immutable data carriers with no behavior — validation annotations work identically on record components |
| Global exception handling | §9.3 | `@RestControllerAdvice extends ResponseEntityExceptionHandler`, overriding the `handleXxx` methods for the Spring-native exceptions listed in §9.3's table, plus `@ExceptionHandler(UrlShortenerException.class)` for the domain hierarchy |
| API-key auth | §10.1 | Spring Security 6.x (`spring-boot-starter-security`) with a custom `AbstractAuthenticationProcessingFilter`/`OncePerRequestFilter` reading `X-API-Key`, hashing (Argon2 via `Spring Security Crypto`'s `Argon2PasswordEncoder`) and comparing against the stored hash; redirect endpoint explicitly permits all (`permitAll()`) per §8.1/§10.1's "no auth-service dependency" requirement |
| SSRF hostname guard | §10.3 | Plain `java.net.InetAddress`/`InetAddressValidator` (Apache Commons Validator) checks against RFC 1918/loopback/link-local ranges at creation time, invoked from the service layer before persistence — no framework dependency |
| Rate limiting | §10.4, §11.3 | Resilience4j `RateLimiter` (per-API-key, in-process) for the prototype; noted as a documented limitation that true multi-instance rate limiting needs a shared store (Redis-backed, e.g. Bucket4j + Redis) — see §20.8 |
| Idempotent creation | §6.3 | Unique index on `idempotency_fingerprint`; service catches the resulting `DataIntegrityViolationException` on a duplicate-within-window and re-fetches/returns the existing row with `200` |
| Observability — metrics | §12.1 | `spring-boot-starter-actuator` + Micrometer, `micrometer-registry-prometheus`; the metrics named in §12.1 (`redirect_latency_ms`, `redirect_cache_hit_rate`, etc.) are registered as Micrometer `Timer`/`Counter`/`Gauge` beans |
| Observability — tracing | §12.3 | Micrometer Tracing (`micrometer-tracing-bridge-otel`) + OpenTelemetry exporter; `requestId` (§9.1/§9.4) is the same value as the trace ID, propagated via MDC (`micrometer-tracing`'s Logback integration adds it to every log line automatically) |
| Observability — logging | §12.2 | SLF4J + Logback, structured JSON encoder (`logstash-logback-encoder`) so `requestId`/trace fields are queryable, not just grep-able |
| Deployment / canary / rollback | §13 | Spring profiles (`application-{env}.yml`) for environment config; Flyway for expand/contract migrations; feature flags via a simple config-driven `@ConditionalOnProperty` toggle for the prototype (a dedicated flag service is a documented future step, not required to demonstrate the pattern) |
| Testing | §17 | JUnit 5 + `spring-boot-starter-test`; **Testcontainers** (`spring-boot-testcontainers`, JUnit 5 `@ServiceConnection`) for real MySQL/Redis/broker in integration and failure-injection tests — this is what makes §17's failure-injection tests (kill Redis mid-test, etc.) actually executable rather than mocked |

### 20.2 Reference dependency/image versions (prototype)

| Component | Version pinned in the reference prototype |
|---|---|
| Java | 21 (LTS) |
| Spring Boot | 3.3.x |
| Spring Framework | 6.1.x (transitively) |
| MySQL | 8.4 LTS (`mysql:8.4` for local/Testcontainers) |
| Redis | 7.x (`redis:7-alpine`) |
| RabbitMQ | 3.13 (`rabbitmq:3.13-management-alpine`) |
| Resilience4j | 2.2.x (`resilience4j-spring-boot3`) |
| Flyway | 10.x (`flyway-mysql` — the MySQL support module) |
| mysql-connector-j | 9.x (JDBC driver, `com.mysql:mysql-connector-j`) |
| Testcontainers | 1.20.x |

### 20.3 Java 21 language features actually used (not just version-labeled)

- **Records** for all DTOs/value objects (`CreateUrlRequest`, `UrlResource`, `UrlStats`, `ErrorResponse`, `FieldError`) — immutability matches their role as wire-format carriers.
- **Sealed interfaces/classes** for the exception hierarchy (§9.3) and for a small internal `CacheLookupResult` (`Hit`/`Miss`/`CircuitOpen`) result type, so the compiler enforces exhaustive handling at each call site rather than relying on convention.
- **Pattern matching for `switch`** (finalized in Java 21) over the sealed `CacheLookupResult` and over the exception hierarchy in the `@RestControllerAdvice`, replacing long `if/instanceof` chains.
- **Virtual threads** (`spring.threads.virtual.enabled=true`, Project Loom, finalized in Java 21) for the embedded Tomcat request-handling pool — a good fit here specifically because the redirect path's dominant cost is blocking I/O (cache/DB round-trips per F1/F4), which is exactly the workload virtual threads help with; **not** used for the CPU-bound Base62/permutation step, which stays on the caller's thread.
- **Text blocks** for the handful of inline SQL/native queries (e.g. the `SET NX PX`-equivalent Lua script for F7, if implemented as a Redis Lua script rather than client-side `SETNX`) and for structured log message templates.

### 20.4 Compatibility notes / corrections found during verification

Reviewing the design against a real Spring Boot 3+ implementation surfaced a small number of clarifications — none change the architecture, they make it precise enough to build:

- **§6.4's `Cache-Control: no-store` on redirects** requires explicitly overriding Spring's default resource-caching headers on the redirect controller method (`ResponseEntity.status(302).cacheControl(CacheControl.noStore())...`) — Spring MVC does not send this by default, and must not be left to default behavior given the rationale in §6.4.
- **§9.2's 404 `NO_HANDLER` row** depends on two non-default properties — `spring.mvc.throw-exception-if-no-handler-found=true` and `spring.web.resources.add-mappings=false` — without both, Spring's default static-resource handler swallows unmatched paths as a plain 404 with no body, bypassing the `@RestControllerAdvice` and breaking the "every error response is debuggable via `requestId`" guarantee in §9.1.
- **§5.4's "strongly consistent `urls`, eventually consistent `click_aggregates`"** maps cleanly to two separate `DataSource`/`TransactionManager` configurations if the analytics store is a different database technology than MySQL; if it's also MySQL (a reasonable prototype simplification), a single `DataSource` suffices and the "separate consistency model" is enforced by the *access pattern* (async consumer vs. synchronous request thread), not by infrastructure — worth stating explicitly so the two aren't conflated during implementation.
- **§8's F9 (duplicate short-code collision surfaced as a paged `500`)** requires the unique-constraint violation on `short_code` to be distinguished from the same exception type used for §6.3's alias-race (`409`) and §9.2a's alias-taken case — the reference implementation distinguishes them by which column's constraint fired (`DataIntegrityViolationException`'s wrapped `ConstraintViolationException.getConstraintName()`), not by exception type alone; this distinction should be unit-tested directly rather than assumed.
- **Virtual threads (§20.3) and Resilience4j (§20.1) interaction:** Resilience4j's `CircuitBreaker`/`RateLimiter` are thread-safe and virtual-thread-compatible as of the pinned versions in §20.2, but any `synchronized` block (none are currently designed) would pin the carrier thread and defeat the point of virtual threads — noted here so a future contributor doesn't introduce one casually in the F7 stampede-guard code path.

### 20.4a MySQL-specific corrections (database migrated from Postgres to MySQL)

Switching the persistence layer from Postgres to MySQL is not a drop-in swap — reviewing it surfaced one correctness-critical issue and several implementation-mechanics differences. None change the architecture; all must be applied for the design to be correct on MySQL.

- **⚠️ Collation/case-sensitivity — correctness-critical.** Design doc §2.2 states short codes are **case-sensitive** (Base62: `a` ≠ `A`), and this is load-bearing — the Base62 keyspace assumes all 62 characters are distinct. MySQL's commonly-used default collations (`utf8mb4_general_ci`, `utf8mb4_0900_ai_ci`) are **case-insensitive**, which would make the `short_code` `UNIQUE` constraint treat `aZ3kP1` and `AZ3Kp1` as duplicates — silently colliding roughly 1 in every few dozen generated codes and corrupting the uniqueness guarantee the whole generation scheme (§4) depends on. **`short_code` and `customAlias`-derived columns must use a case-sensitive, accent-sensitive collation** — `utf8mb4_bin` (byte-exact) or `utf8mb4_0900_as_cs` (accent- and case-sensitive) on MySQL 8.0+ — set explicitly on the column, not inherited from a case-insensitive schema/table default. This must be asserted in a migration-level test (insert `abc123` and `ABC123`, expect both to succeed) as part of the Flyway migration's validation, not assumed.
- **No native `SEQUENCE` object.** As noted in §4.1, Hibernate emulates the `pooled-lo` sequence generator via a table (typically `id_generator` or Hibernate's default `hibernate_sequence` table) when the dialect is `MySQLDialect`. This table is itself subject to row-locking under concurrent allocation — at very high write QPS this is a slightly different contention profile than a true database sequence (which Postgres/Oracle implement outside normal MVCC/locking), though the pre-allocated block size (§4.1, F8) mitigates it the same way. Worth a dedicated load test comparing allocation latency under contention if write QPS assumptions in §11.1 are pushed significantly higher.
- **`JSON` replaces `JSONB`.** MySQL's `JSON` column type (5.7.8+) stores an optimized internal binary representation automatically — there is no separate "text JSON vs. binary JSON" choice to make as there is on Postgres (`json` vs. `jsonb`). Querying into `top_referrers`/`device_breakdown` uses MySQL's `JSON_EXTRACT`/`->`/`->>` operators rather than Postgres's `jsonb` operators — any hand-written analytics query in `AggregationService`/`StatsQueryService` needs its JSON-path syntax adjusted, not just its column-type declaration.
- **Online DDL differs.** Postgres's `CREATE INDEX CONCURRENTLY` (referenced for index additions in `data-layer` playbooks) has a MySQL/InnoDB equivalent, not an identical syntax: `ALTER TABLE ... ADD INDEX ... ALGORITHM=INPLACE, LOCK=NONE`. `ALGORITHM=INPLACE, LOCK=NONE` is the target for any index-adding migration on a live table; not every alteration supports `LOCK=NONE` (check per MySQL version/alteration type before assuming it's available) — unlike Postgres's `CONCURRENTLY`, this isn't automatic and should be explicitly specified and verified per migration.
- **Optimistic locking (`@Version`) is unaffected** — this is a Hibernate-layer mechanism (an `UPDATE ... WHERE id = ? AND version = ?` with row-count check), not a Postgres-specific feature, and works identically against MySQL/InnoDB.
- **`DataIntegrityViolationException` on unique-constraint violation is unaffected** — Spring's exception-translation layer maps MySQL's duplicate-key error (`SQLIntegrityConstraintViolationException`, MySQL error `1062`) to the same `DataIntegrityViolationException` used in §9.3's exception hierarchy, so E13's alias-race resolution and F9's collision-detection logic require no change.
- **Replication terminology:** MySQL's primary/replica mechanism (binlog-based, `GTID`-consistent replication as of MySQL 8.0) is functionally equivalent to what §3.3/§8/§14 describe as "Postgres streaming replication" for this design's purposes (async replica for Redirect Service's read-fallback path, F4; replica promotion for DR, F10/§14) — no behavioral change to the failure-mode or DR sections, only the underlying replication mechanism's name.
- **Managed-service equivalents for §14's DR targets:** where a cloud-managed Postgres (e.g. RDS/Aurora Postgres) was assumed for automated failover, the MySQL equivalent is Amazon Aurora MySQL or RDS MySQL Multi-AZ (or the equivalent managed MySQL offering on another cloud) — the RPO/RTO targets in §14's table are provider-capability-driven, not Postgres-specific, and hold equivalently on a comparable managed MySQL service.

### 20.5 What did *not* need to change

Confirms the design survives contact with the concrete stack — including the MySQL migration in §20.4a — unmodified at the architecture level: the redirect-path independence from auth/queue (§8.1), the cache-aside + explicit-invalidation pattern (F2), the sharded-counter code generation concept (§4 — only its underlying mechanism changed, not its behavior or guarantees), the error taxonomy's status/code pairs (§9.2, now extended rather than replaced), the URI-path API versioning scheme (§6.1), and the RPO/RTO targets (§14) are all directly realizable on Java 21 / Spring Boot 3+ / MySQL with no architectural compromise — the additions in §20.1–20.4a are implementation detail, precision, and (for §20.4a's collation point) a genuine correctness fix required specifically by the database switch, not an architectural change.

### 20.6 Contract-first workflow

`openapi.yaml` remains the source of truth for the wire contract (§6.2). The reference implementation uses `openapi-generator-maven-plugin` in `interface-only` mode to generate the Spring MVC controller interfaces and DTOs from `openapi.yaml` at build time; hand-written `@RestController` classes implement the generated interfaces. This keeps the OpenAPI spec and the running service from drifting apart, and makes `./mvnw verify` fail the build if a controller no longer matches the contract. The interactive Swagger UI is built on this same file rather than a separate generation path — see §22.

### 20.7 Prototype module layout

For the 2–3 day assignment scope, the three logical services (§3.1: Shortener, Redirect, Analytics) are implemented as a **single Spring Boot application with three logically separated packages** (`shortener`, `redirect`, `analytics`) sharing one JVM/deployment unit, rather than three separately deployed services — this is called out as a deliberate prototype-scope simplification, not a retraction of §3.2's "independently scalable services" design; splitting the packages into separate deployables later is a build/packaging change, not a code redesign, because the package boundaries already match the intended service boundaries.

### 20.8 Known prototype-vs-production gaps introduced by this mapping (add to §19)

- In-process Resilience4j rate limiting (§20.1) is per-instance, not global — acceptable for a single-instance prototype, insufficient once the Shortener service scales horizontally (§11.2); production needs a shared-state limiter (Redis-backed).
- The single-application module layout (§20.7) means the three services currently share a JVM's resource limits and a single deploy/rollback unit, temporarily narrowing §13's "independently deployable services" claim until they're split into separate build artifacts.

---

## 21. Repository & AI-Assistant Tooling (`.claude/` layout, README, diagrams)

This section documents the repository-level tooling that makes the preceding 20 sections *operational* — i.e. actually followed during day-to-day AI-assisted and human development — rather than a document that's read once and drifts from the code. It does not change any architectural decision in §1–20; it is the delivery mechanism for them.

### 21.1 Why this exists

Design doc §16 requires traceability, disciplined prompting, quality gates, and human sign-off for AI-assisted execution. In practice, that requires the AI assistant to have the right context loaded *before* it starts a task, at the right granularity — project-wide invariants for anything, component-specific detail only when working in that component, and concrete runnable playbooks rather than re-deriving "how do I add a validation rule here" from first principles each time. The `.claude/` folder structure below is the mechanism for that.

### 21.2 `.claude/` structure and content model

```
.claude/
├── CLAUDE.md                    ← project-wide, comprehensive (§21.3)
├── rules/                       ← project-level, non-negotiable (§21.4)
│   ├── engineering-rules.md
│   ├── security-rules.md
│   └── api-contract-rules.md
├── components/                  ← one folder per functional component (§21.5)
│   ├── shortener-service/{CLAUDE.md, SKILLS.md}
│   ├── redirect-service/{CLAUDE.md, SKILLS.md}
│   ├── analytics-service/{CLAUDE.md, SKILLS.md}
│   ├── data-layer/{CLAUDE.md, SKILLS.md}
│   ├── security-auth/{CLAUDE.md, SKILLS.md}
│   └── observability/{CLAUDE.md, SKILLS.md}
└── plugins/                     ← cross-component "superpower" workflows (§21.6)
    ├── README.md
    ├── new-endpoint.md
    ├── new-failure-mode.md
    ├── contract-change.md
    ├── security-review.md
    ├── incident-response.md
    └── release-checklist.md
```

The component boundaries mirror §3.2's service/responsibility split exactly, plus three additional cross-cutting components (`data-layer`, `security-auth`, `observability`) that don't map to one of the three logical services but are substantial enough, and touched often enough, to warrant their own context files rather than being buried inside another component's.

### 21.3 Root `CLAUDE.md` — content requirements

The root file is deliberately the most detailed single file in the tooling layer, since it's the mandatory first read for any task. It contains: what the project is and the stack (§1/§20 in miniature), the full repository layout, a numbered list of **non-negotiable invariants** restated from across §1–20 (redirect-path independence from §8.1, `302`-not-`301` from §6.4, invalidate-not-update caching from F2, DB-enforced uniqueness from E13/F9, the single error-handling chokepoint from §9.3, no-PII-logging from §10.2/§12.2, SSRF-guard-before-persist from §10.3, human-sign-off-for-redirect-changes from §16, and expand/contract migrations from §13), the AI traceability format from §16 restated as an operational requirement, a routing table pointing to the right component file per task type, and a project-wide definition of done. Every invariant listed there carries a citation back to the design doc section it was drawn from, so the two documents cannot silently diverge without the divergence being visible.

### 21.4 Project-level rules — content requirements

Three rule files, each enforced (not advisory) and each cross-referencing the design doc section it operationalizes:
- **`engineering-rules.md`** — quality gates before any change is proposed as complete (test/lint/migration discipline), the human-sign-off trigger list, the AI traceability tag format with a worked example, a rule against silently narrowing a documented guarantee, an explicit out-of-scope list matching §1/§19, and a definition of "tested" that distinguishes unit/integration/failure-injection so a failure-mode row (F1–F13) can't be marked "covered" by a mocked unit test standing in for a real failure-injection test.
- **`security-rules.md`** — one rule per relevant subsection of §10 (SSRF mandatory on every URL-accepting path, secrets never in code/logs, redirect-endpoint-stays-unauthenticated-by-design, resource-owner authorization with the 403/404 distinction, validation-as-a-gate, no-internals-in-error-bodies, anti-bypass validation on aliases, and a rule extending SSRF review to any future outbound-fetch feature).
- **`api-contract-rules.md`** — contract-first workflow (`openapi.yaml` as source of truth, regenerate rather than hand-edit generated interfaces), the permanently-unversioned redirect endpoint, the exact breaking/non-breaking classification from §6.1, deprecation discipline, error-contract-change classification, and a CI-enforcement requirement.

### 21.5 Component `CLAUDE.md` / `SKILLS.md` — content model

Each component's `CLAUDE.md` follows a consistent shape: responsibilities, expected key classes (named and scoped, so an AI assistant creating a new class knows where it belongs and what it's responsible for), invariants specific to that component (each traceable to a design doc section/edge-case/failure-mode number), and an explicit "what NOT to do here" list — negative guidance has proven at least as valuable as positive guidance for keeping AI-assisted changes from quietly reintroducing an already-rejected pattern (e.g. synchronous analytics writes in the redirect path, design doc §15.3's worked rejection example).

Each component's `SKILLS.md` is a set of task-shaped playbooks ("add a new validation rule," "write a failure-injection test for F1," "add a new Flyway migration," "debug an unexpected 409") with concrete steps, including exact Maven test-filter commands where relevant. These are the operational counterpart to §17's testing strategy and §15's scenario write-ups — turning "here's how we validated it once" into "here's how you validate it again."

### 21.6 Plugins — cross-component workflow packaging

Where a task spans components (adding an endpoint touches validation, data layer, security, and observability all at once), a single component's `SKILLS.md` isn't the right granularity. `.claude/plugins/` packages these as end-to-end procedures: `new-endpoint.md`, `new-failure-mode.md` (the operational form of §8's failure-mode catalog — how a *new* one gets added with the same rigor as the existing 13), `contract-change.md` (operationalizes §6.1's versioning rules), `security-review.md` (a pre-merge checklist derived directly from `security-rules.md`), `incident-response.md` (maps live alerts to §8's F-numbers for faster triage, and treats "the mitigation didn't behave as documented" as a higher-priority finding than the triggering event itself), and `release-checklist.md` (operationalizes §13's deployment/rollout practices). These are explicitly designed to complement, not replace, this project's generic engineering plugins (`engineering:code-review`, `engineering:testing-strategy`, `engineering:incident-response`, `engineering:deploy-checklist`) — the plugins here add the domain-specific procedure on top of the generic ones.

### 21.7 `README.md` — content requirements

Root-level, human-and-AI-readable entry point distinct in purpose from `.claude/CLAUDE.md`: a documentation map (table pointing to every major doc/tooling file and what it's for), a quick-start (prerequisites, `docker-compose`, run, create, redirect, stats — matching §18 exactly so the two never drift), an architecture-at-a-glance summary with a compact diagram, a short list of the core design decisions worth knowing before reading the full design doc, a testing section explaining what a failure-injection test actually verifies (not just "we have tests"), a contributing section stating the AI-assisted/engineer-owned workflow and pointing at the traceability format and sign-off requirements, and a known-limitations summary matching §19.

### 21.8 Architecture diagrams — content requirements

`docs/architecture-diagrams.md` (Mermaid, rendering natively in GitHub/GitLab): a system/component-view flowchart matching §3.1's ASCII diagram but navigable and stylable (redirect service visually distinguished as the highest-availability-requirement component); sequence diagrams for create (including the idempotency branch from §6.3 and the alias-race resolution from E13), redirect (cache-hit, cache-miss, and Redis-down-fallback cases in one diagram so the degradation path is visible alongside the happy path, per F1), and async analytics aggregation (including the dedup and dead-letter branches from F6/E21); an ER diagram for §5's data model; a failure-mode map grouping all 13 F-numbers by owning component, so "which component do I look at for this alert" is answerable at a glance; a deployment-topology diagram for the multi-AZ target from F10/§14; and an illustrative API-versioning timeline visualizing §6.1's deprecation-window policy.

### 21.9 Maintenance discipline for this tooling layer

The `.claude/` tooling layer, `README.md`, and `docs/architecture-diagrams.md` are treated as part of the deliverable, not incidental scaffolding — `.claude/rules/engineering-rules.md` R4's "don't silently narrow a documented guarantee" applies equally to letting this tooling drift out of sync with the design doc or the actual code. A change to §1–20 that isn't reflected in the relevant `.claude/components/*/CLAUDE.md` (or in the root file's invariant list, if the change is cross-cutting) is incomplete. `release-checklist.md` makes this explicit: a drifted design doc is a release blocker, not a follow-up ticket.

---

## 22. Swagger / OpenAPI UI Implementation

Design doc §20.6 established a contract-first workflow: `openapi.yaml` is the source of truth, and Spring MVC controller interfaces are generated from it, not the other way around. This section specifies how the interactive Swagger UI is implemented so that it **serves that same file** rather than regenerating a second, annotation-derived spec — the single most common way a Swagger UI silently drifts from the real contract is having two independent sources of truth for it. Everything here is additive to §6/§20.6; it does not change the contract-first decision.

### 22.1 Approach: static-spec-served, not annotation-generated

`springdoc-openapi` (the Spring Boot 3+-compatible successor to springfox) is normally used to *generate* an OpenAPI document from `@RestController`/`@Operation` annotations at runtime. This project deliberately does **not** use it that way, for the same reason §20.6 generates controller interfaces from the spec instead of hand-annotating controllers: annotation-driven generation and contract-first are two different sources of truth, and letting both exist invites drift the moment someone edits a controller annotation without touching `openapi.yaml` (or vice versa).

Instead: `openapi.yaml` is packaged as a classpath resource and served as-is at `/v3/api-docs.yaml`; `springdoc-openapi`'s autogenerated scanning is disabled; the bundled Swagger UI (which springdoc still provides as a static asset) is pointed at that served file via configuration, not at springdoc's own generated document. The UI you click through and the file CI validates (§20.6/A6) are byte-for-byte the same artifact.

### 22.2 Dependencies

| Dependency | Purpose |
|---|---|
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` (2.x, Spring Boot 3+-compatible) | Provides the bundled Swagger UI static assets and its entry points: the canonical page is `/swagger-ui/index.html`, and the configured `/swagger-ui.html` redirects (`302`) to it. Used **only** for its UI assets and routing in this project — its automatic spec-generation feature is disabled per §22.1. |

No `springdoc-openapi-starter-webmvc-api` generation dependency is needed beyond what the `-ui` starter already pulls in, since generation is turned off.

### 22.3 Configuration

```yaml
# application.yml (as implemented; see V-11 in docs/design-verification-report.md)
springdoc:
  api-docs:
    enabled: true
    path: /v3/generated-api-docs   # springdoc's OWN generator, relocated away from the contract path and hidden (below)
  swagger-ui:
    enabled: true
    path: /swagger-ui.html         # entry point; springdoc redirects it to /swagger-ui/index.html
    url: /v3/api-docs.yaml         # points the UI at the static contract file, not springdoc's generator
    disable-swagger-default-url: true
  packages-to-scan: none           # nothing to scan — annotation-based generation is intentionally unused
```

The original draft of this snippet set `api-docs.path: /v3/api-docs`. That is wrong in practice: springdoc's generated document and the static controller below then compete for the same address, and content negotiation, not intent, decides which one answers (the two-sources-of-truth failure §22.1 warns about). The generator therefore lives at `/v3/generated-api-docs`, is answered with `404` by `HiddenRoutesFilter`, and only the UI's configuration sub-path stays reachable. The resulting URLs:

| URL | What it is | `local` / `test` | `prod` |
|---|---|---|---|
| `/swagger-ui/index.html` | The Swagger UI (**canonical URL**) | `200` | disabled (`springdoc.swagger-ui.enabled: false`) |
| `/swagger-ui.html` | The configured entry point | `302` to `/swagger-ui/index.html` | `404` |
| `/v3/api-docs.yaml` | The contract, byte-identical to `docs/openapi.yaml` (`SwaggerIT`) | `200`, `application/yaml` | `404` |
| `/v3/api-docs` | Not served: it must never answer with a generated spec | `404` | not served |
| `/v3/generated-api-docs` | springdoc's generator, hidden | `404` | disabled |
| `/v3/generated-api-docs/swagger-config` | The UI's own configuration (the only reachable sub-path) | `200` | disabled |

The `local` / `test` column was measured against a running instance. In the `prod` column only `/swagger-ui.html` and `/v3/api-docs.yaml` were measured (`404` on the container image); the other cells follow from `springdoc.api-docs.enabled: false` and `springdoc.swagger-ui.enabled: false` and were not measured separately. The port is `server.port` (default `8080`); the local run scripts choose another free port when it is busy and print the URLs.

```java
// Serves the actual contract file (classpath:openapi/openapi.yaml, copied into the build
// from docs/openapi.yaml — see §22.6) at the path springdoc-ui is configured to load from.
@RestController
class OpenApiContractController {

    private final Resource contractFile =
        new ClassPathResource("openapi/openapi.yaml");

    @GetMapping(value = "/v3/api-docs.yaml", produces = "application/yaml")
    public Resource serveContract() {
        return contractFile;
    }
}
```

This is intentionally simple — a static-resource serve, not a generation pipeline — which is the point: there is nothing here that can drift from `openapi.yaml` except forgetting to update the copy step in §22.6, which CI catches (§22.7).

### 22.4 Exposure and security per environment

- **`local`/`dev` profiles:** `/swagger-ui/index.html` (and `/swagger-ui.html`, which redirects to it) and `/v3/api-docs.yaml` fully open, no auth — this is a developer convenience surface, not part of the product's authenticated API surface.
- **`staging`:** exposed but gated behind the same reverse-proxy basic-auth or VPN boundary already used for other internal tooling in that environment — not behind the product's own API-key auth (a chicken-and-egg problem: you shouldn't need an API key to discover how to get one).
- **`production`:** disabled by default (`springdoc.api-docs.enabled: false`, `springdoc.swagger-ui.enabled: false` in the `prod` profile) — the machine-readable contract (`openapi.yaml`) is published through the project's documentation site/repo instead of being served live off the production API surface, consistent with `.claude/rules/security-rules.md`'s general posture of minimizing what an unauthenticated caller can discover about the running system. If a product requirement later calls for a public production API explorer, that is a deliberate decision to revisit here, not a default.

### 22.5 "Try it out" authentication wiring

For the `local`/`staging` exposure in §22.4, Swagger UI's "Try it out" needs to attach `X-API-Key` to requests against the authenticated management endpoints (§10.1). This is configured via the spec's existing `ApiKeyAuth` security scheme (already defined in `openapi.yaml`'s `components.securitySchemes`, §6.2) — springdoc's bundled UI automatically renders an "Authorize" button and header field for any `apiKey`-type security scheme present in the served document, with no additional Java configuration required beyond serving the real file per §22.1. The redirect endpoint (`GET /{shortCode}`) correctly shows no lock icon in the UI, since it carries no `security` requirement in the spec, matching its `permitAll()` implementation (§10.1, `.claude/rules/security-rules.md` S3) — this is a useful visual cross-check that the two haven't drifted apart, not just documentation.

### 22.6 Build-time packaging of the contract file

`docs/openapi.yaml` (the repository's single source of truth, edited directly per rule A1) is copied into the build's classpath resources at `src/main/resources/openapi/openapi.yaml` via a Maven `maven-resources-plugin` execution bound to `generate-resources`, rather than maintaining two separate copies by hand:

```xml
<execution>
  <id>copy-openapi-contract</id>
  <phase>generate-resources</phase>
  <goals><goal>copy-resources</goal></goals>
  <configuration>
    <outputDirectory>${project.build.outputDirectory}/openapi</outputDirectory>
    <resources>
      <resource><directory>${project.basedir}/docs</directory><includes><include>openapi.yaml</include></includes></resource>
    </resources>
  </configuration>
</execution>
```

This runs in the same build phase, ahead of `openapi-generator-maven-plugin`'s interface generation (§20.6), so both the generated controller interfaces and the served Swagger UI document come from the identical `docs/openapi.yaml` on every build — one file, two build-time consumers, zero hand-copying.

### 22.7 CI validation

`.claude/rules/api-contract-rules.md` A6 already requires `openapi.yaml` to validate in CI. This section adds one more CI check specific to the UI: after the application context starts in the integration test suite, a test fetches `/v3/api-docs.yaml` and asserts its content is byte-identical to `docs/openapi.yaml` (accounting only for the copy step in §22.6, which should be a pure file copy with no transformation) — this catches the one failure mode particular to a served-static-file approach: someone committing a stale copy at the classpath resource path directly instead of relying on the Maven copy step. It's a cheap test that makes the "single source of truth" claim in §22.1 mechanically enforced rather than aspirational.

### 22.8 Versioned UI (forward-looking, ties to §6.1/A3/A4)

The current single-version (`v1`) contract needs no grouping. When a `v2` is introduced under the breaking-change process in `.claude/plugins/contract-change.md`, springdoc-ui's **groups** feature is the mechanism to expose both simultaneously without one hiding the other: `springdoc.group-configs[0].group=v1` / `group-configs[1].group=v2`, each pointing at its own served static file (`/v3/api-docs/v1.yaml`, `/v3/api-docs/v2.yaml`) via the same static-serve pattern in §22.1/§22.3 duplicated per version, giving a version-switcher dropdown in the UI. The redirect endpoint, being permanently unversioned (§6.1, A2), appears identically in every group's document rather than needing its own group. This is documented here as the forward-looking mechanism, not implemented yet, since only `v1` currently exists.

---

## 23. Implementation Cross-Verification: Resolved Contradictions & Corrections

Building the service from this document surfaced the points below. Each is resolved in the code and pinned by a test; the complete list (V-1 … V-20), coverage matrix and known limitations are in `docs/design-verification-report.md`.

| Section(s) | Issue | Resolution |
|---|---|---|
| E1/E10 vs §9.2a | Blank `longUrl` / bad alias are `INVALID_URL` / `INVALID_ALIAS` in the catalog, but the §9.2a example shows them as `VALIDATION_FAILED` | Dedicated codes win; §9.2a's example should use a field with no dedicated code. **Needs owner confirmation.** |
| §6.3 vs §15.2 | A different alias on retry returns the original vs. an alias request is not a duplicate of an auto code | Fingerprint includes `kind` (auto/custom): SHA-256(owner, kind, url) |
| §5.1 vs §20.1 | Fingerprint index non-unique vs unique | Unique **nullable**, released on deactivate/expiry/window lapse |
| §5.1, §4.1, §20.1 | `id` identity vs Hibernate `@SequenceGenerator`; the code derives from the id | Hibernate assigns ids inside `persist()`, after INSERT state is fixed, so the NOT NULL `short_code` cannot be set afterwards. An explicit block allocator over the same `url_id_seq` table is used |
| F9 vs §4.1 | Auto-code collision is a bug, but aliases share the namespace | Auto-vs-custom clash → next id (metered); auto-vs-auto → F9 (500 + alert, never overwrite) |
| §9.2 | `MALFORMED_JSON` for wrong JSON types needs explicit Jackson coercion config | Implemented (`JacksonConfig`) |
| §10.3 | Non-canonical numeric hosts (`0177.0.0.1`) bypass a resolve-and-check guard | Rejected as ambiguous IP notation |
| §20.4a | `click_aggregates.short_code` needs `utf8mb4_bin` too | Applied |
| F4 | Replica reads need routing; repository reads are `readOnly` and would send auth lookups to the replica | `ReplicaRoutingConfig`; auth, metadata and writes stay on the primary |
| F2 | Lost invalidation while Redis is down | Retried until Redis accepts it; TTL is the hard bound |
| §22.3 | Enabling springdoc and serving a static controller at `/v3/api-docs.yaml` lets the *generated* spec answer | Generator relocated to `/v3/generated-api-docs` and hidden; UI config path stays reachable |
| §8.2.1 | "≥50% over last 20 calls" needs `minimumNumberOfCalls` (default 100) lowered | 10 (window 20) / 5 (window 10) |
| §20.1 | `ofExponentialRandomBackoff` is not full jitter | Custom `random(0, min(cap, base·2^n))` |
| §15.1–15.3 vs the repository | The plan text describes load tests, a raw-event reconciliation test, an added migration rollback script and an engineer-corrected first-draft cache race | None is evidenced in the repository: no load tests; raw events are not retained (§5.2) so reconciliation against them is impossible as written; no rollback script exists (V1–V3 are expand-only); the traceability log has no entry for the race. §15 now carries an *As built* block per scenario. **Open for the engineer:** supply the evidence, say it happened elsewhere, or downgrade the plan text (V-20) |
