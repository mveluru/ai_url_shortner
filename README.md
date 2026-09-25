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
