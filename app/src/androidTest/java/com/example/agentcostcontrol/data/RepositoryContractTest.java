package com.example.agentcostcontrol.data;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.agentcostcontrol.model.Session;
import com.example.agentcostcontrol.model.DecisionStatus;
import com.example.agentcostcontrol.model.Subscription;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Exercises the real Android JSON adapter against a fake transport, without live account writes. */
@RunWith(AndroidJUnit4.class)
public final class RepositoryContractTest {
    private static final String USER_UUID = "11111111-1111-4111-8111-111111111111";
    private static final String PROFILE = "[{\"user_id\":\"42\",\"auth_user_id\":\"" + USER_UUID
            + "\",\"first_name\":\"Sample\",\"last_name\":\"User\",\"email\":\"sample@example.invalid\","
            + "\"user_type\":\"user\",\"created_at\":\"2026-10-01T00:00:00Z\","
            + "\"updated_at\":\"2026-10-01T00:00:00Z\"}]";
    private static final String REFRESHED_SESSION = "{\"access_token\":\"renewed-access-token\","
            + "\"refresh_token\":\"renewed-refresh-token\",\"token_type\":\"bearer\",\"expires_in\":3600,"
            + "\"user\":{\"id\":\"" + USER_UUID + "\",\"email\":\"sample@example.invalid\"}}";

