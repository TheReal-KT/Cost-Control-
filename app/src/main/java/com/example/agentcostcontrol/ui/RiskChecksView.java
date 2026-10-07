package com.example.agentcostcontrol.ui;

import androidx.appcompat.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.data.RiskThresholdStore;
import com.example.agentcostcontrol.domain.RiskEvidence;
import com.example.agentcostcontrol.domain.RiskFlag;
import com.example.agentcostcontrol.domain.RiskFlagType;
import com.example.agentcostcontrol.domain.RiskRuleConfig;
import com.example.agentcostcontrol.domain.RiskRuleEngine;
import com.example.agentcostcontrol.model.Budget;
import com.example.agentcostcontrol.model.Subscription;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Displays deterministic, read-only checks computed on this device from loaded account data. */
public final class RiskChecksView extends LinearLayout {
    private static final int RENEWAL_WINDOW_DAYS = 7; // Shared database contract default.
    private static final int MAX_DISPLAYED_FLAGS = 20;
    private static final BigDecimal MAX_THRESHOLD = new BigDecimal("9999999999.99");

    public interface OnServiceOpenListener {
        void onOpen(long subscriptionId);
    }

    private final RiskThresholdStore thresholdStore;
    private final List<Subscription> subscriptions;
    private final List<Budget> budgets;
    private final boolean subscriptionsComplete;
    private final OnServiceOpenListener onServiceOpen;
    private final LocalDate asOfDate;
    private Map<String, BigDecimal> thresholds;

    public RiskChecksView(Context context, String authenticatedUserId,
                          List<Subscription> subscriptions, List<Budget> budgets,
                          boolean subscriptionsComplete, OnServiceOpenListener onServiceOpen) {
        this(context, authenticatedUserId, subscriptions, budgets, subscriptionsComplete,
                onServiceOpen, LocalDate.now());
    }

