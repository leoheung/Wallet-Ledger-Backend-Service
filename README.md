# Wallet Ledger Backend Service

A production-minded wallet & ledger backend for game players, built with Java 21 and Spring Boot 3.5.

Every balance change is recorded twice — a business `wallet_transactions` row and an immutable
`ledger_entries` line — so balances can always be independently re-derived from the ledger
(reconciliation). Money never touches floating point: amounts are stored as integer minor units.

## Feature overview

**Core (mandatory)**
- Credit a player's wallet (`POST /credit`)
- Debit a player's wallet (`POST /debit`) — rejected with `422 INSUFFICIENT_FUNDS` when overdrawn
- Read the current balance (`GET /wallet`)
- Paginated transaction history (`GET /wallet/transactions`, newest first)
- Every balance change is a permanent, traceable record with a reason
  (mission reward, purchase, admin adjustment, transfer, refund)

**Safety & correctness**
- Idempotency: every write requires an `Idempotency-Key`; retries never apply twice
- Concurrency safety: pessimistic row locks serialise simultaneous writes to one wallet
- Atomicity: every operation runs in one database transaction — failures roll back completely
- Validation: negative/zero amounts, unknown players, missing keys and currency mismatches
  produce explicit, machine-readable errors (RFC 7807 `ProblemDetail` with a `code`)

**Optional, also implemented**
- Player-to-player transfers (`POST /transfer`) — atomic, deadlock-safe, balance-conserving
- Transaction refunds (`POST /transactions/{txnId}/refund`) — append-only reversal, once per transaction
- Redis cache-aside for balance reads with automatic fallback to PostgreSQL
- Domain events (`WalletBalanceChangedEvent`) on committed balance changes
- Balance-check / reconciliation endpoint (`GET /api/v1/admin/reconciliation?playerId=…`)
- OpenAPI / Swagger UI
- Docker compose (PostgreSQL + Redis + app) and Flyway migrations
- Spring Boot Actuator health/metrics

## Tech stack

| Layer | Choice |
|---|---|
| Language / runtime | Java 21 (Temurin) |
| Framework | Spring Boot 3.5.x |
| Data access | Spring Data JPA (Hibernate) |
| Database | PostgreSQL 16 (dev/IT), H2 in PostgreSQL mode (fast unit tests) |
| Migrations | Flyway |
| Cache | Redis 7 (balance reads only; optional, graceful degradation) |
| API docs | springdoc OpenAPI / Swagger UI |
| Build | Maven 3.9 with wrapper (`./mvnw`) |

## How to run

### Option 1 — one command with Docker (easiest, any OS)

