package com.example.agentcostcontrol.data;

import android.content.Context;

import com.example.agentcostcontrol.BuildConfig;
import com.example.agentcostcontrol.model.BillingCycle;
import com.example.agentcostcontrol.model.Budget;
import com.example.agentcostcontrol.model.BudgetDraft;
import com.example.agentcostcontrol.model.DecisionStatus;
import com.example.agentcostcontrol.model.Recommendation;
import com.example.agentcostcontrol.model.RecommendationAction;
import com.example.agentcostcontrol.model.Reminder;
import com.example.agentcostcontrol.model.ReminderDraft;
import com.example.agentcostcontrol.model.ReminderStatus;
import com.example.agentcostcontrol.model.Session;
import com.example.agentcostcontrol.model.SignUpResult;
import com.example.agentcostcontrol.model.Subscription;
import com.example.agentcostcontrol.model.SubscriptionDraft;
import com.example.agentcostcontrol.model.SubscriptionImportance;
import com.example.agentcostcontrol.model.SubscriptionStatus;
import com.example.agentcostcontrol.model.UsageLevel;
import com.example.agentcostcontrol.model.UserDecision;
import com.example.agentcostcontrol.model.UserProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Synchronous Supabase Auth and REST client. Call its methods from a worker thread. */
public final class SupabaseRepository {
    private static final int MAX_PAGE_SIZE = 100;
    private static final long SESSION_REFRESH_SKEW_SECONDS = 60;

    private static final String PROFILE_SELECT =
            "user_id::text,auth_user_id,first_name,last_name,email,user_type,created_at,updated_at";
    private static final String SUBSCRIPTION_SELECT =
            "subscription_id::text,user_id::text,provider_id::text,subscription_name,plan_name,category,subscription_price::text," +
                    "currency,billing_cycle,status,usage_level,importance,auto_renew,start_date,renewal_date,created_at,updated_at";
    private static final String BUDGET_SELECT = "budget_id::text,user_id::text,budget_limit::text,currency,month,year";
    private static final String REMINDER_SELECT =
            "reminder_id::text,user_id::text,subscription_id::text,title,message,remind_at,status,created_at,updated_at";
    private static final String RECOMMENDATION_SELECT =
            "recommendation_id::text,user_id::text,subscription_id::text,title,recommendation_type,reason,confidence_score::text,evidence::text," +
                    "model_name,model_version,potential_monthly_saving::text,potential_annual_saving::text,created_at,expires_at";
    private static final String DECISION_SELECT =
            "decision_id::text,user_id::text,recommendation_id::text,decision,decision_date,title";

    private final String baseUrl;
    private final String publishableKey;
    private final HttpTransport transport;
    private final SessionPersistence sessionPersistence;
    private final Clock clock;
    private final Object authLock = new Object();

    private Session currentSession;
    private long authGeneration;
    private UserProfile cachedProfile;
    private boolean sessionBlocked;

    public SupabaseRepository(Context context) {
        this(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                new HttpTransport.UrlConnection(), new SecureSessionStore(context), Clock.systemUTC());
    }

    SupabaseRepository(String baseUrl, String publishableKey, HttpTransport transport,
                       SessionPersistence sessionPersistence, Clock clock) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.publishableKey = publishableKey == null ? "" : publishableKey.trim();
        this.transport = Objects.requireNonNull(transport, "transport");
        this.sessionPersistence = Objects.requireNonNull(sessionPersistence, "sessionPersistence");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Session signIn(String email, String password) throws ApiException {
        String cleanEmail = requiredInput(email, "Email");
        String cleanPassword = requiredPassword(password);
        JSONObject body = new JSONObject();
        put(body, "email", cleanEmail);
        put(body, "password", cleanPassword);
        synchronized (authLock) {
            JSONObject response = authObject("/auth/v1/token?grant_type=password", "POST", body, null, "sign-in");
            Session session = parseSession(response, null, null);
            saveSessionLocked(session);
            return session;
        }
    }

    public SignUpResult signUp(String email, String password, String firstName, String lastName)
            throws ApiException {
        String cleanEmail = requiredInput(email, "Email");
        String cleanPassword = requiredPassword(password);
        String cleanFirstName = profileName(firstName, "First name");
        String cleanLastName = profileName(lastName, "Last name");
        JSONObject metadata = new JSONObject();
        put(metadata, "first_name", cleanFirstName);
        put(metadata, "last_name", cleanLastName);
        JSONObject body = new JSONObject();
        put(body, "email", cleanEmail);
        put(body, "password", cleanPassword);
        put(body, "data", metadata);

        Session session;
        String authUserId;
        synchronized (authLock) {
            JSONObject response = authObject("/auth/v1/signup", "POST", body, null, "sign-up");
            JSONObject user = response.optJSONObject("user");
            if (user == null && response.has("id")) user = response;
            if (user == null) throw dataFormat("Sign-up response did not contain a user", null);
            try {
                authUserId = UUID.fromString(requiredString(user, "id")).toString();
            } catch (IllegalArgumentException exception) {
                throw dataFormat("Authentication response contained an invalid user ID", exception);
            }
            session = nullableString(response, "access_token") != null
                    && nullableString(response, "refresh_token") != null
                    ? parseSession(response, authUserId, cleanEmail) : null;
            if (session != null) saveSessionLocked(session);
        }

        if (session != null) completeProfileSetup(cleanFirstName, cleanLastName);
        return new SignUpResult(session, authUserId, cleanEmail);
    }

    /** Returns null when no encrypted session is stored; refreshes an expired session before returning. */
    public Session restoreSession() throws ApiException {
        synchronized (authLock) {
            return currentSessionLocked();
        }
    }

