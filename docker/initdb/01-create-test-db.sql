-- Creates the test database used by the PostgreSQL integration tests
-- (`mvn verify -Pit`, configured in src/test/resources/application-it.yml).
-- Mounted as /docker-entrypoint-initdb.d in docker-compose.yml, so it runs
-- automatically the first time the Postgres container is initialised.

CREATE DATABASE wallet_ledger_test;