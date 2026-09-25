# URL Shortener

Java 21 · Spring Boot 3.3 · MySQL 8.4 · Redis 7 · RabbitMQ 3.13. Built from `urldesign/url-shortener-comprehensive-design.md` (v2.5).

## Documentation map

| File | Purpose |
|---|---|
| `urldesign/url-shortener-comprehensive-design.md` | The design (source of truth) |
| `docs/openapi.yaml` | Wire contract; controller interfaces are generated from it |
| `docs/design-verification-report.md` | Design ↔ code cross-check: coverage matrix, contradictions found, limitations |
| `docs/architecture-diagrams.md` | Mermaid: components, create/redirect/analytics sequences, ER, failure map, topology |
| `docs/engineering-summary.md` | Plan, three scenarios, risks, assumptions (assignment deliverable) |
| `docs/ai-traceability-log.md` | generated / edited / rejected log for this build |
| `.claude/` | AI-assistant context: invariants, rules, per-component skills, workflows |
| `ops/prometheus-alerts.yml` | Alert rules from design §12.4 |

## Quick start

Prerequisites: JDK 21, Docker.

```bash
docker compose up -d                                  # MySQL, Redis, RabbitMQ
JAVA_HOME=<jdk21> ./mvnw -pl url-shortener-service spring-boot:run   # Flyway migrates on startup

KEY=$(curl -s -X POST localhost:8080/internal/api-keys | jq -r .apiKey)     # dev/test profiles only
curl -s -X POST localhost:8080/api/v1/urls -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
     -d '{"longUrl":"https://example.com"}'
curl -i localhost:8080/<shortCode>                     # 302 + Cache-Control: no-store; click queued asynchronously
curl -s localhost:8080/api/v1/urls/<shortCode>/stats -H "X-API-Key: $KEY"
```

Swagger UI (local): <http://localhost:8080/swagger-ui.html> · health: `/actuator/health` · metrics: `/actuator/prometheus`.

## Manual testing with curl

Assumes the service is running on `localhost:8080` (`local`/`test` profile) and `jq` is installed.

```bash
BASE=http://localhost:8080
KEY=$(curl -s -X POST $BASE/internal/api-keys -H 'Content-Type: application/json' -d '{"name":"manual-test"}' | jq -r .apiKey)  # shown once
```

**Health**

```bash
curl -s $BASE/actuator/health
```

**Create a short URL** (`201` + `Location`)

```bash
curl -i -X POST $BASE/api/v1/urls -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
     -d '{"longUrl":"https://example.com/some/long/path"}'
CODE=$(curl -s -X POST $BASE/api/v1/urls -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
     -d '{"longUrl":"https://example.com/some/long/path"}' | jq -r .shortCode)
```

**Idempotent replay**: the same `(key, longUrl)` within 24h returns `200` + `Idempotent-Replay: true`

```bash
curl -i -X POST $BASE/api/v1/urls -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
     -d '{"longUrl":"https://example.com/some/long/path"}'
```

**Custom alias and expiry** (`expiresAt` is ISO-8601, UTC)

```bash
curl -i -X POST $BASE/api/v1/urls -H "X-API-Key: $KEY" -H 'Content-Type: application/json' \
     -d '{"longUrl":"https://example.com/promo","customAlias":"my-promo","expiresAt":"2030-01-01T00:00:00Z"}'
```

**Redirect**: `302`, `Location: <longUrl>`, `Cache-Control: no-store`; no API key needed

```bash
curl -i $BASE/$CODE
```

**Get metadata** (`status`: `ACTIVE` / `EXPIRED` / `DEACTIVATED`)

```bash
curl -s $BASE/api/v1/urls/$CODE -H "X-API-Key: $KEY" | jq
```

**Click stats** (daily UTC buckets, eventually consistent; `from`/`to` optional)

```bash
curl -s "$BASE/api/v1/urls/$CODE/stats" -H "X-API-Key: $KEY" | jq
curl -s "$BASE/api/v1/urls/$CODE/stats?from=2026-09-01&to=2026-09-30" -H "X-API-Key: $KEY" | jq
```

**Deactivate** (`204`; a second call and later redirects return `404`)