    /**
     * Returns the latest session already loaded in memory, without performing network or storage I/O.
     * Call restoreSession() once during startup before relying on this snapshot.
     */
    public Session getCurrentSession() {
        synchronized (authLock) {
            return sessionBlocked ? null : currentSession;
        }
    }

    public Session refreshSession() throws ApiException {
        synchronized (authLock) {
            Session session = loadSessionLocked();
            if (session == null) throw authRequired();
            return refreshSessionLocked(session);
        }
    }

    /** Clears local credentials first, then asks Auth to revoke this device's refresh token. */
    public void signOut() throws ApiException {
        Session sessionToRevoke;
        ApiException localClearFailure = null;
        synchronized (authLock) {
            sessionToRevoke = currentSession;
            if (sessionToRevoke == null) {
                try {
                    sessionToRevoke = sessionPersistence.read();
                } catch (SessionPersistenceException exception) {
                    localClearFailure = sessionStorage(exception);
                }
            }
            currentSession = null;
            cachedProfile = null;
            sessionBlocked = true;
            authGeneration++;
            try {
                sessionPersistence.clear();
            } catch (SessionPersistenceException exception) {
                if (localClearFailure == null) localClearFailure = sessionStorage(exception);
            }
        }

        ApiException remoteLogoutFailure = null;
        if (sessionToRevoke != null) {
            try {
                HttpTransport.Response response = send("POST", "/auth/v1/logout?scope=local", null,
                        sessionToRevoke, null);
                if (!isSuccess(response.statusCode)) throw responseError(response, true, "sign-out");
            } catch (IOException exception) {
                remoteLogoutFailure = networkFailure(exception);
            } catch (ApiException exception) {
                remoteLogoutFailure = exception;
            }
        }
        if (remoteLogoutFailure != null) {
            if (localClearFailure != null) remoteLogoutFailure.addSuppressed(localClearFailure);
            throw remoteLogoutFailure;
        }
        if (localClearFailure != null) throw localClearFailure;
    }

    public UserProfile getCurrentProfile() throws ApiException {
        SessionSnapshot snapshot = requireSessionSnapshot();
        UserProfile profile = loadProfile(snapshot);
        if (profile == null) throw profileRequired();
        synchronized (authLock) {
            if (sameUser(currentSession, snapshot.session)) cachedProfile = profile;
        }
        return profile;
    }

    /** Creates the current user's profile or updates only its editable name columns. */
    public UserProfile completeProfileSetup(String firstName, String lastName) throws ApiException {
        String cleanFirstName = profileName(firstName, "First name");
        String cleanLastName = profileName(lastName, "Last name");
        SessionSnapshot snapshot = requireSessionSnapshot();
        UserProfile existing = loadProfile(snapshot);
        JSONObject body = new JSONObject();
        put(body, "first_name", cleanFirstName);
        put(body, "last_name", cleanLastName);

        String path;
        String method;
        if (existing == null) {
            path = "/rest/v1/users?select=" + PROFILE_SELECT;
            method = "POST";
        } else {
            path = "/rest/v1/users?select=" + PROFILE_SELECT
                    + "&auth_user_id=eq." + queryValue(snapshot.session.getAuthUserId())
                    + "&user_id=eq." + existing.getUserId();
            method = "PATCH";
        }
        UserProfile profile = parseProfile(single(requestArray(method, path, body, snapshot, "return=representation"),
                method.equals("POST") ? "Profile could not be created" : "Profile is no longer available"));
        synchronized (authLock) {
            if (sameUser(currentSession, snapshot.session)) cachedProfile = profile;
        }
        return profile;
    }

    public List<Subscription> listSubscriptions(int limit, int offset) throws ApiException {
        validatePage(limit, offset);
        SessionSnapshot snapshot = requireSessionSnapshot();
        long userId = requireUserId(snapshot);
        String path = OwnedRestQuery.ownedList("subscriptions", SUBSCRIPTION_SELECT, userId,
                "renewal_date.asc,subscription_id.asc", limit, offset);
        return parseSubscriptions(requestArray("GET", path, null, snapshot, null));
    }

    public Subscription createSubscription(SubscriptionDraft draft) throws ApiException {
        Objects.requireNonNull(draft, "draft");
        SessionSnapshot snapshot = requireSessionSnapshot();
        JSONObject body = subscriptionBody(draft, requireUserId(snapshot));
        String path = "/rest/v1/subscriptions?select=" + SUBSCRIPTION_SELECT;
        return parseSubscription(single(requestArray("POST", path, body, snapshot, "return=representation"),
                "Subscription could not be created"));
    }

    public Subscription updateSubscription(long id, SubscriptionDraft draft) throws ApiException {
        validateId(id, "subscription");
        Objects.requireNonNull(draft, "draft");
        SessionSnapshot snapshot = requireSessionSnapshot();
        long userId = requireUserId(snapshot);
        String path = OwnedRestQuery.ownedRecord("subscriptions", SUBSCRIPTION_SELECT, userId,
                "subscription_id", id);
        return parseSubscription(single(requestArray("PATCH", path, subscriptionBody(draft, null),
                snapshot, "return=representation"), "Subscription is no longer available"));
    }

    public void deleteSubscription(long id) throws ApiException {
        validateId(id, "subscription");
        SessionSnapshot snapshot = requireSessionSnapshot();
        long userId = requireUserId(snapshot);
        String path = OwnedRestQuery.ownedRecord("subscriptions", "subscription_id::text", userId,
                "subscription_id", id);
        requireOne(requestArray("DELETE", path, null, snapshot, "return=representation"),
                "Subscription is no longer available");
    }

    public List<Budget> listBudgets(int limit, int offset) throws ApiException {
        validatePage(limit, offset);
        SessionSnapshot snapshot = requireSessionSnapshot();
        long userId = requireUserId(snapshot);
        String path = OwnedRestQuery.ownedList("budgets", BUDGET_SELECT, userId,
                "year.desc,month.desc,budget_id.desc", limit, offset);
        return parseBudgets(requestArray("GET", path, null, snapshot, null));
    }

