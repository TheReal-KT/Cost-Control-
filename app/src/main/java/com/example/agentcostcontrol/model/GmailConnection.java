package com.example.agentcostcontrol.model;

/** Gmail connection state returned by the authenticated application backend. */
public final class GmailConnection {
    private final boolean configured;
    private final boolean connected;

    public GmailConnection(boolean configured, boolean connected) {
        this.configured = configured;
        this.connected = connected;
    }

    public boolean isConfigured() { return configured; }
    public boolean isConnected() { return connected; }
}
