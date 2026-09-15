package com.wallet.ledger.application;

import com.wallet.ledger.domain.model.WalletTransaction;

/** Result of a player-to-player transfer: the sender debit leg and receiver credit leg. */
public record TransferResult(WalletTransaction senderLeg,
                             WalletTransaction receiverLeg,
                             boolean replayed) {
}
