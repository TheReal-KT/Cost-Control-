package com.example.agentcostcontrol.domain;

import java.util.Objects;

/** Immutable local check result. It is not a persisted or backend-generated recommendation. */
public final class RiskFlag {
    private final RiskFlagType type;
    private final String title;
    private final String explanation;
    private final RiskEvidence evidence;

    RiskFlag(RiskFlagType type, String title, String explanation, RiskEvidence evidence) {
        this.type = Objects.requireNonNull(type, "type");
        this.title = Objects.requireNonNull(title, "title");
        this.explanation = Objects.requireNonNull(explanation, "explanation");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    public RiskFlagType getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public String getExplanation() {
        return explanation;
    }

    public RiskEvidence getEvidence() {
        return evidence;
    }
}
