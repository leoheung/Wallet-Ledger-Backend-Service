#!/usr/bin/env bash
# Stop the full local stack: Wallet Ledger app, Redis, PostgreSQL.
set -euo pipefail

PG_PORT="${WALLET_PG_PORT:-5432}"
REDIS_PORT="${WALLET_REDIS_PORT:-6379}"
APP_PORT="${WALLET_APP_PORT:-8080}"
DATA_DIR="${WALLET_LEDGER_DATA:-$HOME/.local/share/wallet-ledger}"
PG_DATA="$DATA_DIR/pgdata"
APP_PID_FILE="$DATA_DIR/app.pid"

export PATH="$HOME/.local/share/mise/shims:$PATH"

# ---------------------------------------------------------- Spring Boot app
if [ -f "$APP_PID_FILE" ]; then
  APP_PID="$(cat "$APP_PID_FILE")"
  if kill -0 "$APP_PID" 2>/dev/null; then
    echo "[stop] Stopping Wallet Ledger app (pid $APP_PID)"
    kill "$APP_PID" 2>/dev/null || true
    for _ in $(seq 1 20); do
      kill -0 "$APP_PID" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$APP_PID" 2>/dev/null || true
  fi
  rm -f "$APP_PID_FILE"
  echo "[stop] Wallet Ledger app stopped"
else
  # No pid file: clean up anything still holding the port.
  if curl -sf --max-time 2 "http://localhost:$APP_PORT/actuator/health" >/dev/null 2>&1; then
    echo "[stop] Port $APP_PORT is in use but no pid file found; stop it manually"
  else
    echo "[stop] Wallet Ledger app not running"
  fi
fi

# ------------------------------------------------------------ PostgreSQL
if pg_isready -h /tmp -p "$PG_PORT" >/dev/null 2>&1; then
  pg_ctl -D "$PG_DATA" -m fast stop
  echo "[stop] PostgreSQL stopped"
else
  echo "[stop] PostgreSQL not running"
fi

# ----------------------------------------------------------------- Redis
if redis-cli -p "$REDIS_PORT" ping >/dev/null 2>&1; then
  redis-cli -p "$REDIS_PORT" shutdown nosave
  echo "[stop] Redis stopped"
else
  echo "[stop] Redis not running"
fi
