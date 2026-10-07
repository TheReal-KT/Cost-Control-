package com.example.agentcostcontrol.data;

/** Builds table paths that always include the authenticated profile's application ID. */
final class OwnedRestQuery {
    private OwnedRestQuery() { }

    static String ownedRows(String table, String selection, long userId) {
        requireUserId(userId);
        return "/rest/v1/" + table + "?select=" + selection + "&user_id=eq." + userId;
    }

    static String ownedList(String table, String selection, long userId, String order, int limit, int offset) {
        if (order == null || order.trim().isEmpty()) throw new IllegalArgumentException("order is required");
        return ownedRows(table, selection, userId) + "&order=" + order + "&limit=" + limit + "&offset=" + offset;
    }

    static String ownedRecord(String table, String selection, long userId, String idColumn, long id) {
        requireUserId(userId);
        if (id <= 0) throw new IllegalArgumentException("id must be positive");
        if (idColumn == null || !idColumn.matches("[a-z_]+")) throw new IllegalArgumentException("Invalid ID column");
        return ownedRows(table, selection, userId) + "&" + idColumn + "=eq." + id;
    }

    private static void requireUserId(long userId) {
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
    }
}
