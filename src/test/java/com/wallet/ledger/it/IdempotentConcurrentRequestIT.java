package com.wallet.ledger.it;

import com.wallet.ledger.application.LedgerOperationResult;
import com.wallet.ledger.domain.enums.TransactionReason;
import com.wallet.ledger.domain.exception.BusinessException;
import com.wallet.ledger.domain.model.Wallet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 30 concurrent requests carrying the SAME Idempotency-Key and the SAME body
 * must result in exactly one credit. All 30 callers receive a successful
 * response (the other 29 are replays), never an error or a double credit.
 */
class IdempotentConcurrentRequestIT extends AbstractPgIT {

    @Test
    void sameIdempotencyKeyAppliesExactlyOnceUnderConcurrency() throws Exception {
        Long playerId = newPlayer();
        Wallet wallet = walletAppService.getWallet(playerId);

        int threads = 30;
        List<OperationOutcome> outcomes = StressHarness.runConcurrently(threads, index -> {
            try {
                LedgerOperationResult result = walletAppService.credit(
                        playerId, "USD", new BigDecimal("100.00"),
                        TransactionReason.MISSION_REWARD, "mission-x", "shared-key");
                return OperationOutcome.ok(result.transaction().getId(), result.replayed());
            } catch (BusinessException e) {
                return OperationOutcome.failed(e.errorCode());
            }
        });

        long errors = outcomes.stream().filter(o -> !o.success()).count();
        long applied = outcomes.stream().filter(o -> o.success() && !o.replayed()).count();
        long replays = outcomes.stream().filter(o -> o.success() && o.replayed()).count();
        Set<Long> transactionIds = outcomes.stream()
                .map(OperationOutcome::transactionId)
                .collect(Collectors.toSet());

        assertThat(errors).as("no request fails").isZero();
        assertThat(applied).as("exactly one request applies the credit").isOne();
        assertThat(replays).as("the other 29 requests are replays").isEqualTo(29);
        assertThat(transactionIds).hasSize(1);

        assertThat(walletAppService.getWallet(playerId).getBalance())
                .as("balance credited once")
                .isEqualTo(10_000L);
        assertThat(transactionRepository.countByWalletId(wallet.getId())).isOne();
        assertThat(ledgerEntryRepository.countByWalletId(wallet.getId())).isOne();
        assertThat(reconciliationService.reconcile(playerId).consistent()).isTrue();
    }
}
