package com.example.agentcostcontrol.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
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
        void onDisconnectGmail();
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
        LinearLayout heading = Ui.row(context);
        ImageView icon = new ImageView(context);
        icon.setImageResource(R.drawable.ic_brand_gmail);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(icon, new LinearLayout.LayoutParams(Ui.dp(context, 28), Ui.dp(context, 28)));
        LinearLayout title = Ui.column(context);
        title.addView(Ui.text(context, "Gmail", 16, Ui.color(context, R.color.text_primary), true));
        String connectionStatus;
        if (gmail.statusError != null && !gmail.statusError.trim().isEmpty()) {
            connectionStatus = gmail.statusError;
        } else if (!gmail.statusLoaded) {
            connectionStatus = "Checking connection…";
        } else if (!gmail.configured) {
            connectionStatus = "Not configured";
        } else if (gmail.connected) {
            connectionStatus = "Connected";
        } else {
            connectionStatus = "Not connected";
        }
        title.addView(Ui.secondary(context, connectionStatus));
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMarginStart(Ui.dp(context, 12));
        heading.addView(title, titleParams);
        cardContent.addView(heading);

        LinearLayout buttons = Ui.row(context);
        boolean retryStatus = !gmail.statusLoaded && gmail.statusError != null;
        String connectLabel = retryStatus ? "Retry" : gmail.connected ? "Disconnect" : "Connect";
        MaterialButton connect = Ui.button(context, connectLabel, !gmail.connected);
        connect.setContentDescription(connectLabel + " Gmail");
        connect.setEnabled((retryStatus || (gmail.statusLoaded && gmail.configured)) && !gmail.working);
        connect.setOnClickListener(view -> {
            if (retryStatus) actions.onRefreshGmail();
            else if (gmail.connected) actions.onDisconnectGmail();
            else actions.onConnectGmail();
        });
        buttons.addView(connect, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        MaterialButton sync = Ui.button(context, gmail.syncing ? "Syncing…" : "Sync", false);
        sync.setContentDescription("Sync Gmail subscriptions");
        sync.setEnabled(gmail.statusLoaded && gmail.configured && gmail.connected && !gmail.working);
        sync.setOnClickListener(view -> actions.onSyncSubscriptions());
        LinearLayout.LayoutParams syncParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        syncParams.setMarginStart(Ui.dp(context, 8));
        buttons.addView(sync, syncParams);
        cardContent.addView(buttons, margin(context, 0, 12, 0, 0));

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
