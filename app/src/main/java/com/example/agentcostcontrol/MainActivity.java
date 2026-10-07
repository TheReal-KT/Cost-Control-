package com.example.agentcostcontrol;

import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.example.agentcostcontrol.data.ApiException;
import com.example.agentcostcontrol.data.SupabaseRepository;
import com.example.agentcostcontrol.domain.FinancialSummary;
import com.example.agentcostcontrol.domain.FinancialSummaryCalculator;
import com.example.agentcostcontrol.model.Budget;
import com.example.agentcostcontrol.model.BudgetDraft;
import com.example.agentcostcontrol.model.DecisionStatus;
import com.example.agentcostcontrol.model.Recommendation;
import com.example.agentcostcontrol.model.Reminder;
import com.example.agentcostcontrol.model.ReminderDraft;
import com.example.agentcostcontrol.model.ReminderStatus;
import com.example.agentcostcontrol.model.Session;
import com.example.agentcostcontrol.model.Subscription;
import com.example.agentcostcontrol.model.SubscriptionDraft;
import com.example.agentcostcontrol.model.UserDecision;
import com.example.agentcostcontrol.model.UserProfile;
import com.example.agentcostcontrol.ui.AiChatView;
import com.example.agentcostcontrol.ui.AccountDataViewModel;
import com.example.agentcostcontrol.ui.AuthScreen;
import com.example.agentcostcontrol.ui.BudgetForm;
import com.example.agentcostcontrol.ui.HomeScreen;
import com.example.agentcostcontrol.ui.InsightsScreen;
import com.example.agentcostcontrol.ui.MoneyText;
import com.example.agentcostcontrol.ui.ProfileScreen;
import com.example.agentcostcontrol.ui.RiskChecksView;
import com.example.agentcostcontrol.ui.ReminderForm;
import com.example.agentcostcontrol.ui.RemindersScreen;
import com.example.agentcostcontrol.ui.ServicesScreen;
import com.example.agentcostcontrol.ui.SubscriptionForm;
import com.example.agentcostcontrol.ui.Ui;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.YearMonth;

/** Coordinates authenticated roots and short CRUD routes; all repository calls run off the UI thread. */
public class MainActivity extends AppCompatActivity {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_SUMMARY_PAGES = 10;
    private static final String STATE_ROOT = "root_screen";
    private static final String STATE_STACK_TYPES = "route_types";
    private static final String STATE_STACK_IDS = "route_ids";
    private static final String STATE_STACK_RELATED_IDS = "route_related_ids";
    private static final String STATE_AUTH_MODE = "auth_mode";
    private static final String STATE_AUTH_MESSAGE = "auth_message";
    private static final String STATE_AUTH_FORM = "auth_form";
    private static final String STATE_FORM = "form_draft";
    private static final String STATE_SERVICE_QUERY = "service_query";
    private static final String STATE_SERVICE_FILTER = "service_filter";
    private static final String STATE_REMINDER_FILTER = "reminder_filter";
    private static final String STATE_AI_SUBVIEW = "ai_subview";
    private static final String STATE_SCROLL_POSITIONS = "root_scroll_positions";
    private static final String STATE_CHAT = "chat_view_state";
    private static final String STATE_DECISION_IDS = "decision_ids";
    private static final String STATE_DECISION_VALUES = "decision_values";
    private static final String STATE_AUTH_USER_ID = "protected_state_auth_user_id";
    private static final String STATE_WRITE_ROUTE = "write_route_in_flight";
    private static final String STATE_UNCERTAIN_FORM = "uncertain_write_form";
    private static final String STATE_UNCERTAIN_FORM_ROUTE = "uncertain_write_form_route";
    private static final String STATE_UNCERTAIN_FORM_ID = "uncertain_write_form_id";
    private static final String STATE_UNCERTAIN_FORM_RELATED_ID = "uncertain_write_form_related_id";

    private enum RootScreen {
        HOME(R.id.nav_dashboard, "Home"),
        SERVICES(R.id.nav_subscriptions, "Services"),
        REMINDERS(R.id.nav_reminders, "Reminders"),
        AI(R.id.nav_ai, "AI"),
        PROFILE(R.id.nav_profile, "Profile");

        final int navigationId;
        final String title;

        RootScreen(int navigationId, String title) {
            this.navigationId = navigationId;
            this.title = title;
        }
    }

    private enum RouteType {
        SERVICE_DETAIL,
        SUBSCRIPTION_FORM,
        REMINDER_DETAIL,
        REMINDER_FORM,
        BUDGET_FORM,
        INSIGHT_DETAIL
    }

    private enum AiSubview { CHAT, INSIGHTS }

    private static final class Route {
        final RouteType type;
        final long id;
        final long relatedId;

        Route(RouteType type, long id, long relatedId) {
            this.type = type;
            this.id = id;
            this.relatedId = relatedId;
        }
    }

    private static final class ScreenData {
        List<Subscription> subscriptions = Collections.emptyList();
        List<Reminder> reminders = Collections.emptyList();
        List<Budget> budgets = Collections.emptyList();
        List<Recommendation> recommendations = Collections.emptyList();
        DecisionLookup decisionLookup;
        UserProfile profile;
        Session refreshedSession;
        boolean subscriptionsComplete = true;
        boolean hasMoreSubscriptions;
        boolean hasMoreReminders;
        boolean hasMoreBudgets;
        boolean hasMoreRecommendations;
    }

    private static final class DecisionLookup {
        final long recommendationId;
        final UserDecision decision;

        DecisionLookup(long recommendationId, UserDecision decision) {
            this.recommendationId = recommendationId;
            this.decision = decision;
        }
    }

    private static final class MoreData {
        List<Subscription> subscriptions;
        List<Reminder> reminders;
        List<Budget> budgets;
        List<Recommendation> recommendations;
    }

    private final ArrayList<Route> routes = new ArrayList<>();
    private final EnumMap<RootScreen, Integer> rootScrollPositions = new EnumMap<>(RootScreen.class);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService dataWorker = Executors.newSingleThreadExecutor();

    private SupabaseRepository repository;
    private Session session;
    private UserProfile profile;
    private RootScreen rootScreen = RootScreen.HOME;
    private AiSubview aiSubview = AiSubview.CHAT;
    private AuthScreen.Mode authMode;
    private String authMessage = "";
    private String serviceQuery = "";
    private String serviceFilter = "All";
    private String reminderFilter = "Upcoming";
    private Bundle authFormState;
    private Bundle formState;
    private Bundle chatState;
    private String protectedStateAuthUserId;
    private String pendingRootMessage;
    private int loadGeneration;
    private int navigationGeneration;
    private boolean loadingMore;
    private RouteType writeInFlightRoute;
    private RouteType uncertainWriteRoute;
    private boolean uncertainWriteDialogVisible;
    private boolean uncertainListChecked;
    private Bundle suspendedFormState;
    private RouteType suspendedFormRoute;
    private long suspendedFormId = -1;
    private long suspendedFormRelatedId = -1;

    private FrameLayout screenContainer;
    private TextView screenTitle;
    private BottomNavigationView bottomNavigation;
    private View currentContent;
    private AuthScreen authScreen;
    private SubscriptionForm subscriptionForm;
    private ReminderForm reminderForm;
    private BudgetForm budgetForm;
    private MaterialButton saveButton;
    private MaterialButton cancelButton;
    private MaterialButtonToggleGroup aiTabs;
    private FrameLayout aiContent;
    private LinearLayout aiRootView;
    private AiChatView aiChatView;
    private ScreenData currentData = new ScreenData();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(Ui.color(this, R.color.surface));
        boolean lightNavigationBar = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1;
        getWindow().setNavigationBarColor(Ui.color(this,
                lightNavigationBar ? R.color.surface : R.color.text_primary));
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(true);
        if (lightNavigationBar) {
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                    .setAppearanceLightNavigationBars(true);
        }
        setContentView(R.layout.activity_main);
        repository = new ViewModelProvider(this).get(AccountDataViewModel.class).getRepository();

        screenContainer = findViewById(R.id.screen_container);
        screenTitle = findViewById(R.id.screen_title);
        bottomNavigation = findViewById(R.id.bottom_navigation);
        bottomNavigation.setOnItemSelectedListener(item -> {
            RootScreen destination = rootForNavigation(item.getItemId());
            if (destination == rootScreen && routes.isEmpty() && authMode == null) return true;
            requestRoot(destination);
            return true;
        });
        applyInsets();

