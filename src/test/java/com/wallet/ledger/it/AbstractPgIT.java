package com.wallet.ledger.it;

import com.wallet.ledger.application.ReconciliationService;
import com.wallet.ledger.application.WalletAppService;
import com.wallet.ledger.application.WalletQueryService;
import com.wallet.ledger.domain.repository.LedgerEntryRepository;
import com.wallet.ledger.domain.repository.WalletTransactionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.jdbc.Sql;

/**
 * Base for integration tests that MUST run against real PostgreSQL 16 and the
 * real Redis instance. Tables are truncated and Redis logical DB 15 is flushed
 * before every test method.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Sql(statements = "TRUNCATE TABLE ledger_entries, wallet_transactions, wallets, players "
        + "RESTART IDENTITY CASCADE",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
abstract class AbstractPgIT {

    @Autowired
    protected WalletAppService walletAppService;
    @Autowired
    protected WalletQueryService walletQueryService;
    @Autowired
    protected WalletTransactionRepository transactionRepository;
    @Autowired
    protected LedgerEntryRepository ledgerEntryRepository;
    @Autowired
    protected ReconciliationService reconciliationService;
    @Autowired
    protected StringRedisTemplate redis;
    @PersistenceContext
    protected EntityManager entityManager;

    @BeforeEach
    void flushRedis() {
        // application-it.yml selects database 15; FLUSHDB only clears that DB.
        redis.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    protected Long newPlayer() {
        return walletAppService.createPlayer("it-player", "USD").getId();
    }

    protected static String balanceKey(Long playerId) {
        return "wallet:balance:" + playerId;
    }
}