    /** Selects the unique budget for a period without relying on a page of budget history. */
    public List<Budget> listBudgetsForPeriod(int month, int year) throws ApiException {
        if (month < 1 || month > 12 || year < 2000 || year > 9999) {
            throw new ApiException(ApiException.Category.VALIDATION,
                    "Choose a valid budget period.", null, 0);
        }
        SessionSnapshot snapshot = requireSessionSnapshot();
        long userId = requireUserId(snapshot);
        String path = OwnedRestQuery.ownedRows("budgets", BUDGET_SELECT, userId)
                + "&month=eq." + month + "&year=eq." + year + "&limit=1";
        return parseBudgets(requestArray("GET", path, null, snapshot, null));
    }

    public Budget createBudget(BudgetDraft draft) throws ApiException {
        Objects.requireNonNull(draft, "draft");
        SessionSnapshot snapshot = requireSessionSnapshot();
        JSONObject body = budgetBody(draft, requireUserId(snapshot));
        String path = "/rest/v1/budgets?select=" + BUDGET_SELECT;
        return parseBudget(single(requestArray("POST", path, body, snapshot, "return=representation"),
                "Budget could not be created"));
    }

    public Budget updateBudget(long id, BudgetDraft draft) throws ApiException {
        validateId(id, "budget");
        Objects.requireNonNull(draft, "draft");
        SessionSnapshot snapshot = requireSessionSnapshot();
        long userId = requireUserId(snapshot);
        String path = OwnedRestQuery.ownedRecord("budgets", BUDGET_SELECT, userId, "budget_id", id);
        return parseBudget(single(requestArray("PATCH", path, budgetBody(draft, null), snapshot,
                "return=representation"), "Budget is no longer available"));
    }

    public void deleteBudget(long id) throws ApiException {
        validateId(id, "budget");
        SessionSnapshot snapshot = requireSessionSnapshot();
        long userId = requireUserId(snapshot);
        String path = OwnedRestQuery.ownedRecord("budgets", "budget_id::text", userId, "budget_id", id);
        requireOne(requestArray("DELETE", path, null, snapshot, "return=representation"),
                "Budget is no longer available");
    }

    public List<Reminder> listReminders(int limit, int offset) throws ApiException {
        validatePage(limit, offset);
        SessionSnapshot snapshot = requireSessionSnapshot();
        long userId = requireUserId(snapshot);
        String path = OwnedRestQuery.ownedList("reminders", REMINDER_SELECT, userId,
                "remind_at.asc,reminder_id.asc", limit, offset);
        return parseReminders(requestArray("GET", path, null, snapshot, null));
    }

    public Reminder createReminder(ReminderDraft draft) throws ApiException {
        Objects.requireNonNull(draft, "draft");
        SessionSnapshot snapshot = requireSessionSnapshot();
        JSONObject body = reminderBody(draft, requireUserId(snapshot));
        String path = "/rest/v1/reminders?select=" + REMINDER_SELECT;
        return parseReminder(single(requestArray("POST", path, body, snapshot, "return=representation"),
                "Reminder could not be created"));
    }

    public Reminder updateReminder(long id, ReminderDraft draft) throws ApiException {
        validateId(id, "reminder");
        Objects.requireNonNull(draft, "draft");
        SessionSnapshot snapshot = requireSessionSnapshot();
        long userId = requireUserId(snapshot);
        String path = OwnedRestQuery.ownedRecord("reminders", REMINDER_SELECT, userId, "reminder_id", id);
        return parseReminder(single(requestArray("PATCH", path, reminderBody(draft, null), snapshot,
                "return=representation"), "Reminder is no longer available"));
    }

    public void deleteReminder(long id) throws ApiException {
        validateId(id, "reminder");
        SessionSnapshot snapshot = requireSessionSnapshot();
        long userId = requireUserId(snapshot);
        String path = OwnedRestQuery.ownedRecord("reminders", "reminder_id::text", userId, "reminder_id", id);
        requireOne(requestArray("DELETE", path, null, snapshot, "return=representation"),
                "Reminder is no longer available");
    }

    public List<Recommendation> listRecommendations(int limit, int offset) throws ApiException {
        validatePage(limit, offset);
        SessionSnapshot snapshot = requireSessionSnapshot();
        long userId = requireUserId(snapshot);
        String path = OwnedRestQuery.ownedList("recommendations", RECOMMENDATION_SELECT, userId,
                "created_at.desc,recommendation_id.desc", limit, offset);
        return parseRecommendations(requestArray("GET", path, null, snapshot, null));
    }

    public List<UserDecision> listDecisions(int limit, int offset) throws ApiException {
        validatePage(limit, offset);
        SessionSnapshot snapshot = requireSessionSnapshot();
        long userId = requireUserId(snapshot);
        String path = OwnedRestQuery.ownedList("user_decision", DECISION_SELECT, userId,
                "decision_date.desc,decision_id.desc", limit, offset);
        return parseDecisions(requestArray("GET", path, null, snapshot, null));
    }

    /** Returns null when this user has not decided on the recommendation. */
    public UserDecision getDecision(long recommendationId) throws ApiException {
        validateId(recommendationId, "recommendation");
        SessionSnapshot snapshot = requireSessionSnapshot();
        long userId = requireUserId(snapshot);
        return loadDecision(snapshot, userId, recommendationId);
    }

