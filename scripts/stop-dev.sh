#!/usr/bin/env bash
# Stop local PostgreSQL and Redis.
set -euo pipefail

PG_PORT="${WALLET_PG_PORT:-5432}"
REDIS_PORT="${WALLET_REDIS_PORT:-6379}"
DATA_DIR="${WALLET_LEDGER_DATA:-$HOME/.local/share/wallet-ledger}"
PG_DATA="$DATA_DIR/pgdata"

export PATH="$HOME/.local/share/mise/shims:$PATH"

if pg_isready -h /tmp -p "$PG_PORT" >/dev/null 2>&1; then
  pg_ctl -D "$PG_DATA" -m fast stop
  echo "[stop] PostgreSQL stopped"
else
  echo "[stop] PostgreSQL not running"
fi

if redis-cli -p "$REDIS_PORT" ping >/dev/null 2>&1; then
  redis-cli -p "$REDIS_PORT" shutdown nosave
  echo "[stop] Redis stopped"
else
  echo "[stop] Redis not running"
fi
