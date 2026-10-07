package com.example.agentcostcontrol.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;

/** An application subscription row returned by the protected Supabase API. */
public final class Subscription {
    private final long id;
    private final long userId;
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
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    public Subscription(long id, long userId, Long providerId, String name, String planName,
                        String category, BigDecimal price, String currency, BillingCycle billingCycle,
                        SubscriptionStatus status, UsageLevel usageLevel,
                        SubscriptionImportance importance, boolean autoRenew, LocalDate startDate,
                        LocalDate renewalDate, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        if (id <= 0 || userId <= 0) throw new IllegalArgumentException("Subscription IDs must be positive");
        if (providerId != null && providerId <= 0) throw new IllegalArgumentException("providerId must be positive");
        this.id = id;
        this.userId = userId;
        this.providerId = providerId;
        this.name = Objects.requireNonNull(name, "name");
        this.planName = planName;
        this.category = Objects.requireNonNull(category, "category");
        this.price = requireNonNegative(price, "price");
        this.currency = requireCurrency(currency);
        this.billingCycle = Objects.requireNonNull(billingCycle, "billingCycle");
        this.status = Objects.requireNonNull(status, "status");
        this.usageLevel = usageLevel;
        this.importance = Objects.requireNonNull(importance, "importance");
        this.autoRenew = autoRenew;
        this.startDate = Objects.requireNonNull(startDate, "startDate");
        this.renewalDate = Objects.requireNonNull(renewalDate, "renewalDate");
        if (renewalDate.isBefore(startDate)) throw new IllegalArgumentException("renewalDate precedes startDate");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() < 0) throw new IllegalArgumentException(name + " must be non-negative");
        return value;
    }

    private static String requireCurrency(String value) {
        Objects.requireNonNull(value, "currency");
        if (!value.matches("[A-Z]{3}")) throw new IllegalArgumentException("currency must be a three-letter uppercase code");
        return value;
    }

    public long getId() { return id; }
    public long getUserId() { return userId; }
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
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