    public UserDecision saveDecision(long recommendationId, DecisionStatus decision) throws ApiException {
        validateId(recommendationId, "recommendation");
        Objects.requireNonNull(decision, "decision");
        SessionSnapshot snapshot = requireSessionSnapshot();
        long userId = requireUserId(snapshot);
        Recommendation recommendation = loadRecommendation(snapshot, userId, recommendationId);
        if (recommendation == null) throw notFound("Recommendation is no longer available");

        UserDecision existing = loadDecision(snapshot, userId, recommendationId);
        JSONObject body = new JSONObject();
        put(body, "decision", apiValue(decision));
        put(body, "title", recommendation.getTitle());
        String path;
        String method;
        if (existing == null) {
            put(body, "user_id", userId);
            put(body, "recommendation_id", recommendationId);
            path = "/rest/v1/user_decision?select=" + DECISION_SELECT;
            method = "POST";
        } else {
            path = OwnedRestQuery.ownedRows("user_decision", DECISION_SELECT, userId)
                    + "&recommendation_id=eq." + recommendationId;
            method = "PATCH";
        }
        return parseDecision(single(requestArray(method, path, body, snapshot, "return=representation"),
                "Decision could not be saved"));
    }

    public void deleteDecision(long id) throws ApiException {
        validateId(id, "decision");
        SessionSnapshot snapshot = requireSessionSnapshot();
        long userId = requireUserId(snapshot);
        String path = OwnedRestQuery.ownedRecord("user_decision", "decision_id::text", userId,
                "decision_id", id);
        requireOne(requestArray("DELETE", path, null, snapshot, "return=representation"),
                "Decision is no longer available");
    }

    private UserProfile loadProfile(SessionSnapshot snapshot) throws ApiException {
        synchronized (authLock) {
            if (cachedProfile != null && cachedProfile.getAuthUserId().equals(snapshot.session.getAuthUserId())) {
                return cachedProfile;
            }
        }
        String path = "/rest/v1/users?select=" + PROFILE_SELECT + "&auth_user_id=eq."
                + queryValue(snapshot.session.getAuthUserId()) + "&limit=1";
        JSONArray rows = requestArray("GET", path, null, snapshot, null);
        return rows.length() == 0 ? null : parseProfile(rows.optJSONObject(0));
    }

    private long requireUserId(SessionSnapshot snapshot) throws ApiException {
        UserProfile profile = loadProfile(snapshot);
        if (profile == null) throw profileRequired();
        return profile.getUserId();
    }

    private Recommendation loadRecommendation(SessionSnapshot snapshot, long userId, long recommendationId)
            throws ApiException {
        String path = OwnedRestQuery.ownedRecord("recommendations", RECOMMENDATION_SELECT, userId,
                "recommendation_id", recommendationId) + "&limit=1";
        JSONArray rows = requestArray("GET", path, null, snapshot, null);
        return rows.length() == 0 ? null : parseRecommendation(rows.optJSONObject(0));
    }

    private UserDecision loadDecision(SessionSnapshot snapshot, long userId, long recommendationId)
            throws ApiException {
        String path = OwnedRestQuery.ownedRows("user_decision", DECISION_SELECT, userId)
                + "&recommendation_id=eq." + recommendationId + "&limit=1";
        JSONArray rows = requestArray("GET", path, null, snapshot, null);
        return rows.length() == 0 ? null : parseDecision(row(rows, 0));
    }

    private JSONArray requestArray(String method, String path, JSONObject body, SessionSnapshot snapshot,
                                   String prefer) throws ApiException {
        HttpTransport.Response response;
        try {
            response = "GET".equals(method)
                    ? send(method, path, body, snapshot.session, prefer)
                    : sendMutation(method, path, body, snapshot, prefer);
            if (response.statusCode == 401 && "GET".equals(method)) {
                SessionSnapshot refreshed = refreshAfterUnauthorized(snapshot);
                snapshot.session = refreshed.session;
                snapshot.generation = refreshed.generation;
                response = send(method, path, body, snapshot.session, prefer);
            } else if (response.statusCode == 401) {
                // A write is never replayed automatically. Refresh once so later reads use a valid token,
                // then tell the caller that this operation was rejected and may be explicitly retried.
                refreshAfterUnauthorized(snapshot);
                throw retryRequired();
            }
        } catch (IOException exception) {
            throw networkFailure(exception);
        }
        if (!isSuccess(response.statusCode)) throw responseError(response, false, method);
        try {
            return new JSONArray(response.body);
        } catch (JSONException exception) {
            throw dataFormat("The service returned an invalid response", exception);
        }
    }

    private HttpTransport.Response sendMutation(String method, String path, JSONObject body,
                                                SessionSnapshot snapshot, String prefer)
            throws IOException, ApiException {
        synchronized (authLock) {
            Session current = currentSessionLocked();
            if (current == null || !current.getAuthUserId().equals(snapshot.session.getAuthUserId())) {
                throw authRequired();
            }
            snapshot.session = current;
            snapshot.generation = authGeneration;
            return send(method, path, body, current, prefer);
        }
    }

    private JSONObject authObject(String path, String method, JSONObject body, Session session, String operation)
            throws ApiException {
        HttpTransport.Response response;
        try {
            response = send(method, path, body, session, null);
        } catch (IOException exception) {
            throw networkFailure(exception);
        }
        if (!isSuccess(response.statusCode)) throw responseError(response, true, operation);
        try {
            return new JSONObject(response.body);
        } catch (JSONException exception) {
            throw dataFormat("The authentication service returned an invalid response", exception);
        }
    }

    private HttpTransport.Response send(String method, String path, JSONObject body, Session session, String prefer)
            throws IOException, ApiException {
        ensureConfiguration();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("apikey", publishableKey);
        headers.put("Accept", "application/json");
        if (body != null) headers.put("Content-Type", "application/json; charset=utf-8");
        if (session != null) headers.put("Authorization", "Bearer " + session.getAccessToken());
        if (prefer != null) headers.put("Prefer", prefer);
        return transport.execute(method, baseUrl + path, headers, body == null ? null : body.toString());
    }

