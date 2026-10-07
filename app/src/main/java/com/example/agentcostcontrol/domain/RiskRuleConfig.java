package com.example.agentcostcontrol.domain;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Explicit configuration for deterministic local risk checks. */
public final class RiskRuleConfig {
    public static final int MAXIMUM_ALLOWED_FLAGS = 100;

    private final int renewalWindowDays;
    private final Map<String, BigDecimal> monthlyHighCostThresholdsByCurrency;
    private final int maximumFlags;

    /**
     * @param renewalWindowDays inclusive number of days from today to check for renewals (0-365)
     * @param monthlyHighCostThresholdsByCurrency explicit monthly thresholds, keyed by uppercase
     *     three-letter currency codes; currencies without a threshold are not checked
     * @param maximumFlags maximum returned flags, from 0 to {@value #MAXIMUM_ALLOWED_FLAGS}
     */
    public RiskRuleConfig(
            int renewalWindowDays,
            Map<String, BigDecimal> monthlyHighCostThresholdsByCurrency,
            int maximumFlags) {
        if (renewalWindowDays < 0 || renewalWindowDays > 365) {
            throw new IllegalArgumentException("renewalWindowDays must be between 0 and 365");
        }
        if (maximumFlags < 0 || maximumFlags > MAXIMUM_ALLOWED_FLAGS) {
            throw new IllegalArgumentException("maximumFlags must be between 0 and 100");
        }
        Objects.requireNonNull(monthlyHighCostThresholdsByCurrency,
                "monthlyHighCostThresholdsByCurrency");

        Map<String, BigDecimal> thresholds = new TreeMap<>();
        for (Map.Entry<String, BigDecimal> entry : monthlyHighCostThresholdsByCurrency.entrySet()) {
            String currency = Objects.requireNonNull(entry.getKey(), "threshold currency");
            BigDecimal amount = Objects.requireNonNull(entry.getValue(), "threshold amount");
            if (!currency.matches("[A-Z]{3}")) {
                throw new IllegalArgumentException("threshold currencies must be uppercase 3-letter codes");
            }
            if (amount.signum() <= 0) {
                throw new IllegalArgumentException("high-cost thresholds must be greater than zero");
            }
            thresholds.put(currency, amount);
        }
        this.renewalWindowDays = renewalWindowDays;
        this.monthlyHighCostThresholdsByCurrency = Collections.unmodifiableMap(thresholds);
        this.maximumFlags = maximumFlags;
    }

    public int getRenewalWindowDays() {
        return renewalWindowDays;
    }

    public Map<String, BigDecimal> getMonthlyHighCostThresholdsByCurrency() {
        return monthlyHighCostThresholdsByCurrency;
    }

    public int getMaximumFlags() {
        return maximumFlags;
    }
}
