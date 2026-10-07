package com.example.agentcostcontrol.ui;

import android.app.DatePickerDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;

import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.model.BillingCycle;
import com.example.agentcostcontrol.model.Subscription;
import com.example.agentcostcontrol.model.SubscriptionDraft;
import com.example.agentcostcontrol.model.SubscriptionImportance;
import com.example.agentcostcontrol.model.SubscriptionStatus;
import com.example.agentcostcontrol.model.UsageLevel;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;

/** Stateful service editor. The activity saves the visible field values in its instance state. */
public final class SubscriptionForm extends LinearLayout {
    private static final String KEY_NAME = "name";
    private static final String KEY_PLAN = "plan";
    private static final String KEY_CATEGORY = "category";
    private static final String KEY_PRICE = "price";
    private static final String KEY_CURRENCY = "currency";
    private static final String KEY_CYCLE = "cycle";
    private static final String KEY_STATUS = "status";
    private static final String KEY_USAGE = "usage";
    private static final String KEY_IMPORTANCE = "importance";
    private static final String KEY_START = "start";
    private static final String KEY_RENEWAL = "renewal";
    private static final String KEY_AUTO_RENEW = "auto_renew";

    private final Subscription original;
    private final TextInputLayout name;
    private final TextInputLayout plan;
    private final TextInputLayout category;
    private final TextInputLayout price;
    private final TextInputLayout currency;
    private final TextInputLayout cycle;
    private final TextInputLayout status;
    private final TextInputLayout usage;
    private final TextInputLayout importance;
    private final TextInputLayout startDate;
    private final TextInputLayout renewalDate;
    private final CheckBox autoRenew;
    private final Bundle initialState;

