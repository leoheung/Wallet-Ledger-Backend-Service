-- Wallet Ledger core schema.
-- Plain SQL DDL so it runs on both PostgreSQL (dev/IT) and H2 in PostgreSQL
-- compatibility mode (fast unit tests). Money is stored as BIGINT minor units.

CREATE TABLE players (
    id           BIGSERIAL                  PRIMARY KEY,
    display_name VARCHAR(100)               NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMP WITH TIME ZONE   NOT NULL DEFAULT now()
);

CREATE TABLE wallets (
    id            BIGSERIAL                PRIMARY KEY,
    player_id     BIGINT                   NOT NULL UNIQUE REFERENCES players (id),
    currency_code VARCHAR(3)               NOT NULL,
    -- Cached balance; source of truth is the sum of ledger_entries.
    -- CHECK is a last line of defence against a negative balance bug.
    balance       BIGINT                   NOT NULL DEFAULT 0,
    version       BIGINT                   NOT NULL DEFAULT 0,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_wallets_balance_non_negative CHECK (balance >= 0)
);

-- One row per business action (a credit or a debit). Append-only by contract.
CREATE TABLE wallet_transactions (
    id              BIGSERIAL                PRIMARY KEY,
    wallet_id       BIGINT                   NOT NULL REFERENCES wallets (id),
    type            VARCHAR(10)              NOT NULL,
    status          VARCHAR(20)              NOT NULL,
    reason          VARCHAR(40)              NOT NULL,
    reference_id    VARCHAR(100),
    amount          BIGINT                   NOT NULL,
    currency_code   VARCHAR(3)               NOT NULL,
    idempotency_key VARCHAR(100)             NOT NULL,
    -- SHA-256 fingerprint of the request payload; used to detect same-key/different-body.
    request_hash    VARCHAR(64)              NOT NULL,
    balance_after   BIGINT                   NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_wallet_transactions_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_wallet_transactions_type   CHECK (type IN ('CREDIT', 'DEBIT')),
    CONSTRAINT ck_wallet_transactions_status CHECK (status IN ('COMPLETED')),
    CONSTRAINT uk_wallet_transactions_idempotency UNIQUE (wallet_id, idempotency_key)
);

CREATE INDEX idx_wallet_transactions_wallet_time
    ON wallet_transactions (wallet_id, created_at DESC, id DESC);

-- Immutable double-entry style ledger lines, one per transaction leg.
CREATE TABLE ledger_entries (
    id             BIGSERIAL                PRIMARY KEY,
    transaction_id BIGINT                   NOT NULL REFERENCES wallet_transactions (id),
    wallet_id      BIGINT                   NOT NULL REFERENCES wallets (id),
    direction      VARCHAR(8)               NOT NULL,
    amount         BIGINT                   NOT NULL,
    balance_after  BIGINT                   NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT ck_ledger_entries_amount_positive CHECK (amount > 0),
    CONSTRAINT ck_ledger_entries_direction CHECK (direction IN ('CREDIT', 'DEBIT'))
);

CREATE INDEX idx_ledger_entries_wallet ON ledger_entries (wallet_id, id);