    private SessionSnapshot refreshAfterUnauthorized(SessionSnapshot failedSnapshot) throws ApiException {
        synchronized (authLock) {
            Session current = currentSessionLocked();
            if (current == null || !current.getAuthUserId().equals(failedSnapshot.session.getAuthUserId())) {
                throw authRequired();
            }
            if (authGeneration != failedSnapshot.generation) {
                if (current.getAccessToken().equals(failedSnapshot.session.getAccessToken())) throw authRequired();
                return new SessionSnapshot(current, authGeneration);
            }
            return new SessionSnapshot(refreshSessionLocked(current), authGeneration);
        }
    }

    private Session currentSessionLocked() throws ApiException {
        Session session = currentSession;
        if (session == null && sessionBlocked) return null;
        if (session == null) session = loadSessionLocked();
        if (session == null) return null;
        long refreshBoundary = clock.instant().getEpochSecond() + SESSION_REFRESH_SKEW_SECONDS;
        if (session.getExpiresAt().toEpochSecond() <= refreshBoundary) {
            return refreshSessionLocked(session);
        }
        currentSession = session;
        return session;
    }

    private Session loadSessionLocked() throws ApiException {
        if (sessionBlocked) return null;
        try {
            currentSession = sessionPersistence.read();
            return currentSession;
        } catch (SessionPersistenceException exception) {
            throw sessionStorage(exception);
        }
    }

    private SessionSnapshot requireSessionSnapshot() throws ApiException {
        synchronized (authLock) {
            Session session = currentSessionLocked();
            if (session == null) throw authRequired();
            return new SessionSnapshot(session, authGeneration);
        }
    }

    private Session refreshSessionLocked(Session session) throws ApiException {
        JSONObject body = new JSONObject();
        put(body, "refresh_token", session.getRefreshToken());
        JSONObject response;
        try {
            response = authObject("/auth/v1/token?grant_type=refresh_token", "POST", body, null, "refresh");
        } catch (ApiException exception) {
            if (exception.getCategory() == ApiException.Category.AUTH_REQUIRED
                    || exception.getCategory() == ApiException.Category.INVALID_CREDENTIALS) {
                try {
                    invalidateSessionLocked();
                } catch (ApiException clearFailure) {
                    clearFailure.addSuppressed(exception);
                    throw clearFailure;
                }
                throw new ApiException(ApiException.Category.AUTH_REQUIRED,
                        "Your sign-in has expired. Please sign in again.", exception.getServerCode(),
                        exception.getHttpStatus(), exception);
            }
            throw exception;
        }
        Session refreshed = parseSession(response, session.getAuthUserId(), session.getEmail());
        saveSessionLocked(refreshed);
        return refreshed;
    }

    private void saveSessionLocked(Session session) throws ApiException {
        try {
            sessionPersistence.write(session);
        } catch (SessionPersistenceException exception) {
            currentSession = null;
            cachedProfile = null;
            sessionBlocked = true;
            authGeneration++;
            try {
                sessionPersistence.clear();
            } catch (SessionPersistenceException clearFailure) {
                exception.addSuppressed(clearFailure);
            }
            throw sessionStorage(exception);
        }
        currentSession = session;
        cachedProfile = null;
        sessionBlocked = false;
        authGeneration++;
    }

    private void invalidateSessionLocked() throws ApiException {
        currentSession = null;
        cachedProfile = null;
        sessionBlocked = true;
        authGeneration++;
        try {
            sessionPersistence.clear();
        } catch (SessionPersistenceException exception) {
            throw sessionStorage(exception);
        }
    }

    private Session parseSession(JSONObject response, String fallbackUserId, String fallbackEmail)
            throws ApiException {
        JSONObject user = response.optJSONObject("user");
        String userId = user == null ? fallbackUserId : nullableString(user, "id");
        if (userId == null) userId = nullableString(response, "id");
        if (userId == null) userId = fallbackUserId;
        if (userId == null) throw dataFormat("Authentication response did not identify the user", null);
        try {
            userId = UUID.fromString(userId).toString();
        } catch (IllegalArgumentException exception) {
            throw dataFormat("Authentication response contained an invalid user ID", exception);
        }
        String email = user == null ? fallbackEmail : nullableString(user, "email");
        if (email == null) email = nullableString(response, "email");
        if (email == null) email = fallbackEmail;
        String accessToken = nullableString(response, "access_token");
        String refreshToken = nullableString(response, "refresh_token");
        if (accessToken == null || refreshToken == null) {
            throw dataFormat("Authentication response did not contain a session", null);
        }
        long expiresAt = response.optLong("expires_at", 0L);
        if (expiresAt <= 0) {
            long expiresIn = response.optLong("expires_in", 3600L);
            expiresAt = clock.instant().getEpochSecond() + Math.max(1L, expiresIn);
        }
        String tokenType = nullableString(response, "token_type");
        if (tokenType == null) tokenType = "bearer";
        try {
            return new Session(accessToken, refreshToken, tokenType, userId, email,
                    OffsetDateTime.ofInstant(Instant.ofEpochSecond(expiresAt), ZoneOffset.UTC));
        } catch (RuntimeException exception) {
            throw dataFormat("Authentication response contained invalid session data", exception);
        }
    }

