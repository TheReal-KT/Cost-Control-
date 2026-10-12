package com.example.agentcostcontrol.ui;

import android.app.Dialog;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.agentcostcontrol.BuildConfig;
import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.chat.ChatClient;
import com.example.agentcostcontrol.data.ApiException;
import com.example.agentcostcontrol.data.ChatHistoryRepository;
import com.example.agentcostcontrol.data.ChatHistoryScope;
import com.example.agentcostcontrol.model.ChatConversation;
import com.example.agentcostcontrol.model.ChatTurn;
import com.google.android.material.button.MaterialButton;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Native chat composer, streamed transcript, and account-bound conversation history. */
public final class AiChatView extends LinearLayout {
    private static final long STREAM_RENDER_INTERVAL_MS = 50;
    private static final int CONVERSATION_PAGE_SIZE = 25;
    private static final int TURN_PAGE_SIZE = 40;
    private static final int SAVED_TURN_LIMIT = 40;
    private static final int SAVED_DRAFT_LIMIT = 20;
    private static final int MAX_PENDING_STATE_CHARS = 50_000;
    private static final int MAX_DRAFT_STATE_CHARS = 8_000;
    private static final int MAX_PERSISTED_STATE_CHARS = 22_000;
    private static final int MAX_PENDING_TURNS = 40;
    private static final int MAX_TITLE_LENGTH = 80;
    private static final String NEW_CHAT_DRAFT_KEY = "new-chat";
    private static final String STATE_DRAFT = "draft";
    private static final String STATE_CONVERSATION_ID = "conversation_id";
    private static final String STATE_ENDPOINT_CONVERSATION_ID = "endpoint_conversation_id";
    private static final String STATE_CONVERSATION_TITLE = "conversation_title";
    private static final String STATE_STARTED = "started";
    private static final String STATE_HISTORY_OFFSET = "history_offset";
    private static final String STATE_HAS_MORE_TURNS = "history_has_more";
    private static final String STATE_TURN_IDS = "turn_ids";
    private static final String STATE_TURN_QUESTIONS = "turn_questions";
    private static final String STATE_TURN_ANSWERS = "turn_answers";
    private static final String STATE_TURN_COMPLETE = "turn_complete";
    private static final String STATE_TURN_PERSISTED = "turn_persisted";
    private static final String STATE_TURN_REMOTE_EXISTS = "turn_remote_exists";
    private static final String STATE_TURN_SAVE_FAILED = "turn_save_failed";
    private static final String STATE_DRAFT_KEYS = "draft_keys";
    private static final String STATE_DRAFT_VALUES = "draft_values";
    private static final String STATE_PENDING_CONVERSATION_IDS = "pending_conversation_ids";
    private static final String STATE_PENDING_TURN_IDS = "pending_turn_ids";
    private static final String STATE_PENDING_TITLES = "pending_titles";
    private static final String STATE_PENDING_QUESTIONS = "pending_questions";
    private static final String STATE_PENDING_ANSWERS = "pending_answers";
    private static final String STATE_PENDING_COMPLETE = "pending_complete";
    private static final String STATE_PENDING_REMOTE_EXISTS = "pending_remote_exists";

    private static final class LocalTurn {
        final String id;
        final String conversationId;
        final String title;
        final String question;
        String answer;
        boolean complete;
        boolean persisted;
        boolean remoteExists;
        boolean streaming;
        boolean saving;
        boolean saveFailed;
        long contentRevision;
        long saveAttempt;

        LocalTurn(String id, String conversationId, String title, String question, String answer,
                  boolean complete, boolean persisted) {
            this.id = id;
            this.conversationId = conversationId;
            this.title = title;
            this.question = question;
            this.answer = answer;
            this.complete = complete;
            this.persisted = persisted;
            this.remoteExists = persisted;
        }
    }

    private final EditText prompt;
    private final TextView feedback;
    private final View emptyState;
    private final TextView empty;
    private final TextView emptySubtitle;
    private final LinearLayout messages;
    private final ScrollView scroll;
    private final MaterialButton send;
    private final MaterialButton retrySave;
    private final ChatClient client = new ChatClient();
    private final ExecutorService modelWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService historyWorker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object streamUpdateLock = new Object();
    private final StringBuilder pendingStreamText = new StringBuilder();
    private final ArrayList<LocalTurn> transcript = new ArrayList<>();
    private final ArrayList<ChatConversation> historyConversations = new ArrayList<>();
    private final Map<String, String> endpointIdsByConversation = new HashMap<>();
    private final Map<String, ArrayList<LocalTurn>> unsavedByConversation = new HashMap<>();
    private final Map<String, String> draftsByConversation = new HashMap<>();
    private final Set<String> deletingConversationIds = new HashSet<>();

    private String accessToken;
    private String conversationId;
    private String endpointConversationId;
    private String conversationTitle;
    private ChatHistoryRepository historyRepository;
    private Consumer<Boolean> conversationStateChangedListener;
    private Runnable openInsightsListener;
    private Future<?> pending;
    private volatile int generation;
    private int historyListGeneration;
    private int conversationLoadGeneration;
    private int turnPageGeneration;
    private boolean disposed;
    private boolean requestActive;
    private boolean startedConversation;
    private boolean historyLoading;
    private boolean historyLoadFailed;
    private boolean historyHasMoreConversations;
    private boolean hasMoreEarlierTurns;
    private boolean conversationLoadFailed;
    private boolean historyDeleteFailed;
    private boolean loadingConversation;
    private boolean loadingEarlierTurns;
    private boolean earlierTurnsLoadFailed;
    private boolean suppressDraftTracking;
    private int historyOffset;
    private int turnOffset;
    private StringBuilder streamingAnswer = new StringBuilder();
    private Runnable streamFlushTask;
    private LocalTurn streamFlushTurn;
    private int streamFlushGeneration;
    private boolean streamingFeedbackShown;
    private LocalTurn activeTurn;
    private LocalTurn retryableTurn;
    private TextView streamingBody;

    private Dialog historyDialog;
    private TextView historyStatus;
    private LinearLayout historyRows;
    private MaterialButton historyRetry;
    private MaterialButton historyLoadMore;
    private MaterialButton earlierTurnsControl;
    private ChatConversation conversationToRetry;

    public AiChatView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        LayoutInflater.from(context).inflate(R.layout.screen_ai_chat, this, true);
        prompt = findViewById(R.id.chat_prompt);
        prompt.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        feedback = findViewById(R.id.chat_feedback);
        emptyState = findViewById(R.id.chat_empty_state);
        empty = findViewById(R.id.chat_empty);
        emptySubtitle = findViewById(R.id.chat_empty_subtitle);
        messages = findViewById(R.id.chat_turns);
        scroll = findViewById(R.id.chat_scroll);
        scroll.addOnLayoutChangeListener((view, left, top, right, bottom,
                                         oldLeft, oldTop, oldRight, oldBottom) -> {
            int oldHeight = oldBottom - oldTop;
            if (bottom - top >= oldHeight || scroll.getChildCount() == 0) return;
            // Keep the latest reply visible if the reader was at the bottom before resizing.
            int contentBottom = scroll.getChildAt(0).getBottom();
            if (scroll.getScrollY() + oldHeight >= contentBottom - dp(24)) {
                scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
            }
        });
        send = findViewById(R.id.chat_send);
        retrySave = findViewById(R.id.chat_retry_save);

