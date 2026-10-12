package com.example.agentcostcontrol.chat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Parses the application's SSE contract; EOF and partial text never imply success. */
public final class ChatStreamReader {
    private ChatStreamReader() { }

    public static ChatClient.Reply read(InputStream input, ChatClient.StreamListener listener)
            throws IOException, JSONException, ChatClient.Failure {
        BufferedReader reader = new BufferedReader(new InputStreamReader(new BoundedInput(input),
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)));
        String event = "";
        StringBuilder data = new StringBuilder();
        StringBuilder answer = new StringBuilder();
        String conversation = null;
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() == 0) { event = ""; continue; }
                JSONObject value = new JSONObject(data.toString());
                switch (event) {
                    case "start":
                        if (conversation != null) throw new JSONException("Duplicate start");
                        Object id = value.opt("conversation_id");
                        if (!(id instanceof String) || !((String) id).matches("[A-Za-z0-9_-]{1,128}")) {
                            throw new JSONException("Invalid conversation");
                        }
                        conversation = (String) id;
                        break;
                    case "delta":
                        Object text = value.opt("text");
                        if (conversation == null || !(text instanceof String)
                                || answer.length() + ((String) text).length() > 16_000) {
                            throw new JSONException("Invalid delta");
                        }
                        answer.append((String) text);
                        listener.onDelta((String) text);
                        break;
                    case "done":
                        if (conversation == null || !conversation.equals(value.opt("conversation_id"))
                                || answer.toString().trim().isEmpty()) {
                            throw new JSONException("Invalid completion");
                        }
                        return new ChatClient.Reply(answer.toString().trim(), conversation);
                    case "error":
                        Object status = value.opt("status");
                        if (!(status instanceof Integer) || (Integer) status < 400 || (Integer) status > 599) {
                            throw new JSONException("Invalid stream failure");
                        }
                        throw new ChatClient.Failure((Integer) status);
                    default:
                        throw new JSONException("Unknown stream event");
                }
                event = "";
                data.setLength(0);
            } else if (line.startsWith("event:")) {
                event = field(line.substring(6));
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) data.append('\n');
                data.append(field(line.substring(5)));
            }
        }
        throw new IOException("Incomplete answer");
    }

    private static String field(String value) {
        return value.startsWith(" ") ? value.substring(1) : value;
    }

    private static final class BoundedInput extends FilterInputStream {
        private int remaining = 131_072;

        BoundedInput(InputStream input) { super(input); }

        private void count(int bytes) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
            if (bytes > 0 && (remaining -= bytes) < 0) throw new IOException("Stream too large");
        }

        @Override public int read() throws IOException {
            int value = in.read();
            count(value == -1 ? 0 : 1);
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int bytes = in.read(buffer, offset, Math.min(length, Math.max(1, remaining + 1)));
            count(bytes);
            return bytes;
        }
    }
}
