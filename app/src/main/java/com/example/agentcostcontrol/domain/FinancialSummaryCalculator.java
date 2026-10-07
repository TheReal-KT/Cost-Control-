package com.example.agentcostcontrol.domain;

import com.example.agentcostcontrol.model.BillingCycle;
import com.example.agentcostcontrol.model.Subscription;
import com.example.agentcostcontrol.model.SubscriptionStatus;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Deterministic financial calculations over a caller-provided subscription snapshot. */
public final class FinancialSummaryCalculator {
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);
    private static final int MONEY_SCALE = 2;

    private FinancialSummaryCalculator() {
    }

    /**
     * Summarizes ACTIVE subscriptions. Annual prices are summed per currency before division by
     * twelve, and each final currency total is rounded once to the database's two-decimal scale.
     */
    public static FinancialSummary calculate(List<Subscription> subscriptions) {
        Objects.requireNonNull(subscriptions, "subscriptions");
        Map<String, CurrencyTotals> totalsByCurrency = new HashMap<>();
        int activeCount = 0;

        for (Subscription subscription : subscriptions) {
            Objects.requireNonNull(subscription, "subscriptions must not contain null");
            if (subscription.getStatus() != SubscriptionStatus.ACTIVE) {
                continue;
            }

            activeCount++;
            CurrencyTotals totals = totalsByCurrency.computeIfAbsent(
                    subscription.getCurrency(), ignored -> new CurrencyTotals());
            if (subscription.getBillingCycle() == BillingCycle.ANNUAL) {
                totals.annualPrices = totals.annualPrices.add(subscription.getPrice());
            } else {
                totals.monthlyPrices = totals.monthlyPrices.add(subscription.getPrice());
            }
        }

        Map<String, BigDecimal> monthlySpendByCurrency = new TreeMap<>();
        for (Map.Entry<String, CurrencyTotals> entry : totalsByCurrency.entrySet()) {
            CurrencyTotals totals = entry.getValue();
            BigDecimal monthlyEquivalent = totals.monthlyPrices.add(
                    totals.annualPrices.divide(TWELVE, MathContext.DECIMAL128));
            monthlySpendByCurrency.put(
                    entry.getKey(), monthlyEquivalent.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        }
        return new FinancialSummary(activeCount, monthlySpendByCurrency);
    }

    /** Returns an unrounded monthly equivalent for a single subscription. */
    public static BigDecimal monthlyEquivalent(Subscription subscription) {
        Objects.requireNonNull(subscription, "subscription");
        if (subscription.getBillingCycle() == BillingCycle.ANNUAL) {
            return subscription.getPrice().divide(TWELVE, MathContext.DECIMAL128);
        }
        return subscription.getPrice();
    }

    private static final class CurrencyTotals {
        private BigDecimal monthlyPrices = BigDecimal.ZERO;
        private BigDecimal annualPrices = BigDecimal.ZERO;
    }
}