    private ApiException responseError(HttpTransport.Response response, boolean authRequest, String operation) {
        String code = null;
        String serverMessage = "";
        try {
            JSONObject value = new JSONObject(response.body);
            code = firstNonNull(nullableString(value, "code"), nullableString(value, "error_code"),
                    nullableString(value, "error"));
            serverMessage = firstNonNull(nullableString(value, "msg"), nullableString(value, "message"), "")
                    .toLowerCase(Locale.ROOT);
        } catch (JSONException ignored) {
            // Non-JSON gateway errors are still classified by their HTTP status.
        }
        code = safeDiagnosticCode(code);
        String normalizedCode = code == null ? "" : code.toLowerCase(Locale.ROOT);
        ApiException.Category category;
        String message;

        if (normalizedCode.contains("email_not_confirmed") || serverMessage.contains("email not confirmed")) {
            category = ApiException.Category.EMAIL_CONFIRMATION_REQUIRED;
            message = "Please confirm your email address, then sign in.";
        } else if (normalizedCode.contains("user_already_exists") || "23505".equals(normalizedCode)
                || response.statusCode == 409) {
            category = ApiException.Category.CONFLICT;
            message = "This record already exists. Refresh and try again.";
        } else if (authRequest && "sign-in".equals(operation)
                && (response.statusCode == 400 || response.statusCode == 401
                || normalizedCode.contains("invalid_credentials"))) {
            category = ApiException.Category.INVALID_CREDENTIALS;
            message = "The email address or password is incorrect.";
        } else if (authRequest && "refresh".equals(operation)
                && (response.statusCode == 400 || response.statusCode == 401)) {
            category = ApiException.Category.AUTH_REQUIRED;
            message = "Your sign-in has expired. Please sign in again.";
        } else if (normalizedCode.equals("42501") || response.statusCode == 403) {
            category = ApiException.Category.FORBIDDEN;
            message = "You do not have permission to access this record.";
        } else if (normalizedCode.equals("23503")) {
            category = ApiException.Category.VALIDATION;
            message = "A linked record is unavailable. Review the information and try again.";
        } else if (normalizedCode.equals("23514") || response.statusCode == 400 || response.statusCode == 422) {
            category = ApiException.Category.VALIDATION;
            message = "Check the information and try again.";
        } else if (response.statusCode == 401) {
            category = ApiException.Category.AUTH_REQUIRED;
            message = "Please sign in again.";
        } else if (response.statusCode == 404) {
            category = ApiException.Category.NOT_FOUND;
            message = "This record is no longer available.";
        } else if (response.statusCode == 429 || response.statusCode >= 500) {
            category = ApiException.Category.SERVER;
            message = "The service is busy. Please try again.";
        } else {
            category = ApiException.Category.UNKNOWN;
            message = "The service could not complete this request. Try again.";
        }
        return new ApiException(category, message, code, response.statusCode);
    }

    private JSONObject subscriptionBody(SubscriptionDraft draft, Long userId) throws ApiException {
        JSONObject body = new JSONObject();
        if (userId != null) put(body, "user_id", userId);
        put(body, "provider_id", draft.getProviderId() == null ? JSONObject.NULL : draft.getProviderId());
        put(body, "subscription_name", draft.getName());
        put(body, "plan_name", nullableJsonValue(draft.getPlanName()));
        put(body, "category", draft.getCategory());
        put(body, "subscription_price", jsonMoney(draft.getPrice()));
        put(body, "currency", draft.getCurrency());
        put(body, "billing_cycle", apiValue(draft.getBillingCycle()));
        put(body, "status", apiValue(draft.getStatus()));
        put(body, "usage_level", draft.getUsageLevel() == null ? JSONObject.NULL : apiValue(draft.getUsageLevel()));
        put(body, "importance", apiValue(draft.getImportance()));
        put(body, "auto_renew", draft.isAutoRenew());
        put(body, "start_date", draft.getStartDate().toString());
        put(body, "renewal_date", draft.getRenewalDate().toString());
        return body;
    }

    private JSONObject budgetBody(BudgetDraft draft, Long userId) throws ApiException {
        JSONObject body = new JSONObject();
        if (userId != null) put(body, "user_id", userId);
        put(body, "budget_limit", jsonMoney(draft.getLimitAmount()));
        put(body, "currency", draft.getCurrency());
        put(body, "month", draft.getMonth());
        put(body, "year", draft.getYear());
        return body;
    }

    private JSONObject reminderBody(ReminderDraft draft, Long userId) throws ApiException {
        JSONObject body = new JSONObject();
        if (userId != null) put(body, "user_id", userId);
        put(body, "subscription_id", draft.getSubscriptionId());
        put(body, "title", draft.getTitle());
        put(body, "message", nullableJsonValue(draft.getMessage()));
        put(body, "remind_at", draft.getRemindAt().toString());
        put(body, "status", apiValue(draft.getStatus()));
        return body;
    }

    private static Object nullableJsonValue(String value) {
        return value == null ? JSONObject.NULL : value;
    }

    private static BigDecimal jsonMoney(BigDecimal amount) {
        return new BigDecimal(MoneyCodec.toJsonNumber(amount));
    }