    RiskChecksView(Context context, String authenticatedUserId,
                   List<Subscription> subscriptions, List<Budget> budgets,
                   boolean subscriptionsComplete, OnServiceOpenListener onServiceOpen,
                   LocalDate asOfDate) {
        super(context);
        this.thresholdStore = new RiskThresholdStore(context, authenticatedUserId);
        this.subscriptions = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(subscriptions, "subscriptions")));
        this.budgets = Collections.unmodifiableList(new ArrayList<>(
                Objects.requireNonNull(budgets, "budgets")));
        this.subscriptionsComplete = subscriptionsComplete;
        this.onServiceOpen = onServiceOpen;
        this.asOfDate = Objects.requireNonNull(asOfDate, "asOfDate");
        this.thresholds = thresholdStore.loadMonthlyHighCostThresholds();
        setOrientation(VERTICAL);
        renderChecks();
    }

    private void renderChecks() {
        removeAllViews();

        LinearLayout headingRow = Ui.row(getContext());
        headingRow.addView(Ui.section(getContext(), "Checks"),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        MaterialButton settings = Ui.textButton(getContext(), "Thresholds");
        settings.setOnClickListener(view -> showThresholdSettings());
        headingRow.addView(settings);
        addView(headingRow, margin(getContext(), 0, 0, 0, 4));
        if (!subscriptionsComplete) {
            addView(Ui.secondary(getContext(),
                    "Partial results · " + subscriptions.size()
                            + (subscriptions.size() == 1 ? " service checked." : " services checked.")),
                    margin(getContext(), 0, 0, 0, 12));
        }
        List<RiskFlag> flags = RiskRuleEngine.evaluate(subscriptions, budgets, asOfDate,
                new RiskRuleConfig(RENEWAL_WINDOW_DAYS, thresholds, MAX_DISPLAYED_FLAGS));
        if (flags.isEmpty()) {
            addView(Ui.secondary(getContext(), "No checks to show."), margin(getContext(), 0, 2, 0, 8));
            return;
        }

        if (flags.size() == MAX_DISPLAYED_FLAGS) {
            addView(Ui.secondary(getContext(), "First " + MAX_DISPLAYED_FLAGS + " checks"),
                    margin(getContext(), 0, 0, 0, 8));
        }
        LinearLayout checksList = Ui.column(getContext());
        for (RiskFlag flag : flags) checksList.addView(flagCard(flag), margin(getContext(), 0, 0, 0, 10));
        addView(checksList);
    }

    private View flagCard(RiskFlag flag) {
        LinearLayout content = Ui.column(getContext());
        content.setPadding(Ui.dp(getContext(), 14), Ui.dp(getContext(), 12),
                Ui.dp(getContext(), 14), Ui.dp(getContext(), 12));
        content.addView(Ui.text(getContext(), flag.getTitle(), 15,
                Ui.color(getContext(), R.color.text_primary), true));
        if (flag.getType() == RiskFlagType.POSSIBLE_DUPLICATE_CATEGORY) {
            content.addView(Ui.secondary(getContext(), "Review whether you need both."),
                    margin(getContext(), 0, 4, 0, 0));
        }

        String evidence = evidenceSummary(flag.getEvidence());
        if (!evidence.isEmpty()) {
            content.addView(Ui.secondary(getContext(), evidence), margin(getContext(), 0, 5, 0, 0));
        }
        if (onServiceOpen != null) {
            int linked = 0;
            for (Long subscriptionId : flag.getEvidence().getSubscriptionIds()) {
                if (linked++ == 3) break;
                MaterialButton open = Ui.textButton(getContext(), "View " + serviceName(subscriptionId));
                open.setOnClickListener(view -> onServiceOpen.onOpen(subscriptionId));
                content.addView(open, margin(getContext(), 0, 8, 0, 0));
            }
        }
        return Ui.card(getContext(), content);
    }

    private String serviceName(long id) {
        for (Subscription subscription : subscriptions) {
            if (subscription.getId() == id) return subscription.getName();
        }
        return "service";
    }

    private String evidenceSummary(RiskEvidence evidence) {
        StringBuilder summary = new StringBuilder();
        if (evidence.getBudgetId() != null) {
            summary.append("Budget");
            if (evidence.getBudgetPeriod() != null) {
                summary.append(" · ").append(evidence.getBudgetPeriod());
            }
            if (evidence.getMonthlyAmount() != null) {
                summary.append(" · spend ").append(evidence.getMonthlyAmount().toPlainString())
                        .append(' ').append(evidence.getCurrency());
            }
            if (evidence.getThresholdAmount() != null) {
                summary.append(" · limit ").append(evidence.getThresholdAmount().toPlainString())
                        .append(' ').append(evidence.getCurrency());
            }
            return summary.toString();
        }

        int described = 0;
        for (Map.Entry<Long, LocalDate> entry : evidence.getRenewalDatesBySubscriptionId().entrySet()) {
            if (described++ == 3) {
                summary.append(" · ").append(evidence.getSubscriptionIds().size() - 3).append(" more");
                break;
            }
            if (summary.length() > 0) summary.append(" · ");
            summary.append(serviceName(entry.getKey()));
            if (entry.getValue() != null) summary.append(" · renews ").append(entry.getValue());
        }
        if (evidence.getMonthlyAmount() != null) {
            if (summary.length() > 0) summary.append(" · ");
            summary.append("monthly equivalent ").append(evidence.getMonthlyAmount().toPlainString())
                    .append(' ').append(evidence.getCurrency());
        } else if (evidence.getCurrency() != null) {
            if (summary.length() > 0) summary.append(" · ");
            summary.append(evidence.getCurrency());
        }
        if (evidence.getThresholdAmount() != null) {
            summary.append(" · threshold ").append(evidence.getThresholdAmount().toPlainString())
                    .append(' ').append(evidence.getCurrency());
        }
        return summary.toString();
    }

    private void showThresholdSettings() {
        ThresholdSettingsContent form = new ThresholdSettingsContent(getContext());
        AlertDialog dialog = new MaterialAlertDialogBuilder(getContext())
                .setTitle("Monthly high-cost thresholds")
                .setView(form.scrollView)
                .setNegativeButton("Done", null)
                .setPositiveButton("Save threshold", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            saveButton.setOnClickListener(view -> saveThreshold(form));
        });
        dialog.show();
    }

    private void saveThreshold(ThresholdSettingsContent form) {
        form.currency.setError(null);
        form.amount.setError(null);
        form.message.setText("");

        String currency = Ui.value(form.currency).toUpperCase(Locale.ROOT);
        if (!currency.matches("[A-Z]{3}")) {
            form.currency.setError("Enter a three-letter currency code.");
            return;
        }

        BigDecimal amount;
        try {
            amount = new BigDecimal(Ui.value(form.amount));
        } catch (NumberFormatException | NullPointerException exception) {
            form.amount.setError("Enter a valid monthly amount.");
            return;
        }
        if (amount.signum() <= 0 || amount.scale() > 2 || amount.compareTo(MAX_THRESHOLD) > 0) {
            form.amount.setError("Use an amount above zero, up to 9,999,999,999.99, with at most two decimals.");
            return;
        }

        try {
            thresholdStore.putMonthlyHighCostThreshold(currency, amount);
            reloadThresholds();
            form.renderSavedThresholds();
            form.currency.getEditText().setText("");
            form.amount.getEditText().setText("");
            form.message.setText("Saved for " + currency + ".");
        } catch (IllegalStateException exception) {
            form.message.setText("Could not save this setting on the device. Try again.");
        }
    }

    private void removeThreshold(String currency, ThresholdSettingsContent form) {
        try {
            thresholdStore.removeMonthlyHighCostThreshold(currency);
            reloadThresholds();
            form.renderSavedThresholds();
            form.message.setText("Removed the " + currency + " threshold.");
        } catch (IllegalStateException exception) {
            form.message.setText("Could not remove this setting on the device. Try again.");
        }
    }

    private void reloadThresholds() {
        thresholds = thresholdStore.loadMonthlyHighCostThresholds();
        renderChecks();
    }

    private final class ThresholdSettingsContent {
        final ScrollView scrollView;
        final LinearLayout savedThresholds;
        final TextInputLayout currency;
        final TextInputLayout amount;
        final TextView message;

        ThresholdSettingsContent(Context context) {
            LinearLayout page = Ui.column(context);
            page.setPadding(Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4));
            page.addView(Ui.secondary(context,
                    "Flag low-use services at or above this monthly amount."),
                    margin(context, 0, 0, 0, 12));
            page.addView(Ui.section(context, "Saved thresholds"), margin(context, 0, 0, 0, 4));
            savedThresholds = Ui.column(context);
            page.addView(savedThresholds, margin(context, 0, 0, 0, 8));

            currency = Ui.field(context, "Currency code", "",
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            amount = Ui.field(context, "Monthly amount", "",
                    InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                            | InputType.TYPE_NUMBER_FLAG_SIGNED);
            page.addView(currency, margin(context, 0, 0, 0, 8));
            page.addView(amount, margin(context, 0, 0, 0, 4));
            message = Ui.secondary(context, "");
            page.addView(message, margin(context, 0, 4, 0, 0));

            scrollView = Ui.scroll(context, page);
            int availableHeight = context.getResources().getDisplayMetrics().heightPixels;
            int maxHeight = Math.min(Ui.dp(context, 440), Math.max(Ui.dp(context, 240), availableHeight * 2 / 3));
            scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, maxHeight));
            renderSavedThresholds();
        }

        void renderSavedThresholds() {
            savedThresholds.removeAllViews();
            if (thresholds.isEmpty()) {
                savedThresholds.addView(Ui.secondary(getContext(), "No thresholds."));
                return;
            }
            for (Map.Entry<String, BigDecimal> entry : thresholds.entrySet()) {
                LinearLayout row = Ui.row(getContext());
                row.addView(Ui.text(getContext(), entry.getKey() + " · "
                                + entry.getValue().toPlainString() + " / month", 14,
                        Ui.color(getContext(), R.color.text_primary), false),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                MaterialButton remove = Ui.textButton(getContext(), "Remove");
                remove.setOnClickListener(view -> removeThreshold(entry.getKey(), this));
                row.addView(remove);
                savedThresholds.addView(row, margin(getContext(), 0, 0, 0, 4));
            }
        }
    }

    private static LinearLayout.LayoutParams margin(Context context, int start, int top, int end, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(Ui.dp(context, start), Ui.dp(context, top), Ui.dp(context, end), Ui.dp(context, bottom));
        return params;
    }
}
