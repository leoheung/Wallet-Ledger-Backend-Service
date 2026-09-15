package com.wallet.ledger.api.dto;

import com.wallet.ledger.application.Money;
import com.wallet.ledger.application.TransferResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record TransferResponse(Long fromPlayerId,
                               Long toPlayerId,
                               Long senderTransactionId,
                               Long receiverTransactionId,
                               String currencyCode,
                               BigDecimal amount,
                               BigDecimal senderBalanceAfter,
                               BigDecimal receiverBalanceAfter,
                               boolean replayed,
                               OffsetDateTime createdAt) {

    /** Domain transfer legs do not carry player ids; they are supplied by the controller. */
    public static TransferResponse from(Long fromPlayerId,
                                        Long toPlayerId,
                                        TransferResult result) {
        var sender = result.senderLeg();
        var receiver = result.receiverLeg();
        return new TransferResponse(
                fromPlayerId,
                toPlayerId,
                sender.getId(),
                receiver == null ? null : receiver.getId(),
                sender.getCurrencyCode(),
                Money.toMajorUnits(sender.getAmount(), sender.getCurrencyCode()),
                Money.toMajorUnits(sender.getBalanceAfter(), sender.getCurrencyCode()),
                receiver == null ? null
                        : Money.toMajorUnits(receiver.getBalanceAfter(), receiver.getCurrencyCode()),
                result.replayed(),
                sender.getCreatedAt().withOffsetSameInstant(ZoneOffset.UTC));
    }
}
