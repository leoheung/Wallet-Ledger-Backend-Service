package com.wallet.ledger.it;

import com.wallet.ledger.application.ReconciliationService;
import com.wallet.ledger.application.WalletAppService;
import com.wallet.ledger.domain.repository.LedgerEntryRepository;
import com.wallet.ledger.domain.repository.WalletTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;

/**
 * Base for integration tests that MUST run against real PostgreSQL 16.
 * Tables are truncated before every test method; identity sequences restart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Sql(statements = "TRUNCATE TABLE ledger_entries, wallet_transactions, wallets, players "
        + "RESTART IDENTITY CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
abstract class AbstractPgIT {

    @Autowired
    protected WalletAppService walletAppService;
    @Autowired
    protected WalletTransactionRepository transactionRepository;
    @Autowired
    protected LedgerEntryRepository ledgerEntryRepository;
    @Autowired
    protected ReconciliationService reconciliationService;
    @PersistenceContext
    protected EntityManager entityManager;

    protected Long newPlayer() {
        return walletAppService.createPlayer("it-player", "USD").getId();
    }
}
