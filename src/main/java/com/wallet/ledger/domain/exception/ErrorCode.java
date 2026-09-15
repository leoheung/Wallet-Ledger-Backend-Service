package com.wallet.ledger.domain.exception;

import org.springframework.http.HttpStatus;

/** Stable, machine-readable error codes mapped to HTTP statuses. */
public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Request validation failed"),
    PLAYER_NOT_FOUND(HttpStatus.NOT_FOUND, "Player not found"),
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "Wallet not found"),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT,
            "Idempotency-Key was already used with a different request payload"),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient funds"),
    CURRENCY_MISMATCH(HttpStatus.UNPROCESSABLE_ENTITY,
            "Request currency does not match the wallet currency"),
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
