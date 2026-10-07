package com.example.agentcostcontrol.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.model.Reminder;
import com.example.agentcostcontrol.model.ReminderStatus;
import com.example.agentcostcontrol.model.Subscription;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RemindersScreen {
    public interface Actions {
        void onAdd();
        void onOpen(Reminder reminder);
        void onFilterChanged(String filter);
        void onLoadMore();
    }

    public interface DetailActions {
        void onEdit();
        void onStatusChange(ReminderStatus status);
        void onDelete();
    }

    private RemindersScreen() { }

    public static View list(Context context, List<Reminder> reminders, List<Subscription> subscriptions,
                            String filter, boolean hasMore, Actions actions) {
        String selectedFilter = filter == null ? "Upcoming" : filter;
        LinearLayout page = Ui.page(context);
        page.addView(Ui.secondary(context, "Schedules you manage for your services."), margin(context, 0, 2, 0, 12));

        ChipGroup filters = new ChipGroup(context);
        filters.setSingleSelection(true);
        filters.setSelectionRequired(true);
        for (String choice : new String[]{"Upcoming", "Completed"}) {
            Chip chip = new Chip(context);
            chip.setText(choice);
            chip.setCheckable(true);
            chip.setClickable(true);
            chip.setMinimumHeight(Ui.dp(context, 40));
            chip.setChecked(choice.equals(selectedFilter));
            filters.addView(chip, new ChipGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        page.addView(filters, margin(context, 0, 0, 0, 12));
        com.google.android.material.button.MaterialButton add = Ui.button(context, "Add reminder", true);
        add.setOnClickListener(view -> actions.onAdd());
        page.addView(add, margin(context, 0, 2, 0, 16));

        LinearLayout items = Ui.column(context);
        page.addView(items);
        renderList(context, items, reminders, subscriptions, selectedFilter, hasMore, actions);
        filters.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            Chip chip = group.findViewById(checkedIds.get(0));
            String next = chip.getText().toString();
            actions.onFilterChanged(next);
            renderList(context, items, reminders, subscriptions, next, hasMore, actions);
        });
        return Ui.scroll(context, page);
    }

    public static View detail(Context context, Reminder reminder, Subscription subscription, DetailActions actions) {
        LinearLayout page = Ui.page(context);
        page.addView(Ui.heading(context, subscription == null ? reminder.getTitle() : subscription.getName()));
        if (subscription != null) {
            page.addView(Ui.secondary(context, subscription.getCategory()), margin(context, 0, 2, 0, 14));
            LinearLayout money = Ui.column(context);
            money.setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 14));
            money.addView(Ui.text(context, MoneyText.format(subscription.getPrice(), subscription.getCurrency()),
                    22, Ui.color(context, R.color.text_primary), true));
            money.addView(Ui.secondary(context, "Renews " + subscription.getRenewalDate() + " · "
                    + subscription.getCurrency()), margin(context, 0, 4, 0, 0));
            page.addView(Ui.card(context, money), margin(context, 0, 6, 0, 16));
        }
        String state = ServicesScreen.titleCase(reminder.getStatus().name());
        page.addView(Ui.section(context, reminder.getTitle()), margin(context, 0, 2, 0, 6));
        if (reminder.getMessage() != null && !reminder.getMessage().trim().isEmpty()) {
            page.addView(Ui.secondary(context, reminder.getMessage()), margin(context, 0, 0, 0, 12));
        }
        addDetailRow(context, page, "Status", state);
        addDetailRow(context, page, "Scheduled", reminder.getRemindAt().toLocalDate().toString());
        addDetailRow(context, page, "Local time", reminder.getRemindAt().toLocalTime()
                .format(DateTimeFormatter.ofPattern("h:mm a")) + " · " + ZoneId.systemDefault().getId());

        com.google.android.material.button.MaterialButton change = Ui.button(context,
                reminder.getStatus() == ReminderStatus.COMPLETED ? "Reopen reminder" : "Mark complete", true);
        change.setOnClickListener(view -> actions.onStatusChange(reminder.getStatus() == ReminderStatus.COMPLETED
                ? ReminderStatus.PENDING : ReminderStatus.COMPLETED));
        page.addView(change, margin(context, 0, 22, 0, 8));
        com.google.android.material.button.MaterialButton reschedule = Ui.button(context, "Reschedule", false);
        reschedule.setOnClickListener(view -> actions.onEdit());
        page.addView(reschedule, margin(context, 0, 0, 0, 8));
        com.google.android.material.button.MaterialButton delete = Ui.textButton(context, "Delete reminder");
        delete.setTextColor(Ui.color(context, R.color.danger));
        delete.setOnClickListener(view -> actions.onDelete());
        page.addView(delete, margin(context, 0, 0, 0, 12));
        return Ui.scroll(context, page);
    }

    private static void renderList(Context context, LinearLayout parent, List<Reminder> reminders,
                                   List<Subscription> subscriptions, String filter, boolean hasMore, Actions actions) {
        parent.removeAllViews();
        List<Reminder> matching = new ArrayList<>();
        for (Reminder reminder : reminders) {
            boolean completedGroup = reminder.getStatus() == ReminderStatus.COMPLETED
                    || reminder.getStatus() == ReminderStatus.DISMISSED;
            if ("Completed".equals(filter) == completedGroup) matching.add(reminder);
        }
        matching.sort(Comparator.comparing(Reminder::getRemindAt));
        if (matching.isEmpty()) {
            parent.addView(Ui.secondary(context, "Completed".equals(filter)
                    ? "No completed reminders yet." : "No upcoming reminders. Add one when it will help."),
                    margin(context, 0, 14, 0, 12));
        }
        for (Reminder reminder : matching) {
            Subscription subscription = findSubscription(subscriptions, reminder.getSubscriptionId());
            String serviceName = subscription == null ? reminder.getTitle() : subscription.getName();
            boolean overdue = reminder.getStatus() == ReminderStatus.PENDING
                    && reminder.getRemindAt().toInstant().isBefore(java.time.Instant.now());
            LinearLayout row = Ui.row(context);
            LinearLayout copy = Ui.column(context);
            copy.addView(Ui.text(context, reminder.getTitle(), 15, Ui.color(context, R.color.text_primary), true));
            copy.addView(Ui.secondary(context, (overdue ? "Overdue · " : "") + serviceName + " · "
                    + reminder.getRemindAt().toLocalDate()
                    + " · " + reminder.getRemindAt().toLocalTime().format(DateTimeFormatter.ofPattern("h:mm a"))),
                    margin(context, 0, 4, 0, 0));
            row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            row.addView(ServicesScreen.statusPill(context, ServicesScreen.titleCase(reminder.getStatus().name()),
                    reminder.getStatus() == ReminderStatus.COMPLETED));
            row.setMinimumHeight(Ui.dp(context, 76));
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription(reminder.getTitle() + ", " + serviceName + ", "
                    + reminder.getRemindAt().toLocalDate() + " at " + reminder.getRemindAt().toLocalTime());
            row.setOnClickListener(view -> actions.onOpen(reminder));
            parent.addView(row);
            parent.addView(Ui.divider(context), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 1)));
        }
        if (hasMore) {
            com.google.android.material.button.MaterialButton loadMore = Ui.textButton(context, "Load more reminders");
            loadMore.setOnClickListener(view -> actions.onLoadMore());
            parent.addView(loadMore, margin(context, 0, 14, 0, 8));
        }
    }

    private static Subscription findSubscription(List<Subscription> subscriptions, long id) {
        for (Subscription subscription : subscriptions) if (subscription.getId() == id) return subscription;
        return null;
    }

    private static void addDetailRow(Context context, LinearLayout page, String label, String value) {
        LinearLayout row = Ui.row(context);
        row.setMinimumHeight(Ui.dp(context, 54));
        row.addView(Ui.secondary(context, label), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(Ui.text(context, value, 14, Ui.color(context, R.color.text_primary), false));
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
