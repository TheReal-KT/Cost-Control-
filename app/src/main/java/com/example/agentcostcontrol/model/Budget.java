package com.example.agentcostcontrol.model;

import java.math.BigDecimal;
import java.util.Objects;

public final class Budget {
    private final long id;
    private final long userId;
    private final BigDecimal limitAmount;
    private final String currency;
    private final int month;
    private final int year;

    public Budget(long id, long userId, BigDecimal limitAmount, String currency, int month, int year) {
        if (id <= 0 || userId <= 0) throw new IllegalArgumentException("Budget IDs must be positive");
        this.id = id;
        this.userId = userId;
        this.limitAmount = Objects.requireNonNull(limitAmount, "limitAmount");
        if (limitAmount.signum() < 0 || limitAmount.scale() > 2
                || limitAmount.compareTo(new BigDecimal("9999999999.99")) > 0) {
            throw new IllegalArgumentException("limitAmount is outside the supported money range");
        }
        this.currency = Objects.requireNonNull(currency, "currency");
        if (!currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("currency must be a three-letter uppercase code");
        if (month < 1 || month > 12 || year < 2000 || year > 9999) throw new IllegalArgumentException("Invalid budget period");
        this.month = month;
        this.year = year;
    }

    public long getId() { return id; }
    public long getUserId() { return userId; }
    public BigDecimal getLimitAmount() { return limitAmount; }
    public String getCurrency() { return currency; }
    public int getMonth() { return month; }
    public int getYear() { return year; }
}
