package com.wallet.ledger.it;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards that -Pit really executes against PostgreSQL and never silently H2. */
class PostgresVerificationIT extends AbstractPgIT {

    @Test
    void databaseIsPostgresql() {
        String version = (String) entityManager
                .createNativeQuery("select version()")
                .getSingleResult();
        assertThat(version).contains("PostgreSQL");
    }
}