    @Test
    public void readsUseAuthenticatedOwnershipAndDecodeMoneyExactly() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, PROFILE);
        transport.add(200, "[{\"subscription_id\":\"101\",\"user_id\":\"42\",\"provider_id\":null,"
                + "\"subscription_name\":\"Sample service\",\"plan_name\":null,\"category\":\"productivity\","
                + "\"subscription_price\":\"9999999999.99\",\"currency\":\"ZAR\",\"billing_cycle\":\"annual\","
                + "\"status\":\"active\",\"usage_level\":null,\"importance\":\"medium\",\"auto_renew\":true,"
                + "\"start_date\":\"2026-10-01\",\"renewal_date\":\"2027-10-01\","
                + "\"created_at\":\"2026-10-01T00:00:00Z\",\"updated_at\":\"2026-10-01T00:00:00Z\"}]");
        SupabaseRepository repository = repository(transport);
        List<Subscription> result = repository.listSubscriptions(100, 0);
        assertEquals(new BigDecimal("9999999999.99"), result.get(0).getPrice());
        assertTrue(transport.requests.get(1).url.contains("user_id=eq.42"));
        assertTrue(transport.requests.get(1).url.contains("limit=100"));
        assertEquals("Bearer synthetic-access-token", transport.requests.get(1).headers.get("Authorization"));
        assertFalse(transport.requests.get(0).url.contains("select=*"));
        assertFalse(transport.requests.get(0).url.contains("password"));
    }

    @Test
    public void zeroRowDeleteIsFailureAndFiltersTheSelectedOwnedRecord() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, PROFILE);
        transport.add(200, "[]");
        try {
            repository(transport).deleteSubscription(101);
            fail("A zero-row mutation must not report success");
        } catch (ApiException error) {
            assertEquals(ApiException.Category.NOT_FOUND, error.getCategory());
        }
        Request mutation = transport.requests.get(1);
        assertEquals("DELETE", mutation.method);
        assertTrue(mutation.url.contains("user_id=eq.42"));
        assertTrue(mutation.url.contains("subscription_id=eq.101"));
    }

    @Test
    public void unauthorizedWriteIsNotRetried() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, PROFILE);
        transport.add(401, "{\"code\":\"PGRST301\"}");
        transport.add(401, "{\"error_code\":\"refresh_token_not_found\"}");
        try {
            repository(transport).deleteSubscription(101);
            fail("Unauthorized write must fail");
        } catch (ApiException error) {
            assertEquals(ApiException.Category.AUTH_REQUIRED, error.getCategory());
        }
        assertEquals(3, transport.requests.size());
        assertEquals("POST", transport.requests.get(2).method);
        assertTrue(transport.requests.get(2).url.contains("grant_type=refresh_token"));
    }

    @Test
    public void rejectedWriteRefreshesSessionWithoutReplayingMutation() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, PROFILE);
        transport.add(401, "{\"code\":\"PGRST301\"}");
        transport.add(200, REFRESHED_SESSION);
        SupabaseRepository repository = repository(transport);
        try {
            repository.deleteSubscription(101);
            fail("Caller must explicitly retry a rejected write");
        } catch (ApiException error) {
            assertEquals(ApiException.Category.RETRY_REQUIRED, error.getCategory());
        }
        assertEquals(3, transport.requests.size());
        assertEquals("renewed-access-token", repository.getCurrentSession().getAccessToken());
    }

    @Test
    public void readRefreshUsesNewTokenAndExposesCurrentSession() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, PROFILE);
        transport.add(401, "{\"code\":\"PGRST301\"}");
        transport.add(200, REFRESHED_SESSION);
        transport.add(200, "[]");
        SupabaseRepository repository = repository(transport);
        assertTrue(repository.listSubscriptions(100, 0).isEmpty());
        assertEquals(4, transport.requests.size());
        assertEquals("Bearer renewed-access-token", transport.requests.get(3).headers.get("Authorization"));
        assertEquals("renewed-access-token", repository.getCurrentSession().getAccessToken());
    }

    @Test
    public void signInPreservesPasswordWhitespace() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, "{\"access_token\":\"synthetic-access-token\",\"refresh_token\":\"synthetic-refresh-token\","
                + "\"token_type\":\"bearer\",\"expires_in\":3600,\"user\":{\"id\":\"" + USER_UUID
                + "\",\"email\":\"sample@example.invalid\"}}");
        repository(transport).signIn("sample@example.invalid", "  intentional spaces  ");
        assertEquals("  intentional spaces  ", new JSONObject(transport.requests.get(0).body).getString("password"));
    }

    @Test
    public void currentBudgetQueryFiltersOwnerAndPeriodWithoutPagingThroughHistory() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, PROFILE);
        transport.add(200, "[{\"budget_id\":\"17\",\"user_id\":\"42\",\"budget_limit\":\"1500.00\","
                + "\"currency\":\"ZAR\",\"month\":10,\"year\":2026}]");
        assertEquals(new BigDecimal("1500.00"),
                repository(transport).listBudgetsForPeriod(10, 2026).get(0).getLimitAmount());
        String query = transport.requests.get(1).url;
        assertTrue(query.contains("user_id=eq.42"));
        assertTrue(query.contains("month=eq.10"));
        assertTrue(query.contains("year=eq.2026"));
        assertTrue(query.contains("limit=1"));
    }

    @Test
    public void decisionRefreshReadsStoredResultAndAbsenceForTheOwnedRecommendation() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, PROFILE);
        transport.add(200, "[{\"decision_id\":\"18\",\"user_id\":\"42\",\"recommendation_id\":\"901\","
                + "\"decision\":\"approved\",\"decision_date\":\"2026-10-06T10:00:00Z\","
                + "\"title\":\"Review sample service\"}]");
        transport.add(200, PROFILE);
        transport.add(200, "[]");
        SupabaseRepository repository = repository(transport);
        assertEquals(DecisionStatus.APPROVED, repository.getDecision(901).getDecision());
        assertNull(repository.getDecision(901));
        assertEquals(4, transport.requests.size());
        for (int index : new int[] {1, 3}) {
            Request request = transport.requests.get(index);
            assertEquals("GET", request.method);
            assertTrue(request.url.contains("user_id=eq.42"));
            assertTrue(request.url.contains("recommendation_id=eq.901"));
            assertTrue(request.url.contains("limit=1"));
        }
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
        void add(int status, String body) { responses.add(new Response(status, body)); }
        @Override public Response execute(String method, String url, Map<String, String> headers, String body) {
            requests.add(new Request(method, url, headers, body));
            if (responses.isEmpty()) throw new AssertionError("Unexpected extra request");
            return responses.remove();
        }
    }
}
