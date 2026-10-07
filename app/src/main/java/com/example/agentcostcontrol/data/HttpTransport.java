package com.example.agentcostcontrol.data;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

interface HttpTransport {
    Response execute(String method, String url, Map<String, String> headers, String body) throws IOException;

    final class Response {
        final int statusCode;
        final String body;

        Response(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    final class UrlConnection implements HttpTransport {
        private static final int CONNECT_TIMEOUT_MILLIS = 15_000;
        private static final int READ_TIMEOUT_MILLIS = 20_000;
        private static final int MAX_RESPONSE_BYTES = 1_048_576;

        @Override
        public Response execute(String method, String url, Map<String, String> headers, String body)
                throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod(method);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
                connection.setReadTimeout(READ_TIMEOUT_MILLIS);
                connection.setUseCaches(false);
                headers.forEach(connection::setRequestProperty);
                if (body != null) {
                    connection.setDoOutput(true);
                    byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                    connection.setFixedLengthStreamingMode(payload.length);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(payload);
                    }
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String responseBody = stream == null ? "" : readResponseBody(stream);
                return new Response(status, responseBody);
            } finally {
                connection.disconnect();
            }
        }

        private static String readResponseBody(InputStream stream) throws IOException {
            try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_RESPONSE_BYTES) throw new IOException("Supabase response exceeds the allowed size");
                    output.write(buffer, 0, count);
                }
                return new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }
}
