package com.example.agentcostcontrol.data;

import android.os.SystemClock;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.NoMatchingViewException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.agentcostcontrol.MainActivity;
import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.model.Session;
import com.example.agentcostcontrol.model.UserProfile;
import com.example.agentcostcontrol.ui.AccountDataViewModel;
import com.example.agentcostcontrol.ui.Ui;
import com.google.android.material.navigation.NavigationView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static androidx.test.espresso.Espresso.closeSoftKeyboard;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.action.ViewActions.swipeUp;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises the real activity and repository with a read-only, in-memory account. */
@RunWith(AndroidJUnit4.class)
public final class SidebarNavigationTest {
    @Test public void sidebarInsetsFollowThePhysicalStartEdgeInBothLayoutDirections() throws Exception {
        try (ActivityScenario<MainActivity> scenario = launchAccount(new ReadOnlyAccount())) {
            scenario.onActivity(activity -> {
                DrawerLayout drawer = activity.findViewById(R.id.app_root);
                View sidebar = activity.findViewById(R.id.navigation_sidebar);
                View content = activity.findViewById(R.id.main_content);
                WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(11, 23, 29, 31))
                        .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(17, 41, 37, 43))
                        .build();
                for (int direction : new int[]{View.LAYOUT_DIRECTION_RTL, View.LAYOUT_DIRECTION_LTR}) {
                    drawer.setLayoutDirection(direction);
                    ViewCompat.dispatchApplyWindowInsets(drawer, insets);
                    boolean rtl = direction == View.LAYOUT_DIRECTION_RTL;
                    DrawerLayout.LayoutParams placement = (DrawerLayout.LayoutParams) sidebar.getLayoutParams();
                    assertEquals(rtl ? Gravity.RIGHT : Gravity.LEFT,
                            GravityCompat.getAbsoluteGravity(placement.gravity, direction)
                                    & Gravity.HORIZONTAL_GRAVITY_MASK);
                    assertEquals(rtl ? 0 : 17, sidebar.getPaddingLeft());
                    assertEquals(rtl ? 37 : 0, sidebar.getPaddingRight());
                    assertEquals(41, sidebar.getPaddingTop());
                    assertEquals(43, sidebar.getPaddingBottom());
                    assertEquals(17, content.getPaddingLeft());
                    assertEquals(37, content.getPaddingRight());
                    assertEquals(41, content.getPaddingTop());
                    assertEquals(43, content.getPaddingBottom());
                }
            });
        }
    }

    @Test public void navigationStaysAtTheTopWhileHistoryScrollsThroughAllPages() throws Exception {
        ReadOnlyAccount account = new ReadOnlyAccount();
        try (ActivityScenario<MainActivity> scenario = launchAccount(account)) {
            onView(withId(R.id.navigation_open)).perform(click());
            waitForText("Saved chat 1");
            scenario.onActivity(activity -> {
                NavigationView menu = activity.findViewById(R.id.sidebar_navigation);
                assertEquals(6, menu.getMenu().size());
                assertEquals(R.id.nav_dashboard, menu.getCheckedItem().getItemId());
                capture(activity, "sidebar-navigation.png");
            });
            onView(withId(R.id.chat_history_scroll)).perform(swipeUp());
            onView(allOf(withText("Home"), isDescendantOfA(withId(R.id.sidebar_navigation))))
                    .check(matches(isDisplayed()));
            onView(allOf(withText("Profile"), isDescendantOfA(withId(R.id.sidebar_navigation))))
                    .check(matches(isDisplayed()));
            onView(withText(R.string.chat_history_load_more)).perform(scrollTo(), click());
            waitForText("Saved chat 28", false);
            onView(withText("Saved chat 28")).perform(scrollTo()).check(matches(isDisplayed()));
            assertEquals(java.util.Arrays.asList(0, 25), account.historyOffsets);
        }
    }

    @Test public void chatDraftSurvivesInsightsAndRecreationAndHistoryOpensFromAnotherRoot()
            throws Exception {
        ReadOnlyAccount account = new ReadOnlyAccount();
        try (ActivityScenario<MainActivity> scenario = launchAccount(account)) {
            selectDestination("AI");
            waitForChat();
            scenario.onActivity(activity -> capture(activity, "sidebar-chat.png"));
            onView(withId(R.id.chat_prompt)).perform(replaceText("Keep this unsent question"));
            closeSoftKeyboard();
            selectDestination("Insights");
            waitForTitle("Insights");
            selectDestination("AI");
            waitForChat();
            onView(withId(R.id.chat_prompt)).check(matches(withText("Keep this unsent question")));
            scenario.recreate();
            waitForChat();
            onView(withId(R.id.chat_prompt)).check(matches(withText("Keep this unsent question")));

            selectDestination("Insights");
            waitForTitle("Insights");
            onView(withId(R.id.navigation_open)).perform(click());
            waitForText("Saved chat 1");
            onView(withText("Saved chat 1")).perform(click());
            waitForText("A saved answer");
            scenario.onActivity(activity -> {
                NavigationView menu = activity.findViewById(R.id.sidebar_navigation);
                assertEquals(R.id.nav_chat, menu.getCheckedItem().getItemId());
                DrawerLayout drawer = activity.findViewById(R.id.app_root);
                assertFalse(drawer.isDrawerVisible(GravityCompat.START));
            });

            onView(withId(R.id.navigation_open)).perform(click());
            onView(withId(R.id.chat_new)).perform(click());
            onView(withId(R.id.chat_empty_state)).check(matches(isDisplayed()));
            onView(withId(R.id.chat_prompt)).check(matches(withText("")));
        }
    }

    @Test public void backClosesTheSidebarBeforeLeavingChat() throws Exception {
        try (ActivityScenario<MainActivity> scenario = launchAccount(new ReadOnlyAccount())) {
            selectDestination("AI");
            waitForChat();
            onView(withId(R.id.navigation_open)).perform(click());
            waitForText("Saved chat 1");
            pressBack();
            onView(withId(R.id.chat_prompt)).check(matches(isDisplayed()));
            scenario.onActivity(activity -> {
                DrawerLayout drawer = activity.findViewById(R.id.app_root);
                assertFalse(drawer.isDrawerVisible(GravityCompat.START));
            });
            pressBack();
            waitForTitle("Home");
        }
    }

    @Test public void returningFromGmailConnectionReloadsTheProfileScreen() throws Exception {
        ReadOnlyAccount account = new ReadOnlyAccount();
        try (ActivityScenario<MainActivity> scenario = launchProfileAccount(account)) {
            waitForProfileGmail(scenario);

            scenario.onActivity(activity -> setField(activity, "gmailConnectPendingResume", true));
            scenario.moveToState(Lifecycle.State.STARTED);
            scenario.moveToState(Lifecycle.State.RESUMED);

            waitForProfileGmail(scenario);
        }
    }

    @Test public void shortScreensKeepHistoryReachableBelowAScrollableMenu() throws Exception {
        try (ActivityScenario<MainActivity> scenario = launchAccount(new ReadOnlyAccount())) {
            scenario.onActivity(activity -> activity.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 390)));
            waitFor(() -> onView(withId(R.id.app_root)).check((view, error) -> {
                if (error != null) throw error;
                assertTrue("The app window has not finished resizing",
                        view.getHeight() <= Ui.dp(view.getContext(), 400));
            }));
            waitForView(R.id.navigation_open);
            onView(withId(R.id.navigation_open)).perform(click());
            waitForText("Saved chat 1");
            scenario.onActivity(activity -> {
                View history = activity.findViewById(R.id.chat_history_scroll);
                View menu = activity.findViewById(R.id.sidebar_navigation);
                assertTrue("History has no room to scroll", history.getHeight() > 0);
                assertTrue("Navigation overlaps history", menu.getBottom()
                        <= activity.findViewById(R.id.sidebar_history).getTop());
                capture(activity, "sidebar-compact.png");
            });
            scrollMenuTo("Profile");
            onView(withId(R.id.chat_history_scroll)).perform(swipeUp());
        }
    }

    private static void capture(MainActivity activity, String filename) {
        View root = activity.findViewById(R.id.app_root);
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        String outputPath = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir");
        File directory = outputPath == null ? activity.getExternalFilesDir(null) : new File(outputPath);
        assertTrue("Could not create the preview directory", directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, filename))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (IOException error) {
            throw new AssertionError("Could not save the navigation preview", error);
        } finally {
            bitmap.recycle();
        }
    }

    private static ActivityScenario<MainActivity> launchAccount(ReadOnlyAccount account) throws Exception {
        Session session = account.repository.restoreSession();
        UserProfile profile = account.repository.getCurrentProfile();
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            waitForView(R.id.auth_email);
            scenario.onActivity(activity -> {
                // Retain the fake transport across recreation through the production ViewModel boundary.
                AccountDataViewModel model = new ViewModelProvider(activity).get(AccountDataViewModel.class);
                setField(model, "repository", account.repository);
                setField(activity, "repository", account.repository);
                try {
                    Method enter = MainActivity.class.getDeclaredMethod("enterSignedInApp",
                            Session.class, UserProfile.class);
                    enter.setAccessible(true);
                    enter.invoke(activity, session, profile);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError("Could not arrange the read-only account", error);
                }
            });
            waitForView(R.id.navigation_open);
            return scenario;
        } catch (AssertionError | RuntimeException error) {
            scenario.close();
            throw error;
        }
    }

    private static ActivityScenario<MainActivity> launchProfileAccount(ReadOnlyAccount account) throws Exception {
        Session session = account.repository.restoreSession();
        UserProfile profile = account.repository.getCurrentProfile();
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            scenario.onActivity(activity -> {
                AccountDataViewModel model = new ViewModelProvider(activity).get(AccountDataViewModel.class);
                setField(model, "repository", account.repository);
                setField(activity, "repository", account.repository);
                try {
                    Method enter = MainActivity.class.getDeclaredMethod("enterSignedInApp",
                            Session.class, UserProfile.class);
                    enter.setAccessible(true);
                    enter.invoke(activity, session, profile);

                    Field root = MainActivity.class.getDeclaredField("rootScreen");
                    root.setAccessible(true);
                    Object profileRoot = null;
                    for (Object value : root.getType().getEnumConstants()) {
                        if ("PROFILE".equals(value.toString())) profileRoot = value;
                    }
                    if (profileRoot == null) throw new AssertionError("Profile root is unavailable");
                    Method switchRoot = MainActivity.class.getDeclaredMethod("switchRoot", root.getType());
                    switchRoot.setAccessible(true);
                    switchRoot.invoke(activity, profileRoot);
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError("Could not arrange the read-only profile account", error);
                }
            });
            waitForProfileGmail(scenario);
            return scenario;
        } catch (AssertionError | RuntimeException error) {
            scenario.close();
            throw error;
        }
    }

    private static void waitForProfileGmail(ActivityScenario<MainActivity> scenario) {
        waitFor(() -> scenario.onActivity(activity -> {
            TextView title = activity.findViewById(R.id.screen_title);
            assertEquals("The profile route should be visible after Gmail returns",
                    "Profile", title.getText().toString());
            ViewGroup container = activity.findViewById(R.id.screen_container);
            assertTrue("The screen should render content instead of remaining blank",
                    container.getChildCount() > 0 && containsText(container, "Gmail connected")
                            && containsText(container, "Sync subscriptions"));
        }));
    }

    private static boolean containsText(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) return true;
        if (!(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            if (containsText(group.getChildAt(index), expected)) return true;
        }
        return false;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not inject the test repository", error);
        }
    }

    private static void selectDestination(String title) {
        onView(withId(R.id.navigation_open)).perform(click());
        onView(allOf(withText(title), isDescendantOfA(withId(R.id.sidebar_navigation)))).perform(click());
    }

    private static void scrollMenuTo(String title) {
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                onView(allOf(withText(title), isDescendantOfA(withId(R.id.sidebar_navigation))))
                        .check(matches(isDisplayed()));
                return;
            } catch (AssertionError | NoMatchingViewException notVisibleYet) {
                onView(withId(R.id.sidebar_navigation)).perform(swipeUp());
            }
        }
        onView(allOf(withText(title), isDescendantOfA(withId(R.id.sidebar_navigation))))
                .check(matches(isDisplayed()));
    }

    private static void waitForTitle(String title) {
        waitFor(() -> onView(withId(R.id.screen_title)).check(matches(withText(title))));
    }

    private static void waitForChat() { waitForView(R.id.chat_prompt); }

    private static void waitForView(int id) {
        waitFor(() -> onView(withId(id)).check(matches(isDisplayed())));
    }

    private static void waitForText(String text) { waitForText(text, true); }

    private static void waitForText(String text, boolean displayed) {
        waitFor(() -> {
            if (displayed) onView(withText(text)).check(matches(isDisplayed()));
            else onView(withText(text)).check(matches(withText(text)));
        });
    }

    private static void waitFor(Runnable assertion) {
        long deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(5);
        Throwable lastFailure = null;
        do {
            try {
                assertion.run();
                return;
            } catch (AssertionError | RuntimeException error) {
                lastFailure = error;
                SystemClock.sleep(25);
            }
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Navigation did not reach the expected state", lastFailure);
    }

    private static final class ReadOnlyAccount {
        private static final String USER_ID = "11111111-1111-4111-8111-111111111111";
        final List<Integer> historyOffsets = Collections.synchronizedList(new ArrayList<>());
        final SupabaseRepository repository;

        ReadOnlyAccount() {
            Session session = new Session("test-access", "test-refresh", "bearer", USER_ID,
                    "sidebar@example.invalid", OffsetDateTime.now().plusHours(1));
            SessionPersistence persistence = new SessionPersistence() {
                private Session current = session;
                @Override public Session read() { return current; }
                @Override public void write(Session value) { current = value; }
                @Override public void clear() { current = null; }
            };
            repository = new SupabaseRepository("https://sidebar.example.invalid", "sb_publishable_test",
                    (method, url, headers, body) -> {
                        assertEquals("Navigation must only read account data", "GET", method);
                        try {
                            return new HttpTransport.Response(200, response(url));
                        } catch (org.json.JSONException error) {
                            throw new AssertionError("Invalid navigation fixture", error);
                        }
                    }, persistence, Clock.systemUTC());
        }

        private String response(String url) throws org.json.JSONException {
            android.net.Uri uri = android.net.Uri.parse(url);
            String path = uri.getPath();
            if ("/integrations/gmail".equals(path)) {
                return new JSONObject().put("configured", true).put("connected", true).toString();
            }
            if ("/auth/v1/user".equals(path)) {
                return new JSONObject().put("id", USER_ID).put("email", "sidebar@example.invalid").toString();
            }
            if ("/rest/v1/users".equals(path)) {
                return new JSONArray().put(new JSONObject().put("user_id", "42")
                        .put("auth_user_id", USER_ID).put("first_name", "Sidebar").put("last_name", "Test")
                        .put("email", "sidebar@example.invalid").put("user_type", "user")
                        .put("created_at", "2026-10-01T00:00:00Z")
                        .put("updated_at", "2026-10-01T00:00:00Z")).toString();
            }
            if ("/rest/v1/chat_conversations".equals(path)) {
                int offset = Integer.parseInt(uri.getQueryParameter("offset"));
                int limit = Integer.parseInt(uri.getQueryParameter("limit"));
                historyOffsets.add(offset);
                JSONArray rows = new JSONArray();
                for (int index = offset; index < Math.min(28, offset + limit); index++) {
                    rows.put(new JSONObject().put("conversation_id", conversationId(index))
                            .put("title", "Saved chat " + (index + 1))
                            .put("created_at", "2026-10-01T00:00:00Z")
                            .put("updated_at", "2026-10-13T00:00:00Z"));
                }
                return rows.toString();
            }
            if ("/rest/v1/chat_turns".equals(path)) {
                String id = uri.getQueryParameter("conversation_id").substring(3);
                return new JSONArray().put(new JSONObject().put("conversation_id", id)
                        .put("turn_id", UUID.nameUUIDFromBytes(id.getBytes(StandardCharsets.UTF_8)).toString())
                        .put("question", "A saved question").put("answer", "A saved answer")
                        .put("status", "complete").put("created_at", "2026-10-01T00:00:00Z")
                        .put("updated_at", "2026-10-01T00:00:00Z")).toString();
            }
            assertTrue("Unexpected account read: " + path, path.startsWith("/rest/v1/"));
            return "[]";
        }

        private static String conversationId(int index) {
            return UUID.nameUUIDFromBytes(("sidebar-chat-" + index).getBytes(StandardCharsets.UTF_8)).toString();
        }
    }
}
