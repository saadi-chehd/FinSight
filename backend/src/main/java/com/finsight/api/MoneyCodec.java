package com.finsight.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;

/** Shared exact-scale monetary validation and string formatting helpers. */
public final class MoneyCodec {
    public static final BigDecimal ZERO = new BigDecimal("0.00");
    public static final BigDecimal MAX = new BigDecimal("99999999999999999.99");
    public static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD", "EUR", "GBP", "TRY");

    private MoneyCodec() { }

    public static BigDecimal exact(BigDecimal amount) {
        if (amount == null) {
            throw new IllegalArgumentException("Money is required");
        }
        try {
            BigDecimal value = amount.setScale(2, RoundingMode.UNNECESSARY);
            if (value.abs().compareTo(MAX) > 0) {
                throw new IllegalArgumentException("Money exceeds NUMERIC(19,2) range");
            }
            return value;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Money must use whole minor units", e);
        }
    }

    public static BigDecimal positive(BigDecimal amount) {
        BigDecimal value = exact(amount);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        return value;
    }

    public static BigDecimal parseExact(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Money value is required");
        }
        try {
            return exact(new BigDecimal(text.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid monetary amount", e);
        }
    }

    public static BigDecimal parsePositive(String text) {
        BigDecimal value = parseExact(text);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        return value;
    }

    public static String currency(String currency) {
        if (currency == null || !SUPPORTED_CURRENCIES.contains(currency.trim())) {
            throw new IllegalArgumentException("Unsupported currency");
        }
        return currency.trim();
    }

    public static String format(BigDecimal amount) {
        return exact(amount).toPlainString();
    }
}
