package com.wallet.ledger.it;

import com.wallet.ledger.application.LedgerOperationResult;
import com.wallet.ledger.domain.enums.TransactionReason;
import com.wallet.ledger.domain.exception.BusinessException;
import com.wallet.ledger.domain.model.Wallet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 50 concurrent credits (100.00 each) and 60 concurrent debits (50.00 each)
 * overlap against a wallet seeded with 100,000.00. Every request must succeed
 * regardless of interleaving, the final balance must equal the seed plus the
 * exact sum of changes, and the ledger must reconcile.
 */
class ConcurrentMixedFlowIT extends AbstractPgIT {

    @Test
    void interleavedCreditsAndDebitsProduceExactBalance() throws Exception {
        Long playerId = newPlayer();
        Wallet wallet = walletAppService.getWallet(playerId);

        walletAppService.credit(playerId, "USD", new BigDecimal("100000.00"),
                TransactionReason.ADMIN_ADJUSTMENT, null, "seed");

        int credits = 50;
        int debits = 60;
        int threads = credits + debits;
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        List<OperationOutcome> outcomes = new ArrayList<>();

        StressHarness.runConcurrently(threads, index -> {
            try {
                LedgerOperationResult result;
                if (index < credits) {
                    result = walletAppService.credit(playerId, "USD", new BigDecimal("100.00"),
                            TransactionReason.MISSION_REWARD, "reward-" + index, "credit-" + index);
                } else {
                    int debitIndex = index - credits;
                    result = walletAppService.debit(playerId, "USD", new BigDecimal("50.00"),
                            TransactionReason.PURCHASE, "order-" + debitIndex, "debit-" + debitIndex);
                }
                synchronized (outcomes) {
                    outcomes.add(OperationOutcome.ok(result.transaction().getId(), result.replayed()));
                }
                return null;
            } catch (BusinessException e) {
                synchronized (outcomes) {
                    outcomes.add(OperationOutcome.failed(e.errorCode()));
                }
                return null;
            } catch (Throwable t) {
                unexpected.compareAndSet(null, t);
                return null;
            }
        });

        assertThat(unexpected.get()).as("no unexpected exceptions").isNull();
        assertThat(outcomes).hasSize(threads);
        assertThat(outcomes.stream().filter(OperationOutcome::success).count())
                .as("all 110 operations succeed")
                .isEqualTo(threads);

        // 100000 + 50*100 - 60*50 = 102000
        assertThat(walletAppService.getWallet(playerId).getBalance())
                .isEqualTo(10_200_000L);

        // seed + 50 + 60
        assertThat(transactionRepository.countByWalletId(wallet.getId())).isEqualTo(111);
        assertThat(ledgerEntryRepository.countByWalletId(wallet.getId())).isEqualTo(111);
        assertThat(reconciliationService.reconcile(playerId).consistent()).isTrue();
    }
}