        prompt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (!suppressDraftTracking && !requestActive) {
                    draftsByConversation.put(draftKey(), value.toString());
                }
            }
            @Override public void afterTextChanged(Editable value) { }
        });

        View model = findViewById(R.id.chat_model);
        model.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
            }
        });
        model.setOnClickListener(view -> {
            android.widget.PopupMenu menu = new android.widget.PopupMenu(context, model);
            menu.getMenu().add(R.string.chat_model_full).setCheckable(true).setChecked(true);
            menu.setOnMenuItemClickListener(item -> {
                showFeedback(R.string.chat_free_notice);
                return true;
            });
            menu.show();
        });

        ImageButton history = findViewById(R.id.chat_history);
        history.setTooltipText(getContext().getString(R.string.chat_history_open));
        history.setOnClickListener(view -> openHistory());
        ImageButton newChat = findViewById(R.id.chat_new);
        newChat.setTooltipText(getContext().getString(R.string.chat_new));
        newChat.setOnClickListener(view -> startNewChat());
        findViewById(R.id.chat_open_insights).setOnClickListener(view -> {
            if (openInsightsListener != null) {
                prompt.clearFocus();
                openInsightsListener.run();
            }
        });

        findViewById(R.id.chat_suggestion_renew).setOnClickListener(
                view -> useSuggestion(R.string.chat_suggestion_renew));
        findViewById(R.id.chat_suggestion_spend).setOnClickListener(
                view -> useSuggestion(R.string.chat_suggestion_spend));
        findViewById(R.id.chat_suggestion_review).setOnClickListener(
                view -> useSuggestion(R.string.chat_suggestion_review));

        send.setOnClickListener(view -> {
            if (requestActive) stopActiveTurn(); else submit();
        });
        retrySave.setOnClickListener(view -> retryFailedSave());
        setSending(false);
        updateSaveControls();
    }

    public void setOnOpenInsightsListener(Runnable listener) {
        openInsightsListener = listener;
    }

    public void setAccessToken(String token) {
        accessToken = token;
    }

    public void setHistoryRepository(ChatHistoryRepository source) {
        historyRepository = source;
        updateSaveControls();
        if (historyDialog != null && historyDialog.isShowing() && historyRepository != null
                && historyConversations.isEmpty() && !historyLoading) {
            loadConversations(false);
        }
    }

    public void setOnConversationStateChangedListener(Consumer<Boolean> listener) {
        conversationStateChangedListener = listener;
        if (listener != null) listener.accept(startedConversation);
    }

    public boolean hasStartedConversation() {
        return startedConversation;
    }

    public boolean handleBackPress() {
        if (historyDialog == null || !historyDialog.isShowing()) return false;
        closeHistory();
        return true;
    }

    public void closeHistory() {
        Dialog current = historyDialog;
        if (current == null) return;
        historyListGeneration++;
        conversationLoadGeneration++;
        historyDialog = null;
        historyStatus = null;
        historyRows = null;
        historyRetry = null;
        historyLoadMore = null;
        conversationToRetry = null;
        historyLoading = false;
        loadingConversation = false;
        if (current.isShowing()) current.dismiss();
        refreshComposerEnabled();
    }

    private void useSuggestion(int resource) {
        if (disposed || requestActive) return;
        prompt.setText(resource);
        prompt.setSelection(prompt.getText().length());
        prompt.requestFocus();
    }

    private String draftKey() {
        return conversationId == null ? NEW_CHAT_DRAFT_KEY : conversationId;
    }

    private void setDraftText(String draft) {
        suppressDraftTracking = true;
        prompt.setText(draft);
        prompt.setSelection(prompt.getText().length());
        suppressDraftTracking = false;
        draftsByConversation.put(draftKey(), draft);
    }

    private void submit() {
        String question = prompt.getText().toString().trim();
        if (question.isEmpty() || disposed || loadingConversation) return;
        if (conversationId != null && deletingConversationIds.contains(conversationId)) {
            showFeedback(R.string.chat_history_deleting);
            return;
        }
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

        LocalTurn reusableTurn = retryableTurn != null && conversationId != null
                && retryableTurn.conversationId.equals(conversationId)
                && retryableTurn.question.equals(question) && !retryableTurn.complete
                ? retryableTurn : null;
        int pendingChars = pendingTurnStateCharacters();
        int projectedPendingChars = pendingChars + pendingTurnStateCharactersForNew(question);
        int projectedPendingCount = pendingTurnCount();
        if (reusableTurn != null && !reusableTurn.persisted) {
            projectedPendingChars = pendingChars + Math.max(0, 16_000 - reusableTurn.answer.length());
        } else {
            projectedPendingCount++;
        }
        if (projectedPendingChars > MAX_PENDING_STATE_CHARS || projectedPendingCount > MAX_PENDING_TURNS) {
            showFeedback(R.string.chat_pending_saves_limit);
            return;
        }

        if (conversationId == null) {
            conversationId = UUID.randomUUID().toString();
            conversationTitle = makeTitle(question);
            endpointConversationId = null;
            draftsByConversation.remove(NEW_CHAT_DRAFT_KEY);
            draftsByConversation.put(conversationId, "");
            turnOffset = 0;
            loadingEarlierTurns = false;
            earlierTurnsLoadFailed = false;
            hasMoreEarlierTurns = false;
        }
        draftsByConversation.put(conversationId, "");

        LocalTurn turn = reusableTurn;
        if (turn == null) {
            turn = new LocalTurn(UUID.randomUUID().toString(), conversationId, conversationTitle,
                    question, "", false, false);
            transcript.add(turn);
        } else {
            turn.contentRevision++;
            turn.answer = "";
            turn.complete = false;
            turn.persisted = false;
            turn.saveFailed = false;
            turn.saving = false;
            if (!transcript.contains(turn)) transcript.add(turn);
        }
        retryableTurn = null;
        activeTurn = turn;
        activeTurn.streaming = true;
        streamingAnswer = new StringBuilder();
        clearPendingStreamText();
        streamingFeedbackShown = false;
        setStartedConversation(true);

        int requestGeneration = ++generation;
        String token = accessToken;
        String requestConversationId = conversationId;
        String endpointConversation = endpointConversationId;
        LocalTurn submittedTurn = turn;
        String questionForRequest = submittedTurn.question;
        prompt.setEnabled(false);
        requestActive = true;
        setSending(true);
        renderMessages(true);
        showFeedback(R.string.chat_wait);
        updateSaveControls();

        pending = modelWorker.submit(() -> {
            try {
                ChatClient.Reply reply = client.askStream(BuildConfig.AI_ENDPOINT_URL, token,
                        questionForRequest, endpointConversation,
                        text -> queueStreamText(requestGeneration, submittedTurn, text));
                mainHandler.post(() -> {
                    flushPendingStreamText(requestGeneration, submittedTurn);
                    if (disposed || requestGeneration != generation || activeTurn != submittedTurn
                            || !requestConversationId.equals(conversationId)) return;
                    endpointConversationId = reply.conversationId;
                    endpointIdsByConversation.put(requestConversationId, reply.conversationId);
                    submittedTurn.answer = reply.answer;
                    submittedTurn.complete = true;
                    submittedTurn.contentRevision++;
                    submittedTurn.persisted = false;
                    submittedTurn.saveFailed = false;
                    addUnsavedTurn(submittedTurn);
                    submittedTurn.streaming = false;
                    activeTurn = null;
                    retryableTurn = null;
                    streamingAnswer.setLength(0);
                    streamingBody = null;
                    streamingFeedbackShown = false;
                    setDraftText("");
                    showFeedback(R.string.chat_saving);
                    idle();
                    renderMessages(true);
                    persistTurn(submittedTurn);
                });
            } catch (ChatClient.Failure error) {
                int message = error.httpStatus == 429 ? R.string.chat_limited
                        : error.httpStatus == 401 ? R.string.chat_session_expired
                        : error.httpStatus == 404 ? R.string.chat_conversation_expired : R.string.chat_failed;
                fail(requestGeneration, submittedTurn, message, error.httpStatus == 404);
            } catch (JSONException error) {
                fail(requestGeneration, submittedTurn, R.string.chat_invalid_response, false);
            } catch (IOException error) {
                fail(requestGeneration, submittedTurn, R.string.chat_failed, false);
            }
        });
    }

    private void fail(int requestGeneration, LocalTurn turn, int message, boolean clearBackendCorrelation) {
        mainHandler.post(() -> {
            if (disposed || requestGeneration != generation || activeTurn != turn
                    || !turn.conversationId.equals(conversationId)) return;
            flushPendingStreamText(requestGeneration, turn);
            if (clearBackendCorrelation) {
                endpointConversationId = null;
                endpointIdsByConversation.remove(turn.conversationId);
            }
            turn.answer = streamingAnswer.toString();
            turn.complete = false;
            turn.streaming = false;
            activeTurn = null;
            retryableTurn = turn;
            streamingAnswer.setLength(0);
            streamingBody = null;
            streamingFeedbackShown = false;
            draftsByConversation.put(conversationId, turn.question);
            showFeedback(message);
            idle();
            renderMessages(true);
            persistTurn(turn);
        });
    }

    private void idle() {
        pending = null;
        requestActive = false;
        prompt.setEnabled(true);
        setSending(false);
    }

    private void stopActiveTurn() {
        if (!requestActive && activeTurn == null) return;
        LocalTurn stopped = activeTurn;
        int stoppedGeneration = generation;
        generation++;
        if (stopped != null) flushPendingStreamText(stoppedGeneration, stopped, true);
        client.cancel();
        if (pending != null) pending.cancel(true);
        pending = null;
        requestActive = false;
        prompt.setEnabled(true);
        setSending(false);
        if (stopped != null) {
            stopped.answer = streamingAnswer.toString();
            stopped.complete = false;
            stopped.streaming = false;
            retryableTurn = stopped;
            draftsByConversation.put(stopped.conversationId, stopped.question);
            activeTurn = null;
            persistTurn(stopped);
        }
        streamingAnswer.setLength(0);
        streamingBody = null;
        streamingFeedbackShown = false;
        feedback.setVisibility(GONE);
        renderMessages(true);
    }

    private void queueStreamText(int requestGeneration, LocalTurn turn, String text) {
        synchronized (streamUpdateLock) {
            if (requestGeneration != generation) return;
            pendingStreamText.append(text);
            if (streamFlushTask != null) return;
            streamFlushGeneration = requestGeneration;
            streamFlushTurn = turn;
            streamFlushTask = () -> flushPendingStreamText(requestGeneration, turn);
            mainHandler.postDelayed(streamFlushTask, STREAM_RENDER_INTERVAL_MS);
        }
    }

    private void flushPendingStreamText(int requestGeneration, LocalTurn turn) {
        flushPendingStreamText(requestGeneration, turn, false);
    }

    private void flushPendingStreamText(int requestGeneration, LocalTurn turn, boolean keepStoppedText) {
        String delta;
        synchronized (streamUpdateLock) {
            if (streamFlushTask == null || streamFlushGeneration != requestGeneration
                    || streamFlushTurn != turn) return;
            mainHandler.removeCallbacks(streamFlushTask);
            streamFlushTask = null;
            streamFlushTurn = null;
            delta = pendingStreamText.toString();
            pendingStreamText.setLength(0);
        }
        if (delta.isEmpty() || disposed || (!keepStoppedText && requestGeneration != generation)
                || activeTurn != turn) return;

        streamingAnswer.append(delta);
        turn.answer = streamingAnswer.toString();
        turn.contentRevision++;
        turn.persisted = false;
        turn.saveFailed = false;
        addUnsavedTurn(turn);
        if (streamingBody != null) streamingBody.append(delta);
        if (!streamingFeedbackShown) {
            showFeedback(R.string.chat_streaming);
            streamingFeedbackShown = true;
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private void clearPendingStreamText() {
        synchronized (streamUpdateLock) {
            if (streamFlushTask != null) mainHandler.removeCallbacks(streamFlushTask);
            streamFlushTask = null;
            streamFlushTurn = null;
            pendingStreamText.setLength(0);
        }
    }

    private void startNewChat() {
        if (disposed) return;
        boolean wasSending = requestActive;
        if (conversationId == null) draftsByConversation.remove(NEW_CHAT_DRAFT_KEY);
        else draftsByConversation.put(conversationId,
                wasSending ? "" : prompt.getText().toString());
        stopActiveTurn();
        if (conversationId != null && wasSending) draftsByConversation.put(conversationId, "");
        closeHistory();
        conversationId = null;
        endpointConversationId = null;
        conversationTitle = null;
        transcript.clear();
        retryableTurn = null;
        turnOffset = 0;
        hasMoreEarlierTurns = false;
        loadingEarlierTurns = false;
        earlierTurnsLoadFailed = false;
        setDraftText("");
        draftsByConversation.remove(NEW_CHAT_DRAFT_KEY);
        prompt.setEnabled(true);
        feedback.setVisibility(GONE);
        setStartedConversation(false);
        updateSaveControls();
        renderMessages(false);
    }

    private void setStartedConversation(boolean started) {
        if (startedConversation == started) return;
        startedConversation = started;
        if (conversationStateChangedListener != null) {
            conversationStateChangedListener.accept(started);
        }
    }

    private void showFeedback(int resource) {
        showFeedbackMessage(getContext().getString(resource));
    }

    private void showFeedbackMessage(String text) {
        feedback.setText(text);
        feedback.setVisibility(VISIBLE);
    }

    private void renderMessages(boolean scrollToBottom) {
        emptyState.setVisibility(transcript.isEmpty() && !loadingConversation ? VISIBLE : GONE);
        empty.setVisibility(VISIBLE);
        emptySubtitle.setVisibility(VISIBLE);
        findViewById(R.id.chat_suggestion_renew).setVisibility(VISIBLE);
        findViewById(R.id.chat_suggestion_spend).setVisibility(VISIBLE);
        findViewById(R.id.chat_suggestion_review).setVisibility(VISIBLE);

        messages.removeAllViews();
        earlierTurnsControl = null;
        streamingBody = null;
        if (hasMoreEarlierTurns || earlierTurnsLoadFailed || loadingEarlierTurns) addEarlierTurnsControl();
        for (LocalTurn turn : transcript) {
            TextView body = addTurn(turn);
            if (turn.streaming) streamingBody = body;
        }
        if (scrollToBottom) scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        updateSaveControls();
    }

    private void addEarlierTurnsControl() {
        MaterialButton earlier = new MaterialButton(getContext());
        earlier.setText(loadingEarlierTurns ? R.string.chat_history_loading_earlier : earlierTurnsLoadFailed
                ? R.string.chat_history_earlier_failed : R.string.chat_history_earlier);
        earlier.setAllCaps(false);
        earlier.setMinHeight(dp(48));
        earlierTurnsControl = earlier;
        refreshEarlierTurnsControl();
        earlier.setOnClickListener(view -> loadEarlierTurns());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(14);
        messages.addView(earlier, params);
    }

    private boolean conversationLoadingWithoutHistory() {
        return loadingConversation || historyRepository == null;
    }

    private void refreshEarlierTurnsControl() {
        if (earlierTurnsControl == null) return;
        earlierTurnsControl.setEnabled(!loadingEarlierTurns && !conversationLoadingWithoutHistory()
                && !hasSavingTurnForCurrentConversation());
    }

    private boolean hasSavingTurnForCurrentConversation() {
        if (requestActive) return true;
        for (LocalTurn turn : transcript) {
            if (turn.conversationId.equals(conversationId) && turn.saving) return true;
        }
        return false;
    }

    private TextView addTurn(LocalTurn turn) {
        addBubble(turn.question, true, false);
        String answer = turn.answer.isEmpty() && !turn.streaming
                ? getContext().getString(R.string.chat_no_response) : turn.answer;
        return addBubble(answer, false, !turn.complete && !turn.streaming);
    }

    private TextView addBubble(String text, boolean user, boolean incomplete) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(VERTICAL);
        row.setGravity(user ? Gravity.END : Gravity.START);
        row.setTag(user ? "chat_user_row" : "chat_assistant_row");

        if (incomplete) {
            TextView state = new TextView(getContext());
            state.setText(R.string.chat_incomplete);
            state.setTextColor(getContext().getColor(R.color.caution));
            state.setTextSize(12);
            state.setPadding(dp(4), 0, dp(4), dp(5));
            row.addView(state, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        TextView body = new TextView(getContext());
        body.setText(text);
        body.setTextColor(getContext().getColor(R.color.text_primary));
        body.setTextSize(16);
        body.setTextIsSelectable(true);
        body.setLineSpacing(dp(3), 1);
        body.setPadding(dp(14), dp(11), dp(14), dp(11));
        body.setBackgroundResource(user ? R.drawable.chat_user_bubble : R.drawable.chat_assistant_bubble);
        body.setMaxWidth(Math.round(getResources().getDisplayMetrics().widthPixels * 0.82f));
        body.setTag(user ? "chat_user_message" : "chat_assistant_message");
        body.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                // Keep speaker context available to screen readers without visible role headings.
                info.setContentDescription(getContext().getString(user ? R.string.chat_you
                        : R.string.chat_assistant) + ": " + ((TextView) host).getText());
            }
        });
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.bottomMargin = dp(14);
        row.addView(body, bodyParams);

        messages.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return body;
    }

    private void setSending(boolean active) {
        send.setText("");
        send.setIconResource(active ? R.drawable.ic_chat_stop : R.drawable.ic_chat_send);
        send.setContentDescription(getContext().getString(active ? R.string.chat_stop : R.string.chat_send));
        refreshComposerEnabled();
    }

    private void refreshComposerEnabled() {
        boolean currentConversationDeleting = conversationId != null
                && deletingConversationIds.contains(conversationId);
        prompt.setEnabled(!requestActive && !loadingConversation && !currentConversationDeleting);
        send.setEnabled(requestActive || (!loadingConversation && !currentConversationDeleting));
    }

    private void updateSaveControls() {
        LocalTurn failedTurn = findFailedSave();
        retrySave.setVisibility(failedTurn == null ? GONE : VISIBLE);
        retrySave.setEnabled(failedTurn != null && historyRepository != null && !failedTurn.saving
                && !deletingConversationIds.contains(failedTurn.conversationId));
        if (failedTurn != null) {
            retrySave.setText(failedTurn.conversationId.equals(conversationId)
                    ? R.string.chat_retry_save : R.string.chat_retry_save_earlier);
        }
    }

    private LocalTurn findFailedSave() {
        for (int index = transcript.size() - 1; index >= 0; index--) {
            LocalTurn turn = transcript.get(index);
            if (turn.saveFailed && !turn.saving) return turn;
        }
        for (ArrayList<LocalTurn> pendingTurns : unsavedByConversation.values()) {
            for (int index = pendingTurns.size() - 1; index >= 0; index--) {
                LocalTurn turn = pendingTurns.get(index);
                if (turn.saveFailed && !turn.saving) return turn;
            }
        }
        return null;
    }

    private void retryFailedSave() {
        LocalTurn turn = findFailedSave();
        if (turn == null) return;
        persistTurn(turn);
    }

    private void persistTurn(LocalTurn turn) {
        if (disposed) return;
        if (deletingConversationIds.contains(turn.conversationId)) {
            showFeedback(R.string.chat_history_deleting);
            updateSaveControls();
            return;
        }
        turn.persisted = false;
        addUnsavedTurn(turn);
        if (historyRepository == null) {
            turn.saving = false;
            turn.saveFailed = true;
            showFeedback(R.string.chat_save_failed);
            updateSaveControls();
            return;
        }
        turn.saving = true;
        turn.saveFailed = false;
        updateSaveControls();
        ChatHistoryRepository source = historyRepository;
        final ChatHistoryScope scope;
        try {
            scope = source.captureScope();
        } catch (ApiException error) {
            turn.saving = false;
            turn.saveFailed = true;
            showFeedback(R.string.chat_save_failed);
            updateSaveControls();
            refreshEarlierTurnsControl();
            return;
        }
        String title = turn.title;
        if (title == null) title = makeTitle(turn.question);
        String savedTitle = title;
        String savedConversationId = turn.conversationId;
        String savedTurnId = turn.id;
        String savedQuestion = turn.question;
        String savedAnswer = turn.answer;
        boolean savedComplete = turn.complete;
        long savedRevision = turn.contentRevision;
        long saveAttempt = ++turn.saveAttempt;
        try {
            historyWorker.execute(() -> {
                try {
                    source.saveTurn(scope, savedConversationId, savedTurnId, savedTitle,
                            savedQuestion, savedAnswer, savedComplete);
                    mainHandler.post(() -> onTurnSaved(turn, savedAnswer, savedComplete,
                            savedRevision, saveAttempt));
                } catch (ApiException error) {
                    mainHandler.post(() -> onTurnSaveFailed(turn, savedAnswer, savedComplete,
                            savedRevision, saveAttempt));
                }
            });
        } catch (RejectedExecutionException error) {
            turn.saving = false;
            turn.saveFailed = true;
            showFeedback(R.string.chat_save_failed);
            updateSaveControls();
            refreshEarlierTurnsControl();
        }
    }

    private void addUnsavedTurn(LocalTurn turn) {
        ArrayList<LocalTurn> pendingTurns = unsavedByConversation.get(turn.conversationId);
        if (pendingTurns == null) {
            pendingTurns = new ArrayList<>();
            unsavedByConversation.put(turn.conversationId, pendingTurns);
        }
        for (LocalTurn existing : pendingTurns) if (existing.id.equals(turn.id)) return;
        pendingTurns.add(turn);
    }

    private void onTurnSaved(LocalTurn turn, String savedAnswer, boolean savedComplete,
                             long savedRevision, long saveAttempt) {
        if (disposed) return;
        boolean wasRemote = turn.remoteExists;
        turn.remoteExists = true;
        if (!wasRemote && turn.conversationId.equals(conversationId)) turnOffset++;
        if (!isCurrentSaveAttempt(turn, savedAnswer, savedComplete, savedRevision, saveAttempt)) {
            refreshEarlierTurnsControl();
            return;
        }
        turn.persisted = true;
        turn.saving = false;
        turn.saveFailed = false;
        removeUnsavedTurn(turn);
        LocalTurn anotherFailure = findFailedSave();
        if (anotherFailure == null && !requestActive) feedback.setVisibility(GONE);
        updateSaveControls();
        refreshEarlierTurnsControl();
        if (historyDialog != null && historyDialog.isShowing()) loadConversations(false);
    }

    private void onTurnSaveFailed(LocalTurn turn, String savedAnswer, boolean savedComplete,
                                  long savedRevision, long saveAttempt) {
        if (disposed || !isCurrentSaveAttempt(turn, savedAnswer, savedComplete, savedRevision, saveAttempt)) return;
        turn.persisted = false;
        turn.saving = false;
        turn.saveFailed = true;
        addUnsavedTurn(turn);
        showFeedback(R.string.chat_save_failed);
        updateSaveControls();
        refreshEarlierTurnsControl();
    }

    private boolean isCurrentSaveAttempt(LocalTurn turn, String savedAnswer, boolean savedComplete,
                                         long savedRevision, long saveAttempt) {
        return turn.contentRevision == savedRevision && turn.saveAttempt == saveAttempt
                && turn.complete == savedComplete && turn.answer.equals(savedAnswer);
    }

    private void removeUnsavedTurn(LocalTurn turn) {
        ArrayList<LocalTurn> pendingTurns = unsavedByConversation.get(turn.conversationId);
        if (pendingTurns == null) return;
        Iterator<LocalTurn> iterator = pendingTurns.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().id.equals(turn.id)) iterator.remove();
        }
        if (pendingTurns.isEmpty()) unsavedByConversation.remove(turn.conversationId);
    }

    private void openHistory() {
        if (disposed) return;
        if (historyDialog != null && historyDialog.isShowing()) return;
        historyConversations.clear();
        historyOffset = 0;
        historyHasMoreConversations = false;
        historyLoading = false;
        historyLoadFailed = false;
        historyDeleteFailed = false;
        conversationLoadFailed = false;
        conversationToRetry = null;
        createHistoryDialog();
        renderHistoryPanel();
        if (historyRepository == null) {
            historyLoadFailed = true;
            renderHistoryPanel();
        } else {
            loadConversations(false);
        }
    }

    private void createHistoryDialog() {
        Dialog dialog = new Dialog(getContext());
        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(VERTICAL);
        panel.setBackgroundResource(R.drawable.chat_history_panel);
        panel.setPadding(dp(18), dp(16), dp(14), dp(16));

        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(getContext());
        title.setText(R.string.chat_history_title);
        title.setTextColor(getContext().getColor(R.color.text_primary));
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton close = new ImageButton(getContext());
        close.setImageResource(R.drawable.ic_chat_close);
        close.setContentDescription(getContext().getString(R.string.chat_history_close));
        close.setTooltipText(getContext().getString(R.string.chat_history_close));
        close.setBackgroundResource(android.R.drawable.list_selector_background);
        close.setPadding(dp(12), dp(12), dp(12), dp(12));
        header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        historyStatus = new TextView(getContext());
        historyStatus.setTextColor(getContext().getColor(R.color.text_secondary));
        historyStatus.setTextSize(14);
        historyStatus.setPadding(dp(4), dp(8), dp(4), dp(10));
        historyStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(historyStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView historyScroll = new ScrollView(getContext());
        historyRows = new LinearLayout(getContext());
        historyRows.setOrientation(VERTICAL);
        historyScroll.addView(historyRows, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(historyScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        historyRetry = new MaterialButton(getContext());
        historyRetry.setText(R.string.chat_retry);
        historyRetry.setAllCaps(false);
        historyRetry.setMinHeight(dp(48));
        historyRetry.setVisibility(GONE);
        historyRetry.setOnClickListener(view -> {
            if (conversationToRetry != null) openConversation(conversationToRetry);
            else loadConversations(false);
        });
        panel.addView(historyRetry, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        historyLoadMore = new MaterialButton(getContext());
        historyLoadMore.setText(R.string.chat_history_load_more);
        historyLoadMore.setAllCaps(false);
        historyLoadMore.setMinHeight(dp(48));
        historyLoadMore.setVisibility(GONE);
        historyLoadMore.setOnClickListener(view -> loadConversations(true));
        panel.addView(historyLoadMore, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        close.setOnClickListener(view -> closeHistory());
        dialog.setContentView(panel);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(ignored -> {
            if (historyDialog == dialog) {
                historyDialog = null;
                historyStatus = null;
                historyRows = null;
                historyRetry = null;
                historyLoadMore = null;
                historyListGeneration++;
                conversationLoadGeneration++;
                historyLoading = false;
                loadingConversation = false;
                conversationToRetry = null;
                refreshComposerEnabled();
            }
        });
        historyDialog = dialog;
        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            int panelWidth = Math.min(dp(360), Math.round(screenWidth * 0.88f));
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.gravity = Gravity.START | Gravity.TOP;
            attributes.dimAmount = 0.55f;
            window.setAttributes(attributes);
            window.setLayout(panelWidth, WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    private void loadConversations(boolean append) {
        if (historyRepository == null || historyDialog == null || !historyDialog.isShowing()) {
            historyLoadFailed = true;
            renderHistoryPanel();
            return;
        }
        historyLoading = true;
        historyLoadFailed = false;
        conversationLoadFailed = false;
        conversationToRetry = null;
        renderHistoryPanel();
        final int requestedOffset = append ? historyOffset : 0;
        final int requestGeneration = ++historyListGeneration;
        ChatHistoryRepository source = historyRepository;
        final ChatHistoryScope scope;
        try {
            scope = source.captureScope();
        } catch (ApiException error) {
            historyLoading = false;
            historyLoadFailed = true;
            renderHistoryPanel();
            return;
        }
        try {
            historyWorker.execute(() -> {
                try {
                    List<ChatConversation> page = source.listConversations(scope,
                            requestedOffset, CONVERSATION_PAGE_SIZE);
                    mainHandler.post(() -> {
                        if (disposed || requestGeneration != historyListGeneration
                                || historyDialog == null || !historyDialog.isShowing()) return;
                        if (!append) historyConversations.clear();
                        for (ChatConversation item : page) {
                            boolean duplicate = false;
                            for (ChatConversation existing : historyConversations) {
                                if (existing.getId().equals(item.getId())) {
                                    duplicate = true;
                                    break;
                                }
                            }
                            if (!duplicate) historyConversations.add(item);
                        }
                        historyOffset = requestedOffset + page.size();
                        historyHasMoreConversations = page.size() == CONVERSATION_PAGE_SIZE;
                        historyLoading = false;
                        historyLoadFailed = false;
                        historyDeleteFailed = false;
                        renderHistoryPanel();
                    });
                } catch (ApiException error) {
                    mainHandler.post(() -> {
                        if (disposed || requestGeneration != historyListGeneration
                                || historyDialog == null || !historyDialog.isShowing()) return;
                        historyLoading = false;
                        historyLoadFailed = true;
                        renderHistoryPanel();
                    });
                }
            });
        } catch (RejectedExecutionException error) {
            historyLoading = false;
            historyLoadFailed = true;
            renderHistoryPanel();
        }
    }

    private void renderHistoryPanel() {
        if (historyStatus == null || historyRows == null || historyRetry == null || historyLoadMore == null) return;
        historyRows.removeAllViews();
        if (historyLoading) {
            historyStatus.setText(conversationToRetry == null ? R.string.chat_history_loading
                    : R.string.chat_history_loading_conversation);
        } else if (conversationLoadFailed) {
            historyStatus.setText(R.string.chat_history_conversation_failed);
        } else if (historyLoadFailed) {
            historyStatus.setText(historyRepository == null ? R.string.chat_history_unavailable
                    : R.string.chat_history_load_failed);
        } else if (historyDeleteFailed) {
            historyStatus.setText(R.string.chat_history_delete_failed);
        } else if (!deletingConversationIds.isEmpty()) {
            historyStatus.setText(R.string.chat_history_deleting);
        } else if (historyConversations.isEmpty()) {
            historyStatus.setText(R.string.chat_history_empty);
        } else {
            historyStatus.setText("");
        }

        for (ChatConversation conversation : historyConversations) addHistoryRow(conversation);
        historyRetry.setVisibility(historyLoadFailed || conversationLoadFailed ? VISIBLE : GONE);
        historyRetry.setEnabled(!historyLoading && historyRepository != null);
        historyLoadMore.setVisibility(historyHasMoreConversations ? VISIBLE : GONE);
        historyLoadMore.setEnabled(historyHasMoreConversations && !historyLoading);
    }

    private void addHistoryRow(ChatConversation conversation) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(android.R.drawable.list_selector_background);
        row.setMinimumHeight(dp(64));

        LinearLayout item = new LinearLayout(getContext());
        item.setOrientation(VERTICAL);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(12), dp(10), dp(12), dp(10));
        item.setClickable(true);
        item.setFocusable(true);
        String titleText = conversation.getTitle() == null || conversation.getTitle().trim().isEmpty()
                ? getContext().getString(R.string.chat_title) : conversation.getTitle();
        TextView title = new TextView(getContext());
        title.setText(titleText);
        title.setTextColor(getContext().getColor(R.color.text_primary));
        title.setTextSize(15);
        title.setMaxLines(2);
        item.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView hint = new TextView(getContext());
        hint.setText(R.string.chat_history_open_hint);
        hint.setTextColor(getContext().getColor(R.color.text_secondary));
        hint.setTextSize(12);
        hint.setPadding(0, dp(3), 0, 0);
        item.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        item.setContentDescription(titleText + ", " + getContext().getString(R.string.chat_history_open_hint));
        item.setEnabled(!deletingConversationIds.contains(conversation.getId()));
        item.setOnClickListener(view -> {
            if (!deletingConversationIds.contains(conversation.getId())) openConversation(conversation);
        });
        row.addView(item, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageButton delete = new ImageButton(getContext());
        delete.setImageResource(R.drawable.ic_chat_delete);
        delete.setColorFilter(getContext().getColor(R.color.text_secondary));
        delete.setContentDescription(getContext().getString(R.string.chat_history_delete) + ": " + titleText);
        delete.setTooltipText(getContext().getString(R.string.chat_history_delete));
        delete.setBackgroundResource(android.R.drawable.list_selector_background);
        delete.setPadding(dp(12), dp(12), dp(12), dp(12));
        delete.setEnabled(!deletingConversationIds.contains(conversation.getId()));
        delete.setOnClickListener(view -> confirmDeleteConversation(conversation));
        row.addView(delete, new LinearLayout.LayoutParams(dp(48), dp(48)));
        historyRows.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void confirmDeleteConversation(ChatConversation conversation) {
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.chat_history_delete_title)
                .setMessage(R.string.chat_history_delete_message)
                .setNegativeButton(R.string.chat_cancel, (dialog, which) -> dialog.dismiss())
                .setPositiveButton(R.string.chat_delete, (dialog, which) -> deleteConversation(conversation))
                .show();
    }

    private void deleteConversation(ChatConversation conversation) {
        if (historyRepository == null || deletingConversationIds.contains(conversation.getId())) return;
        ChatHistoryRepository source = historyRepository;
        final ChatHistoryScope scope;
        try {
            scope = source.captureScope();
        } catch (ApiException error) {
            historyDeleteFailed = true;
            renderHistoryPanel();
            return;
        }
        final String deletedId = conversation.getId();
        if (deletedId.equals(conversationId)) stopActiveTurn();
        deletingConversationIds.add(deletedId);
        refreshComposerEnabled();
        updateSaveControls();
        historyDeleteFailed = false;
        renderHistoryPanel();
        try {
            historyWorker.execute(() -> {
                try {
                    source.deleteConversation(scope, deletedId);
                    mainHandler.post(() -> onConversationDeleted(deletedId));
                } catch (ApiException error) {
                    mainHandler.post(() -> {
                        if (disposed) return;
                        deletingConversationIds.remove(deletedId);
                        refreshComposerEnabled();
                        updateSaveControls();
                        historyDeleteFailed = true;
                        renderHistoryPanel();
                    });
                }
            });
        } catch (RejectedExecutionException error) {
            deletingConversationIds.remove(deletedId);
            refreshComposerEnabled();
            updateSaveControls();
            historyDeleteFailed = true;
            renderHistoryPanel();
        }
    }

    private void onConversationDeleted(String deletedId) {
        if (disposed) return;
        deletingConversationIds.remove(deletedId);
        historyConversations.removeIf(item -> item.getId().equals(deletedId));
        historyOffset = Math.max(0, historyOffset - 1);
        historyDeleteFailed = false;
        boolean wasCurrent = deletedId.equals(conversationId);
        if (wasCurrent) startNewChat();
        unsavedByConversation.remove(deletedId);
        draftsByConversation.remove(deletedId);
        endpointIdsByConversation.remove(deletedId);
        updateSaveControls();
        if (wasCurrent) {
            return;
        }
        renderHistoryPanel();
        loadConversations(false);
    }

    private void openConversation(ChatConversation conversation) {
        if (conversation == null || historyRepository == null || historyDialog == null) return;
        if (conversation.getId().equals(conversationId)) {
            closeHistory();
            return;
        }
        draftsByConversation.put(draftKey(), prompt.getText().toString());
        stopActiveTurn();
        loadingConversation = true;
        refreshComposerEnabled();
        historyLoading = true;
        conversationLoadFailed = false;
        conversationToRetry = conversation;
        renderHistoryPanel();
        showFeedback(R.string.chat_history_loading_conversation);
        renderMessages(false);
        ChatHistoryRepository source = historyRepository;
        final ChatHistoryScope scope;
        try {
            scope = source.captureScope();
        } catch (ApiException error) {
            loadingConversation = false;
            refreshComposerEnabled();
            historyLoading = false;
            conversationLoadFailed = true;
            renderHistoryPanel();
            return;
        }
        final int requestGeneration = ++conversationLoadGeneration;
        final String requestedConversationId = conversation.getId();
        try {
            historyWorker.execute(() -> {
                try {
                    List<ChatTurn> recentTurns = source.listTurns(scope, requestedConversationId, 0, TURN_PAGE_SIZE);
                    ArrayList<ChatTurn> ordered = new ArrayList<>(recentTurns);
                    Collections.reverse(ordered);
                    mainHandler.post(() -> {
                        if (disposed || requestGeneration != conversationLoadGeneration
                                || historyDialog == null || !historyDialog.isShowing()) return;
                        conversationId = requestedConversationId;
                        conversationTitle = conversation.getTitle();
                        endpointConversationId = endpointIdsByConversation.get(conversationId);
                        transcript.clear();
                        for (ChatTurn turn : ordered) {
                            transcript.add(new LocalTurn(turn.getId(), turn.getConversationId(),
                                    conversationTitle, turn.getQuestion(), turn.getAnswer(), turn.isComplete(), true));
                        }
                        mergeUnsavedTurns(conversationId);
                        turnOffset = recentTurns.size();
                        hasMoreEarlierTurns = recentTurns.size() == TURN_PAGE_SIZE;
                        loadingConversation = false;
                        refreshComposerEnabled();
                        historyLoading = false;
                        conversationLoadFailed = false;
                        conversationToRetry = null;
                        retryableTurn = findLastIncompleteTurn();
                        setStartedConversation(true);
                        setDraftText(draftsByConversation.getOrDefault(conversationId, ""));
                        feedback.setVisibility(GONE);
                        closeHistory();
                        renderMessages(true);
                    });
                } catch (ApiException error) {
                    mainHandler.post(() -> {
                        if (disposed || requestGeneration != conversationLoadGeneration
                                || historyDialog == null || !historyDialog.isShowing()) return;
                        loadingConversation = false;
                        refreshComposerEnabled();
                        historyLoading = false;
                        conversationLoadFailed = true;
                        feedback.setVisibility(GONE);
                        renderMessages(false);
                        renderHistoryPanel();
                    });
                }
            });
        } catch (RejectedExecutionException error) {
            loadingConversation = false;
            refreshComposerEnabled();
            historyLoading = false;
            conversationLoadFailed = true;
            feedback.setVisibility(GONE);
            renderMessages(false);
            renderHistoryPanel();
        }
    }

    private void mergeUnsavedTurns(String id) {
        ArrayList<LocalTurn> pendingTurns = unsavedByConversation.get(id);
        if (pendingTurns == null) return;
        ArrayList<LocalTurn> nowPersisted = new ArrayList<>();
        for (LocalTurn pendingTurn : pendingTurns) {
            int loadedIndex = -1;
            for (int index = 0; index < transcript.size(); index++) {
                LocalTurn loadedTurn = transcript.get(index);
                if (loadedTurn.id.equals(pendingTurn.id)) {
                    loadedIndex = index;
                    break;
                }
            }
            if (loadedIndex < 0) {
                transcript.add(pendingTurn);
                continue;
            }

            LocalTurn remoteTurn = transcript.get(loadedIndex);
            pendingTurn.remoteExists = true;
            boolean sameContent = pendingTurn.question.equals(remoteTurn.question)
                    && pendingTurn.answer.equals(remoteTurn.answer)
                    && pendingTurn.complete == remoteTurn.complete;
            transcript.set(loadedIndex, pendingTurn);
            if (sameContent) {
                pendingTurn.persisted = true;
                pendingTurn.saving = false;
                pendingTurn.saveFailed = false;
                nowPersisted.add(pendingTurn);
            } else {
                pendingTurn.persisted = false;
                if (!pendingTurn.saving) pendingTurn.saveFailed = true;
            }
        }
        for (LocalTurn loaded : nowPersisted) removeUnsavedTurn(loaded);
    }

    private boolean containsTurn(String id) {
        for (LocalTurn turn : transcript) if (turn.id.equals(id)) return true;
        return false;
    }

    private LocalTurn findLastIncompleteTurn() {
        for (int index = transcript.size() - 1; index >= 0; index--) {
            LocalTurn turn = transcript.get(index);
            if (!turn.complete) return turn;
        }
        return null;
    }

    private void loadEarlierTurns() {
        if (loadingEarlierTurns || hasSavingTurnForCurrentConversation() || !hasMoreEarlierTurns
                || conversationId == null || historyRepository == null) return;
        loadingEarlierTurns = true;
        earlierTurnsLoadFailed = false;
        renderMessages(false);
        final int requestedOffset = turnOffset;
        final int oldMessagesHeight = messages.getHeight();
        final int oldScrollY = scroll.getScrollY();
        final int requestGeneration = ++turnPageGeneration;
        final String requestedConversation = conversationId;
        ChatHistoryRepository source = historyRepository;
        final ChatHistoryScope scope;
        try {
            scope = source.captureScope();
        } catch (ApiException error) {
            loadingEarlierTurns = false;
            earlierTurnsLoadFailed = true;
            renderMessages(false);
            return;
        }
        try {
            historyWorker.execute(() -> {
                try {
                    List<ChatTurn> page = source.listTurns(scope, requestedConversation,
                            requestedOffset, TURN_PAGE_SIZE);
                    ArrayList<ChatTurn> ordered = new ArrayList<>(page);
                    Collections.reverse(ordered);
                    mainHandler.post(() -> {
                        if (disposed || requestGeneration != turnPageGeneration
                                || !requestedConversation.equals(conversationId)) return;
                        int insertion = 0;
                        for (ChatTurn turn : ordered) {
                            if (!containsTurn(turn.getId())) {
                                transcript.add(insertion++, new LocalTurn(turn.getId(), turn.getConversationId(),
                                        conversationTitle, turn.getQuestion(), turn.getAnswer(), turn.isComplete(), true));
                            }
                        }
                        turnOffset = Math.max(turnOffset, requestedOffset + page.size());
                        hasMoreEarlierTurns = page.size() == TURN_PAGE_SIZE;
                        loadingEarlierTurns = false;
                        earlierTurnsLoadFailed = false;
                        renderMessages(false);
                        scroll.post(() -> scroll.scrollTo(0, oldScrollY
                                + Math.max(0, messages.getHeight() - oldMessagesHeight)));
                    });
                } catch (ApiException error) {
                    mainHandler.post(() -> {
                        if (disposed || requestGeneration != turnPageGeneration
                                || !requestedConversation.equals(conversationId)) return;
                        loadingEarlierTurns = false;
                        earlierTurnsLoadFailed = true;
                        renderMessages(false);
                    });
                }
            });
        } catch (RejectedExecutionException error) {
            loadingEarlierTurns = false;
            earlierTurnsLoadFailed = true;
            renderMessages(false);
        }
    }

    private String makeTitle(String question) {
        String normalized = question.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= MAX_TITLE_LENGTH) return normalized;
        return normalized.substring(0, MAX_TITLE_LENGTH - 1).trim() + "…";
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private ArrayList<LocalTurn> pendingTurnsSnapshot() {
        ArrayList<LocalTurn> turns = new ArrayList<>();
        Set<String> includedIds = new HashSet<>();
        for (ArrayList<LocalTurn> pendingTurns : unsavedByConversation.values()) {
            for (LocalTurn turn : pendingTurns) {
                if (!turn.persisted && includedIds.add(turn.id)) turns.add(turn);
            }
        }
        for (LocalTurn turn : transcript) {
            if (!turn.persisted && includedIds.add(turn.id)) turns.add(turn);
        }
        if (activeTurn != null && !activeTurn.persisted && includedIds.add(activeTurn.id)) {
            turns.add(activeTurn);
        }
        return turns;
    }

    private int pendingTurnCount() {
        return pendingTurnsSnapshot().size();
    }

    private int pendingTurnStateCharacters() {
        int characters = 0;
        for (LocalTurn turn : pendingTurnsSnapshot()) {
            characters += turnStateCharacters(turn);
        }
        return characters;
    }

    private int turnStateCharacters(LocalTurn turn) {
        String title = turn.title == null ? makeTitle(turn.question) : turn.title;
        return turn.id.length() + turn.conversationId.length() + title.length()
                + turn.question.length() + turn.answer.length();
    }

    private int pendingTurnStateCharactersForNew(String question) {
        return 36 + 36 + makeTitle(question).length() + question.length() + 16_000;
    }

    private ArrayList<LocalTurn> selectPendingTurnsForState(ArrayList<LocalTurn> pendingTurns) {
        ArrayList<LocalTurn> selected = new ArrayList<>();
        int characters = 0;
        for (int pass = 0; pass < 2; pass++) {
            boolean currentConversation = pass == 0;
            for (int index = pendingTurns.size() - 1; index >= 0; index--) {
                LocalTurn turn = pendingTurns.get(index);
                if ((turn.conversationId.equals(conversationId)) != currentConversation) continue;
                if (selected.size() >= MAX_PENDING_TURNS) continue;
                int turnCharacters = turnStateCharacters(turn);
                if (characters + turnCharacters > MAX_PENDING_STATE_CHARS) continue;
                selected.add(turn);
                characters += turnCharacters;
            }
        }
        return selected;
    }

    private boolean[] toBooleanArray(ArrayList<Boolean> values) {
        boolean[] result = new boolean[values.size()];
        for (int index = 0; index < values.size(); index++) result[index] = values.get(index);
        return result;
    }

    public Bundle saveState() {
        if (activeTurn != null && !activeTurn.persisted) {
            addUnsavedTurn(activeTurn);
            persistTurn(activeTurn);
        }

        Bundle state = new Bundle();
        String currentDraft = prompt.getText().toString();
        state.putString(STATE_DRAFT, currentDraft);
        state.putString(STATE_CONVERSATION_ID, conversationId);
        state.putString(STATE_ENDPOINT_CONVERSATION_ID, endpointConversationId);
        state.putString(STATE_CONVERSATION_TITLE, conversationTitle);
        state.putBoolean(STATE_STARTED, startedConversation);
        ArrayList<LocalTurn> pendingSnapshot = pendingTurnsSnapshot();
        ArrayList<LocalTurn> pendingForState = selectPendingTurnsForState(pendingSnapshot);
        if (pendingForState.size() != pendingSnapshot.size()) {
            showFeedback(R.string.chat_pending_state_limit);
        }

        ArrayList<LocalTurn> snapshot = new ArrayList<>(transcript);
        if (activeTurn != null && !snapshot.contains(activeTurn)) snapshot.add(activeTurn);
        Set<String> selectedPendingIds = new HashSet<>();
        int activePendingTurnCount = 0;
        for (LocalTurn turn : pendingForState) {
            selectedPendingIds.add(turn.id);
            if (turn.conversationId.equals(conversationId)) activePendingTurnCount++;
        }
        Set<String> bundledTurnIds = new HashSet<>();
        int persistedCharacters = 0;
        int persistedTurnCount = 0;
        for (LocalTurn turn : snapshot) {
            if (!turn.persisted && selectedPendingIds.contains(turn.id)) bundledTurnIds.add(turn.id);
        }
        for (int index = snapshot.size() - 1; index >= 0; index--) {
            LocalTurn turn = snapshot.get(index);
            if (!turn.persisted) continue;
            if (persistedTurnCount >= SAVED_TURN_LIMIT - activePendingTurnCount) break;
            int turnCharacters = turnStateCharacters(turn);
            if (persistedCharacters + turnCharacters > MAX_PERSISTED_STATE_CHARS) break;
            persistedCharacters += turnCharacters;
            persistedTurnCount++;
            bundledTurnIds.add(turn.id);
        }

        ArrayList<LocalTurn> bundledTurns = new ArrayList<>();
        int omittedRemoteTurns = 0;
        for (LocalTurn turn : snapshot) {
            if (bundledTurnIds.contains(turn.id)) bundledTurns.add(turn);
            else if (turn.remoteExists) omittedRemoteTurns++;
        }
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> questions = new ArrayList<>();
        ArrayList<String> answers = new ArrayList<>();
        boolean[] complete = new boolean[bundledTurns.size()];
        boolean[] persisted = new boolean[bundledTurns.size()];
        boolean[] remoteExists = new boolean[bundledTurns.size()];
        boolean[] saveFailed = new boolean[bundledTurns.size()];
        int outputIndex = 0;
        for (LocalTurn turn : bundledTurns) {
            ids.add(turn.id);
            questions.add(turn.question);
            answers.add(turn.answer);
            complete[outputIndex] = turn.complete && !turn.streaming;
            persisted[outputIndex] = turn.persisted;
            remoteExists[outputIndex] = turn.remoteExists;
            saveFailed[outputIndex] = turn.saveFailed || !turn.persisted;
            outputIndex++;
        }
        state.putInt(STATE_HISTORY_OFFSET, Math.max(0, turnOffset - omittedRemoteTurns));
        state.putBoolean(STATE_HAS_MORE_TURNS, hasMoreEarlierTurns || omittedRemoteTurns > 0);
        state.putStringArrayList(STATE_TURN_IDS, ids);
        state.putStringArrayList(STATE_TURN_QUESTIONS, questions);
        state.putStringArrayList(STATE_TURN_ANSWERS, answers);
        state.putBooleanArray(STATE_TURN_COMPLETE, complete);
        state.putBooleanArray(STATE_TURN_PERSISTED, persisted);
        state.putBooleanArray(STATE_TURN_REMOTE_EXISTS, remoteExists);
        state.putBooleanArray(STATE_TURN_SAVE_FAILED, saveFailed);

        ArrayList<String> pendingConversationIds = new ArrayList<>();
        ArrayList<String> pendingTurnIds = new ArrayList<>();
        ArrayList<String> pendingTitles = new ArrayList<>();
        ArrayList<String> pendingQuestions = new ArrayList<>();
        ArrayList<String> pendingAnswers = new ArrayList<>();
        ArrayList<Boolean> pendingComplete = new ArrayList<>();
        ArrayList<Boolean> pendingRemoteExists = new ArrayList<>();
        for (LocalTurn turn : pendingForState) {
            if (bundledTurnIds.contains(turn.id)) continue;
            pendingConversationIds.add(turn.conversationId);
            pendingTurnIds.add(turn.id);
            pendingTitles.add(turn.title == null ? makeTitle(turn.question) : turn.title);
            pendingQuestions.add(turn.question);
            pendingAnswers.add(turn.answer);
            pendingComplete.add(turn.complete && !turn.streaming);
            pendingRemoteExists.add(turn.remoteExists);
        }
        state.putStringArrayList(STATE_PENDING_CONVERSATION_IDS, pendingConversationIds);
        state.putStringArrayList(STATE_PENDING_TURN_IDS, pendingTurnIds);
        state.putStringArrayList(STATE_PENDING_TITLES, pendingTitles);
        state.putStringArrayList(STATE_PENDING_QUESTIONS, pendingQuestions);
        state.putStringArrayList(STATE_PENDING_ANSWERS, pendingAnswers);
        state.putBooleanArray(STATE_PENDING_COMPLETE, toBooleanArray(pendingComplete));
        state.putBooleanArray(STATE_PENDING_REMOTE_EXISTS, toBooleanArray(pendingRemoteExists));

        ArrayList<String> draftKeys = new ArrayList<>();
        ArrayList<String> draftValues = new ArrayList<>();
        int draftCharacters = currentDraft.length();
        int savedDraftCount = 1;
        String activeDraftKey = draftKey();
        for (Map.Entry<String, String> entry : draftsByConversation.entrySet()) {
            if (entry.getKey().equals(activeDraftKey) || savedDraftCount >= SAVED_DRAFT_LIMIT) continue;
            int nextDraftCharacters = entry.getKey().length() + entry.getValue().length();
            if (entry.getValue().length() > 4000
                    || draftCharacters + nextDraftCharacters > MAX_DRAFT_STATE_CHARS) continue;
            draftKeys.add(entry.getKey());
            draftValues.add(entry.getValue());
            draftCharacters += nextDraftCharacters;
            savedDraftCount++;
        }
        state.putStringArrayList(STATE_DRAFT_KEYS, draftKeys);
        state.putStringArrayList(STATE_DRAFT_VALUES, draftValues);
        return state;
    }

    public void restoreState(Bundle state) {
        if (state == null || disposed) return;
        conversationId = state.getString(STATE_CONVERSATION_ID);
        endpointConversationId = state.getString(STATE_ENDPOINT_CONVERSATION_ID);
        conversationTitle = state.getString(STATE_CONVERSATION_TITLE);
        if (conversationId != null && endpointConversationId != null) {
            endpointIdsByConversation.put(conversationId, endpointConversationId);
        }
        startedConversation = state.getBoolean(STATE_STARTED, conversationId != null);
        turnOffset = Math.max(0, state.getInt(STATE_HISTORY_OFFSET, 0));
        hasMoreEarlierTurns = state.getBoolean(STATE_HAS_MORE_TURNS, false);

        ArrayList<String> draftKeys = state.getStringArrayList(STATE_DRAFT_KEYS);
        ArrayList<String> draftValues = state.getStringArrayList(STATE_DRAFT_VALUES);
        if (draftKeys != null && draftValues != null && draftKeys.size() == draftValues.size()
                && draftKeys.size() <= SAVED_DRAFT_LIMIT) {
            draftsByConversation.clear();
            for (int index = 0; index < draftKeys.size(); index++) {
                String draft = draftValues.get(index);
                if (draft != null && draft.length() <= 4000) {
                    draftsByConversation.put(draftKeys.get(index), draft);
                }
            }
        }
        setDraftText(state.getString(STATE_DRAFT, ""));

        ArrayList<String> ids = state.getStringArrayList(STATE_TURN_IDS);
        ArrayList<String> questions = state.getStringArrayList(STATE_TURN_QUESTIONS);
        ArrayList<String> answers = state.getStringArrayList(STATE_TURN_ANSWERS);
        boolean[] complete = state.getBooleanArray(STATE_TURN_COMPLETE);
        boolean[] persisted = state.getBooleanArray(STATE_TURN_PERSISTED);
        boolean[] remoteExists = state.getBooleanArray(STATE_TURN_REMOTE_EXISTS);
        boolean[] saveFailed = state.getBooleanArray(STATE_TURN_SAVE_FAILED);
        if (ids != null && questions != null && answers != null
                && ids.size() == questions.size() && ids.size() == answers.size()
                && ids.size() <= SAVED_TURN_LIMIT && complete != null && persisted != null
                && saveFailed != null && complete.length == ids.size()
                && persisted.length == ids.size() && saveFailed.length == ids.size()
                && (remoteExists == null || remoteExists.length == ids.size())) {
            transcript.clear();
            for (int index = 0; index < ids.size(); index++) {
                LocalTurn turn = new LocalTurn(ids.get(index), conversationId, conversationTitle,
                        questions.get(index),
                        answers.get(index), complete[index], persisted[index]);
                turn.remoteExists = remoteExists == null ? persisted[index] : remoteExists[index];
                turn.saveFailed = saveFailed[index] || !persisted[index];
                transcript.add(turn);
                if (!turn.persisted) addUnsavedTurn(turn);
            }
        }

        ArrayList<String> pendingConversationIds = state.getStringArrayList(STATE_PENDING_CONVERSATION_IDS);
        ArrayList<String> pendingTurnIds = state.getStringArrayList(STATE_PENDING_TURN_IDS);
        ArrayList<String> pendingTitles = state.getStringArrayList(STATE_PENDING_TITLES);
        ArrayList<String> pendingQuestions = state.getStringArrayList(STATE_PENDING_QUESTIONS);
        ArrayList<String> pendingAnswers = state.getStringArrayList(STATE_PENDING_ANSWERS);
        boolean[] pendingComplete = state.getBooleanArray(STATE_PENDING_COMPLETE);
        boolean[] pendingRemoteExists = state.getBooleanArray(STATE_PENDING_REMOTE_EXISTS);
        if (pendingConversationIds != null && pendingTurnIds != null && pendingTitles != null
                && pendingQuestions != null && pendingAnswers != null
                && pendingConversationIds.size() == pendingTurnIds.size()
                && pendingTurnIds.size() == pendingTitles.size()
                && pendingTurnIds.size() == pendingQuestions.size()
                && pendingTurnIds.size() == pendingAnswers.size()
                && pendingComplete != null && pendingRemoteExists != null
                && pendingComplete.length == pendingTurnIds.size()
                && pendingRemoteExists.length == pendingTurnIds.size()
                && pendingTurnIds.size() <= MAX_PENDING_TURNS) {
            int pendingCharacters = 0;
            for (int index = 0; index < pendingTurnIds.size(); index++) {
                pendingCharacters += pendingConversationIds.get(index).length()
                        + pendingTurnIds.get(index).length() + pendingTitles.get(index).length()
                        + pendingQuestions.get(index).length() + pendingAnswers.get(index).length();
            }
            if (pendingCharacters <= MAX_PENDING_STATE_CHARS) {
                for (int index = 0; index < pendingTurnIds.size(); index++) {
                    LocalTurn turn = new LocalTurn(pendingTurnIds.get(index),
                            pendingConversationIds.get(index), pendingTitles.get(index),
                            pendingQuestions.get(index), pendingAnswers.get(index), pendingComplete[index], false);
                    turn.remoteExists = pendingRemoteExists[index];
                    turn.saveFailed = true;
                    addUnsavedTurn(turn);
                    if (turn.conversationId.equals(conversationId) && !containsTurn(turn.id)) {
                        transcript.add(turn);
                    }
                }
            } else {
                showFeedback(R.string.chat_pending_saves_limit);
            }
        }
        retryableTurn = findLastIncompleteTurn();
        renderMessages(false);
        if (conversationStateChangedListener != null) {
            conversationStateChangedListener.accept(startedConversation);
        }
        updateSaveControls();
    }

    @Override
    protected void onDetachedFromWindow() {
        closeHistory();
        super.onDetachedFromWindow();
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        closeHistory();
        generation++;
        clearPendingStreamText();
        client.cancel();
        if (pending != null) pending.cancel(true);
        pending = null;
        requestActive = false;
        accessToken = null;
        conversationId = null;
        endpointConversationId = null;
        conversationTitle = null;
        activeTurn = null;
        retryableTurn = null;
        streamingAnswer.setLength(0);
        streamingBody = null;
        transcript.clear();
        historyConversations.clear();
        endpointIdsByConversation.clear();
        unsavedByConversation.clear();
        modelWorker.shutdownNow();
        historyWorker.shutdownNow();
    }
}
