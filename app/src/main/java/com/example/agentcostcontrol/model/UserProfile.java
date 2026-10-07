package com.example.agentcostcontrol.model;

import java.time.OffsetDateTime;
import java.util.Objects;

/** Safe application profile columns. Supabase's legacy password column is never selected. */
public final class UserProfile {
    private final long userId;
    private final String authUserId;
    private final String firstName;
    private final String lastName;
    private final String email;
    private final String userType;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    public UserProfile(long userId, String authUserId, String firstName, String lastName,
                       String email, String userType, OffsetDateTime createdAt,
                       OffsetDateTime updatedAt) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        this.userId = userId;
        this.authUserId = Objects.requireNonNull(authUserId, "authUserId");
        this.firstName = Objects.requireNonNull(firstName, "firstName");
        this.lastName = Objects.requireNonNull(lastName, "lastName");
        this.email = Objects.requireNonNull(email, "email");
        this.userType = Objects.requireNonNull(userType, "userType");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public long getUserId() { return userId; }
    public String getAuthUserId() { return authUserId; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getEmail() { return email; }
    public String getUserType() { return userType; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
