package com.wallet.ledger.api.dto;

import com.wallet.ledger.application.Money;
import com.wallet.ledger.domain.model.Wallet;

import java.math.BigDecimal;

public record WalletResponse(Long playerId,
                             Long walletId,
                             String currencyCode,
                             BigDecimal balance) {

    public static WalletResponse from(Long playerId, Wallet wallet) {
        return new WalletResponse(
                playerId,
                wallet.getId(),
                wallet.getCurrencyCode(),
                Money.toMajorUnits(wallet.getBalance(), wallet.getCurrencyCode()));
    }
}
