package com.example.agentcostcontrol.ui;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.AndroidViewModel;

import com.example.agentcostcontrol.data.ApiException;
import com.example.agentcostcontrol.data.SupabaseRepository;
import com.example.agentcostcontrol.model.Session;
import com.example.agentcostcontrol.model.SubscriptionSyncResult;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Retains account-scoped work and the Auth refresh lock while Android recreates the activity. */
public final class AccountDataViewModel extends AndroidViewModel {
    public interface GmailSyncObserver {
        void onGmailSyncChanged(GmailSyncState state);
    }

    public static final class GmailSyncState {
        private final long generation;
        private final String authUserId;
        private final boolean inProgress;
        private final SubscriptionSyncResult result;
        private final ApiException failure;
        private final Session session;

        private GmailSyncState(long generation, String authUserId, boolean inProgress,
                               SubscriptionSyncResult result, ApiException failure, Session session) {
            this.generation = generation;
            this.authUserId = authUserId;
            this.inProgress = inProgress;
            this.result = result;
            this.failure = failure;
            this.session = session;
        }

        public long getGeneration() { return generation; }
        public String getAuthUserId() { return authUserId; }
        public boolean isInProgress() { return inProgress; }
        public SubscriptionSyncResult getResult() { return result; }
        public ApiException getFailure() { return failure; }
        public Session getSession() { return session; }
    }

    private final SupabaseRepository repository;
    private final ExecutorService gmailSyncWorker = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ArrayList<GmailSyncObserver> gmailSyncObservers = new ArrayList<>();
    private GmailSyncState gmailSyncState;
    private long gmailSyncGeneration;

    public AccountDataViewModel(Application application) {
        this(application, new SupabaseRepository(application));
    }

    AccountDataViewModel(Application application, SupabaseRepository repository) {
        super(application);
        this.repository = repository;
    }

    public SupabaseRepository getRepository() {
        return repository;
    }

    public synchronized GmailSyncState getGmailSyncState() {
        return gmailSyncState;
    }

    public void addGmailSyncObserver(GmailSyncObserver observer) {
        GmailSyncState current;
        synchronized (this) {
            if (!gmailSyncObservers.contains(observer)) gmailSyncObservers.add(observer);
            current = gmailSyncState;
        }
        postGmailSyncState(observer, current);
    }

    public synchronized void removeGmailSyncObserver(GmailSyncObserver observer) {
        gmailSyncObservers.remove(observer);
    }

    public boolean startGmailSync(String authUserId) {
        if (authUserId == null || authUserId.trim().isEmpty()) return false;
        GmailSyncState started;
        synchronized (this) {
            if (gmailSyncState != null) return false;
            started = new GmailSyncState(++gmailSyncGeneration, authUserId, true, null, null, null);
            gmailSyncState = started;
        }
        notifyGmailSyncObservers(started);
        gmailSyncWorker.execute(() -> runGmailSync(started));
        return true;
    }

    public void acknowledgeGmailSync(long generation) {
        synchronized (this) {
            if (gmailSyncState != null && gmailSyncState.generation == generation
                    && !gmailSyncState.inProgress) {
                gmailSyncState = null;
            }
        }
    }

    public void clearGmailSyncState() {
        synchronized (this) {
            gmailSyncGeneration++;
            gmailSyncState = null;
        }
        notifyGmailSyncObservers(null);
    }

    private void runGmailSync(GmailSyncState started) {
        SubscriptionSyncResult result = null;
        ApiException failure = null;
        try {
            result = repository.syncSubscriptionsFromGmail(started.authUserId);
        } catch (ApiException error) {
            failure = error;
        }
        Session currentSession = repository.getCurrentSession();

        GmailSyncState completed;
        synchronized (this) {
            if (gmailSyncState == null || gmailSyncState.generation != started.generation) return;
            completed = new GmailSyncState(started.generation, started.authUserId, false,
                    result, failure, currentSession);
            gmailSyncState = completed;
        }
        notifyGmailSyncObservers(completed);
    }

    private void notifyGmailSyncObservers(GmailSyncState state) {
        ArrayList<GmailSyncObserver> observers;
        synchronized (this) {
            observers = new ArrayList<>(gmailSyncObservers);
        }
        for (GmailSyncObserver observer : observers) postGmailSyncState(observer, state);
    }

    private void postGmailSyncState(GmailSyncObserver observer, GmailSyncState state) {
        mainHandler.post(() -> {
            synchronized (AccountDataViewModel.this) {
                if (!gmailSyncObservers.contains(observer)) return;
            }
            observer.onGmailSyncChanged(state);
        });
    }

    @Override
    protected void onCleared() {
        gmailSyncWorker.shutdownNow();
        synchronized (this) {
            gmailSyncObservers.clear();
            gmailSyncState = null;
        }
        super.onCleared();
    }
}
