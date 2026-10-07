package com.example.agentcostcontrol.data;

import java.math.BigDecimal;
import java.util.Objects;

/** Exact decimal conversion used for PostgreSQL numeric money fields. */
final class MoneyCodec {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    private MoneyCodec() { }

    static String toJsonNumber(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0 || amount.scale() > 2 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("Money must be non-negative with at most two decimal places");
        }
        return amount.toPlainString();
    }

    static BigDecimal fromText(String value) {
        try {
            BigDecimal amount = new BigDecimal(value);
            if (amount.signum() < 0 || amount.scale() > 2 || amount.compareTo(MAX_AMOUNT) > 0) {
                throw new NumberFormatException("Money is outside the supported precision");
            }
            return amount;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Invalid decimal amount", exception);
        }
    }
}
