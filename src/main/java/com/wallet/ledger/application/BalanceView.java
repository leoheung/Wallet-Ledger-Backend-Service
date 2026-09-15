package com.wallet.ledger.application;

import com.wallet.ledger.application.cache.BalanceSnapshot;
import com.wallet.ledger.domain.model.Wallet;

/** Read model returned by the balance query path. */
public record BalanceView(Long playerId,
                          Long walletId,
                          String currencyCode,
                          long balanceMinor,
                          long version) {

    public static BalanceView from(Long playerId, Wallet wallet) {
        return new BalanceView(playerId, wallet.getId(), wallet.getCurrencyCode(),
                wallet.getBalance(), wallet.getVersion());
    }

    public static BalanceView from(BalanceSnapshot snapshot) {
        return new BalanceView(snapshot.playerId(), snapshot.walletId(),
                snapshot.currencyCode(), snapshot.balanceMinor(), snapshot.version());
    }

    public BalanceSnapshot toSnapshot() {
        return new BalanceSnapshot(playerId, walletId, currencyCode, balanceMinor, version);
    }
}
