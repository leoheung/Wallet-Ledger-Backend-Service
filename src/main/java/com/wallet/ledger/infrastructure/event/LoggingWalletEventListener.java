package com.wallet.ledger.infrastructure.event;

import com.wallet.ledger.domain.event.WalletBalanceChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Demonstrates the domain-event extension point: balance changes are only
 * observed AFTER_COMMIT, so no listener can react to a change that rolls back.
 * Kept as structured logging in this service; other bounded contexts
 * (notifications, analytics, ...) would subscribe the same way.
 */
@Component
public class LoggingWalletEventListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingWalletEventListener.class);

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBalanceChanged(WalletBalanceChangedEvent event) {
        log.info("wallet_balance_changed player_id={} wallet_id={} transaction_id={} type={} "
                        + "amount_minor={} balance_after_minor={} currency={} reason={} idempotency_key={}",
                event.playerId(),
                event.walletId(),
                event.transactionId(),
                event.type(),
                event.amountMinor(),
                event.balanceAfterMinor(),
                event.currencyCode(),
                event.reason(),
                event.idempotencyKey());
    }
}
