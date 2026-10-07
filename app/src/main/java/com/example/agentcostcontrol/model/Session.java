package com.example.agentcostcontrol.model;

import java.time.OffsetDateTime;
import java.util.Objects;

/** Supabase Auth credentials. Never include token values in UI text or diagnostics. */
public final class Session {
    private final String accessToken;
    private final String refreshToken;
    private final String tokenType;
    private final String authUserId;
    private final String email;
    private final OffsetDateTime expiresAt;

    public Session(String accessToken, String refreshToken, String tokenType, String authUserId,
                   String email, OffsetDateTime expiresAt) {
        this.accessToken = requireText(accessToken, "accessToken");
        this.refreshToken = requireText(refreshToken, "refreshToken");
        this.tokenType = requireText(tokenType, "tokenType");
        this.authUserId = requireText(authUserId, "authUserId");
        this.email = email;
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " is empty");
        return value;
    }

    public String getAccessToken() { return accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public String getTokenType() { return tokenType; }
    public String getAuthUserId() { return authUserId; }
    public String getEmail() { return email; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }

    @Override
    public String toString() {
        return "Session{expiresAt=" + expiresAt + "}";
    }
}
