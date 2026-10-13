package com.example.agentcostcontrol.data;

import com.example.agentcostcontrol.model.ChatConversation;
import com.example.agentcostcontrol.model.ChatTurn;

import java.util.List;

/** User-scoped persistence contract for native chat history. Calls run on a worker thread. */
public interface ChatHistoryRepository {
    /** Capture before scheduling worker work so a queued request cannot adopt a later account. */
    ChatHistoryScope captureScope() throws ApiException;

    List<ChatConversation> listConversations(ChatHistoryScope scope, int offset, int limit) throws ApiException;

    /** Returns newest turns first; later offsets contain older turns. */
    List<ChatTurn> listTurns(ChatHistoryScope scope, String conversationId, int offset, int limit)
            throws ApiException;

    /**
     * Saves using stable client-generated UUIDs and returns the canonical stored turn.
     * An already-completed server answer can be preserved against an incomplete retry.
     */
    ChatTurn saveTurn(ChatHistoryScope scope, String conversationId, String turnId, String title,
                      String question, String answer, boolean complete) throws ApiException;

    void deleteConversation(ChatHistoryScope scope, String conversationId) throws ApiException;
}
