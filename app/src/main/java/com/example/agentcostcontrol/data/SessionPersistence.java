package com.example.agentcostcontrol.data;

import com.example.agentcostcontrol.model.Session;

interface SessionPersistence {
    Session read() throws SessionPersistenceException;
    void write(Session session) throws SessionPersistenceException;
    void clear() throws SessionPersistenceException;
}

final class SessionPersistenceException extends Exception {
    SessionPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    SessionPersistenceException(String message) {
        super(message);
    }
}
