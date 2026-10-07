package com.example.agentcostcontrol.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.model.Budget;
import com.example.agentcostcontrol.model.Reminder;
import com.example.agentcostcontrol.model.Subscription;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Home overview composed only from values returned by the signed-in user's data queries. */
public final class HomeScreen {
    public interface Actions {
        void onAddService();
        void onAddReminder();
        void onOpenServices();
        void onOpenReminders();
        void onOpenService(Subscription subscription);
        void onOpenReminder(Reminder reminder);
    }

    private HomeScreen() { }

    public static View create(Context context, List<Subscription> subscriptions, List<Budget> budgets,
                              List<Reminder> reminders, Map<String, BigDecimal> monthlySpend,
                              int activeCount, boolean totalsComplete, Actions actions) {
        LinearLayout page = Ui.page(context);
        page.addView(Ui.heading(context, "Monthly spend"));

        if (monthlySpend.isEmpty()) {
            page.addView(Ui.text(context, "No active services.", 15,
                    Ui.color(context, R.color.text_secondary), false), margin(context, 0, 8, 0, 12));
        } else {
            for (Map.Entry<String, BigDecimal> total : monthlySpend.entrySet()) {
                page.addView(Ui.text(context, MoneyText.format(total.getValue(), total.getKey()), 30,
                        Ui.color(context, R.color.text_primary), true), margin(context, 0, 6, 0, 0));
            }
        }
        if (activeCount > 0) {
            page.addView(Ui.secondary(context, activeCount + " active " + (activeCount == 1 ? "service" : "services")),
                    margin(context, 0, 8, 0, 0));
        }
        if (!totalsComplete) {
            page.addView(Ui.secondary(context, "Totals include the first 1,000 services."),
                    margin(context, 0, 4, 0, 0));
        }

        addRule(context, page, 24);
        page.addView(Ui.section(context, "Monthly budgets"), margin(context, 0, 0, 0, 4));
        YearMonth currentMonth = YearMonth.now();
        boolean foundBudget = false;
        for (Budget budget : budgets) {
            if (budget.getMonth() == currentMonth.getMonthValue() && budget.getYear() == currentMonth.getYear()) {
                foundBudget = true;
                BigDecimal spend = monthlySpend.getOrDefault(budget.getCurrency(), BigDecimal.ZERO);
                String label = MoneyText.format(spend, budget.getCurrency()) + " of "
                        + MoneyText.format(budget.getLimitAmount(), budget.getCurrency());
                page.addView(Ui.text(context, label, 14, Ui.color(context, R.color.text_primary), false),
                        margin(context, 0, 10, 0, 0));
            }
        }
        if (!foundBudget) {
            page.addView(Ui.secondary(context, "No budget this month."), margin(context, 0, 8, 0, 0));
        }

        addRule(context, page, 24);
        LinearLayout renewalsHeading = Ui.row(context);
        renewalsHeading.addView(Ui.section(context, "Next renewal"), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        renewalsHeading.addView(Ui.textButton(context, "See all"), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        renewalsHeading.getChildAt(1).setOnClickListener(view -> actions.onOpenReminders());
        page.addView(renewalsHeading);

        Subscription nextSubscription = subscriptions.stream()
                .filter(subscription -> subscription.getStatus() == com.example.agentcostcontrol.model.SubscriptionStatus.ACTIVE)
                .filter(subscription -> !subscription.getRenewalDate().isBefore(LocalDate.now()))
                .min(Comparator.comparing(Subscription::getRenewalDate)).orElse(null);
        if (nextSubscription == null) {
            page.addView(Ui.secondary(context, "No upcoming renewals."), margin(context, 0, 10, 0, 0));
        } else {
            LinearLayout row = Ui.row(context);
            LinearLayout copy = Ui.column(context);
            copy.addView(Ui.text(context, nextSubscription.getName(), 15,
                    Ui.color(context, R.color.text_primary), true));
            copy.addView(Ui.secondary(context, nextSubscription.getRenewalDate().toString()),
                    margin(context, 0, 3, 0, 0));
            row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(Ui.text(context, MoneyText.format(nextSubscription.getPrice(), nextSubscription.getCurrency()),
                    14, Ui.color(context, R.color.text_primary), true));
            row.setMinimumHeight(Ui.dp(context, 68));
            row.setContentDescription(nextSubscription.getName() + ", renews "
                    + nextSubscription.getRenewalDate() + ", "
                    + MoneyText.format(nextSubscription.getPrice(), nextSubscription.getCurrency()));
            row.setOnClickListener(view -> actions.onOpenService(nextSubscription));
            page.addView(row, margin(context, 0, 4, 0, 0));
        }

        Reminder upcomingReminder = reminders.stream()
                .filter(reminder -> reminder.getStatus() == com.example.agentcostcontrol.model.ReminderStatus.PENDING)
                .min(Comparator.comparing(Reminder::getRemindAt)).orElse(null);
        if (upcomingReminder != null) {
            Subscription linked = findSubscription(subscriptions, upcomingReminder.getSubscriptionId());
            String name = linked == null ? upcomingReminder.getTitle() : linked.getName();
            String reminderPrefix = upcomingReminder.getRemindAt().toInstant().isBefore(java.time.Instant.now())
                    ? "Overdue reminder · " : "Reminder · ";
            page.addView(Ui.text(context, reminderPrefix + name + " · "
                            + upcomingReminder.getRemindAt().toLocalDate(), 13,
                    Ui.color(context, R.color.text_secondary), false), margin(context, 0, 10, 0, 0));
            page.getChildAt(page.getChildCount() - 1).setOnClickListener(view -> actions.onOpenReminder(upcomingReminder));
        }

        addRule(context, page, 22);
        LinearLayout buttons = Ui.row(context);
        com.google.android.material.button.MaterialButton addService = Ui.button(context, "Add service", true);
        addService.setOnClickListener(view -> actions.onAddService());
        buttons.addView(addService, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        com.google.android.material.button.MaterialButton addReminder = Ui.button(context, "Add reminder", false);
        addReminder.setOnClickListener(view -> actions.onAddReminder());
        LinearLayout.LayoutParams reminderParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        reminderParams.setMarginStart(Ui.dp(context, 10));
        buttons.addView(addReminder, reminderParams);
        page.addView(buttons);
        com.google.android.material.button.MaterialButton allServices = Ui.textButton(context, "All services");
        allServices.setOnClickListener(view -> actions.onOpenServices());
        page.addView(allServices, margin(context, 0, 12, 0, 0));

        return Ui.scroll(context, page);
    }

    private static Subscription findSubscription(List<Subscription> subscriptions, long id) {
        for (Subscription subscription : subscriptions) if (subscription.getId() == id) return subscription;
        return null;
    }

    private static void addRule(Context context, LinearLayout parent, int topMargin) {
        parent.addView(Ui.divider(context), margin(context, 0, topMargin, 0, topMargin));
    }

    private static LinearLayout.LayoutParams margin(Context context, int start, int top, int end, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(Ui.dp(context, start), Ui.dp(context, top), Ui.dp(context, end), Ui.dp(context, bottom));
        return params;
    }
}
