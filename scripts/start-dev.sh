#!/usr/bin/env bash
# Start the already-initialised local PostgreSQL and Redis.
set -euo pipefail

PG_PORT="${WALLET_PG_PORT:-5432}"
REDIS_PORT="${WALLET_REDIS_PORT:-6379}"
DATA_DIR="${WALLET_LEDGER_DATA:-$HOME/.local/share/wallet-ledger}"
PG_DATA="$DATA_DIR/pgdata"
REDIS_DIR="$DATA_DIR/redis"
LOG_DIR="$DATA_DIR/logs"

export PATH="$HOME/.local/share/mise/shims:$PATH"
mkdir -p "$REDIS_DIR" "$LOG_DIR"

if pg_isready -h /tmp -p "$PG_PORT" >/dev/null 2>&1; then
  echo "[start] PostgreSQL already running on port $PG_PORT"
else
  pg_ctl -D "$PG_DATA" -l "$LOG_DIR/postgres.log" \
    -o "-p $PG_PORT -k /tmp -c listen_addresses=localhost" start
  for _ in $(seq 1 30); do
    pg_isready -h /tmp -p "$PG_PORT" >/dev/null 2>&1 && break
    sleep 1
  done
fi
pg_isready -h /tmp -p "$PG_PORT"

if redis-cli -p "$REDIS_PORT" ping >/dev/null 2>&1; then
  echo "[start] Redis already running on port $REDIS_PORT"
else
  redis-server \
    --port "$REDIS_PORT" --bind 127.0.0.1 \
    --daemonize yes --dir "$REDIS_DIR" \
    --pidfile "$REDIS_DIR/redis.pid" --logfile "$LOG_DIR/redis.log" \
    --save "" --appendonly no
  for _ in $(seq 1 20); do
    redis-cli -p "$REDIS_PORT" ping 2>/dev/null | grep -q PONG && break
    sleep 1
  done
fi
redis-cli -p "$REDIS_PORT" ping
echo "[start] All services up."
