package com.example.agentcostcontrol.data;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OwnedRestQueryTest {
    @Test
    public void listAlwaysFiltersByProfileAndUsesStablePagination() {
        String path = OwnedRestQuery.ownedList("subscriptions", "subscription_id::text", 42,
                "renewal_date.asc,subscription_id.asc", 25, 50);

        assertEquals("/rest/v1/subscriptions?select=subscription_id::text&user_id=eq.42"
                + "&order=renewal_date.asc,subscription_id.asc&limit=25&offset=50", path);
    }

    @Test
    public void mutationPathIncludesBothOwnerAndRecordId() {
        String path = OwnedRestQuery.ownedRecord("reminders", "reminder_id::text", 987,
                "reminder_id", 1234);

        assertTrue(path.contains("user_id=eq.987"));
        assertTrue(path.contains("reminder_id=eq.1234"));
    }

    @Test
    public void rejectsInvalidOwnersAndColumnNames() {
        assertInvalid(() -> OwnedRestQuery.ownedRows("subscriptions", "*", 0));
        assertInvalid(() -> OwnedRestQuery.ownedRecord("subscriptions", "*", 42, "user_id;drop", 1));
    }

    private static void assertInvalid(Runnable action) {
        try {
            action.run();
            fail("Expected invalid owned query input to be rejected");
        } catch (IllegalArgumentException expected) {
            // Expected: all public repository paths use a validated app user ID and fixed ID-column names.
        }
    }
}
