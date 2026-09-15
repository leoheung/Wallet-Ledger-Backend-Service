package com.wallet.ledger.it;

import com.wallet.ledger.application.TransferResult;
import com.wallet.ledger.domain.enums.TransactionReason;
import com.wallet.ledger.domain.model.Wallet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Many transfers in BOTH directions between the same two wallets at the same
 * time. Rows are locked in ascending wallet-id order, so this must complete
 * without a single deadlock/error and must conserve total currency.
 */
class ConcurrentBidirectionalTransferIT extends AbstractPgIT {

    @Test
    void concurrentTransfersBothWays_neverDeadlock_andConserveFunds() throws Exception {
        Long playerA = newPlayer();
        Long playerB = newPlayer();
        walletAppService.credit(playerA, "USD", new BigDecimal("1000.00"),
                TransactionReason.ADMIN_ADJUSTMENT, null, "seed-a");
        walletAppService.credit(playerB, "USD", new BigDecimal("1000.00"),
                TransactionReason.ADMIN_ADJUSTMENT, null, "seed-b");

        int eachDirection = 20; // 40 transfers total, 5.00 each
        int threads = eachDirection * 2;

        List<TransferAttempt> outcomes = StressHarness.runConcurrently(threads, index -> {
            boolean aToB = index % 2 == 0;
            Long from = aToB ? playerA : playerB;
            Long to = aToB ? playerB : playerA;
            try {
                TransferResult result = walletAppService.transfer(
                        from, to, "USD", new BigDecimal("5.00"),
                        null, "transfer-" + index);
                return new TransferAttempt(true, result.replayed(), null);
            } catch (Exception e) {
                return new TransferAttempt(false, false, e.getClass().getSimpleName() + ":" + e.getMessage());
            }
        });

        assertThat(outcomes).hasSize(threads);
        assertThat(outcomes.stream().filter(a -> !a.success).toList())
                .as("no failures: no deadlocks, lock timeouts or constraint errors")
                .isEmpty();
        assertThat(outcomes.stream().filter(a -> a.replayed).count())
                .as("all keys were unique, nothing replays")
                .isZero();

        Wallet walletA = walletAppService.getWallet(playerA);
        Wallet walletB = walletAppService.getWallet(playerB);

        // 20 outflows of 5.00 and 20 inflows of 5.00 per wallet net to zero.
        assertThat(walletA.getBalance()).isEqualTo(100_000L);
        assertThat(walletB.getBalance()).isEqualTo(100_000L);

        // 2 seed credits + 40 transfers x 2 legs = 82 transactions/ledger entries.
        assertThat(transactionRepository.count()).isEqualTo(82L);
        assertThat(ledgerEntryRepository.count()).isEqualTo(82L);

        // Every transfer leg must be cross-linked to its counterparty.
        long transferLegs = transactionRepository.findAll().stream()
                .filter(t -> t.getReason() == TransactionReason.PLAYER_TRANSFER)
                .count();
        assertThat(transferLegs).isEqualTo(80L);
        boolean allLinked = transactionRepository.findAll().stream()
                .filter(t -> t.getReason() == TransactionReason.PLAYER_TRANSFER)
                .allMatch(t -> t.getRelatedTransactionId() != null);
        assertThat(allLinked).isTrue();

        var reconciliationA = reconciliationService.reconcile(playerA);
        var reconciliationB = reconciliationService.reconcile(playerB);
        assertThat(reconciliationA.consistent()).isTrue();
        assertThat(reconciliationB.consistent()).isTrue();
    }

    private record TransferAttempt(boolean success, boolean replayed, String error) {
    }
}
