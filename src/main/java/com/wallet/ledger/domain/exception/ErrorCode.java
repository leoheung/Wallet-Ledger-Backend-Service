package com.wallet.ledger.domain.exception;

import org.springframework.http.HttpStatus;

/** Stable, machine-readable error codes mapped to HTTP statuses. */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),
    PLAYER_NOT_FOUND(HttpStatus.NOT_FOUND, "Player not found"),
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "Wallet not found"),
    TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Transaction not found"),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT,
            "Idempotency-Key was already used with a different request payload"),
    TRANSACTION_ALREADY_REFUNDED(HttpStatus.CONFLICT,
            "The transaction has already been refunded"),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds"),
    CURRENCY_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY,
            "Request currency does not match the wallet currency"),
    TRANSFER_SAME_PLAYER(HttpStatus.BAD_REQUEST,
            "Sender and recipient must be different players"),
    TRANSACTION_NOT_REFUNDABLE(HttpStatus.UNPROCESSABLE_ENTITY,
            "Only original credit/debit transactions can be refunded"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error");

    private final HttpStatus httpStatus;
    private final String title;

    ErrorCode(HttpStatus httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String title() {
        return title;
    }
}
