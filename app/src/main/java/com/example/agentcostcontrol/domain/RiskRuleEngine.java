package com.example.agentcostcontrol.domain;

import com.example.agentcostcontrol.model.Budget;
import com.example.agentcostcontrol.model.Subscription;
import com.example.agentcostcontrol.model.SubscriptionStatus;
import com.example.agentcostcontrol.model.UsageLevel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Evaluates bounded, deterministic checks without persisting actions or recommendations. */
public final class RiskRuleEngine {
    private RiskRuleEngine() {
    }

    /**
     * Evaluates active subscriptions against the supplied date, current-month budgets, and
     * explicit per-currency thresholds. Flags are returned in stable priority order:
     * budget overrun, renewal proximity, low usage/high cost, then possible shared category.
     */
    public static List<RiskFlag> evaluate(
            List<Subscription> subscriptions,
            List<Budget> budgets,
            LocalDate asOfDate,
            RiskRuleConfig config) {
        Objects.requireNonNull(subscriptions, "subscriptions");
        Objects.requireNonNull(budgets, "budgets");
        Objects.requireNonNull(asOfDate, "asOfDate");
        Objects.requireNonNull(config, "config");

        int maximumFlags = config.getMaximumFlags();
        if (maximumFlags == 0) {
            return Collections.emptyList();
        }

        List<Subscription> activeSubscriptions = activeSubscriptions(subscriptions);
        FinancialSummary summary = FinancialSummaryCalculator.calculate(activeSubscriptions);
        List<RiskFlag> flags = new ArrayList<>(maximumFlags);
        YearMonth budgetPeriod = YearMonth.from(asOfDate);

        addBudgetFlags(flags, maximumFlags, budgets, summary, budgetPeriod);
        if (flags.size() < maximumFlags) {
            addRenewalFlags(flags, maximumFlags, activeSubscriptions, asOfDate, config);
        }
        if (flags.size() < maximumFlags) {
            addLowUsageHighCostFlags(flags, maximumFlags, activeSubscriptions, config);
        }
        if (flags.size() < maximumFlags) {
            addPossibleDuplicateFlags(flags, maximumFlags, activeSubscriptions);
        }

        return Collections.unmodifiableList(flags);
    }

    private static List<Subscription> activeSubscriptions(List<Subscription> subscriptions) {
        List<Subscription> active = new ArrayList<>();
        for (Subscription subscription : subscriptions) {
            Objects.requireNonNull(subscription, "subscriptions must not contain null");
            if (subscription.getStatus() == SubscriptionStatus.ACTIVE) {
                active.add(subscription);
            }
        }
        active.sort(Comparator.comparingLong(Subscription::getId));
        return active;
    }

    private static void addBudgetFlags(
            List<RiskFlag> flags,
            int maximumFlags,
            List<Budget> budgets,
            FinancialSummary summary,
            YearMonth budgetPeriod) {
        List<Budget> currentPeriodBudgets = new ArrayList<>();
        for (Budget budget : budgets) {
            Objects.requireNonNull(budget, "budgets must not contain null");
            if (budget.getYear() == budgetPeriod.getYear()
                    && budget.getMonth() == budgetPeriod.getMonthValue()) {
                currentPeriodBudgets.add(budget);
            }
        }
        currentPeriodBudgets.sort(Comparator
                .comparing(Budget::getCurrency)
                .thenComparingLong(Budget::getId));

        for (Budget budget : currentPeriodBudgets) {
            BigDecimal monthlySpend = summary.getMonthlySpendForCurrency(budget.getCurrency());
            if (monthlySpend.compareTo(budget.getLimitAmount()) > 0) {
                String explanation = "Active subscription spend is " + monthlySpend.toPlainString()
                        + " " + budget.getCurrency() + " for " + budgetPeriod
                        + ", above budget #" + budget.getId() + " of "
                        + budget.getLimitAmount().toPlainString() + " " + budget.getCurrency() + ".";
                RiskEvidence evidence = new RiskEvidence(
                        Collections.emptyMap(),
                        budget.getId(),
                        budgetPeriod,
                        budget.getCurrency(),
                        monthlySpend,
                        budget.getLimitAmount());
                flags.add(new RiskFlag(
                        RiskFlagType.BUDGET_EXCEEDED,
                        "Monthly budget exceeded",
                        explanation,
                        evidence));
                if (flags.size() == maximumFlags) {
                    return;
                }
            }
        }
    }

    private static void addRenewalFlags(
            List<RiskFlag> flags,
            int maximumFlags,
            List<Subscription> activeSubscriptions,
            LocalDate asOfDate,
            RiskRuleConfig config) {
        List<Subscription> upcoming = new ArrayList<>();
        for (Subscription subscription : activeSubscriptions) {
            long daysUntilRenewal = ChronoUnit.DAYS.between(asOfDate, subscription.getRenewalDate());
            if (daysUntilRenewal >= 0 && daysUntilRenewal <= config.getRenewalWindowDays()) {
                upcoming.add(subscription);
            }
        }
        upcoming.sort(Comparator
                .comparing(Subscription::getRenewalDate)
                .thenComparingLong(Subscription::getId));

        for (Subscription subscription : upcoming) {
            long daysUntilRenewal = ChronoUnit.DAYS.between(asOfDate, subscription.getRenewalDate());
            String explanation = "Subscription #" + subscription.getId() + " renews on "
                    + subscription.getRenewalDate() + " (" + daysUntilRenewal + " days from "
                    + asOfDate + ") in " + subscription.getCurrency() + ".";
            Map<Long, LocalDate> renewalDates = new HashMap<>();
            renewalDates.put(subscription.getId(), subscription.getRenewalDate());
            flags.add(new RiskFlag(
                    RiskFlagType.RENEWAL_APPROACHING,
                    "Renewal approaching",
                    explanation,
                    new RiskEvidence(
                            renewalDates,
                            null,
                            null,
                            subscription.getCurrency(),
                            FinancialSummaryCalculator.monthlyEquivalent(subscription),
                            null)));
            if (flags.size() == maximumFlags) {
                return;
            }
        }
    }

