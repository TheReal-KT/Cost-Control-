package com.example.agentcostcontrol;

import android.content.Context;
import android.os.Bundle;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextThemeWrapper;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.agentcostcontrol.ui.AiChatView;
import com.example.agentcostcontrol.data.ApiException;
import com.example.agentcostcontrol.data.ChatHistoryRepository;
import com.example.agentcostcontrol.data.ChatHistoryScope;
import com.example.agentcostcontrol.data.SupabaseRepository;
import com.example.agentcostcontrol.model.ChatConversation;
import com.example.agentcostcontrol.model.ChatTurn;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.Espresso.pressBack;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withContentDescription;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

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
    public void savedTranscriptIsBoundedAndEarlierPageOffsetTracksRetainedTurns() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = new ContextThemeWrapper(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    R.style.Theme_AgentCostControl);
            String conversationId = UUID.randomUUID().toString();
            Bundle legacyState = new Bundle();
            legacyState.putString("conversation_id", conversationId);
            legacyState.putString("conversation_title", "Long sample conversation");
            legacyState.putBoolean("started", true);
            legacyState.putInt("history_offset", 40);

            ArrayList<String> ids = new ArrayList<>();
            ArrayList<String> questions = new ArrayList<>();
            ArrayList<String> answers = new ArrayList<>();
            boolean[] complete = new boolean[40];
            boolean[] persisted = new boolean[40];
            boolean[] remoteExists = new boolean[40];
            boolean[] saveFailed = new boolean[40];
            String longQuestion = repeat('q', 4000);
            for (int index = 0; index < 40; index++) {
                ids.add(UUID.randomUUID().toString());
                questions.add(longQuestion);
                answers.add("");
                complete[index] = true;
                persisted[index] = true;
                remoteExists[index] = true;
            }
            legacyState.putStringArrayList("turn_ids", ids);
            legacyState.putStringArrayList("turn_questions", questions);
            legacyState.putStringArrayList("turn_answers", answers);
            legacyState.putBooleanArray("turn_complete", complete);
            legacyState.putBooleanArray("turn_persisted", persisted);
            legacyState.putBooleanArray("turn_remote_exists", remoteExists);
            legacyState.putBooleanArray("turn_save_failed", saveFailed);

            AiChatView chat = new AiChatView(context);
            chat.restoreState(legacyState);
            Bundle saved = chat.saveState();
            ArrayList<String> savedIds = saved.getStringArrayList("turn_ids");
            ArrayList<String> savedQuestions = saved.getStringArrayList("turn_questions");
            assertEquals(5, savedIds.size());
            assertEquals(5, savedQuestions.size());
            assertEquals(5, saved.getInt("history_offset"));
            assertTrue(saved.getBoolean("history_has_more"));
            int retainedCharacters = conversationId.length();
            for (int index = 0; index < savedIds.size(); index++) {
                retainedCharacters += savedIds.get(index).length() + savedQuestions.get(index).length()
                        + "Long sample conversation".length();
            }
            assertTrue(retainedCharacters < 22_000);
            chat.dispose();
        });
    }

    @Test
    public void failedSaveForAnotherConversationSurvivesStateRoundTripAndCanRetry() throws Exception {
        String activeConversationId = UUID.randomUUID().toString();
        String pendingConversationId = UUID.randomUUID().toString();
        String pendingTurnId = UUID.randomUUID().toString();
        Bundle state = new Bundle();
        state.putString("conversation_id", activeConversationId);
        state.putString("conversation_title", "Current chat");
        state.putBoolean("started", true);
        state.putStringArrayList("pending_conversation_ids",
                new ArrayList<>(Collections.singletonList(pendingConversationId)));
        state.putStringArrayList("pending_turn_ids",
                new ArrayList<>(Collections.singletonList(pendingTurnId)));
        state.putStringArrayList("pending_titles",
                new ArrayList<>(Collections.singletonList("Earlier chat")));
        state.putStringArrayList("pending_questions",
                new ArrayList<>(Collections.singletonList("A question from another chat")));
        state.putStringArrayList("pending_answers",
                new ArrayList<>(Collections.singletonList("An incomplete saved answer")));
        state.putBooleanArray("pending_complete", new boolean[]{false});
        state.putBooleanArray("pending_remote_exists", new boolean[]{true});

        AtomicReference<Bundle> savedState = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = new ContextThemeWrapper(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    R.style.Theme_AgentCostControl);
            AiChatView original = new AiChatView(context);
            original.restoreState(state);
            savedState.set(original.saveState());
            original.dispose();
        });
        assertEquals(Collections.singletonList(pendingTurnId),
                savedState.get().getStringArrayList("pending_turn_ids"));

        FakeChatHistoryRepository history = new FakeChatHistoryRepository(
                "Unused chat", "Unused question", "Unused answer", true);
        CountDownLatch saveFinished = new CountDownLatch(1);
        history.saveFinished = saveFinished;
        AtomicReference<AiChatView> restoredReference = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = new ContextThemeWrapper(
                    InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    R.style.Theme_AgentCostControl);
            AiChatView restored = new AiChatView(context);
            restored.setHistoryRepository(history);
            restored.restoreState(savedState.get());
            assertEquals(View.VISIBLE, restored.findViewById(R.id.chat_retry_save).getVisibility());
            restored.findViewById(R.id.chat_retry_save).performClick();
            restoredReference.set(restored);
        });

        assertTrue("Pending save did not retry", saveFinished.await(5, TimeUnit.SECONDS));
        waitForVisibility(restoredReference.get(), R.id.chat_retry_save, View.GONE);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AiChatView restored = restoredReference.get();
            assertEquals(pendingConversationId, history.lastSavedConversationId);
            assertEquals(pendingTurnId, history.lastSavedTurnId);
            assertEquals("A question from another chat", history.lastSavedQuestion);
            assertEquals("An incomplete saved answer", history.lastSavedAnswer);
            assertFalse(history.lastSavedComplete);
            restored.dispose();
        });
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int index = 0; index < count; index++) result.append(value);
        return result.toString();
    }

    private static void waitForVisibility(AiChatView chat, int viewId, int visibility) {
        long deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(5);
        AtomicReference<Integer> lastVisibility = new AtomicReference<>();
        do {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    lastVisibility.set(chat.findViewById(viewId).getVisibility()));
            if (lastVisibility.get() != null && lastVisibility.get() == visibility) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        assertEquals(Integer.valueOf(visibility), lastVisibility.get());
    }

    private static void waitForEnabled(AiChatView chat, int viewId, boolean enabled) {
        long deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(5);
        AtomicReference<Boolean> lastEnabled = new AtomicReference<>();
        do {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    lastEnabled.set(chat.findViewById(viewId).isEnabled()));
            if (lastEnabled.get() != null && lastEnabled.get() == enabled) return;
            SystemClock.sleep(25);
        } while (SystemClock.uptimeMillis() < deadline);
        assertEquals(Boolean.valueOf(enabled), lastEnabled.get());
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

    @Test
    public void historyConversationReopensAndNewChatRestoresEmptyState() throws Exception {
        String title = "Sample saved chat";
        String question = "Can you explain this bill?";
        String answer = "Your sample monthly total is stable.";
        FakeChatHistoryRepository history = new FakeChatHistoryRepository(title, question, answer, false);
        CountDownLatch conversationsLoaded = new CountDownLatch(1);
        CountDownLatch turnsLoaded = new CountDownLatch(1);
        history.conversationsLoaded = conversationsLoaded;
        history.turnsLoaded = turnsLoaded;
        AtomicReference<AiChatView> chatReference = new AtomicReference<>();
        AtomicReference<Boolean> lastStartedState = new AtomicReference<>();

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.auth_email)).check(matches(isDisplayed()));
            try {
                scenario.onActivity(activity -> {
                    AiChatView chat = new AiChatView(activity);
                    chat.setHistoryRepository(history);
                    chat.setOnConversationStateChangedListener(lastStartedState::set);
                    activity.addContentView(chat, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    chatReference.set(chat);
                    chat.findViewById(R.id.chat_history).performClick();
                });

                assertTrue("History request did not finish", conversationsLoaded.await(5, TimeUnit.SECONDS));
                waitForDialogText(title);
                onView(withText(title)).perform(click());
                assertTrue("Turn request did not finish", turnsLoaded.await(5, TimeUnit.SECONDS));
                waitForDisplayed(question);
                waitForDisplayed(answer);
                waitForDisplayed(InstrumentationRegistry.getInstrumentation().getTargetContext()
                        .getString(R.string.chat_incomplete));
                scenario.onActivity(activity -> {
                    AiChatView chat = chatReference.get();
                    LinearLayout turns = chat.findViewById(R.id.chat_turns);
                    assertEquals(Gravity.END, ((LinearLayout) turns.getChildAt(0)).getGravity()
                            & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
                    assertEquals(Gravity.START, ((LinearLayout) turns.getChildAt(1)).getGravity()
                            & Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK);
                    assertTrue(chat.hasStartedConversation());
                    assertEquals(Boolean.TRUE, lastStartedState.get());

                    chat.findViewById(R.id.chat_new).performClick();
                    assertFalse(chat.hasStartedConversation());
                    assertEquals(Boolean.FALSE, lastStartedState.get());
                    assertEquals(View.VISIBLE, chat.findViewById(R.id.chat_empty_state).getVisibility());
                });
                waitForTextToDisappear(question);
            } finally {
                scenario.onActivity(activity -> {
                    AiChatView chat = chatReference.get();
                    if (chat != null) chat.dispose();
                });
            }
        }
    }

    @Test
    public void reopeningOlderRemoteTurnKeepsNewerLocalAnswerAndItsRetry() throws Exception {
        String conversationId = UUID.randomUUID().toString();
        String turnId = UUID.randomUUID().toString();
        String question = "Why did my monthly total change?";
        String localAnswer = "The current saved answer includes the latest adjustment.";
        String remoteAnswer = "An older partial answer.";
        FakeChatHistoryRepository history = new FakeChatHistoryRepository(conversationId, turnId,
                "Updated sample chat", question, remoteAnswer, false);
        CountDownLatch conversationsLoaded = new CountDownLatch(1);
        CountDownLatch turnsLoaded = new CountDownLatch(1);
        history.conversationsLoaded = conversationsLoaded;
        history.turnsLoaded = turnsLoaded;
        Bundle state = new Bundle();
        state.putString("conversation_id", UUID.randomUUID().toString());
        state.putString("conversation_title", "Current chat");
        state.putBoolean("started", true);
        state.putStringArrayList("pending_conversation_ids",
                new ArrayList<>(Collections.singletonList(conversationId)));
        state.putStringArrayList("pending_turn_ids", new ArrayList<>(Collections.singletonList(turnId)));
        state.putStringArrayList("pending_titles",
                new ArrayList<>(Collections.singletonList("Updated sample chat")));
        state.putStringArrayList("pending_questions", new ArrayList<>(Collections.singletonList(question)));
        state.putStringArrayList("pending_answers", new ArrayList<>(Collections.singletonList(localAnswer)));
        state.putBooleanArray("pending_complete", new boolean[]{true});
        state.putBooleanArray("pending_remote_exists", new boolean[]{true});
        AtomicReference<AiChatView> chatReference = new AtomicReference<>();

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.auth_email)).check(matches(isDisplayed()));
            try {
                scenario.onActivity(activity -> {
                    AiChatView chat = new AiChatView(activity);
                    chat.setHistoryRepository(history);
                    chat.restoreState(state);
                    activity.addContentView(chat, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    chatReference.set(chat);
                    chat.findViewById(R.id.chat_history).performClick();
                });

                assertTrue("History list did not load", conversationsLoaded.await(5, TimeUnit.SECONDS));
                waitForDialogText("Updated sample chat");
                onView(withText("Updated sample chat")).perform(click());
                assertTrue("Remote turn did not load", turnsLoaded.await(5, TimeUnit.SECONDS));
                waitForDisplayed(localAnswer);
                onView(withText(remoteAnswer)).check(doesNotExist());
                onView(withId(R.id.chat_retry_save)).check(matches(isDisplayed()));
            } finally {
                scenario.onActivity(activity -> {
                    AiChatView chat = chatReference.get();
                    if (chat != null) chat.dispose();
                });
            }
        }
    }

    @Test
    public void deletingHistoryEntryRequiresConfirmation() throws Exception {
        String title = "Saved chat to delete";
        FakeChatHistoryRepository history = new FakeChatHistoryRepository(
                title, "A saved question", "A saved answer", true);
        CountDownLatch initialListLoaded = new CountDownLatch(1);
        CountDownLatch historyReloaded = new CountDownLatch(1);
        CountDownLatch deleteFinished = new CountDownLatch(1);
        history.conversationsLoaded = initialListLoaded;
        history.historyReloaded = historyReloaded;
        history.deleteFinished = deleteFinished;
        AtomicReference<AiChatView> chatReference = new AtomicReference<>();

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.auth_email)).check(matches(isDisplayed()));
            try {
                scenario.onActivity(activity -> {
                    AiChatView chat = new AiChatView(activity);
                    chat.setHistoryRepository(history);
                    activity.addContentView(chat, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    chatReference.set(chat);
                    chat.findViewById(R.id.chat_history).performClick();
                });

                assertTrue("History request did not finish", initialListLoaded.await(5, TimeUnit.SECONDS));
                waitForDialogText(title);
                String deleteDescription = InstrumentationRegistry.getInstrumentation().getTargetContext()
                        .getString(R.string.chat_history_delete) + ": " + title;
                onView(withContentDescription(deleteDescription)).perform(click());
                onView(withText(R.string.chat_history_delete_message)).check(matches(isDisplayed()));
                onView(withText(R.string.chat_cancel)).perform(click());
                assertEquals(0, history.deleteCount.get());
                String confirmation = InstrumentationRegistry.getInstrumentation().getTargetContext()
                        .getString(R.string.chat_history_delete_message);
                waitForTextToDisappear(confirmation);
                waitForDialogText(title);

                onView(withContentDescription(deleteDescription)).perform(click());
                waitForDialogText(confirmation);
                onView(withText(R.string.chat_delete)).perform(click());
                assertTrue("Delete request did not finish", deleteFinished.await(5, TimeUnit.SECONDS));
                assertTrue("History was not refreshed after delete", historyReloaded.await(5, TimeUnit.SECONDS));
                waitForTextToDisappear(title);
            } finally {
                scenario.onActivity(activity -> {
                    AiChatView chat = chatReference.get();
                    if (chat != null) chat.dispose();
                });
            }
        }
    }

    @Test
    public void failedDeleteBlocksSaveRetryUntilHistoryDeletionFinishes() throws Exception {
        String conversationId = UUID.randomUUID().toString();
        String turnId = UUID.randomUUID().toString();
        String title = "Chat with a pending save";
        FakeChatHistoryRepository history = new FakeChatHistoryRepository(conversationId, turnId,
                title, "A question awaiting save", "A partial answer", false);
        CountDownLatch conversationsLoaded = new CountDownLatch(1);
        CountDownLatch deleteStarted = new CountDownLatch(1);
        CountDownLatch deleteFinished = new CountDownLatch(1);
        CountDownLatch allowDeleteToFinish = new CountDownLatch(1);
        CountDownLatch saveFinished = new CountDownLatch(1);
        history.conversationsLoaded = conversationsLoaded;
        history.deleteStarted = deleteStarted;
        history.deleteFinished = deleteFinished;
        history.allowDeleteToFinish = allowDeleteToFinish;
        history.failDelete = true;
        history.saveFinished = saveFinished;

        Bundle state = new Bundle();
        state.putString("conversation_id", conversationId);
        state.putString("conversation_title", title);
        state.putBoolean("started", true);
        state.putStringArrayList("pending_conversation_ids",
                new ArrayList<>(Collections.singletonList(conversationId)));
        state.putStringArrayList("pending_turn_ids", new ArrayList<>(Collections.singletonList(turnId)));
        state.putStringArrayList("pending_titles", new ArrayList<>(Collections.singletonList(title)));
        state.putStringArrayList("pending_questions",
                new ArrayList<>(Collections.singletonList("A question awaiting save")));
        state.putStringArrayList("pending_answers",
                new ArrayList<>(Collections.singletonList("A partial answer")));
        state.putBooleanArray("pending_complete", new boolean[]{false});
        state.putBooleanArray("pending_remote_exists", new boolean[]{true});
        AtomicReference<AiChatView> chatReference = new AtomicReference<>();

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.auth_email)).check(matches(isDisplayed()));
            try {
                scenario.onActivity(activity -> {
                    AiChatView chat = new AiChatView(activity);
                    chat.setHistoryRepository(history);
                    chat.restoreState(state);
                    activity.addContentView(chat, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    chatReference.set(chat);
                    chat.findViewById(R.id.chat_history).performClick();
                });

                assertTrue("History list did not load", conversationsLoaded.await(5, TimeUnit.SECONDS));
                waitForDialogText(title);
                String deleteDescription = InstrumentationRegistry.getInstrumentation().getTargetContext()
                        .getString(R.string.chat_history_delete) + ": " + title;
                onView(withContentDescription(deleteDescription)).perform(click());
                onView(withText(R.string.chat_delete)).perform(click());
                assertTrue("Delete did not start", deleteStarted.await(5, TimeUnit.SECONDS));

                scenario.onActivity(activity -> {
                    AiChatView chat = chatReference.get();
                    chat.closeHistory();
                    View retry = chat.findViewById(R.id.chat_retry_save);
                    assertEquals(View.VISIBLE, retry.getVisibility());
                    assertFalse("Retry stayed enabled while DELETE was pending", retry.isEnabled());
                    assertTrue("Programmatic retry did not reach its listener", retry.callOnClick());
                    assertEquals("A save was queued behind the pending DELETE", 0, history.saveCount.get());
                });

                allowDeleteToFinish.countDown();
                assertTrue("Delete failure was not returned", history.deleteFinished.await(5, TimeUnit.SECONDS));
                waitForEnabled(chatReference.get(), R.id.chat_retry_save, true);
                scenario.onActivity(activity -> chatReference.get()
                        .findViewById(R.id.chat_retry_save).performClick());
                assertTrue("Pending turn was not recoverable after delete failure",
                        saveFinished.await(5, TimeUnit.SECONDS));
                waitForVisibility(chatReference.get(), R.id.chat_retry_save, View.GONE);
                assertEquals(1, history.saveCount.get());
                assertEquals(conversationId, history.lastSavedConversationId);
                assertEquals(turnId, history.lastSavedTurnId);
            } finally {
                allowDeleteToFinish.countDown();
                scenario.onActivity(activity -> {
                    AiChatView chat = chatReference.get();
                    if (chat != null) chat.dispose();
                });
            }
        }
    }

    @Test
    public void historyLoadFailureCanRetryIntoAnEmptyState() throws Exception {
        FakeChatHistoryRepository history = new FakeChatHistoryRepository(
                "Unused chat", "An unused question", "An unused answer", true);
        history.emptyHistory = true;
        history.failuresRemaining.set(1);
        CountDownLatch firstLoad = new CountDownLatch(1);
        CountDownLatch retryLoad = new CountDownLatch(1);
        history.conversationsLoaded = firstLoad;
        history.emptyHistoryLoaded = retryLoad;
        AtomicReference<AiChatView> chatReference = new AtomicReference<>();

        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            onView(withId(R.id.auth_email)).check(matches(isDisplayed()));
            try {
                scenario.onActivity(activity -> {
                    AiChatView chat = new AiChatView(activity);
                    chat.setHistoryRepository(history);
                    activity.addContentView(chat, new ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    chatReference.set(chat);
                    chat.findViewById(R.id.chat_history).performClick();
                });

                assertTrue("History request did not finish", firstLoad.await(5, TimeUnit.SECONDS));
                String failure = InstrumentationRegistry.getInstrumentation().getTargetContext()
                        .getString(R.string.chat_history_load_failed);
                waitForDialogText(failure);
                onView(withText(R.string.chat_retry)).perform(click());
                assertTrue("Retry did not finish", retryLoad.await(5, TimeUnit.SECONDS));
                waitForDialogText(InstrumentationRegistry.getInstrumentation().getTargetContext()
                        .getString(R.string.chat_history_empty));
            } finally {
                scenario.onActivity(activity -> {
                    AiChatView chat = chatReference.get();
                    if (chat != null) chat.dispose();
                });
            }
        }
    }

    private static void waitForDialogText(String text) {
        // The activity loses focus while history/confirmation dialogs are opening.
        onView(withText(text)).inRoot(isDialog()).check(matches(isDisplayed()));
    }

    private static void waitForDisplayed(String text) {
        long deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(5);
        Throwable lastFailure = null;
        do {
            try {
                onView(withText(text)).check(matches(isDisplayed()));
                return;
            } catch (AssertionError | RuntimeException error) {
                lastFailure = error;
                SystemClock.sleep(25);
            }
        } while (SystemClock.uptimeMillis() < deadline);
        AssertionError failure = new AssertionError("View did not become visible: " + text);
        if (lastFailure != null) failure.initCause(lastFailure);
        throw failure;
    }

    private static void waitForTextToDisappear(String text) {
        long deadline = SystemClock.uptimeMillis() + TimeUnit.SECONDS.toMillis(5);
        Throwable lastFailure = null;
        do {
            try {
                onView(withText(text)).check(doesNotExist());
                return;
            } catch (AssertionError | RuntimeException error) {
                lastFailure = error;
                SystemClock.sleep(25);
            }
        } while (SystemClock.uptimeMillis() < deadline);
        AssertionError failure = new AssertionError("View remained visible: " + text);
        if (lastFailure != null) failure.initCause(lastFailure);
        throw failure;
    }

    private static final class FakeChatHistoryRepository implements ChatHistoryRepository {
        private final ChatConversation conversation;
        private final ChatTurn turn;
        private volatile boolean deleted;
        private volatile CountDownLatch conversationsLoaded;
        private volatile CountDownLatch turnsLoaded;
        private volatile CountDownLatch historyReloaded;
        private volatile CountDownLatch deleteFinished;
        private volatile CountDownLatch deleteStarted;
        private volatile CountDownLatch allowDeleteToFinish;
        private volatile CountDownLatch emptyHistoryLoaded;
        private volatile CountDownLatch saveFinished;
        private volatile String lastSavedConversationId;
        private volatile String lastSavedTurnId;
        private volatile String lastSavedQuestion;
        private volatile String lastSavedAnswer;
        private volatile boolean lastSavedComplete;
        private volatile boolean failDelete;
        private volatile boolean emptyHistory;
        private final AtomicInteger failuresRemaining = new AtomicInteger();
        private final AtomicInteger listCount = new AtomicInteger();
        private final AtomicInteger deleteCount = new AtomicInteger();
        private final AtomicInteger saveCount = new AtomicInteger();

        FakeChatHistoryRepository(String title, String question, String answer, boolean complete) {
            this(UUID.randomUUID().toString(), UUID.randomUUID().toString(), title, question, answer, complete);
        }

        FakeChatHistoryRepository(String conversationId, String turnId, String title,
                                  String question, String answer, boolean complete) {
            OffsetDateTime now = OffsetDateTime.now();
            conversation = new ChatConversation(conversationId, title, now, now);
            turn = new ChatTurn(conversationId, turnId, question, answer,
                    complete, now, now);
        }

        @Override public ChatHistoryScope captureScope() { return null; }

        @Override public List<ChatConversation> listConversations(ChatHistoryScope scope, int offset, int limit)
                throws ApiException {
            int call = listCount.incrementAndGet();
            if (call == 1 && conversationsLoaded != null) {
                conversationsLoaded.countDown();
            }
            if (deleted && historyReloaded != null) historyReloaded.countDown();
            if (failuresRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new ApiException(ApiException.Category.NETWORK, "Temporary failure", null, 0);
            }
            if (emptyHistory && emptyHistoryLoaded != null) emptyHistoryLoaded.countDown();
            return deleted || emptyHistory ? Collections.emptyList() : Collections.singletonList(conversation);
        }

        @Override public List<ChatTurn> listTurns(ChatHistoryScope scope, String conversationId,
                                                   int offset, int limit) {
            if (turnsLoaded != null) turnsLoaded.countDown();
            return conversationId.equals(conversation.getId())
                    ? Collections.singletonList(turn) : Collections.emptyList();
        }

        @Override public ChatTurn saveTurn(ChatHistoryScope scope, String conversationId, String turnId,
                                           String title, String question, String answer, boolean complete) {
            saveCount.incrementAndGet();
            lastSavedConversationId = conversationId;
            lastSavedTurnId = turnId;
            lastSavedQuestion = question;
            lastSavedAnswer = answer;
            lastSavedComplete = complete;
            OffsetDateTime now = OffsetDateTime.now();
            if (saveFinished != null) saveFinished.countDown();
            return new ChatTurn(conversationId, turnId, question, answer, complete, now, now);
        }

        @Override public void deleteConversation(ChatHistoryScope scope, String conversationId) throws ApiException {
            deleteCount.incrementAndGet();
            if (deleteStarted != null) deleteStarted.countDown();
            if (allowDeleteToFinish != null) {
                try {
                    allowDeleteToFinish.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(ApiException.Category.NETWORK, "Delete interrupted", null, 0, error);
                }
            }
            if (deleteFinished != null) deleteFinished.countDown();
            if (failDelete) {
                throw new ApiException(ApiException.Category.NETWORK, "Delete failed", null, 0);
            }
            deleted = true;
        }
    }
}
