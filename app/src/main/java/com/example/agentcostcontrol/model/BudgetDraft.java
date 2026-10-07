package com.example.agentcostcontrol.model;

import java.math.BigDecimal;
import java.util.Objects;

public final class BudgetDraft {
    private final BigDecimal limitAmount;
    private final String currency;
    private final int month;
    private final int year;

    private BudgetDraft(Builder builder) {
        limitAmount = Objects.requireNonNull(builder.limitAmount, "limitAmount");
        if (limitAmount.signum() < 0 || limitAmount.scale() > 2
                || limitAmount.compareTo(new BigDecimal("9999999999.99")) > 0) {
            throw new IllegalArgumentException("limitAmount must be a non-negative amount with at most two decimal places");
        }
        currency = Objects.requireNonNull(builder.currency, "currency");
        if (!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("currency must be a three-letter uppercase code");
        if (builder.month < 1 || builder.month > 12 || builder.year < 2000 || builder.year > 9999) {
            throw new IllegalArgumentException("Invalid budget period");
        }
        month = builder.month;
        year = builder.year;
    }

    public static Builder builder() { return new Builder(); }
    public BigDecimal getLimitAmount() { return limitAmount; }
    public String getCurrency() { return currency; }
    public int getMonth() { return month; }
    public int getYear() { return year; }

    public static final class Builder {
        private BigDecimal limitAmount;
        private String currency = "ZAR";
        private int month;
        private int year;
        public Builder setLimitAmount(BigDecimal value) { limitAmount = value; return this; }
        public Builder setCurrency(String value) { currency = value; return this; }
        public Builder setMonth(int value) { month = value; return this; }
        public Builder setYear(int value) { year = value; return this; }
        public BudgetDraft build() { return new BudgetDraft(this); }
    }
}
