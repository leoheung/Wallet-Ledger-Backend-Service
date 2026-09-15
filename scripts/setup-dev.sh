#!/usr/bin/env bash
# One-time local setup: initialise PostgreSQL data directory, create databases,
# and start both PostgreSQL and Redis. Idempotent — safe to run repeatedly.
#
# Requirements: mise (https://mise.jdx.dev) with the tools pinned in ../mise.toml
set -euo pipefail

PG_PORT="${WALLET_PG_PORT:-5432}"
REDIS_PORT="${WALLET_REDIS_PORT:-6379}"
DATA_DIR="${WALLET_LEDGER_DATA:-$HOME/.local/share/wallet-ledger}"
PG_DATA="$DATA_DIR/pgdata"
REDIS_DIR="$DATA_DIR/redis"
LOG_DIR="$DATA_DIR/logs"

# Network binding. Defaults listen on ALL interfaces (0.0.0.0) for remote dev
# access; authentication is trust/none, so this is LOCAL-DEV ONLY.
# Restrict with: WALLET_PG_LISTEN_ADDRESSES=localhost WALLET_REDIS_BIND=127.0.0.1
PG_LISTEN_ADDRESSES="${WALLET_PG_LISTEN_ADDRESSES:-*}"
PG_HBA_MARKER="# wallet-ledger remote access"
REDIS_BIND="${WALLET_REDIS_BIND:-0.0.0.0}"

# Ensure mise shims are on PATH even when invoked from a non-login shell.
export PATH="$HOME/.local/share/mise/shims:$PATH"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

log() { printf '\033[1;32m[setup]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[setup]\033[0m %s\n' "$*"; }

mkdir -p "$DATA_DIR" "$REDIS_DIR" "$LOG_DIR"

for cmd in initdb pg_ctl createdb pg_isready redis-server redis-cli; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "[setup] '$cmd' not found. Run: cd \"$PROJECT_DIR\" && mise install" >&2
    exit 1
  fi
done

# ---------------------------------------------------------------- PostgreSQL
if [ ! -d "$PG_DATA" ]; then
  log "Initialising PostgreSQL cluster at $PG_DATA"
  initdb -D "$PG_DATA" \
    --username="$USER" --auth-local=trust --auth-host=trust \
    --encoding=UTF8 >/dev/null
else
  log "PostgreSQL cluster already initialised"
fi

# Allow remote TCP clients (initdb only whitelists loopback in pg_hba.conf).
PG_HBA="$PG_DATA/pg_hba.conf"
PG_HBA_CHANGED=0
if ! grep -qF "$PG_HBA_MARKER" "$PG_HBA" 2>/dev/null; then
  log "Appending remote-access rules to pg_hba.conf (trust, all IPv4/IPv6)"
  {
    echo "$PG_HBA_MARKER"
    echo "host    all    all    0.0.0.0/0    trust"
    echo "host    all    all    ::/0         trust"
  } >> "$PG_HBA"
  PG_HBA_CHANGED=1
else
  log "pg_hba.conf remote-access rules already present"
fi

if ! pg_isready -h /tmp -p "$PG_PORT" >/dev/null 2>&1; then
  log "Starting PostgreSQL on port $PG_PORT (listen_addresses='$PG_LISTEN_ADDRESSES')"
  pg_ctl -D "$PG_DATA" -l "$LOG_DIR/postgres.log" \
    -o "-p $PG_PORT -k /tmp -c listen_addresses='$PG_LISTEN_ADDRESSES'" start
  for _ in $(seq 1 30); do
    pg_isready -h /tmp -p "$PG_PORT" >/dev/null 2>&1 && break
    sleep 1
  done
else
  log "PostgreSQL already running"
  if [ "$PG_HBA_CHANGED" = 1 ]; then
    log "Reloading PostgreSQL to apply pg_hba.conf changes"
    pg_ctl -D "$PG_DATA" reload
  fi
fi
pg_isready -h /tmp -p "$PG_PORT"

for dbname in wallet_ledger wallet_ledger_test; do
  if createdb -h /tmp -p "$PG_PORT" "$dbname" 2>/dev/null; then
    log "Created database '$dbname'"
  else
    warn "Database '$dbname' already exists"
  fi
done

# ------------------------------------------------------------------- Redis
if redis-cli -p "$REDIS_PORT" ping >/dev/null 2>&1; then
  log "Redis already running on port $REDIS_PORT"
else
  log "Starting Redis on port $REDIS_PORT (bind=$REDIS_BIND, protected-mode=no)"
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

log "Environment ready."
log "  PostgreSQL: 0.0.0.0:$PG_PORT (databases: wallet_ledger, wallet_ledger_test)"
log "  Redis:      0.0.0.0:$REDIS_PORT"
warn "Both services accept remote connections without a password — do not use on untrusted networks."
