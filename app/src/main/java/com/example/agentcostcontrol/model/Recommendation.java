package com.example.agentcostcontrol.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

/** Read-only recommendation created by a trusted backend process. */
public final class Recommendation {
    private final long id;
    private final long userId;
    private final long subscriptionId;
    private final String title;
    private final String reason;
    private final RecommendationAction action;
    private final BigDecimal confidenceScore;
    private final String evidenceJson;
    private final String modelName;
    private final String modelVersion;
    private final BigDecimal potentialMonthlySaving;
    private final BigDecimal potentialAnnualSaving;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime expiresAt;

    public Recommendation(long id, long userId, long subscriptionId, String title, String reason,
                          RecommendationAction action, BigDecimal confidenceScore, String evidenceJson,
                          String modelName, String modelVersion, BigDecimal potentialMonthlySaving,
                          BigDecimal potentialAnnualSaving, OffsetDateTime createdAt,
                          OffsetDateTime expiresAt) {
        if (id <= 0 || userId <= 0 || subscriptionId <= 0) {
            throw new IllegalArgumentException("Recommendation IDs must be positive");
        }
        this.id = id;
        this.userId = userId;
        this.subscriptionId = subscriptionId;
        this.title = Objects.requireNonNull(title, "title");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.action = Objects.requireNonNull(action, "action");
        this.confidenceScore = Objects.requireNonNull(confidenceScore, "confidenceScore");
        if (confidenceScore.signum() < 0 || confidenceScore.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("confidenceScore must be between zero and one");
        }
        this.evidenceJson = evidenceJson;
        this.modelName = modelName;
        this.modelVersion = modelVersion;
        this.potentialMonthlySaving = requireNonNegative(potentialMonthlySaving, "potentialMonthlySaving");
        this.potentialAnnualSaving = requireNonNegative(potentialAnnualSaving, "potentialAnnualSaving");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = expiresAt;
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0) throw new IllegalArgumentException(field + " must be non-negative");
        return value;
    }

    public long getId() { return id; }
    public long getUserId() { return userId; }
    public long getSubscriptionId() { return subscriptionId; }
    public String getTitle() { return title; }
    public String getReason() { return reason; }
    public RecommendationAction getAction() { return action; }
    public BigDecimal getConfidenceScore() { return confidenceScore; }
    public String getEvidenceJson() { return evidenceJson; }
    public String getModelName() { return modelName; }
    public String getModelVersion() { return modelVersion; }
    public BigDecimal getPotentialMonthlySaving() { return potentialMonthlySaving; }
    public BigDecimal getPotentialAnnualSaving() { return potentialAnnualSaving; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
}
