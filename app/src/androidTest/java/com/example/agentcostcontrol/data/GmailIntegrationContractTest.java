package com.example.agentcostcontrol.data;

import android.app.Application;

import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStore;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.agentcostcontrol.model.GmailConnection;
import com.example.agentcostcontrol.model.Session;
import com.example.agentcostcontrol.model.SubscriptionSyncResult;
import com.example.agentcostcontrol.ui.AccountDataViewModel;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
public final class GmailIntegrationContractTest {
    private static final String USER_UUID = "11111111-1111-4111-8111-111111111111";
    private static final String REFRESHED_SESSION = "{\"access_token\":\"renewed-access-token\","
            + "\"refresh_token\":\"renewed-refresh-token\",\"token_type\":\"bearer\","
            + "\"expires_in\":3600,\"user\":{\"id\":\"" + USER_UUID
            + "\",\"email\":\"sample@example.invalid\"}}";

    @Test
    public void gmailStateUsesDerivedBackendRootAndVerifiedBearerToken() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, "{\"configured\":true,\"connected\":true}");

        GmailConnection connection = repository(transport).getGmailConnection();

        assertTrue(connection.isConfigured());
        assertTrue(connection.isConnected());
        Request request = transport.requests.get(0);
        assertEquals("GET", request.method);
        assertEquals("https://backend.example.invalid/tenant/integrations/gmail", request.url);
        assertEquals("Bearer synthetic-access-token", request.headers.get("Authorization"));
        assertFalse(request.headers.containsKey("apikey"));
        assertEquals(20_000, request.readTimeoutMillis);
    }

    @Test
    public void connectAndSyncAreExplicitAuthenticatedPosts() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, "{\"redirect_url\":\"https://connect.example.invalid/start?token=abc\"}");
        transport.add(200, "{\"imported\":2,\"skipped\":3,\"scanned\":5,"
                + "\"has_more\":true,\"message\":\"Scan complete\"}");
        SupabaseRepository repository = repository(transport);

        assertEquals("https://connect.example.invalid/start?token=abc", repository.createGmailConnectLink());
        SubscriptionSyncResult result = repository.syncSubscriptionsFromGmail();

        assertEquals(2, result.getImported());
        assertEquals(3, result.getSkipped());
        assertEquals(5, result.getScanned());
        assertTrue(result.hasMore());
        assertEquals("Scan complete", result.getMessage());
        for (int i = 0; i < transport.requests.size(); i++) {
            Request request = transport.requests.get(i);
            assertEquals("POST", request.method);
            assertEquals("{}", request.body);
            assertEquals("Bearer synthetic-access-token", request.headers.get("Authorization"));
            assertFalse(request.headers.containsKey("apikey"));
        }
        assertEquals(20_000, transport.requests.get(0).readTimeoutMillis);
        assertEquals(90_000, transport.requests.get(1).readTimeoutMillis);
        assertEquals("https://backend.example.invalid/tenant/integrations/gmail/connect",
                transport.requests.get(0).url);
        assertEquals("https://backend.example.invalid/tenant/integrations/subscriptions/sync",
                transport.requests.get(1).url);
    }

    @Test
    public void rejectedSyncIsNeverReplayedAfterSessionRefresh() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(401, "{\"error\":\"unauthorized\"}");
        transport.add(200, REFRESHED_SESSION);
        SupabaseRepository repository = repository(transport);

        try {
            repository.syncSubscriptionsFromGmail();
            fail("An unauthorized sync must not be replayed automatically");
        } catch (ApiException error) {
            assertEquals(ApiException.Category.RETRY_REQUIRED, error.getCategory());
        }
        assertEquals(2, transport.requests.size());
        assertEquals("POST", transport.requests.get(0).method);
        assertEquals("renewed-access-token", repository.getCurrentSession().getAccessToken());
    }

    @Test
    public void syncRejectsAChangedAccountBeforeSendingAnyRequest() throws Exception {
        FakeTransport transport = new FakeTransport();
        SupabaseRepository repository = repository(transport);

        try {
            repository.syncSubscriptionsFromGmail("22222222-2222-4222-8222-222222222222");
            fail("A sync must stay bound to the account that started it");
        } catch (ApiException error) {
            assertEquals(ApiException.Category.AUTH_REQUIRED, error.getCategory());
        }
        assertEquals(0, transport.requests.size());
    }

    @Test
    public void serverFailureDuringSyncReportsUncertainOutcomeWithoutLeakingResponse() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(503, "{\"error\":\"private provider details\"}");

        try {
            repository(transport).syncSubscriptionsFromGmail();
            fail("A failed sync response must not look like a confirmed success");
        } catch (ApiException error) {
            assertEquals("GMAIL_SYNC_OUTCOME_UNKNOWN", error.getServerCode());
            assertTrue(error.getUserMessage().contains("Services will reload"));
            assertFalse(error.getUserMessage().contains("private provider details"));
        }
        assertEquals(1, transport.requests.size());
    }

    @Test
    public void malformedSuccessfulSyncResponsesAlsoReportUncertainOutcome() throws Exception {
        assertUnknownSyncOutcome("not-json");
        assertUnknownSyncOutcome("{\"imported\":-1,\"skipped\":0,\"scanned\":0,"
                + "\"has_more\":false,\"message\":\"Scan complete\"}");
    }

    @Test
    public void syncCompletionSurvivesObserverReattachmentUntilAcknowledged() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, "{\"imported\":1,\"skipped\":0,\"scanned\":1,"
                + "\"has_more\":false,\"message\":\"Scan complete\"}");
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch allowResponse = new CountDownLatch(1);
        transport.blockNextResponse(requestStarted, allowResponse);

        ViewModelStore store = new ViewModelStore();
        Application application = (Application) InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        AccountDataViewModel viewModel = new ViewModelProvider(store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application))
                .get(AccountDataViewModel.class);
        setField(viewModel, "repository", repository(transport));
        AccountDataViewModel.GmailSyncObserver oldView = state -> { };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> viewModel.addGmailSyncObserver(oldView));

        assertTrue(viewModel.startGmailSync(USER_UUID));
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> viewModel.removeGmailSyncObserver(oldView));
        allowResponse.countDown();

        AccountDataViewModel.GmailSyncState completed = awaitGmailSyncCompletion(viewModel);
        assertEquals(1, completed.getResult().getImported());

        CountDownLatch reattachedViewReceivedCompletion = new CountDownLatch(1);
        AtomicReference<AccountDataViewModel.GmailSyncState> observed = new AtomicReference<>();
        AccountDataViewModel.GmailSyncObserver newView = state -> {
            if (state != null && !state.isInProgress()) {
                observed.set(state);
                reattachedViewReceivedCompletion.countDown();
            }
        };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> viewModel.addGmailSyncObserver(newView));
        assertTrue(reattachedViewReceivedCompletion.await(5, TimeUnit.SECONDS));
        assertEquals(completed.getGeneration(), observed.get().getGeneration());

        viewModel.acknowledgeGmailSync(completed.getGeneration());
        assertNull(viewModel.getGmailSyncState());
        store.clear();
    }

    @Test
    public void clearingSyncStatePreventsLateCompletionFromReplacingNewSyncState() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, "{\"imported\":1,\"skipped\":0,\"scanned\":1,"
                + "\"has_more\":false,\"message\":\"Old account scan\"}");
        transport.add(200, "{\"imported\":2,\"skipped\":0,\"scanned\":2,"
                + "\"has_more\":false,\"message\":\"New account scan\"}");
        CountDownLatch requestStarted = new CountDownLatch(1);
        CountDownLatch allowResponse = new CountDownLatch(1);
        transport.blockNextResponse(requestStarted, allowResponse);

        ViewModelStore store = new ViewModelStore();
        Application application = (Application) InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getApplicationContext();
        AccountDataViewModel viewModel = new ViewModelProvider(store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application))
                .get(AccountDataViewModel.class);
        setField(viewModel, "repository", repository(transport));

        assertTrue(viewModel.startGmailSync(USER_UUID));
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
        viewModel.clearGmailSyncState();
        assertTrue(viewModel.startGmailSync(USER_UUID));
        AccountDataViewModel.GmailSyncState replacementState = viewModel.getGmailSyncState();
        allowResponse.countDown();

        AccountDataViewModel.GmailSyncState completed = awaitGmailSyncCompletion(viewModel);
        assertEquals(replacementState.getGeneration(), completed.getGeneration());
        assertEquals(2, completed.getResult().getImported());
        store.clear();
    }

    @Test
    public void connectLinkValidationRejectsUnsafeSchemesAndCredentials() {
        assertTrue(SupabaseRepository.isSafeConnectUrl("https://connect.example.invalid/start?token=abc"));
        assertFalse(SupabaseRepository.isSafeConnectUrl("http://connect.example.invalid/start"));
        assertFalse(SupabaseRepository.isSafeConnectUrl("https://user:pass@connect.example.invalid/start"));
        assertFalse(SupabaseRepository.isSafeConnectUrl("javascript:alert(1)"));
        assertFalse(SupabaseRepository.isSafeConnectUrl("https://connect.example.invalid/start#fragment"));
    }

    @Test
    public void backendRootRequiresTheConfiguredHttpsAiChatRoute() {
        assertEquals("https://backend.example.invalid/tenant",
                SupabaseRepository.backendRootFromAiEndpoint("https://backend.example.invalid/tenant/ai/chat"));
        assertEquals("", SupabaseRepository.backendRootFromAiEndpoint("http://backend.example.invalid/ai/chat"));
        assertEquals("", SupabaseRepository.backendRootFromAiEndpoint("https://backend.example.invalid/chat"));
        assertEquals("", SupabaseRepository.backendRootFromAiEndpoint(
                "https://user@backend.example.invalid/ai/chat"));
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
                transport, memory, Clock.fixed(Instant.parse("2026-10-06T10:00:00Z"), ZoneOffset.UTC),
                "https://backend.example.invalid/tenant/ai/chat");
    }

    private static void assertUnknownSyncOutcome(String responseBody) throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, responseBody);
        try {
            repository(transport).syncSubscriptionsFromGmail();
            fail("An invalid success response must not look like a confirmed sync result");
        } catch (ApiException error) {
            assertEquals("GMAIL_SYNC_OUTCOME_UNKNOWN", error.getServerCode());
            assertTrue(error.getUserMessage().contains("Services will reload"));
        }
        assertEquals(1, transport.requests.size());
    }

    private static AccountDataViewModel.GmailSyncState awaitGmailSyncCompletion(
            AccountDataViewModel viewModel) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            AccountDataViewModel.GmailSyncState state = viewModel.getGmailSyncState();
            if (state != null && !state.isInProgress()) return state;
            Thread.sleep(10);
        }
        fail("Gmail sync did not finish within the test deadline");
        return null;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class Request {
        final String method;
        final String url;
        final Map<String, String> headers;
        final String body;
        final int readTimeoutMillis;

        Request(String method, String url, Map<String, String> headers, String body, int readTimeoutMillis) {
            this.method = method;
            this.url = url;
            this.headers = headers;
            this.body = body;
            this.readTimeoutMillis = readTimeoutMillis;
        }
    }

    private static final class FakeTransport implements HttpTransport {
        final ArrayDeque<Response> responses = new ArrayDeque<>();
        final ArrayList<Request> requests = new ArrayList<>();
        private CountDownLatch requestStarted;
        private CountDownLatch responseGate;

        void add(int status, String body) { responses.add(new Response(status, body)); }

        void blockNextResponse(CountDownLatch started, CountDownLatch release) {
            requestStarted = started;
            responseGate = release;
        }

        @Override
        public Response execute(String method, String url, Map<String, String> headers, String body) {
            return executeWithTimeout(method, url, headers, body, -1);
        }

        @Override
        public Response execute(String method, String url, Map<String, String> headers, String body,
                                int readTimeoutMillis) {
            return executeWithTimeout(method, url, headers, body, readTimeoutMillis);
        }

        private Response executeWithTimeout(String method, String url, Map<String, String> headers,
                                            String body, int readTimeoutMillis) {
            requests.add(new Request(method, url, new LinkedHashMap<>(headers), body, readTimeoutMillis));
            if (requestStarted != null) {
                requestStarted.countDown();
                requestStarted = null;
            }
            if (responseGate != null) {
                try {
                    if (!responseGate.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting for the test to release the response");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("The sync worker was interrupted", error);
                } finally {
                    responseGate = null;
                }
            }
            if (responses.isEmpty()) throw new AssertionError("Unexpected extra request");
            return responses.remove();
        }
    }
}
