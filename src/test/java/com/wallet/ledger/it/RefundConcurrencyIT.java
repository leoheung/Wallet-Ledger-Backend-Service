package com.wallet.ledger.it;

import com.wallet.ledger.application.LedgerOperationResult;
import com.wallet.ledger.domain.enums.TransactionReason;
import com.wallet.ledger.domain.exception.BusinessException;
import com.wallet.ledger.domain.exception.ErrorCode;
import com.wallet.ledger.domain.model.Wallet;
import com.wallet.ledger.domain.model.WalletTransaction;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real PostgreSQL proof for refund races:
 *  - two DIFFERENT idempotency keys refunding the same txn at the same time:
 *    exactly one succeeds, the loser gets 409 TRANSACTION_ALREADY_REFUNDED
 *    (service pre-check + DB unique index);
 *  - many concurrent retries with the SAME key collapse onto one reversal.
 */
class RefundConcurrencyIT extends AbstractPgIT {

    @Test
    void twoDifferentKeysRefundConcurrently_exactlyOneWins() throws Exception {
        Long playerId = newPlayer();
        LedgerOperationResult reward = walletAppService.credit(playerId, "USD",
                new BigDecimal("100.00"), TransactionReason.MISSION_REWARD, null, "reward-1");

        List<RefundAttempt> outcomes = StressHarness.runConcurrently(2, index -> {
            try {
                LedgerOperationResult r = walletAppService.refund(
                        playerId, reward.transaction().getId(), "refund-key-" + index);
                return new RefundAttempt(true, r.replayed(), r.transaction().getId(), null);
            } catch (BusinessException e) {
                return new RefundAttempt(false, false, null, e.errorCode());
            }
        });

        long winners = outcomes.stream().filter(a -> a.success && !a.replayed).count();
        long losers = outcomes.stream()
                .filter(a -> !a.success && a.errorCode == ErrorCode.TRANSACTION_ALREADY_REFUNDED)
                .count();

        assertThat(winners).as("exactly one refund applied").isEqualTo(1);
        assertThat(losers).as("the second refund is rejected with 409").isEqualTo(1);

        Wallet wallet = walletAppService.getWallet(playerId);
        assertThat(wallet.getBalance()).isZero();

        long reversalCount = transactionRepository.findAll().stream()
                .filter(t -> t.getOriginalTransactionId() != null)
                .count();
        assertThat(reversalCount).isEqualTo(1L);
    }

    @Test
    void sameKeyRefundRetriedConcurrently_collapsesToOneReversal() throws Exception {
        Long playerId = newPlayer();
        LedgerOperationResult reward = walletAppService.credit(playerId, "USD",
                new BigDecimal("100.00"), TransactionReason.MISSION_REWARD, null, "reward-2");
        long originalId = reward.transaction().getId();

        int threads = 15;
        List<RefundAttempt> outcomes = StressHarness.runConcurrently(threads, index -> {
            try {
                LedgerOperationResult r = walletAppService.refund(
                        playerId, originalId, "same-refund-key");
                return new RefundAttempt(true, r.replayed(), r.transaction().getId(), null);
            } catch (BusinessException e) {
                return new RefundAttempt(false, false, null, e.errorCode());
            }
        });

        long applied = outcomes.stream().filter(a -> a.success && !a.replayed).count();
        long replayed = outcomes.stream().filter(a -> a.success && a.replayed).count();
        long errors = outcomes.stream().filter(a -> !a.success).count();

        assertThat(applied).isEqualTo(1);
        assertThat(replayed).isEqualTo(threads - 1L);
        assertThat(errors).isZero();

        // All 15 responses point at the SAME reversal row.
        long distinctReversalIds = outcomes.stream()
                .filter(a -> a.transactionId != null)
                .map(a -> a.transactionId)
                .distinct()
                .count();
        assertThat(distinctReversalIds).isEqualTo(1);

        Wallet wallet = walletAppService.getWallet(playerId);
        assertThat(wallet.getBalance()).isZero();

        WalletTransaction reversal = transactionRepository.findAll().stream()
                .filter(t -> t.getOriginalTransactionId() != null)
                .findFirst().orElseThrow();
        assertThat(reversal.getOriginalTransactionId()).isEqualTo(originalId);
        assertThat(reversal.getReason()).isEqualTo(TransactionReason.REFUND);
    }

    private record RefundAttempt(boolean success,
                                 boolean replayed,
                                 Long transactionId,
                                 ErrorCode errorCode) {
    }
}
