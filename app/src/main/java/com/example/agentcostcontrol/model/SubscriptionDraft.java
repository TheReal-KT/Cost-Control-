package com.example.agentcostcontrol.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** Editable subscription fields. Ownership and database-generated fields are deliberately absent. */
public final class SubscriptionDraft {
    private final Long providerId;
    private final String name;
    private final String planName;
    private final String category;
    private final BigDecimal price;
    private final String currency;
    private final BillingCycle billingCycle;
    private final SubscriptionStatus status;
    private final UsageLevel usageLevel;
    private final SubscriptionImportance importance;
    private final boolean autoRenew;
    private final LocalDate startDate;
    private final LocalDate renewalDate;

    private SubscriptionDraft(Builder builder) {
        providerId = builder.providerId;
        if (providerId != null && providerId <= 0) throw new IllegalArgumentException("providerId must be positive");
        name = requiredText(builder.name, "name", 150);
        planName = optionalText(builder.planName, "planName", 100);
        category = requiredText(builder.category, "category", 50);
        price = requireMoney(builder.price, "price");
        currency = requireCurrency(builder.currency);
        billingCycle = Objects.requireNonNull(builder.billingCycle, "billingCycle");
        status = Objects.requireNonNull(builder.status, "status");
        usageLevel = builder.usageLevel;
        importance = Objects.requireNonNull(builder.importance, "importance");
        autoRenew = builder.autoRenew;
        startDate = Objects.requireNonNull(builder.startDate, "startDate");
        renewalDate = Objects.requireNonNull(builder.renewalDate, "renewalDate");
        if (renewalDate.isBefore(startDate)) throw new IllegalArgumentException("renewalDate precedes startDate");
    }

    private static String requiredText(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        String clean = value.trim();
        if (clean.isEmpty() || clean.length() > maxLength) throw new IllegalArgumentException("Invalid " + field);
        return clean;
    }

    private static String optionalText(String value, String field, int maxLength) {
        if (value == null) return null;
        String clean = value.trim();
        if (clean.isEmpty()) return null;
        if (clean.length() > maxLength) throw new IllegalArgumentException("Invalid " + field);
        return clean;
    }

    private static BigDecimal requireMoney(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0 || value.scale() > 2 || value.compareTo(new BigDecimal("9999999999.99")) > 0) {
            throw new IllegalArgumentException(field + " must be a non-negative amount with at most two decimal places");
        }
        return value;
    }

    private static String requireCurrency(String value) {
        Objects.requireNonNull(value, "currency");
        if (!value.matches("[A-Z]{3}")) throw new IllegalArgumentException("currency must be a three-letter uppercase code");
        return value;
    }

    public static Builder builder() { return new Builder(); }
    public Long getProviderId() { return providerId; }
    public String getName() { return name; }
    public String getPlanName() { return planName; }
    public String getCategory() { return category; }
    public BigDecimal getPrice() { return price; }
    public String getCurrency() { return currency; }
    public BillingCycle getBillingCycle() { return billingCycle; }
    public SubscriptionStatus getStatus() { return status; }
    public UsageLevel getUsageLevel() { return usageLevel; }
    public SubscriptionImportance getImportance() { return importance; }
    public boolean isAutoRenew() { return autoRenew; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getRenewalDate() { return renewalDate; }

    public static final class Builder {
        private Long providerId;
        private String name;
        private String planName;
        private String category = "other";
        private BigDecimal price;
        private String currency = "ZAR";
        private BillingCycle billingCycle = BillingCycle.MONTHLY;
        private SubscriptionStatus status = SubscriptionStatus.ACTIVE;
        private UsageLevel usageLevel;
        private SubscriptionImportance importance = SubscriptionImportance.MEDIUM;
        private boolean autoRenew = true;
        private LocalDate startDate;
        private LocalDate renewalDate;

        public Builder setProviderId(Long value) { providerId = value; return this; }
        public Builder setName(String value) { name = value; return this; }
        public Builder setPlanName(String value) { planName = value; return this; }
        public Builder setCategory(String value) { category = value; return this; }
        public Builder setPrice(BigDecimal value) { price = value; return this; }
        public Builder setCurrency(String value) { currency = value; return this; }
        public Builder setBillingCycle(BillingCycle value) { billingCycle = value; return this; }
        public Builder setStatus(SubscriptionStatus value) { status = value; return this; }
        public Builder setUsageLevel(UsageLevel value) { usageLevel = value; return this; }
        public Builder setImportance(SubscriptionImportance value) { importance = value; return this; }
        public Builder setAutoRenew(boolean value) { autoRenew = value; return this; }
        public Builder setStartDate(LocalDate value) { startDate = value; return this; }
        public Builder setRenewalDate(LocalDate value) { renewalDate = value; return this; }
        public SubscriptionDraft build() { return new SubscriptionDraft(this); }
    }
}
