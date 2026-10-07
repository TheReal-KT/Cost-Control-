package com.example.agentcostcontrol.model;

import java.time.OffsetDateTime;
import java.util.Objects;

public final class Reminder {
    private final long id;
    private final long userId;
    private final long subscriptionId;
    private final String title;
    private final String message;
    private final OffsetDateTime remindAt;
    private final ReminderStatus status;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    public Reminder(long id, long userId, long subscriptionId, String title, String message,
                    OffsetDateTime remindAt, ReminderStatus status, OffsetDateTime createdAt,
                    OffsetDateTime updatedAt) {
        if (id <= 0 || userId <= 0 || subscriptionId <= 0) {
            throw new IllegalArgumentException("Reminder IDs must be positive");
        }
        this.id = id;
        this.userId = userId;
        this.subscriptionId = subscriptionId;
        this.title = Objects.requireNonNull(title, "title");
        this.message = message;
        this.remindAt = Objects.requireNonNull(remindAt, "remindAt");
        this.status = Objects.requireNonNull(status, "status");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public long getId() { return id; }
    public long getUserId() { return userId; }
    public long getSubscriptionId() { return subscriptionId; }
    public String getTitle() { return title; }
    public String getMessage() { return message; }
    public OffsetDateTime getRemindAt() { return remindAt; }
    public ReminderStatus getStatus() { return status; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