    private List<Subscription> parseSubscriptions(JSONArray rows) throws ApiException {
        List<Subscription> result = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) result.add(parseSubscription(row(rows, i)));
        return Collections.unmodifiableList(result);
    }

    private Subscription parseSubscription(JSONObject row) throws ApiException {
        try {
            return new Subscription(requiredLong(row, "subscription_id"), requiredLong(row, "user_id"),
                    nullableLong(row, "provider_id"), requiredString(row, "subscription_name"),
                    nullableString(row, "plan_name"), requiredString(row, "category"),
                    money(row, "subscription_price"), requiredString(row, "currency"),
                    enumValue(BillingCycle.class, requiredString(row, "billing_cycle"), "billing cycle"),
                    enumValue(SubscriptionStatus.class, requiredString(row, "status"), "subscription status"),
                    nullableEnumValue(UsageLevel.class, nullableString(row, "usage_level"), "usage level"),
                    enumValue(SubscriptionImportance.class, requiredString(row, "importance"), "importance"),
                    requiredBoolean(row, "auto_renew"), LocalDate.parse(requiredString(row, "start_date")),
                    LocalDate.parse(requiredString(row, "renewal_date")),
                    dateTime(row, "created_at"), dateTime(row, "updated_at"));
        } catch (RuntimeException exception) {
            throw dataFormat("The service returned an invalid subscription", exception);
        }
    }

    private List<Budget> parseBudgets(JSONArray rows) throws ApiException {
        List<Budget> result = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) result.add(parseBudget(row(rows, i)));
        return Collections.unmodifiableList(result);
    }

    private Budget parseBudget(JSONObject row) throws ApiException {
        try {
            return new Budget(requiredLong(row, "budget_id"), requiredLong(row, "user_id"),
                    money(row, "budget_limit"), requiredString(row, "currency"),
                    requiredInt(row, "month"), requiredInt(row, "year"));
        } catch (RuntimeException exception) {
            throw dataFormat("The service returned an invalid budget", exception);
        }
    }

    private List<Reminder> parseReminders(JSONArray rows) throws ApiException {
        List<Reminder> result = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) result.add(parseReminder(row(rows, i)));
        return Collections.unmodifiableList(result);
    }

    private Reminder parseReminder(JSONObject row) throws ApiException {
        try {
            return new Reminder(requiredLong(row, "reminder_id"), requiredLong(row, "user_id"),
                    requiredLong(row, "subscription_id"), requiredString(row, "title"),
                    nullableString(row, "message"), dateTime(row, "remind_at"),
                    enumValue(ReminderStatus.class, requiredString(row, "status"), "reminder status"),
                    dateTime(row, "created_at"), dateTime(row, "updated_at"));
        } catch (RuntimeException exception) {
            throw dataFormat("The service returned an invalid reminder", exception);
        }
    }

    private List<Recommendation> parseRecommendations(JSONArray rows) throws ApiException {
        List<Recommendation> result = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) result.add(parseRecommendation(row(rows, i)));
        return Collections.unmodifiableList(result);
    }

    private Recommendation parseRecommendation(JSONObject row) throws ApiException {
        try {
            Object evidence = row.opt("evidence");
            String evidenceJson = evidence == null || evidence == JSONObject.NULL ? null : evidence.toString();
            return new Recommendation(requiredLong(row, "recommendation_id"), requiredLong(row, "user_id"),
                    requiredLong(row, "subscription_id"), requiredString(row, "title"),
                    requiredString(row, "reason"),
                    enumValue(RecommendationAction.class, requiredString(row, "recommendation_type"), "recommendation action"),
                    decimal(row, "confidence_score"), evidenceJson, nullableString(row, "model_name"),
                    nullableString(row, "model_version"), money(row, "potential_monthly_saving"),
                    money(row, "potential_annual_saving"), dateTime(row, "created_at"),
                    nullableDateTime(row, "expires_at"));
        } catch (RuntimeException exception) {
            throw dataFormat("The service returned an invalid recommendation", exception);
        }
    }

    private List<UserDecision> parseDecisions(JSONArray rows) throws ApiException {
        List<UserDecision> result = new ArrayList<>(rows.length());
        for (int i = 0; i < rows.length(); i++) result.add(parseDecision(row(rows, i)));
        return Collections.unmodifiableList(result);
    }

    private UserDecision parseDecision(JSONObject row) throws ApiException {
        try {
            return new UserDecision(requiredLong(row, "decision_id"), requiredLong(row, "user_id"),
                    requiredLong(row, "recommendation_id"),
                    enumValue(DecisionStatus.class, requiredString(row, "decision"), "decision"),
                    dateTime(row, "decision_date"), requiredString(row, "title"));
        } catch (RuntimeException exception) {
            throw dataFormat("The service returned an invalid decision", exception);
        }
    }

    private UserProfile parseProfile(JSONObject row) throws ApiException {
        try {
            String authUserId = requiredString(row, "auth_user_id");
            UUID.fromString(authUserId);
            return new UserProfile(requiredLong(row, "user_id"), authUserId,
                    requiredString(row, "first_name"), requiredString(row, "last_name"),
                    requiredString(row, "email"), requiredString(row, "user_type"),
                    dateTime(row, "created_at"), dateTime(row, "updated_at"));
        } catch (RuntimeException exception) {
            throw dataFormat("The service returned an invalid profile", exception);
        }
    }

    private static JSONObject row(JSONArray rows, int index) throws ApiException {
        JSONObject value = rows.optJSONObject(index);
        if (value == null) throw dataFormat("The service returned an invalid record list", null);
        return value;
    }

    private static JSONObject single(JSONArray rows, String emptyMessage) throws ApiException {
        if (rows.length() == 0) throw notFound(emptyMessage);
        if (rows.length() != 1) throw dataFormat("The service returned more than one record", null);
        return row(rows, 0);
    }

    private static void requireOne(JSONArray rows, String emptyMessage) throws ApiException {
        if (rows.length() == 0) throw notFound(emptyMessage);
        if (rows.length() != 1) throw dataFormat("The service returned an unexpected number of records", null);
    }

    private static String requiredString(JSONObject row, String field) throws ApiException {
        Object value;
        try {
            value = row.get(field);
        } catch (JSONException exception) {
            throw dataFormat("The service response is missing a required field", exception);
        }
        if (value == null || value == JSONObject.NULL) throw dataFormat("The service response is missing a required field", null);
        return value.toString();
    }

    private static String nullableString(JSONObject row, String field) {
        Object value = row.opt(field);
        return value == null || value == JSONObject.NULL ? null : value.toString();
    }

    private static long requiredLong(JSONObject row, String field) throws ApiException {
        String value = requiredString(row, field);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw dataFormat("The service returned an invalid identifier", exception);
        }
    }

    private static Long nullableLong(JSONObject row, String field) throws ApiException {
        String value = nullableString(row, field);
        if (value == null) return null;
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw dataFormat("The service returned an invalid identifier", exception);
        }
    }

    private static int requiredInt(JSONObject row, String field) throws ApiException {
        String value = requiredString(row, field);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw dataFormat("The service returned an invalid number", exception);
        }
    }

    private static boolean requiredBoolean(JSONObject row, String field) throws ApiException {
        Object value = row.opt(field);
        if (!(value instanceof Boolean)) throw dataFormat("The service returned an invalid boolean", null);
        return (Boolean) value;
    }

    private static BigDecimal money(JSONObject row, String field) throws ApiException {
        try {
            return MoneyCodec.fromText(requiredString(row, field));
        } catch (IllegalArgumentException exception) {
            throw dataFormat("The service returned an invalid money amount", exception);
        }
    }

    private static BigDecimal decimal(JSONObject row, String field) throws ApiException {
        try {
            return new BigDecimal(requiredString(row, field));
        } catch (NumberFormatException exception) {
            throw dataFormat("The service returned an invalid decimal amount", exception);
        }
    }

    private static OffsetDateTime dateTime(JSONObject row, String field) throws ApiException {
        try {
            return OffsetDateTime.parse(requiredString(row, field));
        } catch (RuntimeException exception) {
            throw dataFormat("The service returned an invalid timestamp", exception);
        }
    }

    private static OffsetDateTime nullableDateTime(JSONObject row, String field) throws ApiException {
        String value = nullableString(row, field);
        if (value == null) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException exception) {
            throw dataFormat("The service returned an invalid timestamp", exception);
        }
    }

    private static <T extends Enum<T>> T enumValue(Class<T> enumType, String value, String description)
            throws ApiException {
        try {
            return Enum.valueOf(enumType, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw dataFormat("The service returned an invalid " + description, exception);
        }
    }

    private static <T extends Enum<T>> T nullableEnumValue(Class<T> enumType, String value, String description)
            throws ApiException {
        return value == null ? null : enumValue(enumType, value, description);
    }

    private static String apiValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static void put(JSONObject target, String key, Object value) throws ApiException {
        try {
            target.put(key, value);
        } catch (JSONException exception) {
            throw dataFormat("Could not encode request data", exception);
        }
    }

    private void ensureConfiguration() throws ApiException {
        if (baseUrl.isEmpty() || !baseUrl.startsWith("https://") || publishableKey.isEmpty()
                || publishableKey.startsWith("sb_secret_")) {
            throw new ApiException(ApiException.Category.CONFIGURATION,
                    "The service is not configured yet.", "CONFIGURATION", 0);
        }
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
        return normalized;
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private static String requiredInput(String value, String name) throws ApiException {
        if (value == null || value.trim().isEmpty()) {
            throw new ApiException(ApiException.Category.VALIDATION, name + " is required.", null, 0);
        }
        return value.trim();
    }

    private static String requiredPassword(String value) throws ApiException {
        if (value == null || value.isEmpty()) {
            throw new ApiException(ApiException.Category.VALIDATION, "Password is required.", null, 0);
        }
        return value;
    }

    private static String profileName(String value, String label) throws ApiException {
        String clean = requiredInput(value, label);
        if (clean.length() > 50) {
            throw new ApiException(ApiException.Category.VALIDATION, label + " is too long.", null, 0);
        }
        return clean;
    }

    private static void validateId(long id, String label) throws ApiException {
        if (id <= 0) throw new ApiException(ApiException.Category.VALIDATION,
                "Invalid " + label + " identifier.", null, 0);
    }

    private static void validatePage(int limit, int offset) throws ApiException {
        if (limit < 1 || limit > MAX_PAGE_SIZE || offset < 0) {
            throw new ApiException(ApiException.Category.VALIDATION,
                    "Choose a valid page size and position.", null, 0);
        }
    }

    private static String queryValue(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new IllegalStateException("UTF-8 is unavailable", impossible);
        }
    }

    private static String firstNonNull(String first, String second, String fallback) {
        if (first != null && !first.isEmpty()) return first;
        if (second != null && !second.isEmpty()) return second;
        return fallback;
    }

    private static String safeDiagnosticCode(String code) {
        if (code == null || !code.matches("[A-Za-z0-9_.-]{1,80}")) return null;
        return code;
    }

    private static boolean sameUser(Session first, Session second) {
        return first != null && second != null && first.getAuthUserId().equals(second.getAuthUserId());
    }

    private static ApiException authRequired() {
        return new ApiException(ApiException.Category.AUTH_REQUIRED, "Please sign in to continue.", null, 401);
    }

    private static ApiException retryRequired() {
        return new ApiException(ApiException.Category.RETRY_REQUIRED,
                "Your sign-in was renewed. This action was not submitted. Please retry it.",
                "AUTH_TOKEN_RENEWED", 401);
    }

    private static ApiException profileRequired() {
        return new ApiException(ApiException.Category.PROFILE_REQUIRED,
                "Complete your profile to continue.", "PROFILE_REQUIRED", 404);
    }

    private static ApiException notFound(String message) {
        return new ApiException(ApiException.Category.NOT_FOUND, message, "ROW_NOT_FOUND", 404);
    }

    private static ApiException dataFormat(String message, Throwable cause) {
        return new ApiException(ApiException.Category.DATA_FORMAT, message, "INVALID_RESPONSE", 0, cause);
    }

    private static ApiException sessionStorage(Throwable cause) {
        return new ApiException(ApiException.Category.SESSION_STORAGE,
                "Saved sign-in is unavailable. Please sign in again.", "SESSION_STORAGE", 0, cause);
    }

    private static ApiException networkFailure(IOException cause) {
        return new ApiException(ApiException.Category.NETWORK,
                "Unable to reach the service. Check your connection and retry.", "NETWORK", 0, cause);
    }

    private static final class SessionSnapshot {
        private Session session;
        private long generation;

        private SessionSnapshot(Session session, long generation) {
            this.session = session;
            this.generation = generation;
        }
    }
}