```bash
curl -i -X DELETE $BASE/api/v1/urls/$CODE -H "X-API-Key: $KEY"
curl -i $BASE/$CODE
```

**Error cases**

```bash
# 401 UNAUTHORIZED: missing / bad key
curl -i -X POST $BASE/api/v1/urls -H 'Content-Type: application/json' -d '{"longUrl":"https://example.com"}'

# 400 INVALID_URL: malformed URL
curl -i -X POST $BASE/api/v1/urls -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{"longUrl":"not a url"}'

# 400 INVALID_URL: SSRF guard rejects private / loopback / link-local targets
curl -i -X POST $BASE/api/v1/urls -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{"longUrl":"http://127.0.0.1:8080/admin"}'
curl -i -X POST $BASE/api/v1/urls -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{"longUrl":"http://169.254.169.254/latest/meta-data"}'

# 400 ALIAS_RESERVED: reserved word (matched case-insensitively)
curl -i -X POST $BASE/api/v1/urls -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{"longUrl":"https://example.com","customAlias":"Admin"}'

# 409 ALIAS_TAKEN: alias already in use (create "my-promo" twice with different longUrls)
curl -i -X POST $BASE/api/v1/urls -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{"longUrl":"https://example.org","customAlias":"my-promo"}'

# 404 NOT_FOUND: unknown code (same body for expired / deactivated / malformed codes)
curl -i $BASE/doesNotExist

# 403 FORBIDDEN: a code owned by a different API key
KEY2=$(curl -s -X POST $BASE/internal/api-keys | jq -r .apiKey)
curl -i $BASE/api/v1/urls/$CODE -H "X-API-Key: $KEY2"
```

**Dev-only helpers** (`/internal/**` exists only in `local`/`test` profiles)

```bash
KEY_ID=$(curl -s -X POST $BASE/internal/api-keys | jq -r .keyId)
curl -i -X DELETE $BASE/internal/api-keys/$KEY_ID      # revoke a key (rotation: issue new, then revoke old)
curl -i -X DELETE $BASE/internal/urls/$CODE            # hard delete; frees a soft-deleted alias
```

**Metrics**

```bash
curl -s $BASE/actuator/prometheus | grep -E '^(http_server_requests|resilience4j_circuitbreaker)' | head
```

## Architecture at a glance

```
client → [rate limit, auth] → Shortener (write)  ─┐
                              Redirect (read) ────┼→ Redis (cache-aside) → MySQL primary / read replica
                              Analytics (async) ← RabbitMQ (quorum, DLQ) ← click events (fire-and-forget)
```

Decisions worth knowing before reading the design:
1. Codes = counter → keyed Feistel permutation → Base62. No collision retries, not enumerable.
2. The redirect path depends only on Redis **or** MySQL — never on the queue, analytics store or auth.
3. `302` + `no-store`, so expiry/deactivation/analytics stay reliable.
4. The DB unique constraint is the sole arbiter of alias races; nothing pre-checks.
5. Every dependency call has its own circuit breaker; retries (full jitter) sit inside the breaker; writes are never retried.

## Testing

```bash
./mvnw test        # ~300 unit tests, no Docker
./mvnw verify      # + ~90 integration & failure-injection tests on REAL MySQL/Redis/RabbitMQ (Docker required)
python3 scripts/verify-design-coverage.py    # fails if any E1–E24 / F1–F13 row lacks a test
```

A failure-injection test cuts, black-holes or slows a real dependency through Toxiproxy and asserts the *defined* mitigated
behaviour (e.g. Redis down ⇒ redirects still 302 from MySQL, breaker opens, recovery closes it). Not a mock standing in for a failure.

## Contributing

Engineer-owned, AI-assisted. Tag AI-produced changes `[generated]`, `[edited]` or `[rejected]` with a one-line rationale
(`docs/ai-traceability-log.md`). Changes to the redirect hot path, security rules or migrations need explicit engineer sign-off.
Read `.claude/CLAUDE.md` first.

## Known limitations

See `docs/design-verification-report.md` §4. Notably: per-instance rate limits, creation-time-only SSRF check, replica-lag staleness,
and **no load test has been run** (the 5,000 rps / p99 targets are unverified).
