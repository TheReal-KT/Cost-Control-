package com.example.agentcostcontrol.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.widget.AppCompatImageButton;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;

import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.model.Budget;
import com.example.agentcostcontrol.model.Reminder;
import com.example.agentcostcontrol.model.Subscription;
import com.google.android.material.button.MaterialButton;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
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
        void onOpenGmailConnection();
    }

    private HomeScreen() { }

    public static View create(Context context, List<Subscription> subscriptions, List<Budget> budgets,
                              List<Reminder> reminders, Map<String, BigDecimal> monthlySpend,
                              int activeCount, boolean totalsComplete, Actions actions) {
        LinearLayout page = Ui.page(context);
        page.addView(Ui.text(context, "Monthly spend", 15,
                Ui.color(context, R.color.text_secondary), false));

        if (monthlySpend.isEmpty()) {
            addSpendAmount(context, page, BigDecimal.ZERO, "");
            page.addView(Ui.text(context, "No active services.", 15,
                    Ui.color(context, R.color.text_secondary), false), margin(context, 0, 8, 0, 12));
        } else {
            for (Map.Entry<String, BigDecimal> total : monthlySpend.entrySet()) {
                addSpendAmount(context, page, total.getValue(), total.getKey());
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

        LinearLayout quickActions = Ui.row(context);
        addQuickAction(context, quickActions, "Add service", R.drawable.ic_action_add, actions::onAddService);
        addQuickAction(context, quickActions, "Reminder", R.drawable.ic_nav_reminders, actions::onAddReminder);
        addQuickAction(context, quickActions, "Services", R.drawable.ic_nav_services, actions::onOpenServices);
        page.addView(quickActions, margin(context, 0, 26, 0, 26));

        LinearLayout gmailContent = Ui.column(context);
        gmailContent.setPadding(Ui.dp(context, 16), Ui.dp(context, 14),
                Ui.dp(context, 16), Ui.dp(context, 14));
        gmailContent.addView(Ui.section(context, "Find subscriptions from Gmail"));
        gmailContent.addView(Ui.secondary(context,
                "Connect Gmail in Services. Scanning starts only when you tap Sync subscriptions."),
                margin(context, 0, 6, 0, 10));
        MaterialButton gmailButton = Ui.button(context, "Set up Gmail connection", false);
        gmailButton.setOnClickListener(view -> actions.onOpenGmailConnection());
        gmailContent.addView(gmailButton);
        page.addView(Ui.card(context, gmailContent), margin(context, 0, 0, 0, 24));

        LinearLayout servicesHeading = Ui.row(context);
        servicesHeading.addView(Ui.section(context, context.getString(R.string.home_services)),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        MaterialButton seeServices = Ui.textButton(context, "See all");
        seeServices.setStrokeWidth(0);
        seeServices.setTextColor(Ui.color(context, R.color.brand_blue));
        seeServices.setOnClickListener(view -> actions.onOpenServices());
        servicesHeading.addView(seeServices);
        page.addView(servicesHeading);
        List<Subscription> preview = new ArrayList<>(subscriptions);
        preview.sort(Comparator.comparing(Subscription::getRenewalDate).thenComparing(Subscription::getName));
        if (preview.isEmpty()) {
            page.addView(Ui.secondary(context, context.getString(R.string.home_services_empty)),
                    margin(context, 0, 14, 0, 4));
        }
        for (int i = 0; i < Math.min(5, preview.size()); i++) {
            Subscription subscription = preview.get(i);
            LinearLayout row = Ui.row(context);
            ServiceBrand.prepend(context, row, subscription.getName());
            LinearLayout copy = Ui.column(context);
            copy.setPadding(0, Ui.dp(context, 14), Ui.dp(context, 10), Ui.dp(context, 14));
            copy.addView(Ui.text(context, subscription.getName(), 16,
                    Ui.color(context, R.color.text_primary), true));
            copy.addView(Ui.secondary(context, subscription.getStatus()
                    == com.example.agentcostcontrol.model.SubscriptionStatus.ACTIVE
                    ? "Renews " + subscription.getRenewalDate()
                    : ServicesScreen.titleCase(subscription.getStatus().name())), margin(context, 0, 3, 0, 0));
            row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout price = Ui.column(context);
            price.setGravity(Gravity.END);
            price.addView(Ui.text(context, MoneyText.format(subscription.getPrice(), subscription.getCurrency()),
                    15, Ui.color(context, R.color.text_primary), false));
            price.addView(Ui.secondary(context, subscription.getBillingCycle().name()
                    .toLowerCase(java.util.Locale.ROOT)), margin(context, 0, 3, 0, 0));
            row.addView(price);
            row.setMinimumHeight(Ui.dp(context, 76));
            row.setFocusable(true);
            row.setContentDescription(subscription.getName() + ", "
                    + MoneyText.format(subscription.getPrice(), subscription.getCurrency()) + ", "
                    + subscription.getBillingCycle().name().toLowerCase(java.util.Locale.ROOT) + ", "
                    + ServicesScreen.titleCase(subscription.getStatus().name()) + ", renewal "
                    + subscription.getRenewalDate());
            row.setOnClickListener(view -> actions.onOpenService(subscription));
            page.addView(row);
            page.addView(Ui.divider(context), new LinearLayout.LayoutParams(-1, Ui.dp(context, 1)));
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
        ((MaterialButton) renewalsHeading.getChildAt(1)).setStrokeWidth(0);
        ((MaterialButton) renewalsHeading.getChildAt(1)).setTextColor(Ui.color(context, R.color.brand_blue));
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
            ServiceBrand.prepend(context, row, nextSubscription.getName());
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

        return Ui.scroll(context, page);
    }

    private static void addSpendAmount(Context context, LinearLayout page, BigDecimal total, String currency) {
        TextView amount = Ui.text(context, MoneyText.format(total, currency).trim(), 38,
                Ui.color(context, R.color.text_primary), true);
        amount.setLetterSpacing(-.03f);
        page.addView(amount, margin(context, 0, 6, 0, 0));
    }

    private static void addQuickAction(Context context, LinearLayout row, String label, int icon, Runnable action) {
        LinearLayout column = Ui.column(context);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        AppCompatImageButton button = new AppCompatImageButton(context);
        button.setContentDescription(label);
        button.setImageResource(icon);
        button.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(context, R.color.brand_blue_on)));
        button.setPadding(Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14));
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Ui.color(context, R.color.brand_blue));
        button.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x26000000), circle, null));
        button.setOnClickListener(view -> action.run());
        column.addView(button, new LinearLayout.LayoutParams(Ui.dp(context, 52), Ui.dp(context, 52)));
        TextView caption = Ui.text(context, label, 12, Ui.color(context, R.color.text_primary), false);
        caption.setGravity(Gravity.CENTER);
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        column.addView(caption, margin(context, 0, 8, 0, 0));
        row.addView(column, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
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
