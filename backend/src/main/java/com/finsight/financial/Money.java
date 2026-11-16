package com.finsight.financial;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

final class Money {
    static final BigDecimal ZERO = new BigDecimal("0.00");
    static final BigDecimal MAX = new BigDecimal("99999999999999999.99");
    private Money() { }

    static BigDecimal exact(BigDecimal amount) {
        if (amount == null) throw new IllegalArgumentException("Money is required");
        try {
            BigDecimal value = amount.setScale(2, RoundingMode.UNNECESSARY);
            if (value.abs().compareTo(MAX) > 0) throw new IllegalArgumentException("Money exceeds NUMERIC(19,2) range");
            return value;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Money must use whole minor units", e);
        }
    }

    static BigDecimal positive(BigDecimal amount) {
        BigDecimal value = exact(amount);
        if (value.signum() <= 0) throw new IllegalArgumentException("Movement/invoice amounts must be positive");
        return value;
    }

    static BigDecimal threshold(BigDecimal amount) {
        BigDecimal value = exact(amount);
        if (value.signum() < 0) throw new IllegalArgumentException("Liquidity threshold must be nonnegative");
        return value;
    }

    static String currency(String currency) {
        if (currency == null || !Set.of("USD", "EUR", "GBP", "TRY").contains(currency)) {
            throw new IllegalArgumentException("Unsupported currency");
        }
        return currency;
    }
}