Requires only [Docker](https://docs.docker.com/get-docker/) (Docker Desktop on Windows/macOS).

```bash
docker compose up --build
```

PostgreSQL, Redis and the app start together (the app waits for both healthchecks).
The Flyway migration runs automatically on first boot.

- API base: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- Health: `http://localhost:8080/actuator/health`

### Option 2 — local development (Linux/macOS; Windows via WSL)

Requires [mise](https://mise.jdx.dev) with the tools pinned in `mise.toml`
(Java 21, Maven 3.9.16, PostgreSQL 16, Redis 7):

```bash
mise install
scripts/setup-dev.sh    # one-time: init PostgreSQL cluster, create DBs, start PG + Redis
scripts/start-dev.sh    # start PG + Redis + the app (builds on first run)
scripts/stop-dev.sh     # stop everything
```

### Option 3 — zero-install (H2, no PostgreSQL/Redis needed)

Just a JDK 21:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=h2
```

The app runs on an in-memory H2 database with caching disabled. Data does not
survive a restart; suitable for a quick look at the API.

## How to run the tests

**Unit + H2 integration tests (no external services):**

```bash
./mvnw test
```

**PostgreSQL integration & concurrency tests** (real PostgreSQL 16 + Redis):

```bash
docker compose up -d                       # middleware (shell 1)
WALLET_PG_USER=postgres WALLET_PG_PASSWORD=postgres ./mvnw verify -Pit   # shell 2
```

Or, against the locally managed PostgreSQL from `scripts/setup-dev.sh`
(trust auth, OS-user role) simply run:

```bash
./mvnw verify -Pit
```

## API overview

All write operations except refunds take a JSON body:

```json
{ "amount": 12.50, "currencyCode": "USD", "reason": "MISSION_REWARD", "referenceId": "…" }
```

| Method & path | Description |
|---|---|
| `POST /api/v1/players` | Create a player with an empty wallet (default currency `USD`) |
| `GET /api/v1/players/{playerId}/wallet` | Current balance |
| `POST /api/v1/players/{playerId}/wallet/credit` | Add money (`Idempotency-Key` required) |
| `POST /api/v1/players/{playerId}/wallet/debit` | Spend money (`Idempotency-Key` required) |
| `POST /api/v1/players/{playerId}/wallet/transfer` | Transfer to another player (`Idempotency-Key` required) |
| `POST /api/v1/players/{playerId}/wallet/transactions/{txnId}/refund` | Refund a credit/debit (`Idempotency-Key` required) |
| `GET /api/v1/players/{playerId}/wallet/transactions?page=0&size=20` | Paginated history, newest first |
| `GET /api/v1/admin/reconciliation?playerId=…` | Compare cached balance with the ledger sum |

Errors are RFC 7807 `ProblemDetail` responses with a stable `code`
(`INSUFFICIENT_FUNDS`, `CURRENCY_MISMATCH`, `IDEMPOTENCY_KEY_CONFLICT`,
`TRANSACTION_ALREADY_REFUNDED`, `TRANSACTION_NOT_REFUNDABLE`, …).

## Design decisions

**Layered architecture** — `api` (controllers/DTOs/validation), `application`
(use cases & money math), `domain` (entities, repositories, domain events,
exceptions), `infrastructure` (Redis cache, event listeners).

**Ledger approach** — balance is *not* a source of truth. `wallets.balance` is a cached,
convenient read model; `ledger_entries` is an append-only double-entry-style log, one row per
transaction leg, with a signed sum recomputation used by the reconciliation endpoint. This mirrors
accounting practice: you can always rebuild the balance from the ledger, never the other way round.

**Money** — stored as `BIGINT` minor units (e.g. cents); `BigDecimal` appears only at the API
boundary (`Money` converts and validates ISO 4217 codes). Floating point is never stored, so no
precision drift.

**Concurrency control: pessimistic locking over optimistic** — money paths are write-heavy and
contention on shared wallets is plausible, so we chose `SELECT … FOR UPDATE`: the database does the
queuing and the business code stays simple. Trade-off: hot wallets serialise (lock wait) — accepted
for correctness; noted as a limitation. A `@Version` column exists as a belt-and-braces safety net.

**Redis cache-aside for reads only** — `getBalance` is the hot read path; writes always hit the
database (the ledger is the truth). The cache never compromises correctness: a 200 ms timeout and
try/catch around every Redis call degrade transparently to PostgreSQL; post-commit eviction plus a
30 s TTL bounds staleness. Cache is disabled (`wallet.cache.enabled=false`) in unit tests and the H2
profile.

**One transaction per operation** — lock, idempotency check, balance rule, transaction + ledger
entry, and event publication are inside a single `@Transactional` unit, so no partial updates.

## Concurrency & idempotency

**Idempotency** — every credit/debit/transfer/refund requires an `Idempotency-Key`. The
`wallet_transactions` table has a unique constraint on `(wallet_id, idempotency_key)`; the request
payload is fingerprinted (SHA-256 of type/amount/reason/reference/parties). A retry with the same
key and payload returns the original result (`replayed: true`) without applying twice. The same key
with a *different* payload is rejected (`409 IDEMPOTENCY_KEY_CONFLICT`).

**Concurrency** — mutating operations lock the wallet row (`SELECT … FOR UPDATE`) *before* the
idempotency lookup and the balance check, so two racing requests cannot both pass the checks.
Transfers lock both wallet rows in ascending `wallet.id` order in a single query, so bidirectional
concurrent transfers never deadlock. The unique idempotency index is the final database-level
backstop against double application.

## Testing approach

`./mvnw test` runs 50 tests on H2 (PostgreSQL compatibility mode): money conversion edge cases
(JPY zero-decimal, fractional precision, unknown currencies), the full API surface, idempotent
replay, 409 conflicts, refund rules and reconciliation. `./mvnw verify -Pit` runs 13 tests against
real PostgreSQL 16 and Redis, including the concurrency scenarios that matter most:

- **Concurrent debit (the oversubscription race):** 60 debits of 10.00 against a 500.00 balance —
  asserted to result in *exactly* 50 successes, 10 `INSUFFICIENT_FUNDS`, a final balance of exactly
  0, and a consistent ledger.
- **Interleaved credits and debits:** 50 credits + 60 debits racing on one wallet — all succeed and
  the balance and ledger agree.
- **Same idempotency key under concurrency:** 30 parallel requests with the same key — exactly one
  applies, 29 replay.
- **Bidirectional transfers:** 40 concurrent transfers in both directions between two wallets —
  no deadlocks, no failures, balance conserved, all legs cross-linked.
- **Refund racing:** concurrent refunds of the same transaction — only one wins.

A `StressHarness` fires all threads behind a `CountDownLatch` so requests genuinely overlap.

## Assumptions & limitations

- One wallet per player, with a single currency; transfers require both wallets to hold the same
  currency (no FX).
- Pessimistic locking serialises writes on a hot wallet; we did not add sharding or fund
  reservation — noted as the next optimisation if contention ever becomes real.
- Refunds are only supported for plain credits/debits. A transfer must be reversed by a new
  transfer in the opposite direction, keeping both players' ledgers complete.
- Redis adds a bounded staleness (TTL of 30 s at most) for balance reads after a lost eviction;
  eventual consistency is acceptable for a read cache that can be discarded at any time.
- The API has no authentication/authorization (out of scope for this assignment).
- H2 is for fast local tests/demo only; production runs on PostgreSQL (Flyway keeps schemas in sync,
  and a `PostgresVerificationIT` guards against accidentally running the ITs on H2).
- `SELECT … FOR UPDATE` syntax is database-specific; moving to SQL Server would require reworking
  the lock queries.