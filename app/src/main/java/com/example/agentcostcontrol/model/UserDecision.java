package com.example.agentcostcontrol.model;

import java.time.OffsetDateTime;
import java.util.Objects;

public final class UserDecision {
    private final long id;
    private final long userId;
    private final long recommendationId;
    private final DecisionStatus decision;
    private final OffsetDateTime decisionAt;
    private final String title;

    public UserDecision(long id, long userId, long recommendationId, DecisionStatus decision,
                        OffsetDateTime decisionAt, String title) {
        if (id <= 0 || userId <= 0 || recommendationId <= 0) {
            throw new IllegalArgumentException("Decision IDs must be positive");
        }
        this.id = id;
        this.userId = userId;
        this.recommendationId = recommendationId;
        this.decision = Objects.requireNonNull(decision, "decision");
        this.decisionAt = Objects.requireNonNull(decisionAt, "decisionAt");
        this.title = Objects.requireNonNull(title, "title");
    }

    public long getId() { return id; }
    public long getUserId() { return userId; }
    public long getRecommendationId() { return recommendationId; }
    public DecisionStatus getDecision() { return decision; }
    public OffsetDateTime getDecisionAt() { return decisionAt; }
    public String getTitle() { return title; }
}
