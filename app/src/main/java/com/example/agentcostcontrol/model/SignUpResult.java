package com.example.agentcostcontrol.model;

/** A confirmation-required result has no session until the user verifies their email and signs in. */
public final class SignUpResult {
    private final Session session;
    private final String authUserId;
    private final String email;

    public SignUpResult(Session session, String authUserId, String email) {
        this.session = session;
        this.authUserId = authUserId;
        this.email = email;
    }

    public Session getSession() { return session; }
    public String getAuthUserId() { return authUserId; }
    public String getEmail() { return email; }
    public boolean isEmailConfirmationRequired() { return session == null; }
}
