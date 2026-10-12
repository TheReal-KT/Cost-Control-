package com.example.agentcostcontrol.chat;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONException;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

@RunWith(AndroidJUnit4.class)
public final class ChatStreamReaderTest {
    private static final String START = "event: start\ndata: {\"conversation_id\":\"sample_123\"}\n\n";
    private static final String DELTA = "event: delta\ndata: {\"text\":\"Review sample plans.\"}\n\n";
    private static final String DONE = "event: done\ndata: {\"conversation_id\":\"sample_123\"}\n\n";

    private static ByteArrayInputStream input(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test public void deliversUnicodeDeltasBeforeExplicitCompletion() throws Exception {
        List<String> deltas = new ArrayList<>();
        byte[] bytes = (": keepalive\r\n\r\n" + START
                + "event: delta\ndata: {\"text\":\"Café ☕\",\ndata: \"unused\":true}\n\n"
                + DELTA + DONE).getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream fragmented = new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                return super.read(buffer, offset, Math.min(length, 1));
            }
        };
        ChatClient.Reply reply = ChatStreamReader.read(fragmented, deltas::add);
        assertEquals(List.of("Café ☕", "Review sample plans."), deltas);
        assertEquals("Café ☕Review sample plans.", reply.answer);
        assertEquals("sample_123", reply.conversationId);
    }

    @Test public void partialTextDoesNotTurnEofIntoSuccess() {
        List<String> deltas = new ArrayList<>();
        assertThrows(IOException.class, () -> ChatStreamReader.read(input(START + DELTA), deltas::add));
        assertEquals(List.of("Review sample plans."), deltas);
    }

    @Test public void failureAfterPartialTextRetainsStatus() {
        ChatClient.Failure failure = assertThrows(ChatClient.Failure.class, () ->
                ChatStreamReader.read(input(START + DELTA
                        + "event: error\ndata: {\"status\":429,\"detail\":\"Limited\"}\n\n"), text -> { }));
        assertEquals(429, failure.httpStatus);
    }

    @Test public void rejectsInvalidEventOrderAndCompletion() {
        for (String stream : List.of(DELTA + DONE, START + START, START + DONE,
                START + DELTA + DONE.replace("sample_123", "another_user"),
                START + "event: delta\ndata: {\"text\":42}\n\n",
                "event: internal_reasoning\ndata: {}\n\n")) {
            assertThrows(JSONException.class, () -> ChatStreamReader.read(input(stream), text -> { }));
        }
    }

    @Test public void rejectsOversizedStreamAndAnswer() {
        assertThrows(IOException.class, () -> ChatStreamReader.read(input(":" + "x".repeat(131073)), text -> { }));
        assertThrows(JSONException.class, () -> ChatStreamReader.read(input(START
                + "event: delta\ndata: {\"text\":\"" + "x".repeat(16001) + "\"}\n\n"), text -> { }));
    }

    @Test public void unterminatedCompletionAndInvalidUtf8FailClosed() {
        assertThrows(IOException.class, () -> ChatStreamReader.read(input(START + DELTA + DONE.stripTrailing()), text -> { }));
        assertThrows(IOException.class, () -> ChatStreamReader.read(new ByteArrayInputStream(
                new byte[]{(byte) 0xc3, 0x28}), text -> { }));
    }
}