    public SubscriptionForm(Context context, Subscription original, Bundle restoredState) {
        super(context);
        this.original = original;
        setOrientation(VERTICAL);
        setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), Ui.dp(context, 28));

        LocalDate start = original == null ? LocalDate.now() : original.getStartDate();
        LocalDate renewal = original == null ? start.plusMonths(1) : original.getRenewalDate();
        name = addTextField(context, "Service name", original == null ? "" : original.getName(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        plan = addTextField(context, "Plan name (optional)", original == null ? "" : safe(original.getPlanName()),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        category = addTextField(context, "Category", original == null ? "" : original.getCategory(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        price = addTextField(context, "Amount", original == null ? "" : original.getPrice().toPlainString(),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        currency = addTextField(context, "Currency", original == null ? "ZAR" : original.getCurrency(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        cycle = addDropDown(context, "Billing cycle", Arrays.asList("Monthly", "Annual"), original == null
                ? "Monthly" : (original.getBillingCycle() == BillingCycle.ANNUAL ? "Annual" : "Monthly"));
        startDate = addDateField(context, "Start date", start);
        renewalDate = addDateField(context, "Renewal date", renewal);
        status = addDropDown(context, "Status", Arrays.asList("Active", "Paused", "Cancelled"), original == null
                ? "Active" : ServicesScreen.titleCase(original.getStatus().name()));
        usage = addDropDown(context, "Usage", Arrays.asList("Not reported", "Unknown", "Low", "Medium", "High"),
                original == null || original.getUsageLevel() == null ? "Not reported"
                        : ServicesScreen.titleCase(original.getUsageLevel().name()));
        importance = addDropDown(context, "Importance", Arrays.asList("Low", "Medium", "High"), original == null
                ? "Medium" : ServicesScreen.titleCase(original.getImportance().name()));

        autoRenew = new CheckBox(context);
        autoRenew.setText("Auto-renew");
        autoRenew.setTextSize(14);
        autoRenew.setMinHeight(Ui.dp(context, 48));
        autoRenew.setChecked(original == null || original.isAutoRenew());
        addView(autoRenew, margin(context, 0, 2, 0, 10));

        initialState = state();
        if (restoredState != null) restore(restoredState);
    }

    public SubscriptionDraft createDraft() throws FormValidationException {
        clearErrors();
        String serviceName = Ui.value(name);
        String serviceCategory = Ui.value(category);
        String amountValue = Ui.value(price);
        String currencyCode = Ui.value(currency).toUpperCase(java.util.Locale.ROOT);
        if (serviceName.isEmpty()) return invalid(name, "Enter a service name.");
        if (serviceName.length() > 150) return invalid(name, "Use 150 characters or fewer.");
        if (serviceCategory.isEmpty()) return invalid(category, "Enter a category.");
        if (serviceCategory.length() > 50) return invalid(category, "Use 50 characters or fewer.");
        String planName = Ui.value(plan);
        if (planName.length() > 100) return invalid(plan, "Use 100 characters or fewer.");

        BigDecimal amount;
        try {
            amount = new BigDecimal(amountValue);
            if (amount.signum() < 0) return invalid(price, "Enter zero or a positive amount.");
            if (amount.compareTo(new BigDecimal("9999999999.99")) > 0
                    || amount.stripTrailingZeros().scale() > 2) {
                return invalid(price, "Enter an amount up to 9,999,999,999.99 with at most two decimal places.");
            }
        } catch (NumberFormatException exception) {
            return invalid(price, "Enter a valid amount, such as 89.00.");
        }
        if (!currencyCode.matches("[A-Z]{3}")) return invalid(currency, "Use a three-letter currency code.");

        LocalDate start;
        LocalDate renewal;
        try {
            start = LocalDate.parse(Ui.value(startDate));
        } catch (DateTimeParseException exception) {
            return invalid(startDate, "Choose a valid start date.");
        }
        try {
            renewal = LocalDate.parse(Ui.value(renewalDate));
        } catch (DateTimeParseException exception) {
            return invalid(renewalDate, "Choose a valid renewal date.");
        }
        if (renewal.isBefore(start)) return invalid(renewalDate, "Renewal cannot be before the start date.");

        BillingCycle billingCycle = "Annual".equalsIgnoreCase(Ui.value(cycle))
                ? BillingCycle.ANNUAL : BillingCycle.MONTHLY;
        SubscriptionStatus subscriptionStatus = SubscriptionStatus.valueOf(Ui.value(status).toUpperCase(java.util.Locale.ROOT));
        String usageValue = Ui.value(usage);
        UsageLevel usageLevel = "Not reported".equalsIgnoreCase(usageValue)
                ? null : UsageLevel.valueOf(usageValue.toUpperCase(java.util.Locale.ROOT));
        SubscriptionImportance importanceValue = SubscriptionImportance.valueOf(
                Ui.value(importance).toUpperCase(java.util.Locale.ROOT));

        try {
            return SubscriptionDraft.builder()
                    .setName(serviceName)
                    .setProviderId(original == null ? null : original.getProviderId())
                    .setPlanName(emptyToNull(planName))
                    .setPrice(amount)
                    .setCurrency(currencyCode)
                    .setBillingCycle(billingCycle)
                    .setCategory(serviceCategory)
                    .setStartDate(start)
                    .setRenewalDate(renewal)
                    .setStatus(subscriptionStatus)
                    .setUsageLevel(usageLevel)
                    .setImportance(importanceValue)
                    .setAutoRenew(autoRenew.isChecked())
                    .build();
        } catch (IllegalArgumentException exception) {
            String message = exception.getMessage() == null ? "Review this service detail." : exception.getMessage();
            if (message.contains("price")) return invalid(price,
                    "Enter an amount up to 9,999,999,999.99 with at most two decimal places.");
            if (message.contains("planName")) return invalid(plan, "Use 100 characters or fewer.");
            if (message.contains("category")) return invalid(category, "Use 50 characters or fewer.");
            if (message.contains("currency")) return invalid(currency, "Use a three-letter currency code.");
            if (message.contains("renewalDate")) return invalid(renewalDate, "Renewal cannot be before the start date.");
            return invalid(name, message.contains("name") ? "Use 150 characters or fewer." : "Review this service detail.");
        }
    }

    public boolean isDirty() {
        Bundle current = state();
        String[] stringKeys = {KEY_NAME, KEY_PLAN, KEY_CATEGORY, KEY_PRICE, KEY_CURRENCY, KEY_CYCLE,
                KEY_STATUS, KEY_USAGE, KEY_IMPORTANCE, KEY_START, KEY_RENEWAL};
        for (String key : stringKeys) {
            if (!java.util.Objects.equals(current.getString(key), initialState.getString(key))) return true;
        }
        return current.getBoolean(KEY_AUTO_RENEW) != initialState.getBoolean(KEY_AUTO_RENEW);
    }

    public Bundle state() {
        Bundle state = new Bundle();
        state.putString(KEY_NAME, Ui.value(name));
        state.putString(KEY_PLAN, Ui.value(plan));
        state.putString(KEY_CATEGORY, Ui.value(category));
        state.putString(KEY_PRICE, Ui.value(price));
        state.putString(KEY_CURRENCY, Ui.value(currency));
        state.putString(KEY_CYCLE, Ui.value(cycle));
        state.putString(KEY_STATUS, Ui.value(status));
        state.putString(KEY_USAGE, Ui.value(usage));
        state.putString(KEY_IMPORTANCE, Ui.value(importance));
        state.putString(KEY_START, Ui.value(startDate));
        state.putString(KEY_RENEWAL, Ui.value(renewalDate));
        state.putBoolean(KEY_AUTO_RENEW, autoRenew.isChecked());
        return state;
    }

    private void restore(Bundle state) {
        setText(name, state.getString(KEY_NAME));
        setText(plan, state.getString(KEY_PLAN));
        setText(category, state.getString(KEY_CATEGORY));
        setText(price, state.getString(KEY_PRICE));
        setText(currency, state.getString(KEY_CURRENCY));
        setSelection(cycle, state.getString(KEY_CYCLE));
        setSelection(status, state.getString(KEY_STATUS));
        setSelection(usage, state.getString(KEY_USAGE));
        setSelection(importance, state.getString(KEY_IMPORTANCE));
        setText(startDate, state.getString(KEY_START));
        setText(renewalDate, state.getString(KEY_RENEWAL));
        autoRenew.setChecked(state.getBoolean(KEY_AUTO_RENEW, true));
    }

    private TextInputLayout addTextField(Context context, String label, String value, int inputType) {
        TextInputLayout field = Ui.field(context, label, value, inputType);
        addView(field, margin(context, 0, 0, 0, 12));
        return field;
    }

    private TextInputLayout addDropDown(Context context, String label, java.util.List<String> choices, String selected) {
        TextInputLayout field = Ui.dropdown(context, label, choices, selected);
        addView(field, margin(context, 0, 0, 0, 12));
        return field;
    }

    private TextInputLayout addDateField(Context context, String label, LocalDate date) {
        TextInputLayout field = addTextField(context, label, date.toString(), InputType.TYPE_CLASS_DATETIME);
        TextInputEditText editText = (TextInputEditText) field.getEditText();
        if (editText != null) {
            editText.setShowSoftInputOnFocus(false);
            editText.setOnClickListener(view -> showDatePicker(context, field));
        }
        return field;
    }

    private void showDatePicker(Context context, TextInputLayout field) {
        LocalDate current;
        try {
            current = LocalDate.parse(Ui.value(field));
        } catch (DateTimeParseException ignored) {
            current = LocalDate.now();
        }
        DatePickerDialog picker = new DatePickerDialog(context, (view, year, month, day) ->
                field.getEditText().setText(LocalDate.of(year, month + 1, day).toString()),
                current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth());
        picker.show();
    }

    private void clearErrors() {
        name.setError(null);
        category.setError(null);
        price.setError(null);
        currency.setError(null);
        startDate.setError(null);
        renewalDate.setError(null);
    }

    private SubscriptionDraft invalid(TextInputLayout field, String message) throws FormValidationException {
        field.setError(message);
        field.requestFocus();
        throw new FormValidationException(message);
    }

    private static void setText(TextInputLayout field, String value) {
        if (field.getEditText() != null) field.getEditText().setText(value == null ? "" : value);
    }

    private static void setSelection(TextInputLayout field, String value) {
        if (field.getEditText() instanceof android.widget.AutoCompleteTextView && value != null) {
            ((android.widget.AutoCompleteTextView) field.getEditText()).setText(value, false);
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }
    private static String emptyToNull(String value) { return value.isEmpty() ? null : value; }

    private static LayoutParams margin(Context context, int start, int top, int end, int bottom) {
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.setMargins(Ui.dp(context, start), Ui.dp(context, top), Ui.dp(context, end), Ui.dp(context, bottom));
        return params;
    }

    public static final class FormValidationException extends Exception {
        public FormValidationException(String message) { super(message); }
    }
}
