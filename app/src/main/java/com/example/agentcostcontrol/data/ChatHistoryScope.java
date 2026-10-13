package com.example.agentcostcontrol.data;

import java.util.Objects;

/** Opaque account identity captured before chat history work is queued. */
public final class ChatHistoryScope {
    private final String authUserId;

    ChatHistoryScope(String authUserId) {
        this.authUserId = Objects.requireNonNull(authUserId, "authUserId");
    }

    boolean matches(String candidateAuthUserId) {
        return authUserId.equals(candidateAuthUserId);
    }
}
