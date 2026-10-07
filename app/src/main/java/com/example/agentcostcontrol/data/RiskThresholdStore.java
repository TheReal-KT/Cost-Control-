package com.example.agentcostcontrol.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Stores only explicit local high-cost thresholds, scoped to one authenticated account UUID. */
public final class RiskThresholdStore {
    static final String PREFERENCES_NAME = "local_risk_thresholds";
    private static final String KEY_PREFIX = "monthly_high_cost.";
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.99");

    private final SharedPreferences preferences;
    private final String accountKeyPrefix;

    public RiskThresholdStore(Context context, String authenticatedUserId) {
        Objects.requireNonNull(context, "context");
        this.accountKeyPrefix = accountPrefix(authenticatedUserId);
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** Returns a sorted immutable snapshot; an empty map means no high-cost rule is configured. */
    public synchronized Map<String, BigDecimal> loadMonthlyHighCostThresholds() {
        Map<String, BigDecimal> thresholds = new TreeMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(accountKeyPrefix)) continue;
            String currency = key.substring(accountKeyPrefix.length());
            if (!isCurrency(currency) || !(entry.getValue() instanceof String)) continue;
            try {
                BigDecimal amount = new BigDecimal((String) entry.getValue());
                validateAmount(amount);
                thresholds.put(currency, amount);
            } catch (IllegalArgumentException ignored) {
                // A malformed local value is not a user-configured rule.
            }
        }
        return Collections.unmodifiableMap(thresholds);
    }

    public synchronized void putMonthlyHighCostThreshold(String currency, BigDecimal amount) {
        validateCurrency(currency);
        validateAmount(amount);
        boolean saved = preferences.edit()
                .putString(accountKeyPrefix + currency, amount.toPlainString())
                .commit();
        if (!saved) throw new IllegalStateException("Unable to save local risk threshold");
    }

    public synchronized void removeMonthlyHighCostThreshold(String currency) {
        validateCurrency(currency);
        boolean removed = preferences.edit().remove(accountKeyPrefix + currency).commit();
        if (!removed) throw new IllegalStateException("Unable to remove local risk threshold");
    }

    private static String accountPrefix(String authenticatedUserId) {
        Objects.requireNonNull(authenticatedUserId, "authenticatedUserId");
        try {
            String canonicalId = UUID.fromString(authenticatedUserId).toString();
            if (!canonicalId.equalsIgnoreCase(authenticatedUserId)) {
                throw new IllegalArgumentException("authenticatedUserId must use canonical UUID format");
            }
            return KEY_PREFIX + canonicalId + ".";
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("authenticatedUserId must be a UUID", exception);
        }
    }

    private static void validateCurrency(String currency) {
        if (!isCurrency(currency)) {
            throw new IllegalArgumentException("currency must be an uppercase three-letter code");
        }
    }

    private static boolean isCurrency(String currency) {
        return currency != null && currency.matches("[A-Z]{3}");
    }

    private static void validateAmount(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() <= 0 || amount.scale() > 2 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException(
                    "threshold must be positive, have at most two decimals, and fit numeric(12,2)");
        }
    }
}
