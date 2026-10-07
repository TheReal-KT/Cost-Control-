package com.example.agentcostcontrol.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.model.Budget;
import com.example.agentcostcontrol.model.UserProfile;

import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

public final class ProfileScreen {
    public interface Actions {
        void onAddBudget();
        void onEditBudget(Budget budget);
        void onLoadMoreBudgets();
        void onSignOut();
    }

    private ProfileScreen() { }

    public static View create(Context context, UserProfile profile, List<Budget> budgets,
                              boolean hasMoreBudgets, Actions actions) {
        LinearLayout page = Ui.page(context);

        LinearLayout account = Ui.column(context);
        account.setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 14));
        String name = (profile.getFirstName() + " " + profile.getLastName()).trim();
        account.addView(Ui.text(context, name.isEmpty() ? profile.getEmail() : name, 18,
                Ui.color(context, R.color.text_primary), true));
        account.addView(Ui.secondary(context, profile.getEmail()), margin(context, 0, 3, 0, 0));
        page.addView(Ui.card(context, account), margin(context, 0, 14, 0, 22));

        LinearLayout budgetTitle = Ui.row(context);
        budgetTitle.addView(Ui.section(context, "Budgets"), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        com.google.android.material.button.MaterialButton addBudget = Ui.textButton(context, "Add");
        addBudget.setOnClickListener(view -> actions.onAddBudget());
        budgetTitle.addView(addBudget);
        page.addView(budgetTitle);

        if (budgets.isEmpty()) {
            page.addView(Ui.secondary(context, "No budgets saved yet."), margin(context, 0, 10, 0, 4));
        } else {
            for (Budget budget : budgets) {
                LinearLayout row = Ui.row(context);
                LinearLayout copy = Ui.column(context);
                String month = Month.of(budget.getMonth()).getDisplayName(TextStyle.FULL, Locale.getDefault());
                copy.addView(Ui.text(context, month + " " + budget.getYear(), 15,
                        Ui.color(context, R.color.text_primary), true));
                copy.addView(Ui.secondary(context, budget.getCurrency()), margin(context, 0, 3, 0, 0));
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

        page.addView(Ui.section(context, "Preferences"), margin(context, 0, 26, 0, 8));
        addPreference(context, page, "Amounts", "Shown in each service's saved currency; no conversion");
        addPreference(context, page, "Time zone", java.time.ZoneId.systemDefault().getId());

        com.google.android.material.button.MaterialButton signOut = Ui.textButton(context, "Sign out");
        signOut.setOnClickListener(view -> actions.onSignOut());
        page.addView(signOut, margin(context, 0, 28, 0, 0));
        return Ui.scroll(context, page);
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
