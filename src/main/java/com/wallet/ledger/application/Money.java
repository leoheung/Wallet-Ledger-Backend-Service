package com.wallet.ledger.application;

import com.wallet.ledger.domain.exception.BusinessException;
import com.wallet.ledger.domain.exception.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;

/**
 * Money conversion between API-level {@link BigDecimal} major units and the
 * BIGINT minor units (e.g. cents) persisted by the ledger. The ledger never
 * stores floating point values.
 */
public final class Money {

    private Money() {
    }

    public static long toMinorUnits(BigDecimal amount, String currencyCode) {
        Currency currency = requireSupportedCurrency(currencyCode);
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "amount must be greater than zero");
        }
        int fractionDigits = currency.getDefaultFractionDigits();
        try {
            return amount.setScale(fractionDigits, RoundingMode.UNNECESSARY)
                    .movePointRight(fractionDigits)
                    .longValueExact();
        } catch (ArithmeticException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "amount '%s' has more fraction digits than currency %s supports"
                            .formatted(amount.toPlainString(), currencyCode));
        }
    }

    public static BigDecimal toMajorUnits(long minorUnits, String currencyCode) {
        int fractionDigits = requireSupportedCurrency(currencyCode).getDefaultFractionDigits();
        return BigDecimal.valueOf(minorUnits)
                .movePointLeft(fractionDigits)
                .setScale(fractionDigits);
    }

    public static Currency requireSupportedCurrency(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "currencyCode is required");
        }
        try {
            Currency currency = Currency.getInstance(currencyCode);
            if (currency.getDefaultFractionDigits() < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "currency %s has no defined fraction digits".formatted(currencyCode));
            }
            return currency;
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "unknown ISO 4217 currency code: " + currencyCode);
        }
    }
}
