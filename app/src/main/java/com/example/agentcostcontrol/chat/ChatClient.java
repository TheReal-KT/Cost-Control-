package com.example.agentcostcontrol.chat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Calls the authenticated application backend, never the model provider directly. */
public final class ChatClient {
    public static final String MODEL_ID = "nvidia/nemotron-3.5-lightning:free";
    private volatile HttpURLConnection activeConnection;

    public static final class Reply {
        public final String answer;
        public final String conversationId;

        private Reply(String answer, String conversationId) {
            this.answer = answer;
            this.conversationId = conversationId;
        }
    }

    public static final class Failure extends Exception {
        public final int httpStatus;

        public Failure(int httpStatus) {
            super("Chat request failed (" + httpStatus + ")");
            this.httpStatus = httpStatus;
        }
    }

    public Reply ask(String endpoint, String accessToken, String prompt, String conversationId)
            throws IOException, JSONException, Failure {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
        URL url = new URL(endpoint);
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null) {
            throw new IOException("HTTPS endpoint required");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        activeConnection = connection;
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(45_000);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setDoOutput(true);
            JSONObject request = new JSONObject().put("prompt", prompt).put("model", MODEL_ID)
                    .put("data_mode", "synthetic");
            if (conversationId != null) request.put("conversation_id", conversationId);
            byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new Failure(status);
            String body;
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 131_072) throw new IOException("Response too large");
                    output.write(buffer, 0, count);
                }
                body = output.toString(StandardCharsets.UTF_8.name());
            }
            JSONObject response = new JSONObject(body);
            Object rawAnswer = response.opt("answer");
            Object rawId = response.opt("conversation_id");
            if (!(rawAnswer instanceof String) || !(rawId instanceof String)) {
                throw new JSONException("Invalid reply contract");
            }
            String answer = ((String) rawAnswer).trim();
            String nextId = (String) rawId;
            if (answer.isEmpty() || answer.length() > 16_000
                    || !nextId.matches("[A-Za-z0-9_-]{1,128}")) {
                throw new JSONException("Invalid reply contract");
            }
            return new Reply(answer, nextId);
        } finally {
            connection.disconnect();
            if (activeConnection == connection) activeConnection = null;
        }
    }

    public void cancel() {
        HttpURLConnection connection = activeConnection;
        if (connection != null) connection.disconnect();
    }
}
