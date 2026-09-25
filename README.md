# URL Shortener

Java 21 · Spring Boot 3.3 · MySQL 8.4 · Redis 7 · RabbitMQ 3.13. Built from `urldesign/url-shortener-comprehensive-design.md` (v2.5).

## Documentation map

| File | Purpose |
|---|---|
| `urldesign/url-shortener-comprehensive-design.md` | The design (source of truth) |
| `docs/openapi.yaml` | Wire contract; controller interfaces are generated from it |
| `postman/url-shortener.postman_collection.json` | Postman collection: 71 requests, 165 assertions (see *Testing with Postman*) |
| `docs/design-verification-report.md` | Design ↔ code cross-check: coverage matrix, contradictions found, limitations |
| `docs/architecture-diagrams.md` | Mermaid: components, create/redirect/analytics sequences, ER, failure map, topology |
| `docs/engineering-summary.md` | Plan, three scenarios, risks, assumptions (assignment deliverable) |
| `docs/ai-traceability-log.md` | generated / edited / rejected log for this build |
| `.claude/` | AI-assistant context: invariants, rules, per-component skills, workflows |
| `ops/prometheus-alerts.yml` | Alert rules from design §12.4 |

## Quick start

Prerequisites: JDK 21, Docker.

**Windows, one command:** with Docker Desktop and JDK 21 installed, run `run-local.bat` in the repo root. It starts everything, picks free ports for you and opens Swagger UI. Details: [Windows: one command](#windows-one-command-run-localbat).

**macOS / Linux, one command:** with Docker and JDK 21 installed, run `./run-local.sh` in the repo root ([details](#macos--linux-one-command-run-localsh)).

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
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` | Redis cache. `REDIS_PORT` also sets the port Docker publishes. |
| `RABBIT_HOST` / `RABBIT_PORT` / `RABBIT_USER` / `RABBIT_PASSWORD` | `localhost` / `5672` / `guest` / `guest` | Click-event queue |
| `SERVER_PORT` | `8080` | HTTP port of the app (set `PUBLIC_BASE_URL` to match) |
| `MYSQL_PORT`, `RABBIT_MGMT_PORT` | `3306`, `15672` | Docker compose only: host ports for MySQL and the RabbitMQ UI (`RABBIT_PORT` sets the AMQP port for both compose and the app) |
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

### Windows: one command (`run-local.bat`)

For someone who has just been sent this repo and wants it running with no manual steps.

**One-time install:** [Docker Desktop](https://www.docker.com/products/docker-desktop/) (start it and wait until it says *running*) and **JDK 21** (for example Eclipse Temurin 21). Set `JAVA_HOME` to that JDK if it is not your default Java. Nothing else: no Maven, MySQL, Redis or RabbitMQ install, and no `jq` or bash.

```bat
git clone https://github.com/mveluru/ai_url_shortner.git
cd ai_url_shortner
run-local.bat
```

What it does, in order: checks Docker and Compose v2, checks that Java is 21 → picks ports → `docker compose up -d --wait` for MySQL, Redis and RabbitMQ → waits for RabbitMQ → sets `DB_URL`, `PUBLIC_BASE_URL` and the port variables for the app → opens Swagger UI once `/actuator/health` responds → runs `mvnw.cmd -pl url-shortener-service spring-boot:run`. The first run pulls Docker images and downloads Maven dependencies, so allow a few minutes. Flyway creates the schema.

**Ports never need to be edited by hand.** The script reuses containers that are already running from this project. For anything else it takes the default (`3306`, `6379`, `5672`, `15672`, `8080`) or the next free port if something on your machine already uses it. It prints the final URLs and ports, so use those. `run-local.bat noopen` skips opening the browser.

**Try it** (Command Prompt, using `curl.exe`, which ships with Windows 10 and later; use the port the script printed if it is not 8080):

1. **Swagger UI**, no tools needed: open `http://localhost:8080/swagger-ui.html`. Get a key with the request below, click **Authorize**, paste the key, then use *Try it out* on `POST /api/v1/urls`.
2. **Command line:**

```bat
curl.exe -s -X POST http://localhost:8080/internal/api-keys
rem copy the value of "apiKey" from the response, then:
curl.exe -s -X POST http://localhost:8080/api/v1/urls -H "X-API-Key: PASTE_KEY_HERE" -H "Content-Type: application/json" -d "{\"longUrl\":\"https://example.com\"}"
rem copy the "shortCode" from the response, then:
curl.exe -i http://localhost:8080/PASTE_CODE_HERE
```

The last call should return `302` with `Location: https://example.com` and `Cache-Control: no-store`. The full scenario list (aliases, expiry, stats, error cases) is in [Manual testing with curl](#manual-testing-with-curl); those examples are written for bash and `jq`, so on Windows use Git Bash or WSL for them.

**Stop:** press `Ctrl+C` in the `run-local.bat` window, then run `stop-local.bat` (data kept) or `stop-local.bat reset` (also deletes all local data, asks you to type `YES`).

> The two `.bat` files have not yet been run on a real Windows machine, only reviewed and checked against the compose file (limitation L23). If one fails, the message it prints names the step; please report it. macOS and Linux users use `run-local.sh` below.

### macOS / Linux: one command (`run-local.sh`)

Same idea as the Windows script, for bash (works with the bash 3.2 that ships with macOS).

**One-time install:** Docker (Docker Desktop on macOS; Docker Engine with the compose plugin on Linux) and **JDK 21** (`brew install --cask temurin@21` on macOS). The script finds a JDK 21 for you (`/usr/libexec/java_home -v 21`, `/usr/lib/jvm`, sdkman, or `java` on the `PATH`), so you do not need to set `JAVA_HOME` or change your default Java. No Maven, `jq` or database install is needed.

```bash
git clone https://github.com/mveluru/ai_url_shortner.git
cd ai_url_shortner
./run-local.sh              # add --no-open to skip opening Swagger UI in the browser
```

It does the same steps as `run-local.bat`: checks Docker and JDK 21, reuses this project's running containers or picks free ports, starts MySQL, Redis and RabbitMQ, exports the app configuration, opens Swagger UI once the app is healthy, and runs `./mvnw -pl url-shortener-service spring-boot:run`. **Ports need no manual editing**: if 8080 (or 3306, 6379, 5672, 15672) is busy it uses the next free one and prints the URLs. The first run pulls images and downloads dependencies, so allow a few minutes.

Test it with the commands in [Manual testing with curl](#manual-testing-with-curl) (replace `localhost:8080` with the port the script printed if it differs), or use Swagger UI.

**Stop:** `Ctrl+C` in the script's terminal, then `./stop-local.sh` (data kept) or `./stop-local.sh reset` (also deletes all local data, asks you to type `YES`).

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

#### Trying the container next to the compose services (local trial)

This was run on macOS (image built, container started, API exercised). It runs the real `prod` profile against the compose MySQL, Redis and RabbitMQ.

```bash
docker compose up -d
docker build -t url-shortener .

# 1. Give the container its OWN database schema and RabbitMQ vhost (see the warning below)
docker compose exec -T mysql mysql -uroot -proot -e "CREATE DATABASE IF NOT EXISTS urlshortener_docker CHARACTER SET utf8mb4; GRANT ALL ON urlshortener_docker.* TO 'urlshortener'@'%'; FLUSH PRIVILEGES;"
docker compose exec -T rabbitmq rabbitmqctl add_vhost docker_prod
docker compose exec -T rabbitmq rabbitmqctl set_permissions -p docker_prod guest '.*' '.*' '.*'

# 2. Run it on the compose network (find its name with `docker network ls`; it is usually <folder>_default).
#    Host ports 8082/8083 avoid a local app on 8080. Secrets must be >= 32 chars and not start with "dev-only".
docker run -d --name url-shortener-prod --network <folder>_default -p 8082:8080 -p 8083:8081 \
  -e DB_URL='jdbc:mysql://mysql:3306/urlshortener_docker?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=2000&socketTimeout=3000' \
  -e DB_USER=urlshortener -e DB_PASSWORD=urlshortener \
  -e REDIS_HOST=redis \
  -e RABBIT_HOST=rabbitmq -e RABBIT_USER=guest -e RABBIT_PASSWORD=guest -e SPRING_RABBITMQ_VIRTUAL_HOST=docker_prod \
  -e PUBLIC_BASE_URL=https://localhost:8082 \
  -e APP_CODE_FEISTEL_KEY=$(openssl rand -hex 24) -e APP_IP_HASH_SECRET=$(openssl rand -hex 24) \
  url-shortener

docker logs -f url-shortener-prod        # ready at "Started UrlShortenerApplication" (about 17 s)
curl -s http://localhost:8083/actuator/health          # management port: {"status":"UP",...}
```

What to expect, and why:

- **`PUBLIC_BASE_URL` must start with `https://`** in `prod` (`ProdSecretsGuard`). There is no TLS locally, so returned `shortUrl` values read `https://localhost:8082/<code>` while the container serves plain HTTP. Open `http://localhost:8082/<code>` instead.
- **There is no way to issue an API key in `prod`** (`/internal/**` is `local`/`test` only, L17). For a local trial, issue a key on a `local` instance and copy its row: `docker compose exec -T mysql mysql -uroot -proot -e "INSERT INTO urlshortener_docker.api_keys SELECT * FROM urlshortener.api_keys WHERE key_id='<keyId>'"`. The key is hashed with Argon2, so the same key works in both schemas.
- **Public port `8082`:** `/actuator/**`, `/swagger-ui.html` and `/internal/**` return `404`. Actuator is only on `8083`.
- **Logs are JSON** (`docker logs url-shortener-prod`).

> **Warning: do not point a second app instance at a different database but the same RabbitMQ vhost.** Click events go through one shared queue (competing consumers). With a local app (database A) and the container (database B) on the same vhost, each consumes about half of *both* apps' clicks and writes them into its own database: in the trial, 4 redirects showed as `totalClicks: 2` on the container and the other 2 appeared in the local app's schema. Instances that serve the same data share a database and a queue; separate environments need separate vhosts (`SPRING_RABBITMQ_VIRTUAL_HOST`) as above.

Clean up:

```bash
docker rm -f url-shortener-prod
docker compose exec -T mysql mysql -uroot -proot -e "DROP DATABASE urlshortener_docker"
docker compose exec -T rabbitmq rabbitmqctl delete_vhost docker_prod
```

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
| `run-local.bat`: `[ERROR] Docker is not installed or not running` | Start Docker Desktop and wait until it reports *running*. |
| `run-local.bat`: `[ERROR] JDK 21 is required` | The default `java` is another version. Install JDK 21, point `JAVA_HOME` at it, open a **new** terminal. |
| `run-local.bat` window closes at once | Start it from an open Command Prompt so the message stays visible (it also pauses on errors). |
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

## Testing with Postman

`postman/url-shortener.postman_collection.json` exercises every endpoint and error case end to end. It issues its own API keys, creates what it needs, asserts on the responses and cleans up after itself, so it can be re-run at any time.

**In Postman:** *Import* the file, open the collection, and use **Run** (Runner) to execute it in order. Start the app first with `./run-local.sh` or `run-local.bat`. If the app is not on port 8080, change the `baseUrl` variable on the collection's **Variables** tab. Nothing else needs configuring: `apiKey`, `shortCode` and the rest are collection variables the requests set for each other.

**From the command line** (Node 18+; nothing is installed into the repo):

```bash
npx newman run postman/url-shortener.postman_collection.json
npx newman run postman/url-shortener.postman_collection.json --env-var baseUrl=http://localhost:8081   # another port
```

| Folder | What it checks |
|---|---|
| 0. Setup | Issues three API keys (`/internal/api-keys`): main, a foreign key for the `403` test, and one for the error cases (see the rate-limit note) |
| 1. Create | `201` + `Location`; idempotent replay (`200`, `Idempotent-Replay: true`); custom alias + expiry |
| 2. Redirect | `302`, `Location`, `Cache-Control: no-store` (redirect following is switched off on these requests) |
| 3. Read | Metadata (`ACTIVE`); stats (waits 3 s for the async pipeline, asserts `totalClicks >= 1`) |
| 4. Positive paths | **4a** accepted inputs at their limits: alias of exactly 3 and exactly 20 characters (mixed case, `_`, `-`), aliases differing only by case, a URL of exactly 2048 characters, a URL with port / encoded characters / query / fragment, plain `http://`. **4b** each of those redirects with the `Location` equal to what was stored, byte for byte. **4c** analytics with real headers: 4 clicks with different `Referer` and `User-Agent` values, then *exact* stats (`totalClicks: 4`, referrers reduced to lowercase hosts with no path or token kept, `mobile`/`desktop`/`other` split, one UTC day bucket), an explicit range that contains the clicks, a past range that is empty (`200`, not an error), and the default 30-day window. **4d** the same URL from another key gets its own code, `HEAD` on a link, a browser `Accept` header, metadata of an alias showing its expiry |
| 5. Error cases | `401`, `400` (malformed URL, SSRF: loopback, metadata IP, octal host, non-http scheme, reserved alias, bad alias, past expiry, bad range, a URL one character over the 2048 limit), `409`, `404`, `403`; each asserts the `code`, a `requestId`, and that no exception text leaks |
| 6. Lifecycle | Deactivate `204`, then `404` for redirect, repeat delete and stats; metadata shows `DEACTIVATED` |
| 7. Cleanup | Hard-deletes every URL the run created and revokes the three keys (`/internal/**`) |

Things to know:

- **Local profile only.** Setup and Cleanup call `/internal/**`, which does not exist under `prod` (S8), so the collection cannot run against the Docker image as it stands (L17, L26).
- **Stats are eventually consistent.** Folder 3 waits 3 s and asserts a lower bound. Folder 4c polls the stats endpoint for up to about 15 s until all 4 clicks are aggregated, then asserts exact numbers. If the consumer is down or very slow that request fails and reports the count it saw.
- **Create is rate limited per API key (burst 20).** Rejected creates count too, so the error-case requests use a third key; otherwise a single key would hit `429 RATE_LIMITED` mid-run. If you add many more create requests, spread them across keys the same way.
- **Device classes need `app.features.stats-device-breakdown`**, which is on in the local profile; with it off, the device assertion in 4c fails.
- **Small timing edge:** the daily-bucket and default-window checks compare against the UTC date at the start of the analytics folder, so a run that straddles UTC midnight can fail once.
- **It leaves small residue by design:** revoked API-key rows and click-aggregate rows stay in the dev database (design E20). Delete them with SQL if you want a pristine schema.
- If an assertion fails, the response body's `requestId` is the handle to find the server-side log line.

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
./mvnw test        # 278 unit tests, no Docker
./mvnw verify      # + 117 integration & failure-injection tests on REAL MySQL/Redis/RabbitMQ (Docker required; ~3 min)
python3 scripts/verify-design-coverage.py    # fails if any E1–E24 / F1–F13 row lacks a test
npx newman run postman/url-shortener.postman_collection.json    # black-box API check against a running local app
```

A failure-injection test cuts, black-holes or slows a real dependency through Toxiproxy and asserts the *defined* mitigated
behaviour (e.g. Redis down ⇒ redirects still 302 from MySQL, breaker opens, recovery closes it). Not a mock standing in for a failure.

## Requirement coverage report

Measured on **2026-09-25** against `main` with JDK 21, MySQL 8.4 / Redis 7 / RabbitMQ 3.13 (Testcontainers + Toxiproxy).
Requirements come from design v2.5 (`urldesign/url-shortener-comprehensive-design.md`): §2.3 (a–f), §7 (E1–E24), §8 (F1–F13), §10, §12, §17.

| Check | Command | Result |
|---|---|---|
| Unit tests | `./mvnw test` | **278 run, 0 failed, 0 skipped** |
| Integration + failure-injection | `./mvnw verify` | **117 run, 0 failed, 0 skipped** (build 3 min 7 s) |
| Design-row coverage | `python3 scripts/verify-design-coverage.py` | **37 / 37 rows tagged** (36 with tests, F10 infrastructure-only) |
| Live check | curl walk-through against a running instance (see *Manual testing with curl*) | every documented status matched |

> **How to read "37 / 37":** the script confirms every E/F row has at least one test tagged `@Covers`. It does **not** check that the test passes
> (the two runs above do), that it is the right *kind* of test (`R5`), or that the requirement is *sufficient*. The "Kind" columns below were checked by hand.
> Not covered anywhere: see [Known limitations](#not-verified-gaps-in-the-evidence-not-necessarily-in-the-code) L1–L5.

**Legend:** ✅ implemented and tested at the right level · ⚠️ implemented, evidence is partial · ❌ not verified or not built.

### 1. Functional requirements (design §2.3)

| Req | Requirement | Status | Evidence |
|---|---|---|---|
| a | Create short codes; optional custom alias and expiry | ✅ | `CreateUrlIT` (16), `AliasValidatorTest`, `ExpiryValidatorTest`; idempotent replay `200` + `Idempotent-Replay` |
| a | Codes unique, short, hard to guess | ✅ | Counter → keyed Feistel → Base62: `FeistelPermutationTest` (exhaustive bijection), `Base62Test`, `CodeLengthPolicyTest`, `IdAllocationIT`, `CollisionIT` |
| b | Redirect: `302`, `no-store`, public, depends only on Redis-or-MySQL | ✅ | `RedirectIT` (16), `FailureInjectionIT`; verified live |
| b | Redirect latency: cache-hit < 50 ms, cache-miss p99 < 150 ms | ❌ | No load test (L1) |
| c | Clicks recorded asynchronously; never block the redirect | ✅ | `AnalyticsIT` (11), `PublishingTest`, `FailureInjectionIT` F5/F6 |
| d | Analytics read API (`/stats`, daily UTC buckets, `updatedAt`, `INVALID_RANGE`) | ✅ | `AnalyticsIT`; verified live (4 redirects → `totalClicks: 4`) |
| e | Defined degradation under cache / DB / queue failure | ✅ | `FailureInjectionIT` (16), `ReplicaFallbackIT`; see section 3 |
| f | Safe API evolution: versioned, contract-first, `openapi.yaml` ↔ DTOs agree | ✅ | `OpenApiContractTest` (8), `SwaggerIT` (served file byte-identical) |
| — | Deactivation (`DELETE` → `204`, then `404`) | ✅ | `UrlLifecycleIT`; verified live |
| — | Management API auth (API key, Argon2, ownership `403`) | ✅ | `AuthIT`; verified live (`401`, `403`) |
| — | Error taxonomy: 20 codes, one `GlobalExceptionHandler`, `requestId` only | ✅ | `GlobalExceptionHandlerTest` (31, one row per code), `ErrorContractIT` |
| — | Rate limiting per key / per IP (`429` + `Retry-After`) | ⚠️ | `RateLimitIT`, `IpRateLimitIT`; per-instance only (L7) |

### 2. Edge cases E1–E24 (design §7): 24 / 24 tested, all passing

| Rows | Topic | Tests (unit and integration) |
|---|---|---|
| E1–E6 | URL validation: empty, malformed, scheme allowlist, SSRF, self-referential, > 2048 chars | `UrlValidatorTest` (79), `IpClassifierTest` (47), `CreateUrlIT` |
| E7–E10 | Alias collision, soft-deleted alias, reserved words (case-insensitive), pattern | `AliasValidatorTest` (45), `CreateUrlIT`, `CollisionIT`, `MigrationIT` |
| E11–E12 | Expiry in the past / absent | `ExpiryValidatorTest`, `CreateUrlIT` |
| E13 | Concurrent alias race: exactly one `201`, other `409`, decided by the DB constraint | `CreateUrlIT`, `UrlLifecycleIT` (real MySQL) |
| E14–E16 | Expired / deactivated / never-existed → uniform `404` | `RedirectIT`, `UrlLifecycleIT`, `ExpiryBoundaryTest` |
| E17 | Code length grows at 80 % of the space | `CodeLengthPolicyTest` |
| E18 | Illegal path characters → `404` | `RedirectIT` (residual: L12) |
| E19 | Create spam → `429` | `RateLimitIT`, `RateLimitsTest`, `GlobalExceptionHandlerTest` |
| E20–E22 | Click after delete, duplicate events (dedup by `event_id`), invalid range | `AnalyticsIT` (real RabbitMQ) |
| E23 | Double `DELETE` → `204` then `404` | `UrlLifecycleIT` |
| E24 | Oversized `Referer` / `User-Agent` truncated to 512, never rejected | `AnalyticsIT`, `PublishingTest`, `RedirectIT` |

### 3. Failure modes F1–F13 (design §8)

"Real infra" = fault injected into a real dependency through Toxiproxy or a real container, which is what `R5` requires. A mocked unit test alone does not count.

| Row | Failure | Status | Kind | Tests |
|---|---|---|---|---|
| F1 | Redis unreachable → MySQL fallback, breaker opens and recovers | ✅ | Real infra | `FailureInjectionIT`, `RedirectIT`, `FullJitterIntervalFunctionTest` |
| F2 | Cache/DB desync → invalidate, never update | ✅ | Real infra | `FailureInjectionIT`, `RedirectIT` |
| F3 | MySQL primary down (refused **and** black-holed) → fast `503`, no write retry | ✅ | Real infra | `FailureInjectionIT`, `ReplicaFallbackIT` |
| F4 | Primary down, replica up → redirect served from replica | ✅ | Real infra | `FailureInjectionIT`, `ReplicaFallbackIT` |
| F5 | Queue unreachable → event dropped, redirect unaffected, metric | ✅ | Real infra | `FailureInjectionIT`, `PublishingTest` |
| F6 | Consumer down / behind → `updatedAt` shows staleness; DLQ | ✅ | Real infra | `FailureInjectionIT`, `AnalyticsIT` |
| F7 | Cache-miss stampede → single DB read (60 concurrent misses ⇒ ≤ 3) | ⚠️ | Real infra, **functional scale** | `FailureInjectionIT` (4 tests); not load-tested (L1) |
| F8 | ID-block allocation / contention | ✅ | Real infra | `IdAllocationIT`, `ShortCodeGeneratorTest` |
| F9 | Short-code collision never overwrites; `500` + metric | ✅ | Real infra | `CollisionIT`, `IdAllocationIT`, `MigrationIT`, `UniqueConstraintTest` |
| F10 | AZ / region outage | ❌ | Infrastructure only | Documented (L4); app-level half tested under F4 |
| F11 | Auth store down → redirects unaffected | ✅ | Real infra | `AuthIT`, `FailureInjectionIT` |
| F12 | Expiry evaluated at read time, not by the sweep | ✅ | Real infra | `RedirectIT`, `ExpiryBoundaryTest` |
| F13 | Clock skew between instances | ⚠️ | **Unit only** | `ExpiryBoundaryTest`; no multi-node test (L3) |

### 4. Non-functional, security and process requirements

| Area | Requirement | Status | Evidence / gap |
|---|---|---|---|
| Security (§10) | SSRF guard before persist, fail closed; private / loopback / link-local / CGNAT / ULA / embedded-IPv4 / ambiguous numeric hosts | ✅ | `UrlValidatorTest`, `IpClassifierTest`; a real bypass (`0177.0.0.1`) was found by test and fixed (V-7) |
| Security | DNS-rebinding payload; re-validation at redirect time | ❌ | Not tested / not built (L2, L6) |
| Security | Alias injection and reserved-word bypass | ✅ | `AliasValidatorTest` (45) |
| Security | No PII, secrets or internals in logs and error bodies | ✅ | `ObservabilityIT` (real log capture), `ErrorContractIT` |
| Security | `prod` refuses dev secrets; `/internal/**` only in `local`/`test` | ✅ | `ProdSecretsGuardTest`, `ProdProfileIT` |
| Data (§5, §20.4a) | `utf8mb4_bin` short codes, case-sensitive (`abc` ≠ `ABC`); UTC; Flyway | ✅ | `MigrationIT` (collation asserted), `AnalyticsIT` |
| Resilience (§8.2) | One breaker per dependency; retry inner, breaker outer; writes never retried | ✅ | `DesignConformanceTest` (9), `FailureInjectionIT` |
| Observability (§12) | Metrics, alerts (`ops/prometheus-alerts.yml`), structured logs, `requestId` == trace id | ⚠️ | `ObservabilityIT`; OTLP export not wired (L15); alert rules are checked against real metric names, not fired in a running Prometheus |
| Capacity (§11) | 5,000 rps baseline, scaling axes | ❌ | Assumption only (L1) |
| Deployment (§13) | Expand/contract migrations, `prod` profile, `Dockerfile` | ⚠️ | `ProdProfileIT`, `MigrationIT`; image built and run locally on macOS with the prod profile (startup, prod-only routes hidden, create/redirect/stats, `401`, SSRF `400`); not run in a real deployment (L22) |
| Test strategy (§17) | Unit, integration, failure-injection | ✅ | 278 + 117 tests |
| Test strategy (§17) | Load tests | ❌ | Not run (L1) |
| Test strategy (§17) | Security tests | ⚠️ | SSRF, open-redirect (E5), alias injection ✅; DNS rebinding ❌ |
| Process (§16, `R2`) | Human sign-off on high-impact paths | ❌ | **Pending** (L5) |
| Process (§16, `R3`) | AI traceability | ✅ | `docs/ai-traceability-log.md` |
| Process (§21.4) | Static-analysis / coverage gates, CI | ❌ | Not configured (L16) |
| Docs (§18, §21) | Setup guide, `.claude/`, diagrams, verification report | ✅ | `README.md`, `.claude/`, `docs/` |

### Summary

- **Fully covered and passing:** every functional requirement except latency, all 24 edge cases, 10 of 13 failure modes fully (F7 and F13 partial, F10 infrastructure), and the security controls that were specified as tests, on real MySQL, Redis and RabbitMQ.
- **Partially covered:** F7 (functional only), F13 (unit only), observability export, rate limiting (per instance), deployment (image not run).
- **Not covered:** load and latency targets, DNS rebinding, F10 / DR (infrastructure), CI and static analysis, and the pending human sign-off.
- **Design questions still open:** V-1 in `docs/design-verification-report.md` (`INVALID_URL` vs `VALIDATION_FAILED` for blank input) needs an explicit engineer decision.

## Contributing

Engineer-owned, AI-assisted. Tag AI-produced changes `[generated]`, `[edited]` or `[rejected]` with a one-line rationale
(`docs/ai-traceability-log.md`). Changes to the redirect hot path, security rules or migrations need explicit engineer sign-off.
Read `.claude/CLAUDE.md` first.

## Known limitations

Detail and rationale: `docs/design-verification-report.md` §4 and design §19 / §20.8. The list below is the complete set, grouped by kind.

### Not verified (gaps in the evidence, not necessarily in the code)

| # | Limitation | Consequence |
|---|---|---|
| L1 | **No load test has been run**; there is no load tooling in the repo. | The targets (5,000 rps, cache-hit < 50 ms, cache-miss p99 < 150 ms, stampede and queue-outage behaviour *at volume*) are **unverified**. Stampede (F7) and queue outage (F5) are tested functionally only. |
| L2 | **No DNS-rebinding test.** Design §17 lists it under security tests. | The SSRF guard is tested with private/loopback/link-local/CGNAT/ULA/embedded-IPv4/ambiguous-numeric hosts, but not with a host that changes its answer between resolutions. See L6. |
| L3 | **F13 (clock skew) has no multi-node test.** It is covered only by a unit test (`ExpiryBoundaryTest`) that expiry uses one time source; `R5` asks for failure-injection against real infrastructure. | Skew tolerance is by design (DB time, no sub-second expiry) rather than demonstrated. |
| L4 | **F10 (AZ outage) and §14 (DR) are infrastructure**, not code. Only the app-level half (replica fallback, F4) is tested. | Multi-AZ, replica promotion, backups and canary mechanics are documented, not exercised. |
| L5 | **Human sign-off (§16 / `R2`) is pending** for the redirect hot path, `SsrfGuard`/`UrlValidator`, `SecurityConfig`, the migrations and the resilience config. | Do not treat this build as approved for production. |

### Behavioural limitations (by design, documented)

| # | Limitation | Consequence |
|---|---|---|
| L6 | **SSRF is checked at creation time only.** Redirect targets are not re-validated. | A domain that resolves publicly at creation and is re-pointed at an internal address later is not caught. |
| L7 | **Rate limits are per instance**, and their values are defaults, not derived from traffic. | The effective global limit scales with the instance count. |
| L8 | **Replica lag can briefly serve a deactivated link** on a cache miss (F4). | A bounded staleness window on the DB-fallback path. |
| L9 | **F2: the pending cache-invalidation queue is in memory per instance.** A crash during a Redis outage falls back to the TTL (default 10 min). | A deleted link can keep redirecting for up to the TTL in that case. |
| L10 | **Click events are dropped during a broker outage (F5, by design).** | Analytics under-count by that amount; redirects are unaffected. Stats are eventually consistent (`updatedAt` shows freshness). |
| L11 | **Argon2 verification is cached per instance for 30 s.** | A revoked API key can keep working for up to 30 s on an instance that already saw it. |
| L12 | **Container-level path rejections return `400`, not `404`** (invalid `%` escape, `%2F`; V-12). | Differs from E18 for those inputs. |
| L13 | **Analytics retention (90 days) is an assumption**, not a stated requirement. | Needs stakeholder confirmation. |
| L14 | Deactivated custom aliases are never recycled automatically (E8); freeing one needs the dev-only `/internal` hard delete. | There is no production admin path for it yet. |

### Technical and platform limitations

| # | Limitation | Consequence |
|---|---|---|
| L15 | **Tracing export (OTLP) is not wired.** Spans and trace ids exist (`requestId` == trace id). | Export is a deployment setting; nothing is shipped to a tracing backend out of the box. |
| L16 | **No static-analysis or coverage gates** (Checkstyle, SpotBugs, JaCoCo) and **no CI pipeline** (no `.github/`). | The compiler and tests are the only automated gates, and they run only if someone runs them. |
| L17 | **`/internal/**` and Swagger UI exist only in `local`/`test`** (S8, §22.4). | There is no production way to issue API keys yet; that needs an admin/key-management path. |
| L18 | **Single region, single writer.** Multi-region active-active writes are deferred. | A regional outage is a full outage until failover. |
| L19 | **JDK 21 is the supported toolchain.** The build was verified on 21 only; the machine default JDK (24) was not used. | Build with `JAVA_HOME` set to a JDK 21. |
| L20 | **Docker is required** for the integration and failure-injection tests (Testcontainers), and takes about 3 minutes. | `./mvnw test` runs the 278 unit tests without it. |
| L21 | **Outside `run-local.bat` / `run-local.sh`, changing a host port takes several variables** (`MYSQL_PORT` **and** `DB_URL`; `REDIS_PORT`; `RABBIT_PORT`; `SERVER_PORT`). | the scripts set them all for you; for a manual start see the Setup guide. |
| L22 | **The jar start-up path in this README was not executed.** The container image **was** built and run (macOS, `prod` profile, against the compose services; see *Trying the container*), but not on Linux/Windows hosts, behind a TLS gateway, or in an orchestrator. | Treat the jar path and any real deployment as unverified until run. |
| L23 | **`run-local.bat` and `stop-local.bat` have not been executed on Windows** (written and reviewed on macOS). Verified here: the compose port overrides they rely on, the `docker compose port` output they parse, the RabbitMQ readiness command, and that `up` with their computed ports recreates nothing. | Batch syntax, `netstat` port detection and the auto-open of Swagger UI are untested on Windows. macOS/Linux have no equivalent script. |
| L24 | **`run-local.sh` / `stop-local.sh` were tested on macOS only** (bash 3.2, JDK 21, Docker Desktop, with this project's containers already running and port 8080 busy): port reuse and free-port selection, app start, create + redirect against the new instance, a real Ctrl+C, and the `stop-local.sh` argument and confirmation paths. Not run on Linux, and never against an empty machine (first-time image pull). | The Linux branches (`ss` port detection, JDK search in `/usr/lib/jvm`, `xdg-open`) and a from-scratch first run are untested. |
| L25 | **Click events use one shared queue per RabbitMQ vhost.** Two instances on the same vhost but different databases split each other's click events between the two databases. | Analytics silently under-count in each database. Give each environment its own vhost; see the warning under *Trying the container*. Nothing in the app detects this misconfiguration. |
| L26 | **The Postman collection is a manual/black-box check, not part of `./mvnw verify` or any CI.** Verified with Newman (6.2.2) against a running local app: 71 requests, 165 assertions, passing three times in a row (about 5.5 s each); a deliberately broken copy failed as it should. **Not opened in the Postman desktop app.** It needs the `local` profile (it calls `/internal/**`). | It cannot exercise the `prod` image, and nothing runs it automatically, so it can drift from `docs/openapi.yaml` unless someone runs it. |

### Out of scope (design §1 / §19, `R7`)

Multi-tenant user accounts / OAuth, multi-region active-active writes, bulk import, link-in-bio / landing pages, malware / phishing scanning (extension point only). Not built, and not to be added silently.
