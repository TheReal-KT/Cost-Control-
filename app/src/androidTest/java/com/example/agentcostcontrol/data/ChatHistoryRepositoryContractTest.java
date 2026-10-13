package com.example.agentcostcontrol.data;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.agentcostcontrol.model.ChatConversation;
import com.example.agentcostcontrol.model.ChatTurn;
import com.example.agentcostcontrol.model.Session;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Exercises chat history paths and payloads against the repository's real HTTP adapter. */
@RunWith(AndroidJUnit4.class)
public final class ChatHistoryRepositoryContractTest {
    private static final String USER_UUID = "11111111-1111-4111-8111-111111111111";
    private static final String OTHER_USER_UUID = "22222222-2222-4222-8222-222222222222";
    private static final String CONVERSATION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String TURN_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final String PROFILE = "[{\"user_id\":\"42\",\"auth_user_id\":\"" + USER_UUID
            + "\",\"first_name\":\"Sample\",\"last_name\":\"User\",\"email\":\"sample@example.invalid\","
            + "\"user_type\":\"user\",\"created_at\":\"2026-10-01T00:00:00Z\","
            + "\"updated_at\":\"2026-10-01T00:00:00Z\"}]";

    @Test
    public void conversationReadsUseOwnerFilterAndNewestStablePagination() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, PROFILE);
        transport.add(200, "[{\"conversation_id\":\"" + CONVERSATION_ID + "\","
                + "\"title\":\"Review my costs\",\"created_at\":\"2026-10-01T00:00:00Z\","
                + "\"updated_at\":\"2026-10-02T00:00:00Z\"}]");

        SupabaseRepository repository = repository(transport);
        ChatHistoryScope scope = repository.captureScope();
        List<ChatConversation> conversations = repository.listConversations(scope, 20, 10);

        assertEquals(1, conversations.size());
        assertEquals(CONVERSATION_ID, conversations.get(0).getId());
        assertEquals("Review my costs", conversations.get(0).getTitle());
        Request request = transport.requests.get(1);
        assertEquals("GET", request.method);
        assertTrue(request.url.contains("user_id=eq.42"));
        assertTrue(request.url.contains("order=updated_at.desc,conversation_id.desc"));
        assertTrue(request.url.contains("limit=10&offset=20"));
        assertFalse(request.url.contains("select=*"));
    }

    @Test
    public void turnReadsScopeConversationAndReturnNewestFirstPages() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, PROFILE);
        transport.add(200, "[{\"conversation_id\":\"" + CONVERSATION_ID + "\","
                + "\"turn_id\":\"" + TURN_ID + "\",\"question\":\"How can I save?\","
                + "\"answer\":\"Check unused plans.\",\"status\":\"incomplete\","
                + "\"created_at\":\"2026-10-01T00:00:00Z\",\"updated_at\":\"2026-10-01T00:01:00Z\"}]");

        SupabaseRepository repository = repository(transport);
        ChatHistoryScope scope = repository.captureScope();
        List<ChatTurn> turns = repository.listTurns(scope, CONVERSATION_ID, 30, 10);

        assertEquals(1, turns.size());
        assertEquals(TURN_ID, turns.get(0).getId());
        assertEquals(CONVERSATION_ID, turns.get(0).getConversationId());
        assertFalse(turns.get(0).isComplete());
        Request request = transport.requests.get(1);
        assertEquals("GET", request.method);
        assertTrue(request.url.contains("user_id=eq.42"));
        assertTrue(request.url.contains("conversation_id=eq." + CONVERSATION_ID));
        assertTrue(request.url.contains("order=created_at.desc,turn_id.desc"));
        assertTrue(request.url.contains("limit=10&offset=30"));
    }

    @Test
    public void savesUseTransactionalRpcAndStableClientIdsAcrossRetries() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, PROFILE);
        transport.add(200, savedTurn("incomplete", "partial answer"));
        transport.add(200, PROFILE);
        transport.add(200, savedTurn("complete", "final answer"));
        SupabaseRepository repository = repository(transport);
        ChatHistoryScope scope = repository.captureScope();

        ChatTurn partial = repository.saveTurn(scope, CONVERSATION_ID, TURN_ID, "Review my costs",
                "How can I save?", "partial answer", false);
        ChatTurn completed = repository.saveTurn(scope, CONVERSATION_ID, TURN_ID, "Review my costs",
                "How can I save?", "final answer", true);

        assertFalse(partial.isComplete());
        assertTrue(completed.isComplete());
        assertEquals("final answer", completed.getAnswer());
        Request firstWrite = transport.requests.get(1);
        Request retry = transport.requests.get(3);
        assertEquals("POST", firstWrite.method);
        assertTrue(firstWrite.url.endsWith("/rest/v1/rpc/save_chat_turn"));
        assertEquals(firstWrite.url, retry.url);
        JSONObject firstBody = new JSONObject(firstWrite.body);
        JSONObject retryBody = new JSONObject(retry.body);
        assertEquals(CONVERSATION_ID, firstBody.getString("p_conversation_id"));
        assertEquals(TURN_ID, firstBody.getString("p_turn_id"));
        assertEquals(TURN_ID, retryBody.getString("p_turn_id"));
        assertFalse(firstBody.getBoolean("p_complete"));
        assertTrue(retryBody.getBoolean("p_complete"));
        assertEquals("Bearer synthetic-access-token", retry.headers.get("Authorization"));
    }

    @Test
    public void malformedHistoryStatusAndOversizedTitleAreRejectedAsDataFormat() throws Exception {
        FakeTransport statusTransport = new FakeTransport();
        statusTransport.add(200, PROFILE);
        statusTransport.add(200, "[{\"conversation_id\":\"" + CONVERSATION_ID + "\","
                + "\"turn_id\":\"" + TURN_ID + "\",\"question\":\"Question\",\"answer\":\"Answer\","
                + "\"status\":\"pending\",\"created_at\":\"2026-10-01T00:00:00Z\","
                + "\"updated_at\":\"2026-10-01T00:00:00Z\"}]");
        SupabaseRepository statusRepository = repository(statusTransport);
        try {
            statusRepository.listTurns(statusRepository.captureScope(), CONVERSATION_ID, 0, 20);
            fail("Unknown persisted statuses must be rejected");
        } catch (ApiException exception) {
            assertEquals(ApiException.Category.DATA_FORMAT, exception.getCategory());
        }

        FakeTransport titleTransport = new FakeTransport();
        titleTransport.add(200, PROFILE);
        titleTransport.add(200, "[{\"conversation_id\":\"" + CONVERSATION_ID + "\","
                + "\"title\":\"" + repeated('t', 121) + "\","
                + "\"created_at\":\"2026-10-01T00:00:00Z\",\"updated_at\":\"2026-10-01T00:00:00Z\"}]");
        SupabaseRepository titleRepository = repository(titleTransport);
        try {
            titleRepository.listConversations(titleRepository.captureScope(), 0, 20);
            fail("Persisted titles outside the contract must be rejected");
        } catch (ApiException exception) {
            assertEquals(ApiException.Category.DATA_FORMAT, exception.getCategory());
        }
    }

    @Test
    public void oversizedQuestionIsRejectedBeforeAnyHistoryRequest() throws Exception {
        FakeTransport transport = new FakeTransport();
        SupabaseRepository repository = repository(transport);
        ChatHistoryScope scope = repository.captureScope();

        try {
            repository.saveTurn(scope, CONVERSATION_ID, TURN_ID, "Review", repeated('q', 4001), "", false);
            fail("Questions must fit the persisted size bound");
        } catch (ApiException exception) {
            assertEquals(ApiException.Category.VALIDATION, exception.getCategory());
        }
        assertTrue("Invalid local data must not reach Supabase", transport.requests.isEmpty());
    }

    @Test
    public void delayedWriteAfterAccountSwitchIsRejectedBeforeSendingRpc() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, PROFILE);
        transport.add(200, "{\"access_token\":\"other-access-token\","
                + "\"refresh_token\":\"other-refresh-token\",\"token_type\":\"bearer\",\"expires_in\":3600,"
                + "\"user\":{\"id\":\"" + OTHER_USER_UUID + "\",\"email\":\"other@example.invalid\"}}");
        SupabaseRepository repository = repository(transport);
        ChatHistoryScope scope = repository.captureScope();
        transport.afterNextResponse = () -> {
            try {
                repository.signIn("other@example.invalid", "synthetic-password");
            } catch (ApiException exception) {
                throw new AssertionError(exception);
            }
        };

        try {
            repository.saveTurn(scope, CONVERSATION_ID, TURN_ID, "Review", "Question", "Answer", true);
            fail("A write captured under another account must be rejected");
        } catch (ApiException exception) {
            assertEquals(ApiException.Category.AUTH_REQUIRED, exception.getCategory());
        }
        assertEquals(2, transport.requests.size());
        assertTrue(transport.requests.get(1).url.contains("/auth/v1/token?grant_type=password"));
    }

    @Test
    public void deleteFiltersByCurrentProfileAndConversationUuid() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, PROFILE);
        transport.add(200, "[{\"conversation_id\":\"" + CONVERSATION_ID + "\"}]");
        SupabaseRepository repository = repository(transport);
        repository.deleteConversation(repository.captureScope(), CONVERSATION_ID);

        Request request = transport.requests.get(1);
        assertEquals("DELETE", request.method);
        assertTrue(request.url.contains("user_id=eq.42"));
        assertTrue(request.url.contains("conversation_id=eq." + CONVERSATION_ID));
        assertEquals("return=representation", request.headers.get("Prefer"));
    }

    @Test
    public void queuedHistoryWorkCannotAdoptTheNextSignedInAccount() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, "{\"access_token\":\"other-access-token\","
                + "\"refresh_token\":\"other-refresh-token\",\"token_type\":\"bearer\",\"expires_in\":3600,"
                + "\"user\":{\"id\":\"" + OTHER_USER_UUID + "\",\"email\":\"other@example.invalid\"}}");
        SupabaseRepository repository = repository(transport);
        ChatHistoryScope captured = repository.captureScope();
        repository.signIn("other@example.invalid", "synthetic-password");

        try {
            repository.listConversations(captured, 0, 20);
            fail("Queued work must remain bound to the account that scheduled it");
        } catch (ApiException exception) {
            assertEquals(ApiException.Category.AUTH_REQUIRED, exception.getCategory());
        }
        assertEquals(1, transport.requests.size());
        assertTrue(transport.requests.get(0).url.contains("/auth/v1/token?grant_type=password"));
    }

    private static String savedTurn(String status, String answer) {
        return "[{\"conversation_id\":\"" + CONVERSATION_ID + "\",\"turn_id\":\"" + TURN_ID
                + "\",\"question\":\"How can I save?\",\"answer\":\"" + answer
                + "\",\"status\":\"" + status + "\",\"created_at\":\"2026-10-01T00:00:00Z\","
                + "\"updated_at\":\"2026-10-01T00:01:00Z\"}]";
    }

    private static String repeated(char value, int length) {
        char[] chars = new char[length];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private static SupabaseRepository repository(FakeTransport transport) {
        Session session = new Session("synthetic-access-token", "synthetic-refresh-token", "bearer",
                USER_UUID, "sample@example.invalid", OffsetDateTime.parse("2026-10-07T10:00:00Z"));
        SessionPersistence memory = new SessionPersistence() {
            private Session stored = session;
            @Override public Session read() { return stored; }
            @Override public void write(Session value) { stored = value; }
            @Override public void clear() { stored = null; }
        };
        return new SupabaseRepository("https://project.example.invalid", "sb_publishable_synthetic",
                transport, memory, Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC));
    }

    private static final class Request {
        final String method;
        final String url;
        final Map<String, String> headers;
        final String body;

        Request(String method, String url, Map<String, String> headers, String body) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body;
        }
    }

    private static final class FakeTransport implements HttpTransport {
        final ArrayDeque<Response> responses = new ArrayDeque<>();
        final ArrayList<Request> requests = new ArrayList<>();
        Runnable afterNextResponse;

        void add(int status, String body) { responses.add(new Response(status, body)); }

        @Override public Response execute(String method, String url, Map<String, String> headers, String body) {
            requests.add(new Request(method, url, headers, body));
            if (responses.isEmpty()) throw new AssertionError("Unexpected extra request");
            Response response = responses.remove();
            Runnable callback = afterNextResponse;
            afterNextResponse = null;
            if (callback != null) callback.run();
            return response;
        }
    }
}
