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

## Setup guide

### 1. Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | **21** | The build targets Java 21. A newer default JDK (e.g. 24) is not the supported toolchain, so point `JAVA_HOME` at a 21 install. |
| Docker + Compose v2 | any recent | Runs MySQL 8.4, Redis 7 and RabbitMQ 3.13 locally. Also required for `./mvnw verify` (Testcontainers). |
| `curl`, `jq` | any | Only for the manual testing section. |
| Python 3 | any recent | Only for `scripts/verify-design-coverage.py`. |

Maven is not needed: the repo ships the Maven wrapper (`./mvnw`).

```bash
java -version                       # must report 21.x
export JAVA_HOME=$(/usr/libexec/java_home -v 21)     # macOS; on Linux point it at your JDK 21 directory
docker compose version
```

### 2. Get the code

```bash
git clone https://github.com/mveluru/ai_url_shortner.git
cd ai_url_shortner
```

### 3. Start the backing services

```bash
docker compose up -d
docker compose ps                   # wait until mysql shows "healthy"
```

| Service | Host port | Credentials |
|---|---|---|
| MySQL 8.4 (`urlshortener` database) | `3306` (override with `MYSQL_PORT`) | `urlshortener` / `urlshortener` (root: `root`) |
| Redis 7 | `6379` | none |
| RabbitMQ 3.13 | `5672` (AMQP), `15672` (management UI) | `guest` / `guest` |
| Prometheus (optional) | `9090` | `docker compose --profile observability up -d` |

**Port 3306 already in use?** Publish MySQL on another host port and tell the app where to find it:

```bash
MYSQL_PORT=3307 docker compose up -d
export DB_URL='jdbc:mysql://localhost:3307/urlshortener?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=2000&socketTimeout=3000'
```

There is no schema to create by hand: Flyway applies `src/main/resources/db/migration` on first startup.

### 4. Configuration

With no profile set, the app runs as **`local`**, whose defaults match `docker-compose.yml`, so no configuration is needed for local development. Override any of these with environment variables:

| Variable | Default (`local`) | Purpose |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:mysql://localhost:3306/urlshortener?…` / `urlshortener` / `urlshortener` | MySQL |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis cache |
| `RABBIT_HOST` / `RABBIT_PORT` / `RABBIT_USER` / `RABBIT_PASSWORD` | `localhost` / `5672` / `guest` / `guest` | Click-event queue |
| `PUBLIC_BASE_URL` | `http://localhost:8080` | Base of the `shortUrl` returned to clients |
| `APP_CODE_FEISTEL_KEY`, `APP_IP_HASH_SECRET` | dev-only values | Secrets. **Must** be set in `prod`; `ProdSecretsGuard` refuses the dev values. |

The `prod` profile has **no defaults** for endpoints or secrets (startup fails if one is missing), sets `MANAGEMENT_PORT` (default `8081`) for actuator, and turns Swagger UI off. `/internal/**` helper endpoints exist only in `local`/`test`.

### 5. Verify the setup (optional)

```bash
./mvnw test                                     # unit tests, no Docker
./mvnw verify                                   # + integration and failure-injection tests (Docker required)
python3 scripts/verify-design-coverage.py       # design coverage check
```

## Starting the application

### Local development (recommended)

```bash
docker compose up -d                                                  # if not already running
./mvnw -pl url-shortener-service spring-boot:run
```

The first run downloads dependencies and generates the API interfaces from `docs/openapi.yaml`. The app is ready when the log shows Flyway migrating and Spring Boot's `Started ... in N seconds` line. Then check it:

```bash
curl -s localhost:8080/actuator/health          # {"status":"UP", ... db, redis and rabbit all UP}
```

| URL | What |
|---|---|
| <http://localhost:8080/swagger-ui.html> | API explorer (served from `docs/openapi.yaml`) |
| <http://localhost:8080/actuator/health> | Health (readiness depends on MySQL only; Redis/RabbitMQ down degrades but does not fail readiness) |
| <http://localhost:8080/actuator/prometheus> | Metrics |
| <http://localhost:15672> | RabbitMQ management UI (`guest` / `guest`) |

Stop the app with `Ctrl+C` (it shuts down gracefully). Now go to [Manual testing with curl](#manual-testing-with-curl).

### From an IDE

Import the root `pom.xml` as a Maven project with a JDK 21 SDK, build once (`./mvnw -pl url-shortener-service generate-sources`) so the generated API interfaces exist, then run `com.urlshortener.UrlShortenerApplication`. No program arguments or profile are needed.

### As a jar

```bash
./mvnw -q -pl url-shortener-service -am -DskipTests package
java -jar url-shortener-service/target/url-shortener-service-*.jar         # runs as the local profile
```

### As a container (production profile)

```bash
docker build -t url-shortener .

docker run --rm -p 8080:8080 -p 8081:8081 \
  -e DB_URL='jdbc:mysql://<mysql-host>:3306/urlshortener?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=2000&socketTimeout=3000' \
  -e DB_USER=<user> -e DB_PASSWORD=<password> \
  -e REDIS_HOST=<redis-host> \
  -e RABBIT_HOST=<rabbit-host> -e RABBIT_USER=<user> -e RABBIT_PASSWORD=<password> \
  -e PUBLIC_BASE_URL=https://<your-short-domain> \
  -e APP_CODE_FEISTEL_KEY=<secret> -e APP_IP_HASH_SECRET=<secret> \
  url-shortener
```

The image sets `SPRING_PROFILES_ACTIVE=prod`. The public API is on `8080`; actuator (`/actuator/health`, `/actuator/prometheus`) is on the separate management port `8081` and must not be exposed publicly. From a container, `localhost` is the container itself, so use real hostnames (or `host.docker.internal` for services on your machine). Terminate TLS and set `X-Forwarded-*` at the gateway/load balancer: `prod` uses `forward-headers-strategy: native` so per-IP limits see the real client.

### Stopping and resetting

```bash
docker compose down            # stop the services, keep the data
docker compose down -v         # stop and delete MySQL/Redis/RabbitMQ data (fresh database on next start)
```

### Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `Port 8080 was already in use` | Another instance is running. `lsof -iTCP:8080 -sTCP:LISTEN`, stop it, or start with `SERVER_PORT=8081`. |
| `Communications link failure` / Hikari timeout to MySQL | MySQL is not healthy yet (`docker compose ps`), or it is on another port: set `DB_URL` (see step 3). |
| `Access denied` on MySQL | Stale volume from an older run with different credentials: `docker compose down -v` and start again. |
| Build fails with a Java or release error | Wrong JDK: `java -version` must show 21; set `JAVA_HOME`. |
| `/actuator/health` shows `redis` or `rabbit` DOWN | Service not running: `docker compose up -d`. Redirects still work from MySQL, but clicks are not recorded. |
| `404` on `/internal/api-keys` | Running under `prod`; those helpers exist only in `local`/`test`. |

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
