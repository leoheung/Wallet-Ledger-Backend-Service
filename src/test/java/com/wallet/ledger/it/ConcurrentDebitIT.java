package com.wallet.ledger.it;

import com.wallet.ledger.application.LedgerOperationResult;
import com.wallet.ledger.domain.enums.TransactionReason;
import com.wallet.ledger.domain.exception.BusinessException;
import com.wallet.ledger.domain.model.Wallet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Oversubscribed debit race: wallet holds 500.00 and 60 concurrent requests
 * each try to debit 10.00. Exactly 50 must succeed, exactly 10 must be
 * rejected with INSUFFICIENT_FUNDS, and the balance must never go negative.
 */
class ConcurrentDebitIT extends AbstractPgIT {

    @Test
    void concurrentDebitsNeverOverdraw() throws Exception {
        Long playerId = newPlayer();
        Wallet wallet = walletAppService.getWallet(playerId);

        // Seed 500.00
        walletAppService.credit(playerId, "USD", new BigDecimal("500.00"),
                TransactionReason.ADMIN_ADJUSTMENT, null, "seed");
        // Warm the cache to the pre-storm balance (500.00). The storm's commits
        // must evict it, otherwise the post-storm read would serve a stale 500.00.
        assertThat(walletQueryService.getBalanceView(playerId).balanceMinor()).isEqualTo(50_000L);
        assertThat(redis.hasKey(balanceKey(playerId))).isTrue();

        int threads = 60;
        List<OperationOutcome> outcomes = StressHarness.runConcurrently(threads, index -> {
            try {
                LedgerOperationResult result = walletAppService.debit(
                        playerId, "USD", new BigDecimal("10.00"),
                        TransactionReason.PURCHASE, "order-" + index, "debit-" + index);
                return OperationOutcome.ok(result.transaction().getId(), result.replayed());
            } catch (BusinessException e) {
                return OperationOutcome.failed(e.errorCode());
            }
        });

        long succeeded = outcomes.stream().filter(OperationOutcome::success).count();
        long rejected = outcomes.stream()
                .filter(o -> o.errorCode() == com.wallet.ledger.domain.exception.ErrorCode.INSUFFICIENT_FUNDS)
                .count();
        long unexpected = outcomes.stream()
                .filter(o -> !o.success()
                        && o.errorCode() != com.wallet.ledger.domain.exception.ErrorCode.INSUFFICIENT_FUNDS)
                .count();

        assertThat(unexpected).as("no errors other than INSUFFICIENT_FUNDS").isZero();
        assertThat(succeeded).as("exactly 500/10 debits succeed").isEqualTo(50);
        assertThat(rejected).as("exactly 10 debits rejected").isEqualTo(10);

        Wallet after = walletAppService.getWallet(playerId);
        assertThat(after.getBalance()).as("balance never goes negative, ends at zero").isZero();

        // The last committed debit evicted the stale 500.00 entry; the read
        // misses, loads 0 from the DB and must never return the stale value.
        assertThat(walletQueryService.getBalanceView(playerId).balanceMinor())
                .as("balance read through the Redis cache path matches DB truth")
                .isEqualTo(after.getBalance())
                .isZero();

        // Seed transaction + 50 successful debits; rejected requests wrote nothing.
        assertThat(transactionRepository.countByWalletId(wallet.getId())).isEqualTo(51);
        assertThat(ledgerEntryRepository.countByWalletId(wallet.getId())).isEqualTo(51);

        assertThat(reconciliationService.reconcile(playerId).consistent())
                .as("cached balance matches ledger sum")
                .isTrue();
    }
}
