package com.wallet.ledger.domain.enums;

/**
 * Why the balance changed. The assignment names mission rewards, purchases and
 * admin actions; new reasons can be appended as features are added.
 */
public enum TransactionReason {
    MISSION_REWARD,
    PURCHASE,
    ADMIN_ADJUSTMENT,
    OTHER
}
