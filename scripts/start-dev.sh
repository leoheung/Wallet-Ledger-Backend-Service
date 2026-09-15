#!/usr/bin/env bash
# Start the full local development stack:
#   1. PostgreSQL 16  (0.0.0.0:5432)
#   2. Redis 7        (0.0.0.0:6379)
#   3. Wallet Ledger Spring Boot app (0.0.0.0:8080, incl. Swagger UI)
#
# The app is started from the packaged fat jar; it is built automatically on
# first run. Force a rebuild with: REBUILD=1 bash scripts/start-dev.sh
set -euo pipefail

PG_PORT="${WALLET_PG_PORT:-5432}"
REDIS_PORT="${WALLET_REDIS_PORT:-6379}"
APP_PORT="${WALLET_APP_PORT:-8080}"
DATA_DIR="${WALLET_LEDGER_DATA:-$HOME/.local/share/wallet-ledger}"
PG_DATA="$DATA_DIR/pgdata"
REDIS_DIR="$DATA_DIR/redis"
LOG_DIR="$DATA_DIR/logs"
APP_PID_FILE="$DATA_DIR/app.pid"
APP_LOG="$LOG_DIR/app.log"

# Bind to all interfaces by default (LOCAL-DEV ONLY, trust/no auth).
# Override: WALLET_PG_LISTEN_ADDRESSES=localhost WALLET_REDIS_BIND=127.0.0.1 SERVER_ADDRESS=127.0.0.1
PG_LISTEN_ADDRESSES="${WALLET_PG_LISTEN_ADDRESSES:-*}"
REDIS_BIND="${WALLET_REDIS_BIND:-0.0.0.0}"
SERVER_ADDRESS="${SERVER_ADDRESS:-0.0.0.0}"

export PATH="$HOME/.local/share/mise/shims:$PATH"
mkdir -p "$REDIS_DIR" "$LOG_DIR"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_JAR="$PROJECT_DIR/target/wallet-ledger-0.0.1-SNAPSHOT.jar"

# Optional Maven local repository override (e.g. restricted environments).
MVN_REPO_ARG=()
if [ -n "${MAVEN_REPO_LOCAL:-}" ]; then
  MVN_REPO_ARG=("-Dmaven.repo.local=$MAVEN_REPO_LOCAL")
fi

# ------------------------------------------------------------ PostgreSQL
if pg_isready -h /tmp -p "$PG_PORT" >/dev/null 2>&1; then
  echo "[start] PostgreSQL already running on port $PG_PORT"
else
  echo "[start] Starting PostgreSQL on 0.0.0.0:$PG_PORT"
  pg_ctl -D "$PG_DATA" -l "$LOG_DIR/postgres.log" \
    -o "-p $PG_PORT -k /tmp -c listen_addresses='$PG_LISTEN_ADDRESSES'" start
  for _ in $(seq 1 30); do
    pg_isready -h /tmp -p "$PG_PORT" >/dev/null 2>&1 && break
    sleep 1
  done
fi
pg_isready -h /tmp -p "$PG_PORT"

# ----------------------------------------------------------------- Redis
if redis-cli -p "$REDIS_PORT" ping >/dev/null 2>&1; then
  echo "[start] Redis already running on port $REDIS_PORT"
else
  echo "[start] Starting Redis on 0.0.0.0:$REDIS_PORT"
  redis-server \
    --port "$REDIS_PORT" --bind "$REDIS_BIND" --protected-mode no \
    --daemonize yes --dir "$REDIS_DIR" \
    --pidfile "$REDIS_DIR/redis.pid" --logfile "$LOG_DIR/redis.log" \
    --save "" --appendonly no
  for _ in $(seq 1 20); do
    redis-cli -p "$REDIS_PORT" ping 2>/dev/null | grep -q PONG && break
    sleep 1
  done
fi
redis-cli -p "$REDIS_PORT" ping

# --------------------------------------------------------- Spring Boot app
app_pid_alive() {
  [ -f "$APP_PID_FILE" ] && kill -0 "$(cat "$APP_PID_FILE")" 2>/dev/null
}

if app_pid_alive && curl -sf --max-time 2 "http://localhost:$APP_PORT/actuator/health" >/dev/null 2>&1; then
  echo "[start] Wallet Ledger app already running on port $APP_PORT (pid $(cat "$APP_PID_FILE"))"
else
  if [ "${REBUILD:-0}" = "1" ] || [ ! -f "$APP_JAR" ]; then
    echo "[start] Building application jar (./mvnw package -DskipTests)"
    (cd "$PROJECT_DIR" && ./mvnw -q "${MVN_REPO_ARG[@]}" package -DskipTests)
  fi

  echo "[start] Starting Wallet Ledger app on $SERVER_ADDRESS:$APP_PORT (profile=dev)"
  nohup java -jar "$APP_JAR" \
    --spring.profiles.active=dev \
    --server.address="$SERVER_ADDRESS" \
    --server.port="$APP_PORT" \
    > "$APP_LOG" 2>&1 &
  echo $! > "$APP_PID_FILE"

  for _ in $(seq 1 60); do
    if curl -sf --max-time 2 "http://localhost:$APP_PORT/actuator/health" >/dev/null 2>&1; then
      break
    fi
    if ! app_pid_alive; then
      echo "[start] Application process exited early. Last log lines:" >&2
      tail -20 "$APP_LOG" >&2
      exit 1
    fi
    sleep 1
  done
fi

HEALTH="$(curl -s --max-time 3 "http://localhost:$APP_PORT/actuator/health")"
LAN_IP="$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1 || true)"

echo "[start] All services up."
echo "  Health:      http://localhost:$APP_PORT/actuator/health  -> $HEALTH"
echo "  Swagger UI:  http://localhost:$APP_PORT/swagger-ui/index.html"
[ -n "$LAN_IP" ] && echo "               http://$LAN_IP:$APP_PORT/swagger-ui/index.html  (LAN)"
echo "  OpenAPI:     http://localhost:$APP_PORT/v3/api-docs"
echo "  App log:     $APP_LOG   (pid $(cat "$APP_PID_FILE"))"
