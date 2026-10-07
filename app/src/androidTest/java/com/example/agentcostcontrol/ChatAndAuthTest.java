package com.example.agentcostcontrol;

import android.content.Context;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ContextThemeWrapper;
import android.widget.EditText;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.agentcostcontrol.ui.AiChatView;
import com.example.agentcostcontrol.data.SupabaseRepository;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

@RunWith(AndroidJUnit4.class)
public final class ChatAndAuthTest {
    @Test
    public void signedOutUserCannotOpenProtectedTabs() {
        // Run on a fresh emulator installation without an existing signed-in session.
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.auth_email)).check(matches(isDisplayed()));
            onView(withId(R.id.bottom_navigation)).check(matches(withEffectiveVisibility(GONE)));
            onView(withId(R.id.auth_primary_action)).perform(click());
            onView(withId(R.id.auth_email)).check(matches(isDisplayed()));
            onView(withId(R.id.bottom_navigation)).check(matches(withEffectiveVisibility(GONE)));
        }
    }

    @Test
    public void authRecreationRetainsEmailButNotPassword() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> {
                ((EditText) activity.findViewById(R.id.auth_email)).setText("example@example.invalid");
                ((EditText) activity.findViewById(R.id.auth_password)).setText("synthetic-password");
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals("example@example.invalid",
                        ((EditText) activity.findViewById(R.id.auth_email)).getText().toString());
                assertEquals("",
                        ((EditText) activity.findViewById(R.id.auth_password)).getText().toString());
            });
        }
    }

    @Test
    public void systemBackReturnsFromCreateAccountToSignIn() {
        try (ActivityScenario<MainActivity> ignored = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.auth_switch_mode)).perform(click());
            onView(withId(R.id.auth_first_name)).check(matches(isDisplayed()));
            pressBack();
            onView(withId(R.id.auth_switch_mode)).check(matches(isDisplayed()));
            onView(withId(R.id.auth_first_name)).check(doesNotExist());
            onView(withId(R.id.bottom_navigation)).check(matches(withEffectiveVisibility(GONE)));
        }
    }

    @Test
    public void recreationRetainsTheRepositoryThatSerializesTokenRefresh() {
        SupabaseRepository[] retained = new SupabaseRepository[1];
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(activity -> retained[0] = repositoryUsedBy(activity));
            scenario.recreate();
            scenario.onActivity(activity -> assertSame(retained[0], repositoryUsedBy(activity)));
        }
    }

    private static SupabaseRepository repositoryUsedBy(MainActivity activity) {
        // Observe the actual coordinator dependency, rather than creating a separate test ViewModel.
        try {
            java.lang.reflect.Field field = MainActivity.class.getDeclaredField("repository");
            field.setAccessible(true);
            return (SupabaseRepository) field.get(activity);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Could not inspect the retained repository boundary", error);
        }
    }

    @Test
    public void chatDraftSurvivesViewRecreation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = new ContextThemeWrapper(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    R.style.Theme_AgentCostControl);
            AiChatView original = new AiChatView(context);
            ((EditText) original.findViewById(R.id.chat_prompt)).setText("Which sample service renews next?");
            Bundle state = original.saveState();
            original.dispose();
            AiChatView restored = new AiChatView(context);
            restored.restoreState(state);
            assertEquals("Which sample service renews next?",
                    ((EditText) restored.findViewById(R.id.chat_prompt)).getText().toString());
            int width = Math.round(390 * context.getResources().getDisplayMetrics().density);
            int height = Math.round(680 * context.getResources().getDisplayMetrics().density);
            restored.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            restored.layout(0, 0, width, height);
            Bitmap preview = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            restored.draw(new Canvas(preview));
            try (FileOutputStream output = new FileOutputStream(
                    new File(context.getExternalFilesDir(null), "chat-composer.png"))) {
                if (!preview.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new AssertionError("Could not capture native chat preview");
                }
            } catch (IOException error) {
                throw new AssertionError("Could not write native chat preview", error);
            } finally {
                preview.recycle();
            }
            restored.dispose();
        });
    }

    @Test
    public void missingChatBackendKeepsDraftAndReportsUnavailable() {
        Assume.assumeTrue(BuildConfig.AI_ENDPOINT_URL.isEmpty());
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = new ContextThemeWrapper(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    R.style.Theme_AgentCostControl);
            AiChatView chat = new AiChatView(context);
            chat.setAccessToken("synthetic-test-token");
            EditText prompt = chat.findViewById(R.id.chat_prompt);
            prompt.setText("Explain this sample budget");
            chat.findViewById(R.id.chat_send).performClick();
            assertEquals("Explain this sample budget", prompt.getText().toString());
            assertEquals(context.getString(R.string.chat_not_configured),
                    ((TextView) chat.findViewById(R.id.chat_feedback)).getText().toString());
            chat.dispose();
        });
    }
}
