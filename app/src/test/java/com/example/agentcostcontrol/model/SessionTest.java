package com.example.agentcostcontrol.model;

import org.junit.Test;

import java.time.OffsetDateTime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionTest {
    @Test
    public void toStringDoesNotExposeCredentialsOrAccountIdentity() {
        Session session = new Session("access-secret", "refresh-secret", "bearer",
                "auth-user-id", "person@example.com", OffsetDateTime.parse("2026-10-06T12:00:00Z"));

        String display = session.toString();

        assertTrue(display.contains("2026-10-06T12:00Z"));
        assertFalse(display.contains("access-secret"));
        assertFalse(display.contains("refresh-secret"));
        assertFalse(display.contains("auth-user-id"));
        assertFalse(display.contains("person@example.com"));
    }
}
