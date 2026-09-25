#!/usr/bin/env bash
# ============================================================================
#  URL Shortener - one-step local run (macOS and Linux). Windows: run-local.bat.
#
#    ./run-local.sh              start MySQL/Redis/RabbitMQ in Docker, then the app
#    ./run-local.sh --no-open    same, but do not open Swagger UI in the browser
#
#  Needs: Docker (running) with Compose v2, and JDK 21. No Maven install needed (./mvnw).
#  Ports are chosen automatically: containers already running from this project
#  are reused, and anything else that is busy (3306, 6379, 5672, 15672, 8080) moves
#  to the next free port, so this does not clash with software you already run.
#  Stop the app with Ctrl+C, then run ./stop-local.sh.
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

OPEN_BROWSER=1
case "${1:-}" in
  "") ;;
  --no-open) OPEN_BROWSER=0 ;;
  -h|--help) sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
  *) echo "Unknown option: $1 (try --help)" >&2; exit 2 ;;
esac

fail() { printf '\n[ERROR] %s\n' "$*" >&2; exit 1; }

echo
echo "=== URL Shortener: local run ==="
echo

# ---- 1. Docker ---------------------------------------------------------------
command -v docker >/dev/null 2>&1 || fail "Docker is not installed. Install Docker Desktop (macOS) or Docker Engine + the compose plugin (Linux)."
docker info >/dev/null 2>&1 || fail "Docker is not running. Start Docker Desktop (or the docker service), wait until it is ready, then run this again."
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required (the 'docker compose' command). Update Docker."

# ---- 2. JDK 21 ---------------------------------------------------------------
java_ok() { local v; v=$("$1" -version 2>&1 || true); [[ $v == *'version "21'* ]]; }

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] && java_ok "$JAVA_HOME/bin/java"; then
  :                                                              # JAVA_HOME already points at a JDK 21
