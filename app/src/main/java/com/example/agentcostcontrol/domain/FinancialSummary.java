package com.example.agentcostcontrol.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Immutable summary of active subscription spend, grouped by currency. */
public final class FinancialSummary {
    private final int activeSubscriptionCount;
    private final Map<String, BigDecimal> monthlySpendByCurrency;

    FinancialSummary(
            int activeSubscriptionCount,
            Map<String, BigDecimal> monthlySpendByCurrency) {
        this.activeSubscriptionCount = activeSubscriptionCount;
        this.monthlySpendByCurrency = Collections.unmodifiableMap(
                new TreeMap<>(monthlySpendByCurrency));
    }

    public int getActiveSubscriptionCount() {
        return activeSubscriptionCount;
    }

    /**
     * Returns active monthly-equivalent totals rounded once per currency to two decimal places.
     * The returned map is sorted by currency and cannot be modified.
     */
    public Map<String, BigDecimal> getMonthlySpendByCurrency() {
        return monthlySpendByCurrency;
    }

    public BigDecimal getMonthlySpendForCurrency(String currency) {
        BigDecimal amount = monthlySpendByCurrency.get(currency);
        return amount == null ? BigDecimal.ZERO.setScale(2) : amount;
    }
}
