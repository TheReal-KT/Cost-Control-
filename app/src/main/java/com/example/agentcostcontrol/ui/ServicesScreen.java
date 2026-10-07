package com.example.agentcostcontrol.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.model.Subscription;
import com.example.agentcostcontrol.model.SubscriptionStatus;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ServicesScreen {
    public interface Actions {
        void onAdd();
        void onOpen(Subscription subscription);
        void onSearchChanged(String query);
        void onFilterChanged(String filter);
        void onLoadMore();
    }

    private ServicesScreen() { }

    public static View create(Context context, List<Subscription> subscriptions, String query,
                              String filter, boolean hasMore, Actions actions) {
        LinearLayout page = Ui.page(context);
        TextInputLayout searchField = Ui.field(context, "Search services", query,
                android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        TextInputEditText search = (TextInputEditText) searchField.getEditText();
        page.addView(searchField, margin(context, 0, 14, 0, 8));

        ChipGroup filters = new ChipGroup(context);
        filters.setSingleSelection(true);
        filters.setSelectionRequired(true);
        String[] filterValues = {"All", "Active", "Paused", "Cancelled"};
        for (String value : filterValues) {
            Chip chip = new Chip(context);
            chip.setText(value);
            chip.setCheckable(true);
            chip.setClickable(true);
            chip.setMinimumHeight(Ui.dp(context, 40));
            chip.setChecked(value.equals(filter));
            filters.addView(chip, new ChipGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        page.addView(filters, margin(context, 0, 0, 0, 12));

        com.google.android.material.button.MaterialButton add = Ui.button(context, "Add service", true);
        add.setOnClickListener(view -> actions.onAdd());
        page.addView(add, margin(context, 0, 2, 0, 12));

        LinearLayout list = Ui.column(context);
        page.addView(list);
        renderRows(context, list, subscriptions, query, filter, hasMore, actions);
        final String[] currentQuery = {query == null ? "" : query};
        final String[] currentFilter = {filter == null ? "All" : filter};

        if (search != null) {
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                    String next = value.toString();
                    currentQuery[0] = next;
                    actions.onSearchChanged(next);
                    renderRows(context, list, subscriptions, next, currentFilter[0], hasMore, actions);
                }
                @Override public void afterTextChanged(Editable value) { }
            });
        }
        filters.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            Chip selected = group.findViewById(checkedIds.get(0));
            String nextFilter = selected.getText().toString();
            currentFilter[0] = nextFilter;
            actions.onFilterChanged(nextFilter);
            renderRows(context, list, subscriptions, currentQuery[0], nextFilter, hasMore, actions);
        });

        return Ui.scroll(context, page);
    }

    public static View detail(Context context, Subscription subscription, String monthlyEquivalent,
                              DetailActions actions) {
        LinearLayout page = Ui.page(context);
        page.addView(Ui.heading(context, subscription.getName()));
        page.addView(Ui.secondary(context, subscription.getCategory()), margin(context, 0, 2, 0, 12));

        String status = titleCase(subscription.getStatus().name());
        page.addView(statusPill(context, status, subscription.getStatus() == SubscriptionStatus.ACTIVE),
                marginWrap(context, 0, 0, 0, 16));

        LinearLayout money = Ui.column(context);
        money.setPadding(Ui.dp(context, 18), Ui.dp(context, 16), Ui.dp(context, 18), Ui.dp(context, 16));
        money.addView(Ui.secondary(context, "Billed " + subscription.getBillingCycle().name().toLowerCase(Locale.ROOT)));
        money.addView(Ui.text(context, MoneyText.format(subscription.getPrice(), subscription.getCurrency()), 28,
                Ui.color(context, R.color.text_primary), true), margin(context, 0, 3, 0, 0));
        if (monthlyEquivalent != null) {
            money.addView(Ui.text(context, monthlyEquivalent + " / month equivalent", 14,
                    Ui.color(context, R.color.text_secondary), false), margin(context, 0, 12, 0, 0));
        }
        page.addView(Ui.card(context, money));

        addDetailRow(context, page, "Next renewal", subscription.getRenewalDate().toString());
        addDetailRow(context, page, "Started", subscription.getStartDate().toString());
        addDetailRow(context, page, "Usage", subscription.getUsageLevel() == null
                ? "Not reported" : titleCase(subscription.getUsageLevel().name()));
        addDetailRow(context, page, "Importance", titleCase(subscription.getImportance().name()));
        if (subscription.getPlanName() != null && !subscription.getPlanName().trim().isEmpty()) {
            addDetailRow(context, page, "Plan", subscription.getPlanName());
        }
        com.google.android.material.button.MaterialButton edit = Ui.button(context, "Edit service", true);
        edit.setOnClickListener(view -> actions.onEdit());
        page.addView(edit, margin(context, 0, 22, 0, 8));
        com.google.android.material.button.MaterialButton reminder = Ui.button(context, "Add reminder", false);
        reminder.setOnClickListener(view -> actions.onAddReminder());
        page.addView(reminder, margin(context, 0, 0, 0, 16));
        page.addView(Ui.divider(context), margin(context, 0, 12, 0, 12));
        com.google.android.material.button.MaterialButton delete = Ui.textButton(context, "Delete service");
        delete.setTextColor(Ui.color(context, R.color.danger));
        delete.setOnClickListener(view -> actions.onDelete());
        page.addView(delete);
        return Ui.scroll(context, page);
    }

    private static void renderRows(Context context, LinearLayout list, List<Subscription> subscriptions,
                                   String query, String filter, boolean hasMore, Actions actions) {
        list.removeAllViews();
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<Subscription> matching = new ArrayList<>();
        for (Subscription subscription : subscriptions) {
            boolean statusMatches = "All".equals(filter)
                    || titleCase(subscription.getStatus().name()).equals(filter);
            boolean textMatches = normalizedQuery.isEmpty()
                    || subscription.getName().toLowerCase(Locale.ROOT).contains(normalizedQuery)
                    || subscription.getCategory().toLowerCase(Locale.ROOT).contains(normalizedQuery);
            if (statusMatches && textMatches) matching.add(subscription);
        }
        matching.sort(Comparator.comparing(Subscription::getCurrency)
                .thenComparing(Subscription::getRenewalDate).thenComparing(Subscription::getName));

        if (matching.isEmpty()) {
            String message = normalizedQuery.isEmpty() && "All".equals(filter)
                    ? "No services yet."
                    : "No matching services.";
            list.addView(Ui.secondary(context, message), margin(context, 0, 20, 0, 12));
        } else {
            String currentCurrency = null;
            for (Subscription subscription : matching) {
                if (!subscription.getCurrency().equals(currentCurrency)) {
                    currentCurrency = subscription.getCurrency();
                    list.addView(Ui.section(context, currentCurrency), margin(context, 0, 12, 0, 4));
                }
                LinearLayout row = Ui.row(context);
                LinearLayout copy = Ui.column(context);
                copy.addView(Ui.text(context, subscription.getName(), 15,
                        Ui.color(context, R.color.text_primary), true));
                copy.addView(Ui.secondary(context, subscription.getCategory() + " · "
                        + MoneyText.format(subscription.getPrice(), subscription.getCurrency()) + " / "
                        + subscription.getBillingCycle().name().toLowerCase(Locale.ROOT)),
                        margin(context, 0, 3, 0, 0));
                copy.addView(Ui.secondary(context, "Renews " + subscription.getRenewalDate()),
                        margin(context, 0, 2, 0, 0));
                row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(statusPill(context, titleCase(subscription.getStatus().name()),
                        subscription.getStatus() == SubscriptionStatus.ACTIVE));
                row.setMinimumHeight(Ui.dp(context, 76));
                row.setClickable(true);
                row.setFocusable(true);
                row.setContentDescription(subscription.getName() + ", " + subscription.getCategory() + ", "
                        + MoneyText.format(subscription.getPrice(), subscription.getCurrency()) + " "
                        + subscription.getBillingCycle().name().toLowerCase(Locale.ROOT) + ", renews "
                        + subscription.getRenewalDate() + ", " + titleCase(subscription.getStatus().name()));
                row.setOnClickListener(view -> actions.onOpen(subscription));
                list.addView(row);
                list.addView(Ui.divider(context), new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, 1)));
            }
        }
        if (hasMore) {
            com.google.android.material.button.MaterialButton loadMore = Ui.textButton(context, "Load more services");
            loadMore.setOnClickListener(view -> actions.onLoadMore());
            list.addView(loadMore, margin(context, 0, 14, 0, 8));
        }
    }

    public interface DetailActions {
        void onEdit();
        void onAddReminder();
        void onDelete();
    }

    static View statusPill(Context context, String label, boolean positive) {
        android.widget.TextView text = Ui.text(context, label, 12,
                Ui.color(context, positive ? R.color.positive : R.color.text_secondary), true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Ui.color(context, positive ? R.color.brand_blue_soft : R.color.surface_raised));
        background.setCornerRadius(Ui.dp(context, 18));
        text.setBackground(background);
        text.setPadding(Ui.dp(context, 10), Ui.dp(context, 6), Ui.dp(context, 10), Ui.dp(context, 6));
        return text;
    }

    static String titleCase(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.substring(0, 1).toUpperCase(Locale.ROOT) + lower.substring(1);
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

    private static LinearLayout.LayoutParams marginWrap(Context context, int start, int top, int end, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(Ui.dp(context, start), Ui.dp(context, top), Ui.dp(context, end), Ui.dp(context, bottom));
        return params;
    }
}
