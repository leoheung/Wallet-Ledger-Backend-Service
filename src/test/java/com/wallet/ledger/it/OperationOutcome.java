package com.wallet.ledger.it;

import com.wallet.ledger.domain.exception.ErrorCode;

/** Result of one concurrent money-move attempt. */
record OperationOutcome(boolean success,
                        boolean replayed,
                        Long transactionId,
                        ErrorCode errorCode) {

    static OperationOutcome ok(Long transactionId, boolean replayed) {
        return new OperationOutcome(true, replayed, transactionId, null);
    }

    static OperationOutcome failed(ErrorCode errorCode) {
        return new OperationOutcome(false, false, null, errorCode);
    }
}
