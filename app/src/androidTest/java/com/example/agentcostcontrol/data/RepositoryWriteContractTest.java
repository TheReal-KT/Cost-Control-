package com.example.agentcostcontrol.data;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.agentcostcontrol.model.BillingCycle;
import com.example.agentcostcontrol.model.BudgetDraft;
import com.example.agentcostcontrol.model.Session;
import com.example.agentcostcontrol.model.SubscriptionDraft;
import com.example.agentcostcontrol.model.SubscriptionImportance;
import com.example.agentcostcontrol.model.SubscriptionStatus;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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

/** Regression tests for repository writes and confirmation-only Auth responses using synthetic data. */
@RunWith(AndroidJUnit4.class)
public final class RepositoryWriteContractTest {
    private static final String USER_UUID = "11111111-1111-4111-8111-111111111111";
    private static final String PROFILE = "[{\"user_id\":\"42\",\"auth_user_id\":\"" + USER_UUID
            + "\",\"first_name\":\"Sample\",\"last_name\":\"User\",\"email\":\"sample@example.invalid\","
            + "\"user_type\":\"user\",\"created_at\":\"2026-10-01T00:00:00Z\","
            + "\"updated_at\":\"2026-10-01T00:00:00Z\"}]";

    @Test
    public void subscriptionCreateAndEditKeepExactMoneyAndAuthenticatedOwnerScope() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, PROFILE);
        transport.add(201, subscriptionResponse("501", "Automation Suite", "123456789.01", "active"));
        transport.add(200, PROFILE);
        transport.add(200, subscriptionResponse("501", "Automation Suite Plus", "100.20", "paused"));
        SupabaseRepository repository = repository(transport, session());

        SubscriptionDraft createDraft = subscriptionDraft("Automation Suite", "123456789.01", SubscriptionStatus.ACTIVE);
        assertEquals(new BigDecimal("123456789.01"), repository.createSubscription(createDraft).getPrice());
        SubscriptionDraft editDraft = subscriptionDraft("Automation Suite Plus", "100.20", SubscriptionStatus.PAUSED);
        assertEquals(new BigDecimal("100.20"), repository.updateSubscription(501, editDraft).getPrice());

        assertEquals(4, transport.requests.size());
        Request create = transport.requests.get(1);
        assertEquals("POST", create.method);
        assertTrue(create.url.contains("/rest/v1/subscriptions"));
        assertEquals("42", new JSONObject(create.body).getString("user_id"));
        assertTrue(create.body.contains("\"subscription_price\":123456789.01"));
        assertFalse(create.body.contains("\"subscription_price\":\"123456789.01\""));

        Request edit = transport.requests.get(3);
        assertEquals("PATCH", edit.method);
        assertTrue(edit.url.contains("user_id=eq.42"));
        assertTrue(edit.url.contains("subscription_id=eq.501"));
        Object editAmount = new JSONObject(edit.body).get("subscription_price");
        assertFalse(editAmount instanceof String);
        assertEquals(0, new BigDecimal("100.20").compareTo(new BigDecimal(editAmount.toString())));
        assertFalse(edit.body.contains("\"user_id\""));
    }

    @Test
    public void successfulMutationWithMalformedRepresentationIsNotReportedAsSavedOrReplayed() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, PROFILE);
        transport.add(201, "{invalid representation");
        try {
            repository(transport, session()).createSubscription(
                    subscriptionDraft("Sample service", "100.00", SubscriptionStatus.ACTIVE));
            fail("An unusable response cannot prove which record was saved");
        } catch (ApiException error) {
            assertEquals(ApiException.Category.DATA_FORMAT, error.getCategory());
        }
        assertEquals(2, transport.requests.size());
        assertEquals("POST", transport.requests.get(1).method);
    }

    @Test
    public void emailConfirmationSignupReturnsWithoutSessionOrProfileWrite() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, "{\"id\":\"" + USER_UUID + "\",\"email\":\"sample@example.invalid\"}");
        SupabaseRepository repository = repository(transport, null);

        com.example.agentcostcontrol.model.SignUpResult result = repository.signUp(
                "sample@example.invalid", "synthetic password", "Sample", "User");

        assertTrue(result.isEmailConfirmationRequired());
        assertNull(result.getSession());
        assertEquals(USER_UUID, result.getAuthUserId());
        assertNull(repository.getCurrentSession());
        assertEquals(1, transport.requests.size());
        Request signup = transport.requests.get(0);
        assertEquals("POST", signup.method);
        assertTrue(signup.url.endsWith("/auth/v1/signup"));
        assertFalse(signup.headers.containsKey("Authorization"));
        JSONObject requestBody = new JSONObject(signup.body);
        assertEquals("sample@example.invalid", requestBody.getString("email"));
        assertEquals("Sample", requestBody.getJSONObject("data").getString("first_name"));
    }

    @Test
    public void duplicateBudgetPeriodIsReportedAsConflict() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.add(200, PROFILE);
        transport.add(409, "{\"code\":\"23505\",\"message\":\"duplicate synthetic budget period\"}");
        SupabaseRepository repository = repository(transport, session());
        BudgetDraft draft = BudgetDraft.builder()
                .setLimitAmount(new BigDecimal("2400.75"))
                .setCurrency("ZAR")
                .setMonth(10)
                .setYear(2026)
                .build();

        try {
            repository.createBudget(draft);
            fail("A duplicate budget period must not be reported as a successful create");
        } catch (ApiException error) {
            assertEquals(ApiException.Category.CONFLICT, error.getCategory());
            assertEquals("23505", error.getServerCode());
        }

        assertEquals(2, transport.requests.size());
        Request mutation = transport.requests.get(1);
        assertEquals("POST", mutation.method);
        assertTrue(mutation.url.contains("/rest/v1/budgets"));
        assertEquals("42", new JSONObject(mutation.body).getString("user_id"));
        assertTrue(mutation.body.contains("\"budget_limit\":2400.75"));
    }

    private static SubscriptionDraft subscriptionDraft(String name, String amount, SubscriptionStatus status) {
        return SubscriptionDraft.builder()
                .setName(name)
                .setPlanName("Annual plan")
                .setPrice(new BigDecimal(amount))
                .setCurrency("ZAR")
                .setBillingCycle(BillingCycle.ANNUAL)
                .setCategory("productivity")
                .setStatus(status)
                .setImportance(SubscriptionImportance.MEDIUM)
                .setAutoRenew(true)
                .setStartDate(LocalDate.parse("2026-10-01"))
                .setRenewalDate(LocalDate.parse("2027-10-01"))
                .build();
    }

    private static String subscriptionResponse(String id, String name, String amount, String status) {
        return "[{\"subscription_id\":\"" + id + "\",\"user_id\":\"42\",\"provider_id\":null,"
                + "\"subscription_name\":\"" + name + "\",\"plan_name\":\"Annual plan\","
                + "\"category\":\"productivity\",\"subscription_price\":\"" + amount + "\","
                + "\"currency\":\"ZAR\",\"billing_cycle\":\"annual\",\"status\":\"" + status + "\","
                + "\"usage_level\":null,\"importance\":\"medium\",\"auto_renew\":true,"
                + "\"start_date\":\"2026-10-01\",\"renewal_date\":\"2027-10-01\","
                + "\"created_at\":\"2026-10-01T00:00:00Z\",\"updated_at\":\"2026-10-01T00:00:00Z\"}]";
    }

    private static Session session() {
        return new Session("synthetic-access-token", "synthetic-refresh-token", "bearer",
                USER_UUID, "sample@example.invalid", OffsetDateTime.parse("2026-10-07T10:00:00Z"));
    }

    private static SupabaseRepository repository(FakeTransport transport, Session initialSession) {
        SessionPersistence memory = new SessionPersistence() {
            private Session stored = initialSession;
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
        final List<Request> requests = new ArrayList<>();

        void add(int status, String body) { responses.add(new Response(status, body)); }

        @Override public Response execute(String method, String url, Map<String, String> headers, String body)
                throws IOException {
            requests.add(new Request(method, url, headers, body));
            if (responses.isEmpty()) throw new IOException("Unexpected synthetic request");
            return responses.remove();
        }
    }
}
