package com.example.agentcostcontrol.domain;

import com.example.agentcostcontrol.model.BillingCycle;
import com.example.agentcostcontrol.model.Budget;
import com.example.agentcostcontrol.model.Subscription;
import com.example.agentcostcontrol.model.SubscriptionImportance;
import com.example.agentcostcontrol.model.SubscriptionStatus;
import com.example.agentcostcontrol.model.UsageLevel;

import org.junit.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RiskRuleEngineTest {
    private static final LocalDate AS_OF_DATE = LocalDate.parse("2026-10-06");
    private static final LocalDate START_DATE = LocalDate.parse("2026-01-01");
    private static final OffsetDateTime TIMESTAMP = OffsetDateTime.parse("2026-10-06T12:00:00Z");

    @Test
    public void renewalRuleIncludesTodayAndWindowEndButExcludesOutsideAndInactive() {
        List<Subscription> subscriptions = Arrays.asList(
                subscription(1, "10.00", "ZAR", BillingCycle.MONTHLY,
                        SubscriptionStatus.ACTIVE, UsageLevel.UNKNOWN, "video", "2026-10-06"),
                subscription(2, "10.00", "ZAR", BillingCycle.MONTHLY,
                        SubscriptionStatus.ACTIVE, UsageLevel.UNKNOWN, "music", "2026-10-13"),
                subscription(3, "10.00", "ZAR", BillingCycle.MONTHLY,
                        SubscriptionStatus.ACTIVE, UsageLevel.UNKNOWN, "cloud", "2026-10-14"),
                subscription(4, "10.00", "ZAR", BillingCycle.MONTHLY,
                        SubscriptionStatus.PAUSED, UsageLevel.UNKNOWN, "paused", "2026-10-07"));

        List<RiskFlag> flags = RiskRuleEngine.evaluate(
                subscriptions,
                Collections.emptyList(),
                AS_OF_DATE,
                config(7, Collections.emptyMap(), 100));

        assertEquals(2, flags.size());
        assertEquals(RiskFlagType.RENEWAL_APPROACHING, flags.get(0).getType());
        assertEquals(Long.valueOf(1), flags.get(0).getEvidence().getSubscriptionIds().get(0));
        assertEquals(LocalDate.parse("2026-10-06"),
                flags.get(0).getEvidence().getRenewalDatesBySubscriptionId().get(1L));
        assertEquals(Long.valueOf(2), flags.get(1).getEvidence().getSubscriptionIds().get(0));
    }

    @Test
    public void budgetRuleUsesCurrentMonthMatchingCurrencyAndRoundedSummary() {
        List<Subscription> subscriptions = Arrays.asList(
                subscription(1, "100.00", "ZAR", BillingCycle.MONTHLY,
                        SubscriptionStatus.ACTIVE, UsageLevel.UNKNOWN, "one", "2027-01-01"),
                subscription(2, "25.00", "USD", BillingCycle.MONTHLY,
                        SubscriptionStatus.ACTIVE, UsageLevel.UNKNOWN, "two", "2027-01-02"),
                subscription(3, "12.01", "EUR", BillingCycle.ANNUAL,
                        SubscriptionStatus.ACTIVE, UsageLevel.UNKNOWN, "three", "2027-01-03"));
        List<Budget> budgets = Arrays.asList(
                budget(10, "99.99", "ZAR", 10, 2026),
                budget(11, "25.00", "USD", 10, 2026),
                budget(12, "1.00", "EUR", 10, 2026),
                budget(13, "0.00", "ZAR", 9, 2026));

        List<RiskFlag> flags = RiskRuleEngine.evaluate(
                subscriptions,
                budgets,
                AS_OF_DATE,
                config(0, Collections.emptyMap(), 100));

        assertEquals(1, flags.size());
        RiskFlag flag = flags.get(0);
        assertEquals(RiskFlagType.BUDGET_EXCEEDED, flag.getType());
        assertEquals(Long.valueOf(10), flag.getEvidence().getBudgetId());
        assertEquals(YearMonth.of(2026, 10), flag.getEvidence().getBudgetPeriod());
        assertEquals("ZAR", flag.getEvidence().getCurrency());
        assertEquals(new BigDecimal("100.00"), flag.getEvidence().getMonthlyAmount());
        assertTrue(flag.getExplanation().contains("#10"));
    }

    @Test
    public void lowUsageRuleRequiresReportedLowUsageAndCurrencyThreshold() {
        List<Subscription> subscriptions = Arrays.asList(
                subscription(1, "50.00", "ZAR", BillingCycle.MONTHLY,
                        SubscriptionStatus.ACTIVE, UsageLevel.LOW, "a", "2027-01-01"),
                subscription(2, "100.00", "ZAR", BillingCycle.MONTHLY,
                        SubscriptionStatus.ACTIVE, UsageLevel.UNKNOWN, "b", "2027-01-02"),
                subscription(3, "100.00", "ZAR", BillingCycle.MONTHLY,
                        SubscriptionStatus.ACTIVE, null, "c", "2027-01-03"),
                subscription(4, "75.00", "ZAR", BillingCycle.MONTHLY,
                        SubscriptionStatus.ACTIVE, UsageLevel.MEDIUM, "d", "2027-01-04"),
                subscription(5, "100.00", "USD", BillingCycle.MONTHLY,
                        SubscriptionStatus.ACTIVE, UsageLevel.LOW, "e", "2027-01-05"));

        List<RiskFlag> flags = RiskRuleEngine.evaluate(
                subscriptions,
                Collections.emptyList(),
                AS_OF_DATE,
                config(0, Collections.singletonMap("ZAR", new BigDecimal("50.00")), 100));

        assertEquals(1, flags.size());
        assertEquals(RiskFlagType.LOW_USAGE_HIGH_COST, flags.get(0).getType());
        assertEquals(Collections.singletonList(1L), flags.get(0).getEvidence().getSubscriptionIds());
        assertEquals(new BigDecimal("50.00"), flags.get(0).getEvidence().getMonthlyAmount());
        assertEquals(new BigDecimal("50.00"), flags.get(0).getEvidence().getThresholdAmount());
    }

    @Test
    public void duplicateCategoryFlagIsSameCurrencyOnlyAndExplainsUncertainty() {
        List<Subscription> subscriptions = Arrays.asList(
                subscription(1, "10.00", "ZAR", BillingCycle.MONTHLY,
                        SubscriptionStatus.ACTIVE, UsageLevel.UNKNOWN, "Streaming", "2027-01-01"),
                subscription(2, "20.00", "ZAR", BillingCycle.MONTHLY,
                        SubscriptionStatus.ACTIVE, UsageLevel.UNKNOWN, " streaming ", "2027-01-02"),
                subscription(3, "10.00", "USD", BillingCycle.MONTHLY,
                        SubscriptionStatus.ACTIVE, UsageLevel.UNKNOWN, "streaming", "2027-01-03"));

        List<RiskFlag> flags = RiskRuleEngine.evaluate(
                subscriptions,
                Collections.emptyList(),
                AS_OF_DATE,
                config(0, Collections.emptyMap(), 100));

        assertEquals(1, flags.size());
        assertEquals(RiskFlagType.POSSIBLE_DUPLICATE_CATEGORY, flags.get(0).getType());
        assertEquals(Arrays.asList(1L, 2L), flags.get(0).getEvidence().getSubscriptionIds());
        assertEquals("ZAR", flags.get(0).getEvidence().getCurrency());
        assertEquals(LocalDate.parse("2027-01-01"),
                flags.get(0).getEvidence().getRenewalDatesBySubscriptionId().get(1L));
        assertTrue(flags.get(0).getExplanation().contains("may be intentional"));
    }

    @Test
    public void outputIsBoundedAndReadOnly() {
        List<Subscription> subscriptions = Arrays.asList(
                subscription(1, "10.00", "ZAR", BillingCycle.MONTHLY,
                        SubscriptionStatus.ACTIVE, UsageLevel.UNKNOWN, "one", "2026-10-07"),
                subscription(2, "10.00", "ZAR", BillingCycle.MONTHLY,
                        SubscriptionStatus.ACTIVE, UsageLevel.UNKNOWN, "two", "2026-10-08"));

        List<RiskFlag> flags = RiskRuleEngine.evaluate(
                subscriptions,
                Collections.emptyList(),
                AS_OF_DATE,
                config(7, Collections.emptyMap(), 1));

        assertEquals(1, flags.size());
        assertEquals(Long.valueOf(1), flags.get(0).getEvidence().getSubscriptionIds().get(0));
        assertThrows(UnsupportedOperationException.class, () -> flags.add(flags.get(0)));
        assertThrows(UnsupportedOperationException.class,
                () -> flags.get(0).getEvidence().getRenewalDatesBySubscriptionId().put(9L, AS_OF_DATE));
    }

    @Test
    public void configRejectsUnboundedOutputLimitAndNonPositiveCostThresholds() {
        assertThrows(IllegalArgumentException.class,
                () -> config(7, Collections.emptyMap(), RiskRuleConfig.MAXIMUM_ALLOWED_FLAGS + 1));
        assertThrows(IllegalArgumentException.class,
                () -> config(7, Collections.singletonMap("ZAR", BigDecimal.ZERO), 1));
    }

    private static RiskRuleConfig config(
            int renewalWindowDays,
            Map<String, BigDecimal> thresholds,
            int maximumFlags) {
        return new RiskRuleConfig(renewalWindowDays, thresholds, maximumFlags);
    }

    private static Budget budget(long id, String limit, String currency, int month, int year) {
        return new Budget(id, 1L, new BigDecimal(limit), currency, month, year);
    }

    private static Subscription subscription(
            long id,
            String price,
            String currency,
            BillingCycle billingCycle,
            SubscriptionStatus status,
            UsageLevel usageLevel,
            String category,
            String renewalDate) {
        return new Subscription(
                id,
                1L,
                null,
                "Service " + id,
                null,
                category,
                new BigDecimal(price),
                currency,
                billingCycle,
                status,
                usageLevel,
                SubscriptionImportance.MEDIUM,
                true,
                START_DATE,
                LocalDate.parse(renewalDate),
                TIMESTAMP,
                TIMESTAMP);
    }
}
