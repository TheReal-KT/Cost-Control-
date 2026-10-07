package com.example.agentcostcontrol.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.agentcostcontrol.BuildConfig;
import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.chat.ChatClient;
import com.google.android.material.button.MaterialButton;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Compact native composer and transient transcript. The server owns model execution. */
public final class AiChatView extends LinearLayout {
    private final EditText prompt;
    private final TextView feedback;
    private final TextView empty;
    private final LinearLayout messages;
    private final ScrollView scroll;
    private final MaterialButton send;
    private final ChatClient client = new ChatClient();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<String> transcript = new ArrayList<>();
    private String accessToken;
    private String conversationId;
    private Future<?> pending;
    private int generation;
    private boolean disposed;

    public AiChatView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.screen_ai_chat, this, true);
        prompt = findViewById(R.id.chat_prompt);
        feedback = findViewById(R.id.chat_feedback);
        empty = findViewById(R.id.chat_empty);
        messages = findViewById(R.id.chat_messages);
        scroll = findViewById(R.id.chat_scroll);
        send = findViewById(R.id.chat_send);
        MaterialButton model = findViewById(R.id.chat_model);
        model.setOnClickListener(view -> {
            PopupMenu menu = new PopupMenu(context, model);
            menu.getMenu().add(R.string.chat_model_full).setCheckable(true).setChecked(true);
            menu.setOnMenuItemClickListener(item -> {
                showFeedback(R.string.chat_free_notice);
                return true;
            });
            menu.show();
        });
        send.setOnClickListener(view -> {
            if (pending != null) stop(); else submit();
        });
    }

    public void setAccessToken(String token) {
        accessToken = token;
    }

    private void submit() {
        String question = prompt.getText().toString().trim();
        if (question.isEmpty() || disposed) return;
        if (question.length() > 4000) {
            showFeedback(R.string.chat_prompt_too_long);
            return;
        }
        if (accessToken == null || accessToken.isEmpty()) {
            showFeedback(R.string.chat_sign_in);
            return;
        }
        if (BuildConfig.AI_ENDPOINT_URL.isEmpty()) {
            showFeedback(R.string.chat_not_configured);
            return;
        }
        if (!BuildConfig.AI_ENDPOINT_URL.startsWith("https://")) {
            showFeedback(R.string.chat_endpoint_invalid);
            return;
        }
        int requestGeneration = ++generation;
        String token = accessToken;
        String previousConversation = conversationId;
        prompt.setEnabled(false);
        send.setText(R.string.chat_stop);
        showFeedback(R.string.chat_wait);
        pending = worker.submit(() -> {
            try {
                ChatClient.Reply reply = client.ask(BuildConfig.AI_ENDPOINT_URL, token,
                        question, previousConversation);
                post(() -> {
                    if (disposed || requestGeneration != generation) return;
                    conversationId = reply.conversationId;
                    transcript.add(question);
                    transcript.add(reply.answer);
                    while (transcript.size() > 20) {
                        transcript.remove(0);
                        transcript.remove(0);
                    }
                    renderTranscript();
                    prompt.setText("");
                    feedback.setVisibility(GONE);
                    idle();
                });
            } catch (ChatClient.Failure error) {
                int message = error.httpStatus == 429 ? R.string.chat_limited
                        : error.httpStatus == 401 ? R.string.chat_session_expired
                        : error.httpStatus == 404 ? R.string.chat_conversation_expired : R.string.chat_failed;
                fail(requestGeneration, message, error.httpStatus == 404);
            } catch (JSONException error) {
                fail(requestGeneration, R.string.chat_invalid_response);
            } catch (IOException error) {
                fail(requestGeneration, R.string.chat_failed);
            }
        });
    }

    private void fail(int requestGeneration, int message) {
        fail(requestGeneration, message, false);
    }

    private void fail(int requestGeneration, int message, boolean clearConversation) {
        post(() -> {
            if (disposed || requestGeneration != generation) return;
            if (clearConversation) conversationId = null;
            showFeedback(message);
            idle();
        });
    }

    private void idle() {
        pending = null;
        prompt.setEnabled(true);
        send.setText(R.string.chat_send);
    }

    private void stop() {
        generation++;
        client.cancel();
        if (pending != null) pending.cancel(true);
        feedback.setVisibility(GONE);
        idle();
    }

    private void showFeedback(int resource) {
        feedback.setText(resource);
        feedback.setVisibility(VISIBLE);
    }

    private void renderTranscript() {
        messages.removeAllViews();
        messages.addView(empty);
        empty.setVisibility(transcript.isEmpty() ? VISIBLE : GONE);
        for (int index = 0; index < transcript.size(); index++) {
            TextView label = new TextView(getContext());
            label.setText(index % 2 == 0 ? R.string.chat_you : R.string.chat_assistant);
            label.setTextColor(getContext().getColor(R.color.text_secondary));
            label.setTextSize(13);
            label.setPadding(0, dp(20), 0, dp(6));
            messages.addView(label);
            TextView body = new TextView(getContext());
            body.setText(transcript.get(index));
            body.setTextColor(getContext().getColor(R.color.text_primary));
            body.setTextSize(16);
            body.setTextIsSelectable(true);
            body.setLineSpacing(dp(3), 1);
            messages.addView(body);
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    public Bundle saveState() {
        Bundle state = new Bundle();
        state.putString("draft", prompt.getText().toString());
        state.putString("conversation", conversationId);
        state.putStringArrayList("transcript", new ArrayList<>(transcript));
        return state;
    }

    public void restoreState(Bundle state) {
        if (state == null || disposed) return;
        prompt.setText(state.getString("draft", ""));
        conversationId = state.getString("conversation");
        ArrayList<String> saved = state.getStringArrayList("transcript");
        if (saved != null && saved.size() <= 20 && saved.size() % 2 == 0) {
            transcript.clear();
            transcript.addAll(saved);
            renderTranscript();
        }
    }

    public void dispose() {
        disposed = true;
        stop();
        accessToken = null;
        conversationId = null;
        transcript.clear();
        prompt.setText("");
        worker.shutdownNow();
    }
}
