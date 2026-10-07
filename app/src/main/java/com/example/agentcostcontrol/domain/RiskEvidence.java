package com.example.agentcostcontrol.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Read-only, typed evidence for a local risk flag. */
public final class RiskEvidence {
    private final List<Long> subscriptionIds;
    private final Map<Long, LocalDate> renewalDatesBySubscriptionId;
    private final Long budgetId;
    private final YearMonth budgetPeriod;
    private final String currency;
    private final BigDecimal monthlyAmount;
    private final BigDecimal thresholdAmount;

    RiskEvidence(
            Map<Long, LocalDate> renewalDatesBySubscriptionId,
            Long budgetId,
            YearMonth budgetPeriod,
            String currency,
            BigDecimal monthlyAmount,
            BigDecimal thresholdAmount) {
        Map<Long, LocalDate> sortedRenewalDates = new TreeMap<>(renewalDatesBySubscriptionId);
        this.renewalDatesBySubscriptionId = Collections.unmodifiableMap(sortedRenewalDates);
        this.subscriptionIds = Collections.unmodifiableList(
                new ArrayList<>(sortedRenewalDates.keySet()));
        this.budgetId = budgetId;
        this.budgetPeriod = budgetPeriod;
        this.currency = currency;
        this.monthlyAmount = monthlyAmount;
        this.thresholdAmount = thresholdAmount;
    }

    public List<Long> getSubscriptionIds() {
        return subscriptionIds;
    }

    public Map<Long, LocalDate> getRenewalDatesBySubscriptionId() {
        return renewalDatesBySubscriptionId;
    }

    public Long getBudgetId() {
        return budgetId;
    }

    public YearMonth getBudgetPeriod() {
        return budgetPeriod;
    }

    public String getCurrency() {
        return currency;
    }

    /** Monthly spend or monthly-equivalent subscription price, depending on the flag type. */
    public BigDecimal getMonthlyAmount() {
        return monthlyAmount;
    }

    /** Budget limit or explicitly configured high-cost threshold, depending on flag type. */
    public BigDecimal getThresholdAmount() {
        return thresholdAmount;
    }
}
