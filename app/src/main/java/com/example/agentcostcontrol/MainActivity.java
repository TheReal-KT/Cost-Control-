package com.example.agentcostcontrol;

import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayDeque;
import java.util.ArrayList;

/** Hosts the five native screens. Data and authentication can be connected later. */
public class MainActivity extends AppCompatActivity {
    private static final String STATE_CURRENT = "current_screen";
    private static final String STATE_HISTORY = "screen_history";

    private enum Screen {
        DASHBOARD(R.id.nav_dashboard, R.drawable.ic_nav_home, R.string.dashboard, R.string.dashboard_intro,
                R.string.dashboard_empty_title, R.string.dashboard_empty_message),
        SUBSCRIPTIONS(R.id.nav_subscriptions, R.drawable.ic_nav_services, R.string.subscriptions,
                R.string.subscriptions_intro, R.string.subscriptions_empty_title,
                R.string.subscriptions_empty_message),
        REMINDERS(R.id.nav_reminders, R.drawable.ic_nav_reminders, R.string.reminders, R.string.reminders_intro,
                R.string.reminders_empty_title, R.string.reminders_empty_message),
        RECOMMENDATIONS(R.id.nav_recommendations, R.drawable.ic_nav_insights, R.string.recommendations,
                R.string.recommendations_intro, R.string.recommendations_empty_title,
                R.string.recommendations_empty_message),
        PROFILE(R.id.nav_profile, R.drawable.ic_nav_profile, R.string.profile_settings, R.string.profile_intro,
                R.string.profile_empty_title, R.string.profile_empty_message);

        final int navId;
        final int iconId;
        final int titleId;
        final int introId;
        final int emptyTitleId;
        final int emptyMessageId;

        Screen(int navId, int iconId, int titleId, int introId, int emptyTitleId, int emptyMessageId) {
            this.navId = navId;
            this.iconId = iconId;
            this.titleId = titleId;
            this.introId = introId;
            this.emptyTitleId = emptyTitleId;
            this.emptyMessageId = emptyMessageId;
        }
    }

    private final ArrayDeque<Screen> history = new ArrayDeque<>();
    private Screen currentScreen = Screen.DASHBOARD;
    private FrameLayout screenContainer;
    private TextView screenTitle;
    private TextView brandMark;
    private ImageButton backButton;
    private BottomNavigationView bottomNavigation;
    private OnBackInvokedCallback backCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        screenContainer = findViewById(R.id.screen_container);
        screenTitle = findViewById(R.id.screen_title);
        brandMark = findViewById(R.id.brand_mark);
        backButton = findViewById(R.id.back_button);
        backButton.setOnClickListener(view -> navigateBack());
        bottomNavigation = findViewById(R.id.bottom_navigation);
        bottomNavigation.setOnItemSelectedListener(item -> {
            Screen destination = screenForNavId(item.getItemId());
            navigateTo(destination);
            return true;
        });

        if (savedInstanceState != null) {
            String savedScreen = savedInstanceState.getString(STATE_CURRENT);
            currentScreen = screenFromName(savedScreen);
            ArrayList<String> savedHistory = savedInstanceState.getStringArrayList(STATE_HISTORY);
            if (savedHistory != null) {
                for (int i = savedHistory.size() - 1; i >= 0; i--) {
                    history.push(screenFromName(savedHistory.get(i)));
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = this::navigateBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }

        renderScreen();
    }

    private Screen screenFromName(String name) {
        try {
            return Screen.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return Screen.DASHBOARD;
        }
    }

    private Screen screenForNavId(int navId) {
        for (Screen screen : Screen.values()) {
            if (screen.navId == navId) return screen;
        }
        return Screen.DASHBOARD;
    }

    private void navigateTo(Screen destination) {
        if (destination == currentScreen) return;
        history.push(currentScreen);
        currentScreen = destination;
        renderScreen();
    }

    private void navigateBack() {
        if (history.isEmpty()) {
            finish();
            return;
        }
        currentScreen = history.pop();
        renderScreen();
    }

    private void renderScreen() {
        boolean atRoot = history.isEmpty();
        screenTitle.setText(currentScreen == Screen.DASHBOARD ? R.string.app_name : currentScreen.titleId);
        brandMark.setVisibility(atRoot ? View.VISIBLE : View.GONE);
        backButton.setVisibility(atRoot ? View.GONE : View.VISIBLE);
        bottomNavigation.getMenu().findItem(currentScreen.navId).setChecked(true);

        screenContainer.removeAllViews();
        int layoutId = currentScreen == Screen.DASHBOARD
                ? R.layout.screen_dashboard : R.layout.screen_placeholder;
        View content = LayoutInflater.from(this).inflate(layoutId, screenContainer, false);
        screenContainer.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (currentScreen != Screen.DASHBOARD) {
            ((TextView) content.findViewById(R.id.page_heading)).setText(currentScreen.titleId);
            ((TextView) content.findViewById(R.id.page_intro)).setText(currentScreen.introId);
        }
        showEmptyState(content);
    }

    private void showEmptyState(View content) {
        content.findViewById(R.id.loading_indicator).setVisibility(View.GONE);
        content.findViewById(R.id.empty_state).setVisibility(View.VISIBLE);
        ((TextView) content.findViewById(R.id.empty_title)).setText(currentScreen.emptyTitleId);
        ((TextView) content.findViewById(R.id.empty_message)).setText(currentScreen.emptyMessageId);
        ((ImageView) content.findViewById(R.id.empty_icon)).setImageResource(currentScreen.iconId);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_CURRENT, currentScreen.name());
        ArrayList<String> savedHistory = new ArrayList<>();
        for (Screen screen : history) savedHistory.add(screen.name());
        outState.putStringArrayList(STATE_HISTORY, savedHistory);
        super.onSaveInstanceState(outState);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        navigateBack();
    }

    @Override
    protected void onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        }
        super.onDestroy();
    }
}