    private static void addLowUsageHighCostFlags(
            List<RiskFlag> flags,
            int maximumFlags,
            List<Subscription> activeSubscriptions,
            RiskRuleConfig config) {
        List<Subscription> candidates = new ArrayList<>();
        for (Subscription subscription : activeSubscriptions) {
            BigDecimal threshold = config.getMonthlyHighCostThresholdsByCurrency()
                    .get(subscription.getCurrency());
            if (subscription.getUsageLevel() == UsageLevel.LOW
                    && threshold != null
                    && FinancialSummaryCalculator.monthlyEquivalent(subscription)
                    .compareTo(threshold) >= 0) {
                candidates.add(subscription);
            }
        }
        candidates.sort((left, right) -> {
            int amountOrder = FinancialSummaryCalculator.monthlyEquivalent(right)
                    .compareTo(FinancialSummaryCalculator.monthlyEquivalent(left));
            return amountOrder != 0 ? amountOrder : Long.compare(left.getId(), right.getId());
        });

        for (Subscription subscription : candidates) {
            BigDecimal monthlyAmount = FinancialSummaryCalculator.monthlyEquivalent(subscription);
            BigDecimal threshold = config.getMonthlyHighCostThresholdsByCurrency()
                    .get(subscription.getCurrency());
            String explanation = "Subscription #" + subscription.getId() + " reports low usage; its monthly "
                    + monthlyAmount.toPlainString() + " " + subscription.getCurrency()
                    + " equivalent meets the configured high-cost threshold of "
                    + threshold.toPlainString() + " " + subscription.getCurrency() + ".";
            Map<Long, LocalDate> renewalDates = new HashMap<>();
            renewalDates.put(subscription.getId(), subscription.getRenewalDate());
            flags.add(new RiskFlag(
                    RiskFlagType.LOW_USAGE_HIGH_COST,
                    "Low usage and high cost",
                    explanation,
                    new RiskEvidence(
                            renewalDates,
                            null,
                            null,
                            subscription.getCurrency(),
                            monthlyAmount,
                            threshold)));
            if (flags.size() == maximumFlags) {
                return;
            }
        }
    }

    private static void addPossibleDuplicateFlags(
            List<RiskFlag> flags,
            int maximumFlags,
            List<Subscription> activeSubscriptions) {
        Map<DuplicateGroupKey, List<Subscription>> groups = new TreeMap<>();
        for (Subscription subscription : activeSubscriptions) {
            String normalizedCategory = subscription.getCategory().trim().toLowerCase(Locale.ROOT);
            DuplicateGroupKey key = new DuplicateGroupKey(subscription.getCurrency(), normalizedCategory);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(subscription);
        }

        for (Map.Entry<DuplicateGroupKey, List<Subscription>> entry : groups.entrySet()) {
            List<Subscription> group = entry.getValue();
            if (group.size() < 2) {
                continue;
            }
            group.sort(Comparator.comparingLong(Subscription::getId));
            for (int firstIndex = 0; firstIndex < group.size() - 1; firstIndex++) {
                Subscription first = group.get(firstIndex);
                for (int secondIndex = firstIndex + 1; secondIndex < group.size(); secondIndex++) {
                    Subscription second = group.get(secondIndex);
                    String explanation = "Active subscriptions #" + first.getId() + " and #"
                            + second.getId() + " share category \"" + first.getCategory() + "\" in "
                            + first.getCurrency() + ". This may be intentional; review both records "
                            + "before treating them as duplicates.";
                    Map<Long, LocalDate> renewalDates = new HashMap<>();
                    renewalDates.put(first.getId(), first.getRenewalDate());
                    renewalDates.put(second.getId(), second.getRenewalDate());
                    flags.add(new RiskFlag(
                            RiskFlagType.POSSIBLE_DUPLICATE_CATEGORY,
                            "Shared subscription category",
                            explanation,
                            new RiskEvidence(
                                    renewalDates,
                                    null,
                                    null,
                                    first.getCurrency(),
                                    null,
                                    null)));
                    if (flags.size() == maximumFlags) {
                        return;
                    }
                }
            }
        }
    }

    private static final class DuplicateGroupKey implements Comparable<DuplicateGroupKey> {
        private final String currency;
        private final String normalizedCategory;

        private DuplicateGroupKey(String currency, String normalizedCategory) {
            this.currency = currency;
            this.normalizedCategory = normalizedCategory;
        }

        @Override
        public int compareTo(DuplicateGroupKey other) {
            int currencyOrder = currency.compareTo(other.currency);
            return currencyOrder != 0 ? currencyOrder : normalizedCategory.compareTo(other.normalizedCategory);
        }
    }
}
