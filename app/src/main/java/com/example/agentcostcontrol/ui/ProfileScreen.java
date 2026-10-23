package com.example.agentcostcontrol.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.model.Budget;
import com.example.agentcostcontrol.model.UserProfile;
import com.google.android.material.button.MaterialButton;

import java.time.Month;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

public final class ProfileScreen {
    public static final class GmailState {
        public final boolean statusLoaded;
        public final boolean configured;
        public final boolean connected;
        public final boolean working;
        public final boolean syncing;
        public final String statusError;

        public GmailState(boolean statusLoaded, boolean configured, boolean connected, boolean working,
                          boolean syncing, String statusError) {
            this.statusLoaded = statusLoaded;
            this.configured = configured;
            this.connected = connected;
            this.working = working;
            this.syncing = syncing;
            this.statusError = statusError;
        }
    }

    public interface Actions {
        void onAddBudget();
        void onEditBudget(Budget budget);
        void onLoadMoreBudgets();
        void onConnectGmail();
        void onSyncSubscriptions();
        void onRefreshGmail();
        void onSignOut();
    }

    private ProfileScreen() { }

    public static View create(Context context, UserProfile profile, List<Budget> budgets,
                              boolean hasMoreBudgets, GmailState gmail, Actions actions) {
        LinearLayout page = Ui.page(context);

        LinearLayout account = Ui.column(context);
        account.setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 14));
        String name = (profile.getFirstName() + " " + profile.getLastName()).trim();
        account.addView(Ui.text(context, name.isEmpty() ? profile.getEmail() : name, 18,
                Ui.color(context, R.color.text_primary), true));
        if (!name.isEmpty()) {
            account.addView(Ui.secondary(context, profile.getEmail()), margin(context, 0, 3, 0, 0));
        }
        page.addView(Ui.card(context, account), margin(context, 0, 14, 0, 22));

        page.addView(Ui.section(context, "Connections"), margin(context, 0, 0, 0, 8));
        addGmailConnection(context, page, gmail, actions);

        LinearLayout budgetTitle = Ui.row(context);
        budgetTitle.addView(Ui.section(context, "Budgets"), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        com.google.android.material.button.MaterialButton addBudget = Ui.textButton(context, "Add");
        addBudget.setOnClickListener(view -> actions.onAddBudget());
        budgetTitle.addView(addBudget);
        page.addView(budgetTitle);

        if (budgets.isEmpty()) {
            page.addView(Ui.secondary(context, "No budgets."), margin(context, 0, 10, 0, 4));
        } else {
            for (Budget budget : budgets) {
                LinearLayout row = Ui.row(context);
                LinearLayout copy = Ui.column(context);
                String month = Month.of(budget.getMonth()).getDisplayName(TextStyle.FULL, Locale.getDefault());
                copy.addView(Ui.text(context, month + " " + budget.getYear(), 15,
                        Ui.color(context, R.color.text_primary), true));
                row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(Ui.text(context, MoneyText.format(budget.getLimitAmount(), budget.getCurrency()),
                        15, Ui.color(context, R.color.text_primary), true));
                row.setMinimumHeight(Ui.dp(context, 66));
                row.setClickable(true);
                row.setFocusable(true);
                row.setContentDescription("Edit " + month + " " + budget.getYear() + " "
                        + budget.getCurrency() + " budget " + MoneyText.format(budget.getLimitAmount(), budget.getCurrency()));
                row.setOnClickListener(view -> actions.onEditBudget(budget));
                page.addView(row);
                page.addView(Ui.divider(context), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 1)));
            }
        }
        if (hasMoreBudgets) {
            com.google.android.material.button.MaterialButton loadMore = Ui.textButton(context, "Load more budgets");
            loadMore.setOnClickListener(view -> actions.onLoadMoreBudgets());
            page.addView(loadMore, margin(context, 0, 12, 0, 4));
        }

        addPreference(context, page, "Time zone", java.time.ZoneId.systemDefault().getId());

        com.google.android.material.button.MaterialButton signOut = Ui.textButton(context, "Sign out");
        signOut.setOnClickListener(view -> actions.onSignOut());
        page.addView(signOut, margin(context, 0, 28, 0, 0));
        return Ui.scroll(context, page);
    }

    private static void addGmailConnection(Context context, LinearLayout page, GmailState gmail,
                                           Actions actions) {
        LinearLayout cardContent = Ui.column(context);
        cardContent.setPadding(Ui.dp(context, 16), Ui.dp(context, 14),
                Ui.dp(context, 16), Ui.dp(context, 14));
        cardContent.addView(Ui.section(context, "Find subscriptions from Gmail"));

        String connectionStatus;
        if (gmail.statusError != null && !gmail.statusError.trim().isEmpty()) {
            connectionStatus = gmail.statusError;
        } else if (!gmail.statusLoaded) {
            connectionStatus = "Checking your Gmail connection…";
        } else if (!gmail.configured) {
            connectionStatus = "Gmail discovery is not configured on this server yet.";
        } else if (gmail.connected) {
            connectionStatus = "Connected. Gmail is scanned only when you tap Sync subscriptions.";
        } else {
            connectionStatus = "Connect Gmail to find subscriptions in recent email.";
        }
        cardContent.addView(Ui.secondary(context, connectionStatus), margin(context, 0, 6, 0, 0));
        cardContent.addView(Ui.secondary(context,
                "We scan up to 50 matching messages at a time and may miss some subscriptions."),
                margin(context, 0, 4, 0, 0));

        String connectLabel = gmail.connected ? "Gmail connected"
                : gmail.working ? "Preparing Gmail connection…" : "Connect Gmail";
        MaterialButton connect = Ui.button(context, connectLabel, !gmail.connected);
        connect.setEnabled(gmail.statusLoaded && gmail.configured && !gmail.connected && !gmail.working);
        connect.setOnClickListener(view -> actions.onConnectGmail());
        cardContent.addView(connect, margin(context, 0, 12, 0, 6));

        MaterialButton sync = Ui.button(context,
                gmail.syncing ? "Syncing subscriptions…" : "Sync subscriptions", false);
        sync.setEnabled(gmail.statusLoaded && gmail.configured && gmail.connected && !gmail.working);
        sync.setOnClickListener(view -> actions.onSyncSubscriptions());
        cardContent.addView(sync);

        MaterialButton refresh = Ui.textButton(context, "Refresh connection status");
        refresh.setEnabled(!gmail.working);
        refresh.setOnClickListener(view -> actions.onRefreshGmail());
        cardContent.addView(refresh, margin(context, 0, 4, 0, 0));

        page.addView(Ui.card(context, cardContent), margin(context, 0, 0, 0, 20));
    }

    private static void addPreference(Context context, LinearLayout page, String name, String value) {
        LinearLayout row = Ui.column(context);
        row.setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 10));
        row.addView(Ui.secondary(context, name));
        row.addView(Ui.text(context, value, 14, Ui.color(context, R.color.text_primary), false),
                margin(context, 0, 2, 0, 0));
        page.addView(row);
        page.addView(Ui.divider(context), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 1)));
    }

    private static LinearLayout.LayoutParams margin(Context context, int start, int top, int end, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(Ui.dp(context, start), Ui.dp(context, top), Ui.dp(context, end), Ui.dp(context, bottom));
        return params;
    }
}