else
  found=""
  if [ -x /usr/libexec/java_home ]; then                         # macOS: ask the system for a JDK 21
    found=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
  fi
  if [ -z "$found" ]; then                                       # Linux: common install locations
    for d in /usr/lib/jvm/*21* /usr/lib64/jvm/*21* /opt/java/*21* "$HOME"/.sdkman/candidates/java/21*; do
      if [ -x "$d/bin/java" ] && java_ok "$d/bin/java"; then found=$d; break; fi
    done
  fi
  if [ -n "$found" ]; then
    export JAVA_HOME="$found"
  elif command -v java >/dev/null 2>&1 && java_ok java; then
    unset JAVA_HOME                                              # 'java' on PATH is 21; make ./mvnw use it
  else
    have=$( (java -version 2>&1 || true) | head -n1)
    fail "JDK 21 is required. Found: ${have:-no java}. Install JDK 21 and set JAVA_HOME to it (macOS: brew install --cask temurin@21; Linux: your package manager or sdkman), then run this again."
  fi
fi
echo "Using JDK: ${JAVA_HOME:-java on PATH}"

# ---- 3. Ports ----------------------------------------------------------------
port_in_use() {                                                  # is anything listening on TCP port $1?
  local p=$1 out
  if command -v ss >/dev/null 2>&1; then
    out=$(ss -ltn 2>/dev/null || true)
    if grep -Eq "[:.]$p[[:space:]]" <<<"$out"; then return 0; fi
  fi
  if command -v lsof >/dev/null 2>&1 && lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then return 0; fi
  if (exec 3<>"/dev/tcp/127.0.0.1/$p") >/dev/null 2>&1; then return 0; fi
  return 1
}

find_free() {                                                    # first free port >= $1, within 50 tries
  local p=$1 max=$(( $1 + 50 ))
  while port_in_use "$p"; do
    p=$(( p + 1 ))
    [ "$p" -le "$max" ] || return 1
  done
  echo "$p"
}

resolve() {                                                      # resolve VAR SERVICE CONTAINER_PORT DEFAULT_PORT
  local var=$1 svc=$2 cport=$3 def=$4 line found start p
  line=$(docker compose port "$svc" "$cport" 2>/dev/null | tail -n1 || true)
  found=${line##*:}
  if [[ $found =~ ^[0-9]+$ ]] && [ "$found" -gt 0 ]; then
    printf -v "$var" '%s' "$found"
    printf '  %-9s %-6s -> %s (already running)\n' "$svc" "$cport" "$found"
  else
    start=${!var:-$def}
    p=$(find_free "$start") || fail "No free port found starting at $start."
    printf -v "$var" '%s' "$p"
    printf '  %-9s %-6s -> %s\n' "$svc" "$cport" "$p"
  fi
  export "$var"
}

echo "Choosing ports (reusing running containers, otherwise the first free port)..."
resolve MYSQL_PORT      mysql    3306  3306
resolve REDIS_PORT      redis    6379  6379
resolve RABBIT_PORT     rabbitmq 5672  5672
resolve RABBIT_MGMT_PORT rabbitmq 15672 15672
SERVER_PORT=$(find_free "${SERVER_PORT:-8080}") || fail "No free port found for the app."
export SERVER_PORT
printf '  %-9s %-6s -> %s\n' app "" "$SERVER_PORT"

# ---- 4. Backing services -----------------------------------------------------
echo
echo "Starting MySQL, Redis and RabbitMQ in Docker (the first run pulls images)..."
docker compose up -d --wait mysql redis rabbitmq \
  || fail "'docker compose up' failed. Run 'docker compose ps' and 'docker compose logs' to see why."

tries=0
until docker compose exec -T rabbitmq rabbitmq-diagnostics -q ping >/dev/null 2>&1; do
  tries=$(( tries + 1 ))
  if [ "$tries" -ge 30 ]; then echo "[WARN] RabbitMQ did not confirm ready after 60 s - continuing anyway."; break; fi
  sleep 2
done

# ---- 5. App configuration (read by application.yml) --------------------------
export DB_URL="jdbc:mysql://localhost:${MYSQL_PORT}/urlshortener?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&connectTimeout=2000&socketTimeout=3000"
export PUBLIC_BASE_URL="http://localhost:${SERVER_PORT}"
# REDIS_PORT, RABBIT_PORT and SERVER_PORT are exported above and are read by the app as-is.

cat <<EOF

------------------------------------------------------------------------
  App          http://localhost:${SERVER_PORT}
  Swagger UI   http://localhost:${SERVER_PORT}/swagger-ui.html
  Health       http://localhost:${SERVER_PORT}/actuator/health
  RabbitMQ UI  http://localhost:${RABBIT_MGMT_PORT}   user guest / password guest
  MySQL        localhost:${MYSQL_PORT}   database urlshortener, user urlshortener / urlshortener
------------------------------------------------------------------------
  Try it:  see "Manual testing with curl" in README.md
  Stop:    Ctrl+C in this terminal, then run ./stop-local.sh

Starting the application (the first run downloads dependencies and can take a few minutes)...

EOF

# Open Swagger UI once the app reports healthy. Best effort: never affects the app.
POLLER=""
if [ "$OPEN_BROWSER" = 1 ]; then
  (
    for _ in $(seq 1 120); do
      if curl -fsS -m 2 "http://localhost:${SERVER_PORT}/actuator/health" >/dev/null 2>&1; then
        if command -v open >/dev/null 2>&1; then open "http://localhost:${SERVER_PORT}/swagger-ui.html" >/dev/null 2>&1 || true
        elif command -v xdg-open >/dev/null 2>&1; then xdg-open "http://localhost:${SERVER_PORT}/swagger-ui.html" >/dev/null 2>&1 || true
        fi
        exit 0
      fi
      sleep 3
    done
  ) &
  POLLER=$!
fi
cleanup() { if [ -n "$POLLER" ]; then kill "$POLLER" 2>/dev/null || true; fi; }
trap cleanup EXIT
INTERRUPTED=0
trap 'INTERRUPTED=1' INT TERM          # Ctrl+C is a normal way to stop the app, not an error

if [ -x ./mvnw ]; then MVNW=./mvnw; else MVNW="sh ./mvnw"; fi
set +e
$MVNW -pl url-shortener-service spring-boot:run
rc=$?
set -e

echo
if [ "$INTERRUPTED" = 1 ] || [ "$rc" -eq 0 ] || [ "$rc" -eq 130 ] || [ "$rc" -eq 143 ]; then
  echo "Application stopped. Run ./stop-local.sh to stop MySQL, Redis and RabbitMQ."
else
  echo "[ERROR] The application exited with code $rc. See the log above." >&2
  echo "        Common causes: wrong JDK (need 21), a port taken after the check, or Docker services stopped." >&2
  exit "$rc"
fi
