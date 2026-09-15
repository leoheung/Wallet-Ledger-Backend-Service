package com.wallet.ledger.application;

import com.wallet.ledger.domain.exception.BusinessException;
import com.wallet.ledger.domain.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void convertsUsdMajorToMinorAndBack() {
        long minor = Money.toMinorUnits(new BigDecimal("12.50"), "USD");
        assertThat(minor).isEqualTo(1250L);
        assertThat(Money.toMajorUnits(minor, "USD")).isEqualByComparingTo("12.50");
    }

    @Test
    void convertsZeroDecimalCurrency() {
        long minor = Money.toMinorUnits(new BigDecimal("100"), "JPY");
        assertThat(minor).isEqualTo(100L);
        assertThat(Money.toMajorUnits(minor, "JPY")).isEqualByComparingTo("100");
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        assertThatThrownBy(() -> Money.toMinorUnits(BigDecimal.ZERO, "USD"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);

        assertThatThrownBy(() -> Money.toMinorUnits(new BigDecimal("-1.00"), "USD"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsMoreFractionDigitsThanCurrencySupports() {
        assertThatThrownBy(() -> Money.toMinorUnits(new BigDecimal("12.555"), "USD"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("fraction digits");
    }

    @Test
    void rejectsUnknownCurrencyCode() {
        assertThatThrownBy(() -> Money.toMinorUnits(new BigDecimal("1"), "ZZZ"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ZZZ");
    }
}
