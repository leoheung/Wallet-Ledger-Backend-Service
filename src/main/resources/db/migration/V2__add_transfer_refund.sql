-- Phase 3: player-to-player transfers and transaction refunds.
-- Additive-only migration; the CREDIT/DEBIT type semantics stay unchanged:
--   * a transfer is a DEBIT leg (sender) + a CREDIT leg (receiver), linked
--     to each other via related_transaction_id (reason = PLAYER_TRANSFER);
--   * a refund is a single reversing leg pointing at the original txn via
--     original_transaction_id (reason = REFUND).
-- The unique index enforces at most one refund per original transaction;
-- multiple NULLs are allowed in both PostgreSQL and H2 unique indexes.
-- Columns and constraints are declared separately (H2 has no inline
-- REFERENCES support in ALTER TABLE ADD COLUMN).

ALTER TABLE wallet_transactions ADD COLUMN related_transaction_id BIGINT;
ALTER TABLE wallet_transactions ADD COLUMN original_transaction_id BIGINT;

ALTER TABLE wallet_transactions
    ADD CONSTRAINT fk_wallet_transactions_related
    FOREIGN KEY (related_transaction_id) REFERENCES wallet_transactions (id);

ALTER TABLE wallet_transactions
    ADD CONSTRAINT fk_wallet_transactions_original
    FOREIGN KEY (original_transaction_id) REFERENCES wallet_transactions (id);

CREATE UNIQUE INDEX uq_wallet_transactions_original
    ON wallet_transactions (original_transaction_id);
