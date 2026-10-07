package com.example.agentcostcontrol.domain;

import com.example.agentcostcontrol.model.BillingCycle;
import com.example.agentcostcontrol.model.Subscription;
import com.example.agentcostcontrol.model.SubscriptionImportance;
import com.example.agentcostcontrol.model.SubscriptionStatus;
import com.example.agentcostcontrol.model.UsageLevel;

import org.junit.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FinancialSummaryCalculatorTest {
    private static final LocalDate START_DATE = LocalDate.parse("2026-01-01");
    private static final OffsetDateTime TIMESTAMP = OffsetDateTime.parse("2026-10-06T12:00:00Z");

    @Test
    public void calculateGroupsActiveSpendByCurrencyAndExcludesInactiveSubscriptions() {
        List<Subscription> subscriptions = new ArrayList<>();
        subscriptions.add(subscription(1, "100.00", "ZAR", BillingCycle.MONTHLY,
                SubscriptionStatus.ACTIVE, LocalDate.parse("2026-11-01")));
        subscriptions.add(subscription(2, "120.00", "ZAR", BillingCycle.ANNUAL,
                SubscriptionStatus.ACTIVE, LocalDate.parse("2026-11-02")));
        subscriptions.add(subscription(3, "10.00", "USD", BillingCycle.MONTHLY,
                SubscriptionStatus.ACTIVE, LocalDate.parse("2026-11-03")));
        subscriptions.add(subscription(4, "900.00", "ZAR", BillingCycle.MONTHLY,
                SubscriptionStatus.PAUSED, LocalDate.parse("2026-11-04")));
        subscriptions.add(subscription(5, "800.00", "USD", BillingCycle.ANNUAL,
                SubscriptionStatus.CANCELLED, LocalDate.parse("2026-11-05")));

        FinancialSummary summary = FinancialSummaryCalculator.calculate(subscriptions);

        assertEquals(3, summary.getActiveSubscriptionCount());
        assertEquals(new BigDecimal("110.00"), summary.getMonthlySpendByCurrency().get("ZAR"));
        assertEquals(new BigDecimal("10.00"), summary.getMonthlySpendByCurrency().get("USD"));
        assertEquals(2, summary.getMonthlySpendByCurrency().size());
        assertFalse(summary.getMonthlySpendByCurrency().containsKey("EUR"));
    }

    @Test
    public void calculateSumsAnnualPricesBeforeDividingAndRounding() {
        List<Subscription> subscriptions = new ArrayList<>();
        for (int id = 1; id <= 6; id++) {
            subscriptions.add(subscription(id, "0.01", "ZAR", BillingCycle.ANNUAL,
                    SubscriptionStatus.ACTIVE, LocalDate.parse("2027-01-01")));
        }

        FinancialSummary summary = FinancialSummaryCalculator.calculate(subscriptions);

        assertEquals(new BigDecimal("0.01"), summary.getMonthlySpendForCurrency("ZAR"));
    }

    @Test
    public void monthlyEquivalentReturnsUnroundedAnnualAmount() {
        Subscription subscription = subscription(1, "0.01", "ZAR", BillingCycle.ANNUAL,
                SubscriptionStatus.ACTIVE, LocalDate.parse("2027-01-01"));

        BigDecimal monthlyEquivalent = FinancialSummaryCalculator.monthlyEquivalent(subscription);
        assertTrue(monthlyEquivalent.compareTo(BigDecimal.ZERO) > 0);
        assertTrue(monthlyEquivalent.compareTo(new BigDecimal("0.01")) < 0);
        assertTrue(monthlyEquivalent.scale() > 2);
    }

    private static Subscription subscription(
            long id,
            String price,
            String currency,
            BillingCycle billingCycle,
            SubscriptionStatus status,
            LocalDate renewalDate) {
        return new Subscription(
                id,
                1L,
                null,
                "Service " + id,
                null,
                "category-" + id,
                new BigDecimal(price),
                currency,
                billingCycle,
                status,
                UsageLevel.UNKNOWN,
                SubscriptionImportance.MEDIUM,
                true,
                START_DATE,
                renewalDate,
                TIMESTAMP,
                TIMESTAMP);
    }
}