        if (savedInstanceState != null) restoreActivityState(savedInstanceState);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { navigateBack(); }
        });
        restoreAuthentication();
    }

    private void applyInsets() {
        View root = findViewById(R.id.app_root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            boolean imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime());
            view.setPadding(0, bars.top, 0, Math.max(bars.bottom, imeVisible ? ime.bottom : 0));
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    private void restoreActivityState(Bundle state) {
        protectedStateAuthUserId = state.getString(STATE_AUTH_USER_ID);
        String writeRouteName = state.getString(STATE_WRITE_ROUTE);
        if (writeRouteName != null) {
            try { uncertainWriteRoute = RouteType.valueOf(writeRouteName); }
            catch (IllegalArgumentException ignored) { uncertainWriteRoute = null; }
        }
        suspendedFormState = state.getBundle(STATE_UNCERTAIN_FORM);
        String suspendedRouteName = state.getString(STATE_UNCERTAIN_FORM_ROUTE);
        if (suspendedRouteName != null) {
            try { suspendedFormRoute = RouteType.valueOf(suspendedRouteName); }
            catch (IllegalArgumentException ignored) { suspendedFormRoute = null; }
        }
        suspendedFormId = state.getLong(STATE_UNCERTAIN_FORM_ID, -1);
        suspendedFormRelatedId = state.getLong(STATE_UNCERTAIN_FORM_RELATED_ID, -1);
        // Activity recreation invalidates any prior list check; protected data must be fetched again.
        uncertainListChecked = false;
        RootScreen restoredRoot = rootFromName(state.getString(STATE_ROOT));
        rootScreen = restoredRoot == null ? RootScreen.HOME : restoredRoot;
        serviceQuery = state.getString(STATE_SERVICE_QUERY, "");
        serviceFilter = state.getString(STATE_SERVICE_FILTER, "All");
        reminderFilter = state.getString(STATE_REMINDER_FILTER, "Upcoming");
        aiSubview = "INSIGHTS".equals(state.getString(STATE_AI_SUBVIEW)) ? AiSubview.INSIGHTS : AiSubview.CHAT;
        authMessage = state.getString(STATE_AUTH_MESSAGE, "");
        String savedAuthMode = state.getString(STATE_AUTH_MODE);
        if (savedAuthMode != null) {
            try { authMode = AuthScreen.Mode.valueOf(savedAuthMode); }
            catch (IllegalArgumentException ignored) { authMode = AuthScreen.Mode.SIGN_IN; }
        }
        authFormState = state.getBundle(STATE_AUTH_FORM);
        formState = state.getBundle(STATE_FORM);
        chatState = state.getBundle(STATE_CHAT);
        int[] positions = state.getIntArray(STATE_SCROLL_POSITIONS);
        if (positions != null) {
            RootScreen[] roots = RootScreen.values();
            for (int i = 0; i < roots.length && i < positions.length; i++) rootScrollPositions.put(roots[i], positions[i]);
        }

        ArrayList<String> routeNames = state.getStringArrayList(STATE_STACK_TYPES);
        long[] routeIds = state.getLongArray(STATE_STACK_IDS);
        long[] relatedIds = state.getLongArray(STATE_STACK_RELATED_IDS);
        if (routeNames != null) {
            for (int i = 0; i < routeNames.size(); i++) {
                try {
                    RouteType type = RouteType.valueOf(routeNames.get(i));
                    routes.add(new Route(type, routeIds != null && i < routeIds.length ? routeIds[i] : -1,
                            relatedIds != null && i < relatedIds.length ? relatedIds[i] : -1));
                } catch (IllegalArgumentException ignored) {
                    routes.clear();
                    break;
                }
            }
        }
        long[] decisionIds = state.getLongArray(STATE_DECISION_IDS);
        String[] decisionValues = state.getStringArray(STATE_DECISION_VALUES);
        if (decisionIds != null && decisionValues != null) {
            for (int i = 0; i < decisionIds.length && i < decisionValues.length; i++) {
                // Decision values are reloaded for a visible detail route when possible.
                recordedDecisionNames.put(decisionIds[i], decisionValues[i]);
            }
        }
    }

    private final Map<Long, String> recordedDecisionNames = new HashMap<>();

    private void restoreAuthentication() {
        showLoading("Checking sign in");
        int request = ++loadGeneration;
        dataWorker.execute(() -> {
            Session restored = null;
            UserProfile restoredProfile = null;
            ApiException failure = null;
            try {
                restored = repository.restoreSession();
                if (restored != null) {
                    try {
                        restoredProfile = repository.getCurrentProfile();
                    } catch (ApiException profileError) {
                        if (profileError.getCategory() == ApiException.Category.AUTH_REQUIRED) {
                            restored = repository.refreshSession();
                            restoredProfile = repository.getCurrentProfile();
                        } else if (profileError.getCategory() != ApiException.Category.PROFILE_REQUIRED) {
                            throw profileError;
                        }
                    }
                }
            } catch (ApiException error) {
                failure = error;
            }
            Session finalRestored = restored;
            UserProfile finalProfile = restoredProfile;
            ApiException finalFailure = failure;
            mainHandler.post(() -> {
                if (isFinishing() || request != loadGeneration) return;
                if (finalRestored == null) {
                    session = null;
                    profile = null;
                    if (finalFailure != null && finalFailure.getCategory() == ApiException.Category.AUTH_REQUIRED) {
                        clearProtectedState();
                    }
                    if (authMode == null || authMode == AuthScreen.Mode.PROFILE_SETUP) authMode = AuthScreen.Mode.SIGN_IN;
                    authMessage = finalFailure == null ? "" : finalFailure.getUserMessage();
                    renderAuthentication();
                    return;
                }
                if (finalFailure != null && finalFailure.getCategory() == ApiException.Category.AUTH_REQUIRED) {
                    clearProtectedState();
                    session = null;
                    profile = null;
                    authMode = AuthScreen.Mode.SIGN_IN;
                    authMessage = finalFailure.getUserMessage();
                    renderAuthentication();
                    return;
                }
                if (protectedStateAuthUserId == null
                        || !protectedStateAuthUserId.equals(finalRestored.getAuthUserId())) {
                    clearProtectedState();
                }
                protectedStateAuthUserId = finalRestored.getAuthUserId();
                session = finalRestored;
                profile = finalProfile;
                if (finalFailure != null) {
                    session = null;
                    authMode = AuthScreen.Mode.SIGN_IN;
                    authMessage = finalFailure.getUserMessage();
                    renderAuthentication();
                    return;
                }
                if (profile == null) {
                    authMode = AuthScreen.Mode.PROFILE_SETUP;
                    authMessage = "Complete your profile to continue.";
                    renderAuthentication();
                    return;
                }
                authMode = null;
                authMessage = "";
                renderCurrentRoute();
            });
        });
    }

    private void renderAuthentication() {
        navigationGeneration++;
        authScreen = null;
        clearFormViews();
        setChrome(authTitle(), false);
        AuthScreen.Mode mode = authMode == null ? AuthScreen.Mode.SIGN_IN : authMode;
        authScreen = new AuthScreen(this, mode, authMessage, authFormState, new AuthScreen.Actions() {
            @Override public void onSignIn(String email, String password) { signIn(email, password); }
            @Override public void onSignUp(String email, String password, String firstName, String lastName) {
                signUp(email, password, firstName, lastName);
            }
            @Override public void onProfileSetup(String firstName, String lastName) {
                completeProfileSetup(firstName, lastName);
            }
            @Override public void onOpenSignIn() {
                saveAuthState();
                authMode = AuthScreen.Mode.SIGN_IN;
                authMessage = "";
                authFormState = null;
                renderAuthentication();
            }
            @Override public void onOpenCreateAccount() {
                saveAuthState();
                authMode = AuthScreen.Mode.CREATE_ACCOUNT;
                authMessage = "";
                authFormState = null;
                renderAuthentication();
            }
        });
        attach(authScreen);
    }

    private void signIn(String email, String password) {
        setAuthBusy("Signing in");
        final int navigation = navigationGeneration;
        dataWorker.execute(() -> {
            Session signedIn = null;
            UserProfile loadedProfile = null;
            ApiException failure = null;
            try {
                signedIn = repository.signIn(email, password);
                try {
                    loadedProfile = repository.getCurrentProfile();
                } catch (ApiException profileError) {
                    if (profileError.getCategory() != ApiException.Category.PROFILE_REQUIRED) throw profileError;
                }
            } catch (ApiException error) {
                failure = error;
            }
            Session resultSession = signedIn;
            UserProfile resultProfile = loadedProfile;
            ApiException resultFailure = failure;
            mainHandler.post(() -> {
                if (navigation != navigationGeneration || isFinishing()) return;
                if (resultFailure != null) {
                    setAuthError(resultFailure.getUserMessage());
                } else if (resultSession == null) {
                    setAuthError("The sign-in session was not returned. Try again.");
                } else if (resultProfile == null) {
                    session = resultSession;
                    profile = null;
                    authMode = AuthScreen.Mode.PROFILE_SETUP;
                    authMessage = "Finish setting up your account.";
                    authFormState = null;
                    renderAuthentication();
                } else {
                    enterSignedInApp(resultSession, resultProfile);
                }
            });
        });
    }

    private void signUp(String email, String password, String firstName, String lastName) {
        setAuthBusy("Creating account");
        final int navigation = navigationGeneration;
        dataWorker.execute(() -> {
            com.example.agentcostcontrol.model.SignUpResult result = null;
            UserProfile loadedProfile = null;
            ApiException failure = null;
            try {
                result = repository.signUp(email, password, firstName, lastName);
                if (result.getSession() != null) loadedProfile = repository.getCurrentProfile();
            } catch (ApiException error) {
                failure = error;
            }
            com.example.agentcostcontrol.model.SignUpResult finalResult = result;
            UserProfile finalProfile = loadedProfile;
            ApiException finalFailure = failure;
            mainHandler.post(() -> {
                if (navigation != navigationGeneration || isFinishing()) return;
                if (finalFailure != null) {
                    setAuthError(finalFailure.getUserMessage());
                } else if (finalResult == null) {
                    setAuthError("The account could not be created. Try again.");
                } else if (finalResult.getSession() == null) {
                    authMode = AuthScreen.Mode.SIGN_IN;
                    authMessage = "Check your email to verify your account, then sign in.";
                    authFormState = new Bundle();
                    authFormState.putString("email", email);
                    renderAuthentication();
                } else if (finalProfile == null) {
                    session = finalResult.getSession();
                    authMode = AuthScreen.Mode.PROFILE_SETUP;
                    authMessage = "Finish setting up your account.";
                    authFormState = null;
                    renderAuthentication();
                } else {
                    enterSignedInApp(finalResult.getSession(), finalProfile);
                }
            });
        });
    }

    private void completeProfileSetup(String firstName, String lastName) {
        setAuthBusy("Saving profile");
        final int navigation = navigationGeneration;
        dataWorker.execute(() -> {
            UserProfile completedProfile = null;
            ApiException failure = null;
            try { completedProfile = repository.completeProfileSetup(firstName, lastName); }
            catch (ApiException error) { failure = error; }
            UserProfile resultProfile = completedProfile;
            ApiException resultFailure = failure;
            mainHandler.post(() -> {
                if (navigation != navigationGeneration || isFinishing()) return;
                if (resultFailure != null) setAuthError(resultFailure.getUserMessage());
                else enterSignedInApp(session, resultProfile);
            });
        });
    }

    private void enterSignedInApp(Session authenticatedSession, UserProfile authenticatedProfile) {
        boolean sameSavedUser = protectedStateAuthUserId != null
                && protectedStateAuthUserId.equals(authenticatedSession.getAuthUserId());
        if (!sameSavedUser) {
            clearProtectedState();
        }
        session = authenticatedSession;
        profile = authenticatedProfile;
        protectedStateAuthUserId = authenticatedSession.getAuthUserId();
        authMode = null;
        authMessage = "";
        authFormState = null;
        if (!sameSavedUser) {
            routes.clear();
            rootScreen = RootScreen.HOME;
            serviceQuery = "";
            serviceFilter = "All";
            reminderFilter = "Upcoming";
            aiSubview = AiSubview.CHAT;
        }
        if (aiChatView != null) aiChatView.setAccessToken(session.getAccessToken());
        renderCurrentRoute();
    }

    private void setAuthBusy(String message) {
        showLoading(message);
    }

    private void setAuthError(String message) {
        authMessage = message == null || message.isEmpty() ? "Please try again." : message;
        if (authScreen != null && authScreen.getMode() == authMode) {
            authScreen.setMessage(authMessage);
            setChrome(authTitle(), false);
            attach(authScreen);
            return;
        }
        authFormState = authScreen == null ? authFormState : authScreen.state();
        renderAuthentication();
    }

    private void saveAuthState() {
        if (authScreen != null) authFormState = authScreen.state();
    }

    private String authTitle() {
        if (authMode == AuthScreen.Mode.CREATE_ACCOUNT) return "Create account";
        if (authMode == AuthScreen.Mode.PROFILE_SETUP) return "Profile setup";
        return "Sign in";
    }

    private void requestRoot(RootScreen destination) {
        if (authMode != null || session == null || writeInFlightRoute != null) return;
        if (isDirtyForm()) {
            askToDiscard(() -> switchRoot(destination));
            return;
        }
        switchRoot(destination);
    }

    private void beginWrite(RouteType routeType) {
        if (uncertainWriteRoute != null) return;
        writeInFlightRoute = routeType;
    }

    private void finishWrite() {
        writeInFlightRoute = null;
    }

    private void failWrite(ApiException error, RouteType routeType) {
        finishWrite();
        if (error.getCategory() == ApiException.Category.AUTH_REQUIRED) {
            saveVisibleDraft();
            if (session != null) protectedStateAuthUserId = session.getAuthUserId();
            session = null;
            profile = null;
            authMode = AuthScreen.Mode.SIGN_IN;
            authMessage = "Your sign-in expired. Sign in again to continue.";
            authFormState = null;
            renderAuthentication();
            return;
        }
        if (error.getCategory() == ApiException.Category.NETWORK
                || error.getCategory() == ApiException.Category.DATA_FORMAT) {
            saveVisibleDraft();
            uncertainWriteRoute = routeType;
            uncertainListChecked = false;
            renderPage(currentData);
            showUncertainWriteDialog();
            return;
        }
        if (routeType == RouteType.SERVICE_DETAIL || routeType == RouteType.REMINDER_DETAIL
                || routeType == RouteType.INSIGHT_DETAIL) {
            renderPage(currentData);
        } else {
            saveVisibleDraft();
        }
        showSnackbar(error.getUserMessage());
    }

    private void showUncertainWriteDialog() {
        if (uncertainWriteRoute == null || uncertainWriteDialogVisible || isFinishing()) return;
        uncertainWriteDialogVisible = true;
        RootScreen targetRoot = targetRootForWrite(uncertainWriteRoute);
        String listTitle = targetRoot.title;
        boolean refreshedListVisible = uncertainListChecked && rootScreen == targetRoot
                && routes.isEmpty() && (targetRoot != RootScreen.AI || aiSubview == AiSubview.INSIGHTS);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        if (!refreshedListVisible) {
            builder.setTitle("Save status unknown")
                    .setMessage("Couldn’t confirm the save. Check " + listTitle + " before retrying.")
                    .setPositiveButton("Check " + listTitle, (ignored, which) -> {
                        RouteType type = uncertainWriteRoute;
                        preserveUncertainForm();
                        uncertainListChecked = false;
                        if (type == RouteType.INSIGHT_DETAIL) aiSubview = AiSubview.INSIGHTS;
                        uncertainWriteDialogVisible = false;
                        switchRoot(targetRootForWrite(type));
                    })
                    .setNegativeButton("Keep form", (ignored, which) -> {
                        uncertainWriteDialogVisible = false;
                        showSnackbar("Resolve this save before retrying.");
                    });
        } else if (hasMoreInRoot(targetRootForWrite(uncertainWriteRoute))) {
            builder.setTitle("Check more items")
                    .setMessage("Load the remaining items before resolving this save.")
                    .setPositiveButton("Load more", (ignored, which) -> {
                        uncertainWriteDialogVisible = false;
                        loadMore(targetRootForWrite(uncertainWriteRoute));
                    })
                    .setNegativeButton("Keep checking", (ignored, which) -> uncertainWriteDialogVisible = false);
        } else {
            builder.setTitle("Was it saved?")
                    .setMessage("Check the refreshed list and details. Retry only if your change is missing.")
                    .setPositiveButton("Already saved", (ignored, which) -> resolveUncertainWrite(true))
                    .setNegativeButton("Not saved, retry", (ignored, which) -> resolveUncertainWrite(false))
                    .setNeutralButton("Inspect list", (ignored, which) -> uncertainWriteDialogVisible = false);
        }
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(ignored -> uncertainWriteDialogVisible = false);
        dialog.show();
    }

    private boolean hasMoreInRoot(RootScreen root) {
        switch (root) {
            case SERVICES: return currentData.hasMoreSubscriptions;
            case REMINDERS: return currentData.hasMoreReminders;
            case PROFILE: return currentData.hasMoreBudgets;
            case AI: return currentData.hasMoreRecommendations;
            default: return false;
        }
    }

    private void resolveUncertainWrite(boolean alreadySaved) {
        uncertainWriteDialogVisible = false;
        uncertainListChecked = false;
        uncertainWriteRoute = null;
        if (alreadySaved) {
            clearSuspendedWrite();
            formState = null;
            showSnackbar("Save confirmed in the refreshed list.");
            return;
        }
        if (suspendedFormRoute != null) {
            rootScreen = targetRootForWrite(suspendedFormRoute);
            routes.clear();
            routes.add(new Route(suspendedFormRoute, suspendedFormId, suspendedFormRelatedId));
            formState = suspendedFormState == null ? null : new Bundle(suspendedFormState);
            if (suspendedFormRoute == RouteType.INSIGHT_DETAIL) aiSubview = AiSubview.INSIGHTS;
            clearSuspendedWrite();
            navigationGeneration++;
            renderCurrentRoute();
            return;
        }
        renderCurrentRoute();
    }

    private void clearSuspendedWrite() {
        suspendedFormState = null;
        suspendedFormRoute = null;
        suspendedFormId = -1;
        suspendedFormRelatedId = -1;
    }

    private RootScreen targetRootForWrite(RouteType routeType) {
        switch (routeType) {
            case SUBSCRIPTION_FORM:
            case SERVICE_DETAIL: return RootScreen.SERVICES;
            case REMINDER_FORM:
            case REMINDER_DETAIL: return RootScreen.REMINDERS;
            case BUDGET_FORM: return RootScreen.PROFILE;
            default: return RootScreen.AI;
        }
    }

    private void preserveUncertainForm() {
        if (suspendedFormRoute != null) return;
        Route route = routes.isEmpty() ? null : routes.get(routes.size() - 1);
        if (route == null) return;
        Bundle draft = null;
        if (route.type == RouteType.SUBSCRIPTION_FORM || route.type == RouteType.REMINDER_FORM
                || route.type == RouteType.BUDGET_FORM) {
            saveVisibleDraft();
            draft = formState == null ? null : new Bundle(formState);
        }
        suspendedFormState = draft;
        suspendedFormRoute = route.type;
        suspendedFormId = route.id;
        suspendedFormRelatedId = route.relatedId;
    }

    private void restoreSuspendedForm(RouteType routeType, long id, long relatedId) {
        if (uncertainWriteRoute != null || uncertainListChecked) return;
        if (suspendedFormState == null || suspendedFormRoute != routeType
                || suspendedFormId != id || suspendedFormRelatedId != relatedId) return;
        formState = new Bundle(suspendedFormState);
        suspendedFormState = null;
        suspendedFormRoute = null;
        suspendedFormId = -1;
        suspendedFormRelatedId = -1;
    }

    private void switchRoot(RootScreen destination) {
        if (uncertainWriteRoute != null) preserveUncertainForm();
        saveCurrentScrollPosition();
        routes.clear();
        formState = null;
        rootScreen = destination;
        navigationGeneration++;
        if (aiChatView != null && session != null) aiChatView.setAccessToken(session.getAccessToken());
        renderCurrentRoute();
    }

    private RootScreen rootForNavigation(int id) {
        for (RootScreen item : RootScreen.values()) if (item.navigationId == id) return item;
        return RootScreen.HOME;
    }

    private void renderCurrentRoute() {
        loadingMore = false;
        if (authMode != null || session == null) {
            if (authMode == null) authMode = AuthScreen.Mode.SIGN_IN;
            renderAuthentication();
            return;
        }
        if (rootScreen == RootScreen.AI && aiSubview == AiSubview.CHAT && routes.isEmpty()) {
            currentData = new ScreenData();
            renderPage(currentData);
            return;
        }
        final int generation = ++loadGeneration;
        final int navigation = navigationGeneration;
        final String authUserId = session.getAuthUserId();
        final RootScreen requestedRoot = rootScreen;
        final boolean includeInsights = requestedRoot == RootScreen.AI
                && (aiSubview == AiSubview.INSIGHTS || containsRoute(RouteType.INSIGHT_DETAIL));
        final Long decisionLookupId = decisionLookupIdForLoad(requestedRoot, includeInsights);
        if (uncertainWriteRoute != null && routes.isEmpty()
                && requestedRoot == targetRootForWrite(uncertainWriteRoute)) {
            uncertainListChecked = false;
        }
        showLoading("Loading");
        dataWorker.execute(() -> {
            ScreenData data;
            try {
                data = loadData(requestedRoot, includeInsights, decisionLookupId);
            } catch (ApiException error) {
                if (error.getCategory() == ApiException.Category.AUTH_REQUIRED) {
                    try {
                        Session refreshed = repository.refreshSession();
                        data = loadData(requestedRoot, includeInsights, decisionLookupId);
                        data.refreshedSession = refreshed;
                    } catch (ApiException refreshError) {
                        postLoadError(generation, navigation, authUserId, refreshError,
                                repository.getCurrentSession());
                        return;
                    }
                } else {
                    postLoadError(generation, navigation, authUserId, error,
                            repository.getCurrentSession());
                    return;
                }
            }
            ScreenData result = data;
            mainHandler.post(() -> {
                if (!isCurrentRequest(generation, navigation, authUserId)) return;
                if (result.refreshedSession != null) {
                    session = result.refreshedSession;
                    if (aiChatView != null) aiChatView.setAccessToken(session.getAccessToken());
                }
                if (result.profile != null) profile = result.profile;
                currentData = result;
                applyDecisionLookup(result.decisionLookup, decisionLookupId);
                if (uncertainWriteRoute != null && routes.isEmpty()
                        && requestedRoot == targetRootForWrite(uncertainWriteRoute)
                        && (requestedRoot != RootScreen.AI || includeInsights)) {
                    uncertainListChecked = true;
                }
                renderPage(result);
            });
        });
    }

    private ScreenData loadData(RootScreen root, boolean includeInsights, Long decisionLookupId)
            throws ApiException {
        ScreenData data = new ScreenData();
        switch (root) {
            case HOME:
                loadBoundedSubscriptions(data);
                data.budgets = loadCurrentPeriodBudgets();
                data.reminders = repository.listReminders(PAGE_SIZE, 0);
                data.hasMoreReminders = data.reminders.size() == PAGE_SIZE;
                break;
            case SERVICES:
                data.subscriptions = repository.listSubscriptions(PAGE_SIZE, 0);
                data.hasMoreSubscriptions = data.subscriptions.size() == PAGE_SIZE;
                break;
            case REMINDERS:
                loadBoundedSubscriptions(data);
                data.reminders = repository.listReminders(PAGE_SIZE, 0);
                data.hasMoreReminders = data.reminders.size() == PAGE_SIZE;
                break;
            case AI:
                if (includeInsights) {
                    loadBoundedSubscriptions(data);
                    data.budgets = loadCurrentPeriodBudgets();
                    data.recommendations = repository.listRecommendations(PAGE_SIZE, 0);
                    data.hasMoreRecommendations = data.recommendations.size() == PAGE_SIZE;
                    if (decisionLookupId != null) {
                        data.decisionLookup = new DecisionLookup(decisionLookupId,
                                repository.getDecision(decisionLookupId));
                    }
                }
                break;
            case PROFILE:
                data.profile = repository.getCurrentProfile();
                data.budgets = repository.listBudgets(PAGE_SIZE, 0);
                data.hasMoreBudgets = data.budgets.size() == PAGE_SIZE;
                break;
        }
        data.refreshedSession = repository.getCurrentSession();
        return data;
    }

    private Long decisionLookupIdForLoad(RootScreen requestedRoot, boolean includeInsights) {
        if (requestedRoot != RootScreen.AI || !includeInsights) return null;
        if (!routes.isEmpty()) {
            Route route = routes.get(routes.size() - 1);
            return route.type == RouteType.INSIGHT_DETAIL ? route.id : null;
        }
        if (uncertainWriteRoute == RouteType.INSIGHT_DETAIL
                && suspendedFormRoute == RouteType.INSIGHT_DETAIL && suspendedFormId > 0) {
            return suspendedFormId;
        }
        return null;
    }

    private void applyDecisionLookup(DecisionLookup lookup, Long expectedRecommendationId) {
        if (lookup == null || expectedRecommendationId == null
                || lookup.recommendationId != expectedRecommendationId) return;
        if (lookup.decision == null) {
            recordedDecisionNames.remove(lookup.recommendationId);
        } else {
            recordedDecisionNames.put(lookup.recommendationId, lookup.decision.getDecision().name());
        }
    }

    private List<Budget> loadCurrentPeriodBudgets() throws ApiException {
        YearMonth currentPeriod = YearMonth.now();
        return repository.listBudgetsForPeriod(currentPeriod.getMonthValue(), currentPeriod.getYear());
    }

    private void loadBoundedSubscriptions(ScreenData data) throws ApiException {
        ArrayList<Subscription> all = new ArrayList<>();
        for (int page = 0; page < MAX_SUMMARY_PAGES; page++) {
            List<Subscription> results = repository.listSubscriptions(PAGE_SIZE, page * PAGE_SIZE);
            all.addAll(results);
            if (results.size() < PAGE_SIZE) {
                data.subscriptions = all;
                return;
            }
        }
        data.subscriptionsComplete = repository.listSubscriptions(1, MAX_SUMMARY_PAGES * PAGE_SIZE).isEmpty();
        data.subscriptions = all;
    }

    private void loadMore(RootScreen expectedRoot) {
        if (loadingMore || rootScreen != expectedRoot || !routes.isEmpty() || session == null) return;
        saveCurrentScrollPosition();
        loadingMore = true;
        showSnackbar("Loading more…");
        final int generation = loadGeneration;
        final int navigation = navigationGeneration;
        final String authUserId = session.getAuthUserId();
        final int offset;
        switch (expectedRoot) {
            case SERVICES: offset = currentData.subscriptions.size(); break;
            case REMINDERS: offset = currentData.reminders.size(); break;
            case AI: offset = currentData.recommendations.size(); break;
            case PROFILE: offset = currentData.budgets.size(); break;
            default:
                loadingMore = false;
                return;
        }
        dataWorker.execute(() -> {
            MoreData more = new MoreData();
            ApiException failure = null;
            try {
                switch (expectedRoot) {
                    case SERVICES:
                        more.subscriptions = repository.listSubscriptions(PAGE_SIZE, offset);
                        break;
                    case REMINDERS:
                        more.reminders = repository.listReminders(PAGE_SIZE, offset);
                        break;
                    case AI:
                        more.recommendations = repository.listRecommendations(PAGE_SIZE, offset);
                        break;
                    case PROFILE:
                        more.budgets = repository.listBudgets(PAGE_SIZE, offset);
                        break;
                    default:
                        return;
                }
            } catch (ApiException error) { failure = error; }
            MoreData resultPage = more;
            ApiException resultFailure = failure;
            Session resultSession = repository.getCurrentSession();
            mainHandler.post(() -> {
                if (!isCurrentRequest(generation, navigation, authUserId)) return;
                syncSessionSnapshot(resultSession, authUserId);
                loadingMore = false;
                if (resultFailure != null) {
                    if (resultFailure.getCategory() == ApiException.Category.AUTH_REQUIRED
                            || resultFailure.getCategory() == ApiException.Category.PROFILE_REQUIRED) {
                        postLoadError(generation, navigation, authUserId, resultFailure, resultSession);
                    } else {
                        showSnackbar(resultFailure.getUserMessage());
                    }
                    return;
                }
                switch (expectedRoot) {
                    case SERVICES:
                        currentData.subscriptions = append(currentData.subscriptions, resultPage.subscriptions);
                        currentData.hasMoreSubscriptions = resultPage.subscriptions.size() == PAGE_SIZE;
                        break;
                    case REMINDERS:
                        currentData.reminders = append(currentData.reminders, resultPage.reminders);
                        currentData.hasMoreReminders = resultPage.reminders.size() == PAGE_SIZE;
                        break;
                    case AI:
                        currentData.recommendations = append(currentData.recommendations, resultPage.recommendations);
                        currentData.hasMoreRecommendations = resultPage.recommendations.size() == PAGE_SIZE;
                        break;
                    case PROFILE:
                        currentData.budgets = append(currentData.budgets, resultPage.budgets);
                        currentData.hasMoreBudgets = resultPage.budgets.size() == PAGE_SIZE;
                        break;
                    default:
                        break;
                }
                renderPage(currentData);
            });
        });
    }

    private static <T> List<T> append(List<T> current, List<T> next) {
        ArrayList<T> combined = new ArrayList<>(current.size() + next.size());
        combined.addAll(current);
        combined.addAll(next);
        return combined;
    }

    private void postLoadError(int generation, int navigation, String authUserId, ApiException error,
                               Session currentSession) {
        mainHandler.post(() -> {
            if (!isCurrentRequest(generation, navigation, authUserId)) return;
            syncSessionSnapshot(currentSession, authUserId);
            if (error.getCategory() == ApiException.Category.AUTH_REQUIRED) {
                session = null;
                profile = null;
                clearProtectedState();
                authMode = AuthScreen.Mode.SIGN_IN;
                authMessage = "Sign in again to continue.";
                renderAuthentication();
            } else if (error.getCategory() == ApiException.Category.PROFILE_REQUIRED) {
                profile = null;
                authMode = AuthScreen.Mode.PROFILE_SETUP;
                authMessage = "Complete your profile to continue.";
                authFormState = null;
                renderAuthentication();
            } else {
                showError("Couldn’t load this screen", error.getUserMessage(), this::renderCurrentRoute);
            }
        });
    }

    private void syncSessionSnapshot(Session currentSession, String expectedAuthUserId) {
        if (currentSession == null || !expectedAuthUserId.equals(currentSession.getAuthUserId())) return;
        session = currentSession;
        if (aiChatView != null) aiChatView.setAccessToken(currentSession.getAccessToken());
    }

    private boolean isCurrentRequest(int generation, int navigation, String authUserId) {
        return !isFinishing() && generation == loadGeneration && navigation == navigationGeneration
                && session != null && authUserId.equals(session.getAuthUserId());
    }

    private boolean containsRoute(RouteType type) {
        for (Route route : routes) if (route.type == type) return true;
        return false;
    }

    private void renderPage(ScreenData data) {
        navigationGeneration++;
        clearFormViews();
        boolean atRoot = routes.isEmpty();
        setChrome(currentTitle(), atRoot);
        bottomNavigation.getMenu().findItem(rootScreen.navigationId).setChecked(true);
        View page;
        if (atRoot) {
            page = renderRoot(data);
        } else {
            page = renderTaskRoute(data, routes.get(routes.size() - 1));
        }
        if (page == null) return;
        attach(page);
        restoreCurrentScrollPosition(page);
        if (pendingRootMessage != null) {
            String message = pendingRootMessage;
            pendingRootMessage = null;
            screenContainer.post(() -> Snackbar.make(screenContainer, message, Snackbar.LENGTH_LONG).show());
        }
        if (uncertainWriteRoute != null && routes.isEmpty()) {
            screenContainer.post(this::showUncertainWriteDialog);
        }
    }

    private View renderRoot(ScreenData data) {
        switch (rootScreen) {
            case HOME:
                FinancialSummary summary = FinancialSummaryCalculator.calculate(data.subscriptions);
                return HomeScreen.create(this, data.subscriptions, data.budgets, data.reminders,
                        summary.getMonthlySpendByCurrency(), summary.getActiveSubscriptionCount(),
                        data.subscriptionsComplete,
                        new HomeScreen.Actions() {
                            @Override public void onAddService() { openSubscriptionForm(null); }
                            @Override public void onAddReminder() { openReminderForm(null, null); }
                            @Override public void onOpenServices() { requestRoot(RootScreen.SERVICES); }
                            @Override public void onOpenReminders() { requestRoot(RootScreen.REMINDERS); }
                            @Override public void onOpenService(Subscription subscription) { openServiceDetail(subscription); }
                            @Override public void onOpenReminder(Reminder reminder) { openReminderDetail(reminder); }
                        });
            case SERVICES:
                return ServicesScreen.create(this, data.subscriptions, serviceQuery, serviceFilter,
                        data.hasMoreSubscriptions,
                        new ServicesScreen.Actions() {
                            @Override public void onAdd() { openSubscriptionForm(null); }
                            @Override public void onOpen(Subscription subscription) { openServiceDetail(subscription); }
                            @Override public void onSearchChanged(String query) { serviceQuery = query; }
                            @Override public void onFilterChanged(String filter) { serviceFilter = filter; }
                            @Override public void onLoadMore() { loadMore(RootScreen.SERVICES); }
                        });
            case REMINDERS:
                return RemindersScreen.list(this, data.reminders, data.subscriptions, reminderFilter,
                        data.hasMoreReminders,
                        new RemindersScreen.Actions() {
                            @Override public void onAdd() { openReminderForm(null, null); }
                            @Override public void onOpen(Reminder reminder) { openReminderDetail(reminder); }
                            @Override public void onFilterChanged(String filter) { reminderFilter = filter; }
                            @Override public void onLoadMore() { loadMore(RootScreen.REMINDERS); }
                        });
            case AI:
                return renderAiRoot(data);
            case PROFILE:
                UserProfile rootProfile = data.profile == null ? profile : data.profile;
                if (rootProfile == null) {
                    showError("Profile unavailable", "Sign in again to continue.", this::restoreAuthentication);
                    return null;
                }
                return ProfileScreen.create(this, rootProfile, data.budgets, data.hasMoreBudgets,
                        new ProfileScreen.Actions() {
                    @Override public void onAddBudget() { openBudgetForm(null); }
                    @Override public void onEditBudget(Budget budget) { openBudgetForm(budget); }
                    @Override public void onLoadMoreBudgets() { loadMore(RootScreen.PROFILE); }
                    @Override public void onSignOut() { confirmSignOut(); }
                });
            default:
                return null;
        }
    }

    private View renderAiRoot(ScreenData data) {
        if (aiRootView == null) createAiRoot();
        if (aiTabs != null) {
            int selectedId = aiSubview == AiSubview.CHAT ? R.id.ai_chat_tab : R.id.ai_insights_tab;
            aiTabs.check(selectedId);
        }
        aiContent.removeAllViews();
        if (aiSubview == AiSubview.CHAT) {
            if (aiChatView == null) {
                aiChatView = new AiChatView(this);
                aiChatView.setAccessToken(session == null ? null : session.getAccessToken());
                aiChatView.restoreState(chatState);
                chatState = null;
            }
            removeFromParent(aiChatView);
            aiContent.addView(aiChatView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            RiskChecksView localChecks = new RiskChecksView(this, session.getAuthUserId(),
                    data.subscriptions, data.budgets, data.subscriptionsComplete,
                    subscriptionId -> {
                        Subscription subscription = findSubscription(data.subscriptions, subscriptionId);
                        if (subscription != null) openServiceDetail(subscription);
                    });
            View insights = InsightsScreen.list(this, localChecks, data.recommendations, data.subscriptions,
                    data.hasMoreRecommendations, new InsightsScreen.Actions() {
                        @Override public void onOpen(Recommendation recommendation) { openInsightDetail(recommendation); }
                        @Override public void onLoadMore() { loadMore(RootScreen.AI); }
                    });
            aiContent.addView(insights, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            int position = rootScrollPositions.getOrDefault(RootScreen.AI, 0);
            insights.post(() -> insights.scrollTo(0, position));
        }
        return aiRootView;
    }

    private void createAiRoot() {
        aiRootView = new LinearLayout(this);
        aiRootView.setOrientation(LinearLayout.VERTICAL);
        aiRootView.setBackgroundColor(Ui.color(this, R.color.surface));

        aiTabs = new MaterialButtonToggleGroup(this);
        aiTabs.setSingleSelection(true);
        aiTabs.setSelectionRequired(true);
        aiTabs.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 8));
        MaterialButton chat = Ui.button(this, "Chat", true);
        chat.setId(R.id.ai_chat_tab);
        MaterialButton insights = Ui.button(this, "Insights", false);
        insights.setId(R.id.ai_insights_tab);
        aiTabs.addView(chat, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        aiTabs.addView(insights, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        aiTabs.check(R.id.ai_chat_tab);
        aiRootView.addView(aiTabs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        aiContent = new FrameLayout(this);
        aiRootView.addView(aiContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        aiTabs.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            AiSubview next = checkedId == R.id.ai_insights_tab ? AiSubview.INSIGHTS : AiSubview.CHAT;
            if (next == aiSubview) return;
            saveCurrentScrollPosition();
            aiSubview = next;
            navigationGeneration++;
            renderCurrentRoute();
        });
    }

    private View renderTaskRoute(ScreenData data, Route route) {
        switch (route.type) {
            case SERVICE_DETAIL: {
                Subscription subscription = findSubscription(data.subscriptions, route.id);
                if (subscription == null) return missingRecord("This service is no longer available.");
                String monthlyEquivalent = MoneyText.format(
                        FinancialSummaryCalculator.monthlyEquivalent(subscription), subscription.getCurrency());
                return ServicesScreen.detail(this, subscription, monthlyEquivalent,
                        new ServicesScreen.DetailActions() {
                            @Override public void onEdit() { openSubscriptionForm(subscription); }
                            @Override public void onAddReminder() { openReminderForm(null, subscription.getId()); }
                            @Override public void onDelete() { confirmDeleteService(subscription); }
                        });
            }
            case SUBSCRIPTION_FORM: {
                Subscription original = route.id < 0 ? null : findSubscription(data.subscriptions, route.id);
                if (route.id >= 0 && original == null) return missingRecord("This service is no longer available.");
                subscriptionForm = new SubscriptionForm(this, original,
                        formState == null ? null : new Bundle(formState));
                formState = null;
                return formWithActions(subscriptionForm, "Save service", () -> saveSubscription(original),
                        "Cancel", this::navigateBack);
            }
            case REMINDER_DETAIL: {
                Reminder reminder = findReminder(data.reminders, route.id);
                if (reminder == null) return missingRecord("This reminder is no longer available.");
                Subscription subscription = findSubscription(data.subscriptions, reminder.getSubscriptionId());
                return RemindersScreen.detail(this, reminder, subscription, new RemindersScreen.DetailActions() {
                    @Override public void onEdit() { openReminderForm(reminder, reminder.getSubscriptionId()); }
                    @Override public void onStatusChange(ReminderStatus status) { updateReminderStatus(reminder, status); }
                    @Override public void onDelete() { confirmDeleteReminder(reminder); }
                });
            }
            case REMINDER_FORM: {
                Reminder original = route.id < 0 ? null : findReminder(data.reminders, route.id);
                if (route.id >= 0 && original == null) return missingRecord("This reminder is no longer available.");
                Long selectedSubscriptionId = route.relatedId < 0 ? null : route.relatedId;
                reminderForm = new ReminderForm(this, data.subscriptions, original, selectedSubscriptionId,
                        formState == null ? null : new Bundle(formState));
                formState = null;
                return formWithActions(reminderForm, "Save reminder", () -> saveReminder(original),
                        "Cancel", this::navigateBack);
            }
            case BUDGET_FORM: {
                Budget original = route.id < 0 ? null : findBudget(data.budgets, route.id);
                if (route.id >= 0 && original == null) return missingRecord("This budget is no longer available.");
                budgetForm = new BudgetForm(this, original, formState == null ? null : new Bundle(formState));
                formState = null;
                return formWithActions(budgetForm, "Save budget", () -> saveBudget(original),
                        "Cancel", this::navigateBack);
            }
            case INSIGHT_DETAIL: {
                Recommendation recommendation = findRecommendation(data.recommendations, route.id);
                if (recommendation == null) return missingRecord("This recommendation is no longer available.");
                Subscription subscription = findSubscription(data.subscriptions, recommendation.getSubscriptionId());
                String decision = recordedDecisionNames.get(recommendation.getId());
                return InsightsScreen.detail(this, recommendation, subscription, decision,
                        new InsightsScreen.DetailActions() {
                            @Override public void onApprove() { saveDecision(recommendation, DecisionStatus.APPROVED); }
                            @Override public void onDismiss() { saveDecision(recommendation, DecisionStatus.IGNORED); }
                        });
            }
            default:
                return null;
        }
    }

    private View formWithActions(View form, String saveLabel, Runnable save, String cancelLabel, Runnable cancel) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setBackgroundColor(Ui.color(this, R.color.surface));
        wrapper.addView(Ui.scroll(this, form), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout actionRow = Ui.row(this);
        actionRow.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 8));
        saveButton = Ui.button(this, saveLabel, true);
        if (uncertainWriteRoute != null) {
            saveButton.setEnabled(false);
            saveButton.setText("Resolve save status");
        }
        cancelButton = Ui.textButton(this, cancelLabel);
        saveButton.setId(R.id.form_save_action);
        cancelButton.setId(R.id.form_cancel_action);
        saveButton.setOnClickListener(view -> save.run());
        cancelButton.setOnClickListener(view -> cancel.run());
        actionRow.addView(saveButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        cancelParams.setMarginStart(Ui.dp(this, 10));
        actionRow.addView(cancelButton, cancelParams);
        wrapper.addView(actionRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrapper;
    }

    private View missingRecord(String message) {
        LinearLayout page = Ui.page(this);
        page.addView(Ui.heading(this, "Record unavailable"));
        page.addView(Ui.secondary(this, message), margin(this, 0, 8, 0, 16));
        MaterialButton back = Ui.textButton(this, "Back");
        back.setOnClickListener(view -> navigateBack());
        page.addView(back);
        return Ui.scroll(this, page);
    }

    private void openServiceDetail(Subscription subscription) {
        saveCurrentScrollPosition();
        formState = null;
        routes.add(new Route(RouteType.SERVICE_DETAIL, subscription.getId(), -1));
        navigationGeneration++;
        renderCurrentRoute();
    }

    private void openSubscriptionForm(Subscription original) {
        if (uncertainWriteRoute != null) { showUncertainWriteDialog(); return; }
        saveCurrentScrollPosition();
        formState = null;
        long id = original == null ? -1 : original.getId();
        restoreSuspendedForm(RouteType.SUBSCRIPTION_FORM, id, -1);
        routes.add(new Route(RouteType.SUBSCRIPTION_FORM, id, -1));
        navigationGeneration++;
        renderCurrentRoute();
    }

    private void openReminderDetail(Reminder reminder) {
        saveCurrentScrollPosition();
        formState = null;
        routes.add(new Route(RouteType.REMINDER_DETAIL, reminder.getId(), -1));
        navigationGeneration++;
        renderCurrentRoute();
    }

    private void openReminderForm(Reminder original, Long selectedSubscriptionId) {
        if (uncertainWriteRoute != null) { showUncertainWriteDialog(); return; }
        saveCurrentScrollPosition();
        formState = null;
        long id = original == null ? -1 : original.getId();
        long relatedId = original == null && selectedSubscriptionId != null ? selectedSubscriptionId : -1;
        restoreSuspendedForm(RouteType.REMINDER_FORM, id, relatedId);
        routes.add(new Route(RouteType.REMINDER_FORM, id, relatedId));
        navigationGeneration++;
        renderCurrentRoute();
    }

    private void openBudgetForm(Budget original) {
        if (uncertainWriteRoute != null) { showUncertainWriteDialog(); return; }
        saveCurrentScrollPosition();
        formState = null;
        long id = original == null ? -1 : original.getId();
        restoreSuspendedForm(RouteType.BUDGET_FORM, id, -1);
        routes.add(new Route(RouteType.BUDGET_FORM, id, -1));
        navigationGeneration++;
        renderCurrentRoute();
    }

    private void openInsightDetail(Recommendation recommendation) {
        saveCurrentScrollPosition();
        formState = null;
        routes.add(new Route(RouteType.INSIGHT_DETAIL, recommendation.getId(), -1));
        navigationGeneration++;
        renderCurrentRoute();
    }

    private void saveSubscription(Subscription original) {
        if (uncertainWriteRoute != null) { showUncertainWriteDialog(); return; }
        if (subscriptionForm == null || saveButton == null) return;
        SubscriptionDraft draft;
        try { draft = subscriptionForm.createDraft(); }
        catch (SubscriptionForm.FormValidationException ignored) { return; }
        setWriteBusy(true, "Saving…");
        beginWrite(RouteType.SUBSCRIPTION_FORM);
        int navigation = navigationGeneration;
        String authUserId = session.getAuthUserId();
        dataWorker.execute(() -> {
            ApiException failure = null;
            try {
                if (original == null) repository.createSubscription(draft);
                else repository.updateSubscription(original.getId(), draft);
            } catch (ApiException error) { failure = error; }
            ApiException resultFailure = failure;
            Session resultSession = repository.getCurrentSession();
            mainHandler.post(() -> {
                if (!sameView(navigation, authUserId)) return;
                syncSessionSnapshot(resultSession, authUserId);
                if (resultFailure != null) {
                    setWriteBusy(false, "Save service");
                    failWrite(resultFailure, RouteType.SUBSCRIPTION_FORM);
                    return;
                }
                finishWrite();
                routes.remove(routes.size() - 1);
                formState = null;
                pendingRootMessage = original == null ? "Service added." : "Service saved.";
                navigationGeneration++;
                renderCurrentRoute();
            });
        });
    }

    private void saveReminder(Reminder original) {
        if (uncertainWriteRoute != null) { showUncertainWriteDialog(); return; }
        if (reminderForm == null || saveButton == null) return;
        ReminderDraft draft;
        try { draft = reminderForm.createDraft(); }
        catch (ReminderForm.FormValidationException ignored) { return; }
        setWriteBusy(true, "Saving…");
        beginWrite(RouteType.REMINDER_FORM);
        int navigation = navigationGeneration;
        String authUserId = session.getAuthUserId();
        dataWorker.execute(() -> {
            ApiException failure = null;
            try {
                if (original == null) repository.createReminder(draft);
                else repository.updateReminder(original.getId(), draft);
            } catch (ApiException error) { failure = error; }
            ApiException resultFailure = failure;
            Session resultSession = repository.getCurrentSession();
            mainHandler.post(() -> {
                if (!sameView(navigation, authUserId)) return;
                syncSessionSnapshot(resultSession, authUserId);
                if (resultFailure != null) {
                    setWriteBusy(false, "Save reminder");
                    failWrite(resultFailure, RouteType.REMINDER_FORM);
                    return;
                }
                finishWrite();
                routes.remove(routes.size() - 1);
                formState = null;
                pendingRootMessage = original == null ? "Reminder added." : "Reminder rescheduled.";
                navigationGeneration++;
                renderCurrentRoute();
            });
        });
    }

    private void saveBudget(Budget original) {
        if (uncertainWriteRoute != null) { showUncertainWriteDialog(); return; }
        if (budgetForm == null || saveButton == null) return;
        BudgetDraft draft;
        try { draft = budgetForm.createDraft(); }
        catch (BudgetForm.FormValidationException ignored) { return; }
        setWriteBusy(true, "Saving…");
        beginWrite(RouteType.BUDGET_FORM);
        int navigation = navigationGeneration;
        String authUserId = session.getAuthUserId();
        dataWorker.execute(() -> {
            ApiException failure = null;
            try {
                if (original == null) repository.createBudget(draft);
                else repository.updateBudget(original.getId(), draft);
            } catch (ApiException error) { failure = error; }
            ApiException resultFailure = failure;
            Session resultSession = repository.getCurrentSession();
            mainHandler.post(() -> {
                if (!sameView(navigation, authUserId)) return;
                syncSessionSnapshot(resultSession, authUserId);
                if (resultFailure != null) {
                    setWriteBusy(false, "Save budget");
                    if (resultFailure.getCategory() == ApiException.Category.NETWORK
                            || resultFailure.getCategory() == ApiException.Category.DATA_FORMAT
                            || resultFailure.getCategory() == ApiException.Category.AUTH_REQUIRED) {
                        failWrite(resultFailure, RouteType.BUDGET_FORM);
                        return;
                    }
                    String message = resultFailure.getCategory() == ApiException.Category.CONFLICT
                            ? resultFailure.getUserMessage() + " Reload the budget list and try again."
                            : resultFailure.getUserMessage();
                    finishWrite();
                    showSnackbar(message);
                    return;
                }
                finishWrite();
                routes.remove(routes.size() - 1);
                formState = null;
                pendingRootMessage = "Budget saved.";
                navigationGeneration++;
                renderCurrentRoute();
            });
        });
    }

    private void updateReminderStatus(Reminder reminder, ReminderStatus status) {
        if (uncertainWriteRoute != null) { showUncertainWriteDialog(); return; }
        showLoading("Updating reminder");
        beginWrite(RouteType.REMINDER_DETAIL);
        int navigation = navigationGeneration;
        String authUserId = session.getAuthUserId();
        ReminderDraft draft = ReminderDraft.builder().setSubscriptionId(reminder.getSubscriptionId())
                .setTitle(reminder.getTitle()).setMessage(reminder.getMessage())
                .setRemindAt(reminder.getRemindAt()).setStatus(status).build();
        dataWorker.execute(() -> {
            ApiException failure = null;
            try { repository.updateReminder(reminder.getId(), draft); }
            catch (ApiException error) { failure = error; }
            ApiException resultFailure = failure;
            Session resultSession = repository.getCurrentSession();
            mainHandler.post(() -> {
                if (!sameView(navigation, authUserId)) return;
                syncSessionSnapshot(resultSession, authUserId);
                if (resultFailure != null) {
                    failWrite(resultFailure, RouteType.REMINDER_DETAIL);
                } else {
                    finishWrite();
                    pendingRootMessage = status == ReminderStatus.COMPLETED
                            ? "Reminder completed." : "Reminder reopened.";
                    renderCurrentRoute();
                }
            });
        });
    }

    private void saveDecision(Recommendation recommendation, DecisionStatus status) {
        if (uncertainWriteRoute != null) { showUncertainWriteDialog(); return; }
        showLoading("Saving decision");
        beginWrite(RouteType.INSIGHT_DETAIL);
        int navigation = navigationGeneration;
        String authUserId = session.getAuthUserId();
        dataWorker.execute(() -> {
            ApiException failure = null;
            try { repository.saveDecision(recommendation.getId(), status); }
            catch (ApiException error) { failure = error; }
            ApiException resultFailure = failure;
            Session resultSession = repository.getCurrentSession();
            mainHandler.post(() -> {
                if (!sameView(navigation, authUserId)) return;
                syncSessionSnapshot(resultSession, authUserId);
                if (resultFailure != null) {
                    failWrite(resultFailure, RouteType.INSIGHT_DETAIL);
                } else {
                    finishWrite();
                    renderCurrentRoute();
                    pendingRootMessage = null;
                    showSnackbar("Your decision is recorded. Your service is unchanged.");
                }
            });
        });
    }

    private void confirmDeleteService(Subscription subscription) {
        new AlertDialog.Builder(this)
                .setTitle("Delete service?")
                .setMessage("Deleting " + subscription.getName()
                        + " also deletes its linked reminders and recommendations.")
                .setNegativeButton("Keep service", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("Delete service", (dialog, which) -> deleteService(subscription))
                .show();
    }

    private void deleteService(Subscription subscription) {
        if (uncertainWriteRoute != null) { showUncertainWriteDialog(); return; }
        showLoading("Deleting service");
        beginWrite(RouteType.SERVICE_DETAIL);
        int navigation = navigationGeneration;
        String authUserId = session.getAuthUserId();
        dataWorker.execute(() -> {
            ApiException failure = null;
            try { repository.deleteSubscription(subscription.getId()); }
            catch (ApiException error) { failure = error; }
            ApiException resultFailure = failure;
            Session resultSession = repository.getCurrentSession();
            mainHandler.post(() -> {
                if (!sameView(navigation, authUserId)) return;
                syncSessionSnapshot(resultSession, authUserId);
                if (resultFailure != null) {
                    failWrite(resultFailure, RouteType.SERVICE_DETAIL);
                } else {
                    finishWrite();
                    routes.clear();
                    rootScreen = RootScreen.SERVICES;
                    pendingRootMessage = "Service deleted.";
                    navigationGeneration++;
                    renderCurrentRoute();
                }
            });
        });
    }

    private void confirmDeleteReminder(Reminder reminder) {
        new AlertDialog.Builder(this)
                .setTitle("Delete reminder?")
                .setMessage("This removes the saved reminder schedule.")
                .setNegativeButton("Keep reminder", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("Delete reminder", (dialog, which) -> deleteReminder(reminder))
                .show();
    }

    private void deleteReminder(Reminder reminder) {
        if (uncertainWriteRoute != null) { showUncertainWriteDialog(); return; }
        showLoading("Deleting reminder");
        beginWrite(RouteType.REMINDER_DETAIL);
        int navigation = navigationGeneration;
        String authUserId = session.getAuthUserId();
        dataWorker.execute(() -> {
            ApiException failure = null;
            try { repository.deleteReminder(reminder.getId()); }
            catch (ApiException error) { failure = error; }
            ApiException resultFailure = failure;
            Session resultSession = repository.getCurrentSession();
            mainHandler.post(() -> {
                if (!sameView(navigation, authUserId)) return;
                syncSessionSnapshot(resultSession, authUserId);
                if (resultFailure != null) {
                    failWrite(resultFailure, RouteType.REMINDER_DETAIL);
                } else {
                    finishWrite();
                    routes.clear();
                    rootScreen = RootScreen.REMINDERS;
                    reminderFilter = "Upcoming";
                    pendingRootMessage = "Reminder deleted.";
                    navigationGeneration++;
                    renderCurrentRoute();
                }
            });
        });
    }

    private void confirmSignOut() {
        new AlertDialog.Builder(this)
                .setTitle("Sign out?")
                .setNegativeButton("Stay signed in", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("Sign out", (dialog, which) -> signOut())
                .show();
    }

    private void signOut() {
        loadGeneration++;
        navigationGeneration++;
        session = null;
        profile = null;
        routes.clear();
        clearProtectedState();
        authMode = AuthScreen.Mode.SIGN_IN;
        authMessage = "";
        authFormState = null;
        renderAuthentication();
        dataWorker.execute(() -> {
            ApiException failure = null;
            try { repository.signOut(); }
            catch (ApiException error) { failure = error; }
            ApiException resultFailure = failure;
            mainHandler.post(() -> {
                if (resultFailure != null && session == null && authMode == AuthScreen.Mode.SIGN_IN) {
                    authMessage = resultFailure.getUserMessage();
                    renderAuthentication();
                }
            });
        });
    }

    private void navigateBack() {
        if (writeInFlightRoute != null) return;
        if (authMode != null) {
            if (authMode == AuthScreen.Mode.CREATE_ACCOUNT) {
                saveAuthState();
                authMode = AuthScreen.Mode.SIGN_IN;
                authMessage = "";
                authFormState = null;
                renderAuthentication();
            } else if (authMode == AuthScreen.Mode.PROFILE_SETUP) {
                authMode = AuthScreen.Mode.SIGN_IN;
                authMessage = "Sign in with the account linked to this profile.";
                authFormState = null;
                renderAuthentication();
            } else {
                finish();
            }
            return;
        }
        if (!routes.isEmpty()) {
            if (isDirtyForm()) {
                askToDiscard(() -> popRoute());
            } else {
                popRoute();
            }
            return;
        }
        if (rootScreen != RootScreen.HOME) {
            switchRoot(RootScreen.HOME);
            return;
        }
        finish();
    }

    private void popRoute() {
        if (routes.isEmpty()) return;
        if (uncertainWriteRoute != null) preserveUncertainForm();
        saveCurrentScrollPosition();
        routes.remove(routes.size() - 1);
        formState = null;
        navigationGeneration++;
        renderCurrentRoute();
    }

    private boolean isDirtyForm() {
        if (subscriptionForm != null) return subscriptionForm.isDirty();
        if (reminderForm != null) return reminderForm.isDirty();
        if (budgetForm != null) return budgetForm.isDirty();
        return false;
    }

    private void askToDiscard(Runnable discard) {
        new AlertDialog.Builder(this)
                .setTitle("Discard your changes?")
                .setMessage("Your draft has not been saved.")
                .setNegativeButton("Keep editing", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("Discard draft", (dialog, which) -> discard.run())
                .show();
    }

    private void setWriteBusy(boolean busy, String label) {
        if (saveButton != null) {
            saveButton.setEnabled(!busy);
            saveButton.setText(label);
        }
        if (cancelButton != null) cancelButton.setEnabled(!busy);
    }

    private boolean sameView(int navigation, String authUserId) {
        return !isFinishing() && navigation == navigationGeneration && session != null
                && session.getAuthUserId().equals(authUserId);
    }

    private void showLoading(String message) {
        boolean authentication = authMode != null || session == null;
        setChrome(authentication ? authTitle() : currentTitle(), !authentication && routes.isEmpty());
        FrameLayout frame = new FrameLayout(this);
        LinearLayout group = new LinearLayout(this);
        group.setGravity(Gravity.CENTER);
        group.setOrientation(LinearLayout.VERTICAL);
        ProgressBar progress = new ProgressBar(this);
        progress.setIndeterminate(true);
        group.addView(progress, new LinearLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32)));
        TextView label = Ui.secondary(this, message);
        group.addView(label, margin(this, 0, 12, 0, 0));
        frame.addView(group, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        attach(frame);
    }

    private void showError(String title, String message, Runnable retry) {
        LinearLayout page = Ui.page(this);
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(Ui.heading(this, title));
        page.addView(Ui.secondary(this, message == null ? "Please try again." : message), margin(this, 0, 8, 0, 18));
        MaterialButton button = Ui.button(this, "Retry", true);
        button.setOnClickListener(view -> retry.run());
        page.addView(button);
        attach(Ui.scroll(this, page));
    }

    private void showSnackbar(String message) {
        if (message != null && !message.isEmpty() && screenContainer != null) {
            Snackbar.make(screenContainer, message, Snackbar.LENGTH_LONG).show();
        }
    }

    private void setChrome(String title, boolean showBottomNav) {
        screenTitle.setText(title);
        bottomNavigation.setVisibility(showBottomNav ? View.VISIBLE : View.GONE);
    }

    private String currentTitle() {
        if (routes.isEmpty()) return rootScreen.title;
        Route route = routes.get(routes.size() - 1);
        switch (route.type) {
            case SERVICE_DETAIL: return "Service";
            case SUBSCRIPTION_FORM: return route.id < 0 ? "Add service" : "Edit service";
            case REMINDER_DETAIL: return "Reminder";
            case REMINDER_FORM: return route.id < 0 ? "Add reminder" : "Reschedule";
            case BUDGET_FORM: return route.id < 0 ? "Add budget" : "Edit budget";
            case INSIGHT_DETAIL: return "Review insight";
            default: return rootScreen.title;
        }
    }

    private void attach(View view) {
        screenContainer.removeAllViews();
        currentContent = view;
        screenContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void clearFormViews() {
        authScreen = null;
        subscriptionForm = null;
        reminderForm = null;
        budgetForm = null;
        saveButton = null;
        cancelButton = null;
    }

    private void saveCurrentScrollPosition() {
        if (routes.isEmpty() && currentContent instanceof ScrollView) {
            rootScrollPositions.put(rootScreen, ((ScrollView) currentContent).getScrollY());
        } else if (routes.isEmpty() && rootScreen == RootScreen.AI && aiContent != null
                && aiContent.getChildCount() > 0 && aiContent.getChildAt(0) instanceof ScrollView) {
            rootScrollPositions.put(rootScreen, aiContent.getChildAt(0).getScrollY());
        }
    }

    private void restoreCurrentScrollPosition(View page) {
        if (routes.isEmpty() && page instanceof ScrollView) {
            int position = rootScrollPositions.getOrDefault(rootScreen, 0);
            page.post(() -> page.scrollTo(0, position));
        }
    }

    private void clearProtectedState() {
        routes.clear();
        currentData = new ScreenData();
        profile = null;
        formState = null;
        protectedStateAuthUserId = null;
        writeInFlightRoute = null;
        uncertainWriteRoute = null;
        uncertainWriteDialogVisible = false;
        uncertainListChecked = false;
        suspendedFormState = null;
        suspendedFormRoute = null;
        suspendedFormId = -1;
        suspendedFormRelatedId = -1;
        recordedDecisionNames.clear();
        rootScrollPositions.clear();
        rootScreen = RootScreen.HOME;
        aiSubview = AiSubview.CHAT;
        serviceQuery = "";
        serviceFilter = "All";
        reminderFilter = "Upcoming";
        if (aiChatView != null) {
            aiChatView.dispose();
            aiChatView = null;
        }
        aiRootView = null;
        aiTabs = null;
        aiContent = null;
        chatState = null;
    }

    private void removeFromParent(View view) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
    }

    private void saveVisibleDraft() {
        if (subscriptionForm != null) formState = subscriptionForm.state();
        else if (reminderForm != null) formState = reminderForm.state();
        else if (budgetForm != null) formState = budgetForm.state();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        saveCurrentScrollPosition();
        saveVisibleDraft();
        saveAuthState();
        outState.putString(STATE_ROOT, rootScreen.name());
        outState.putStringArrayList(STATE_STACK_TYPES, routeTypeNames());
        outState.putLongArray(STATE_STACK_IDS, routeIds(false));
        outState.putLongArray(STATE_STACK_RELATED_IDS, routeIds(true));
        outState.putString(STATE_AUTH_MODE, authMode == null ? null : authMode.name());
        outState.putString(STATE_AUTH_MESSAGE, authMessage);
        if (authFormState != null) outState.putBundle(STATE_AUTH_FORM, new Bundle(authFormState));
        if (formState != null) outState.putBundle(STATE_FORM, new Bundle(formState));
        outState.putString(STATE_SERVICE_QUERY, serviceQuery);
        outState.putString(STATE_SERVICE_FILTER, serviceFilter);
        outState.putString(STATE_REMINDER_FILTER, reminderFilter);
        outState.putString(STATE_AI_SUBVIEW, aiSubview.name());
        outState.putString(STATE_AUTH_USER_ID, session == null
                ? protectedStateAuthUserId : session.getAuthUserId());
        RouteType pendingWrite = writeInFlightRoute != null ? writeInFlightRoute : uncertainWriteRoute;
        outState.putString(STATE_WRITE_ROUTE, pendingWrite == null ? null : pendingWrite.name());
        if (suspendedFormState != null) outState.putBundle(STATE_UNCERTAIN_FORM, new Bundle(suspendedFormState));
        outState.putString(STATE_UNCERTAIN_FORM_ROUTE,
                suspendedFormRoute == null ? null : suspendedFormRoute.name());
        outState.putLong(STATE_UNCERTAIN_FORM_ID, suspendedFormId);
        outState.putLong(STATE_UNCERTAIN_FORM_RELATED_ID, suspendedFormRelatedId);
        int[] positions = new int[RootScreen.values().length];
        for (int i = 0; i < RootScreen.values().length; i++) {
            positions[i] = rootScrollPositions.getOrDefault(RootScreen.values()[i], 0);
        }
        outState.putIntArray(STATE_SCROLL_POSITIONS, positions);
        if (aiChatView != null) outState.putBundle(STATE_CHAT, aiChatView.saveState());
        long[] decisionIds = new long[recordedDecisionNames.size()];
        String[] decisionValues = new String[recordedDecisionNames.size()];
        int index = 0;
        for (Map.Entry<Long, String> entry : recordedDecisionNames.entrySet()) {
            decisionIds[index] = entry.getKey();
            decisionValues[index] = entry.getValue();
            index++;
        }
        outState.putLongArray(STATE_DECISION_IDS, decisionIds);
        outState.putStringArray(STATE_DECISION_VALUES, decisionValues);
        super.onSaveInstanceState(outState);
    }

    private ArrayList<String> routeTypeNames() {
        ArrayList<String> names = new ArrayList<>();
        for (Route route : routes) names.add(route.type.name());
        return names;
    }

    private long[] routeIds(boolean related) {
        long[] ids = new long[routes.size()];
        for (int i = 0; i < routes.size(); i++) ids[i] = related ? routes.get(i).relatedId : routes.get(i).id;
        return ids;
    }

    private RootScreen rootFromName(String value) {
        if (value == null) return null;
        try { return RootScreen.valueOf(value); }
        catch (IllegalArgumentException ignored) { return null; }
    }

    private static Subscription findSubscription(List<Subscription> subscriptions, long id) {
        for (Subscription subscription : subscriptions) if (subscription.getId() == id) return subscription;
        return null;
    }

    private static Reminder findReminder(List<Reminder> reminders, long id) {
        for (Reminder reminder : reminders) if (reminder.getId() == id) return reminder;
        return null;
    }

    private static Budget findBudget(List<Budget> budgets, long id) {
        for (Budget budget : budgets) if (budget.getId() == id) return budget;
        return null;
    }

    private static Recommendation findRecommendation(List<Recommendation> recommendations, long id) {
        for (Recommendation recommendation : recommendations) if (recommendation.getId() == id) return recommendation;
        return null;
    }

    private static LinearLayout.LayoutParams margin(android.content.Context context, int start, int top,
                                                     int end, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(Ui.dp(context, start), Ui.dp(context, top), Ui.dp(context, end), Ui.dp(context, bottom));
        return params;
    }

    @Override
    protected void onDestroy() {
        loadGeneration++;
        if (aiChatView != null) aiChatView.dispose();
        dataWorker.shutdownNow();
        super.onDestroy();
    }
}
