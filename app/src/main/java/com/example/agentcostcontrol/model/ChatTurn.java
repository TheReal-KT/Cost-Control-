package com.example.agentcostcontrol.model;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** A persisted user question paired with the assistant answer produced for it. */
public final class ChatTurn {
    private final String conversationId;
    private final String id;
    private final String question;
    private final String answer;
    private final boolean complete;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    public ChatTurn(String conversationId, String id, String question, String answer, boolean complete,
                    OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.conversationId = canonicalUuid(conversationId, "conversationId");
        this.id = canonicalUuid(id, "id");
        this.question = requireBoundedText(question, "question", 4000, false);
        this.answer = requireBoundedText(answer, "answer", 16000, true);
        this.complete = complete;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    private static String canonicalUuid(String value, String field) {
        Objects.requireNonNull(value, field);
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equalsIgnoreCase(value)) throw new IllegalArgumentException("Non-canonical UUID");
            return canonical;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a UUID", exception);
        }
    }

    private static String requireBoundedText(String value, String field, int maximumLength, boolean allowEmpty) {
        Objects.requireNonNull(value, field);
        if (value.length() > maximumLength || (!allowEmpty && value.trim().isEmpty())) {
            throw new IllegalArgumentException(field + " is outside the allowed length");
        }
        return value;
    }

    public String getConversationId() { return conversationId; }
    public String getId() { return id; }
    public String getQuestion() { return question; }
    public String getAnswer() { return answer; }
    public boolean isComplete() { return complete; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
