package com.example.agentcostcontrol.model;

/** Bounded Gmail scan result returned by the authenticated application backend. */
public final class SubscriptionSyncResult {
    private final int imported;
    private final int skipped;
    private final int scanned;
    private final boolean hasMore;
    private final String message;

    public SubscriptionSyncResult(int imported, int skipped, int scanned, boolean hasMore, String message) {
        if (imported < 0 || skipped < 0 || scanned < 0) {
            throw new IllegalArgumentException("Sync counts cannot be negative");
        }
        this.imported = imported;
        this.skipped = skipped;
        this.scanned = scanned;
        this.hasMore = hasMore;
        this.message = message == null ? "" : message;
    }

    public int getImported() { return imported; }
    public int getSkipped() { return skipped; }
    public int getScanned() { return scanned; }
    public boolean hasMore() { return hasMore; }
    public String getMessage() { return message; }
}
