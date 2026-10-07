package com.example.agentcostcontrol.model;

import java.time.OffsetDateTime;
import java.util.Objects;

public final class ReminderDraft {
    private final long subscriptionId;
    private final String title;
    private final String message;
    private final OffsetDateTime remindAt;
    private final ReminderStatus status;

    private ReminderDraft(Builder builder) {
        if (builder.subscriptionId <= 0) throw new IllegalArgumentException("subscriptionId must be positive");
        subscriptionId = builder.subscriptionId;
        title = cleanRequired(builder.title, "title", 150);
        message = cleanOptional(builder.message, "message", 1000);
        remindAt = Objects.requireNonNull(builder.remindAt, "remindAt");
        status = Objects.requireNonNull(builder.status, "status");
    }

    private static String cleanRequired(String value, String field, int maxLength) {
        Objects.requireNonNull(value, field);
        String clean = value.trim();
        if (clean.isEmpty() || clean.length() > maxLength) throw new IllegalArgumentException("Invalid " + field);
        return clean;
    }

    private static String cleanOptional(String value, String field, int maxLength) {
        if (value == null) return null;
        String clean = value.trim();
        if (clean.isEmpty()) return null;
        if (clean.length() > maxLength) throw new IllegalArgumentException("Invalid " + field);
        return clean;
    }

    public static Builder builder() { return new Builder(); }
    public long getSubscriptionId() { return subscriptionId; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public OffsetDateTime getRemindAt() { return remindAt; }
    public ReminderStatus getStatus() { return status; }

    public static final class Builder {
        private long subscriptionId;
        private String title;
        private String message;
        private OffsetDateTime remindAt;
        private ReminderStatus status = ReminderStatus.PENDING;
        public Builder setSubscriptionId(long value) { subscriptionId = value; return this; }
        public Builder setTitle(String value) { title = value; return this; }
        public Builder setMessage(String value) { message = value; return this; }
        public Builder setRemindAt(OffsetDateTime value) { remindAt = value; return this; }
        public Builder setStatus(ReminderStatus value) { status = value; return this; }
        public ReminderDraft build() { return new ReminderDraft(this); }
    }
}
