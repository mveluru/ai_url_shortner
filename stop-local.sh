#!/usr/bin/env bash
# ============================================================================
#  Stops the Docker services started by run-local.sh.
#
#    ./stop-local.sh          stop MySQL, Redis and RabbitMQ; the data is kept
#    ./stop-local.sh reset    stop them AND delete all their data (fresh database next time)
#
#  The application itself runs in the run-local.sh terminal: stop it with Ctrl+C there.
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

docker info >/dev/null 2>&1 || { echo "[ERROR] Docker is not running, so there is nothing to stop." >&2; exit 1; }

case "${1:-}" in
  "")
    echo "Stopping MySQL, Redis and RabbitMQ (data is kept)..."
    docker compose down
    ;;
  reset)
    echo "This stops the services AND deletes all local MySQL, Redis and RabbitMQ data."
    read -r -p "Type YES to continue: " ans
    [ "$ans" = "YES" ] || { echo "Cancelled."; exit 1; }
    docker compose down -v
    ;;
  *)
    echo "Usage: ./stop-local.sh [reset]" >&2
    exit 2
    ;;
esac
