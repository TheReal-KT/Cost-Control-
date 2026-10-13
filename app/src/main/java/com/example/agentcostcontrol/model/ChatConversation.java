package com.example.agentcostcontrol.model;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** A signed-in user's saved chat conversation. */
public final class ChatConversation {
    private final String id;
    private final String title;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    public ChatConversation(String id, String title, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.id = canonicalUuid(id, "id");
        this.title = requireBoundedText(title, "title", 120, false);
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

    public String getId() { return id; }
    public String getTitle() { return title; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
