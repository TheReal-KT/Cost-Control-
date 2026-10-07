package com.example.agentcostcontrol.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.widget.LinearLayout;

import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.model.Budget;
import com.example.agentcostcontrol.model.BudgetDraft;
import com.google.android.material.textfield.TextInputLayout;

import java.math.BigDecimal;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BudgetForm extends LinearLayout {
    private static final String KEY_AMOUNT = "amount";
    private static final String KEY_CURRENCY = "currency";
    private static final String KEY_MONTH = "month";
    private static final String KEY_YEAR = "year";

    private final Budget original;
    private final TextInputLayout amount;
    private final TextInputLayout currency;
    private final TextInputLayout month;
    private final TextInputLayout year;
    private final Bundle initialState;

    public BudgetForm(Context context, Budget original, Bundle restoredState) {
        super(context);
        this.original = original;
        setOrientation(VERTICAL);
        setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), Ui.dp(context, 28));

        YearMonth now = YearMonth.now();
        addView(Ui.secondary(context, "Budgets compare monthly costs in the same currency."),
                margin(context, 0, 0, 0, 16));
        amount = addField(context, "Monthly limit", original == null ? "" : original.getLimitAmount().toPlainString(),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        currency = addField(context, "Currency", original == null ? "ZAR" : original.getCurrency(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);

        List<String> monthNames = new ArrayList<>();
        for (Month item : Month.values()) monthNames.add(monthLabel(item.getValue()));
        month = addDropDown(context, "Month", monthNames, monthLabel(original == null
                ? now.getMonthValue() : original.getMonth()));
        year = addField(context, "Year", Integer.toString(original == null ? now.getYear() : original.getYear()),
                InputType.TYPE_CLASS_NUMBER);
        initialState = state();
        if (restoredState != null) restore(restoredState);
    }

    public BudgetDraft createDraft() throws FormValidationException {
        clearErrors();
        BigDecimal limit;
        try {
            limit = new BigDecimal(Ui.value(amount));
            if (limit.signum() < 0) return invalid(amount, "Enter zero or a positive limit.");
            if (limit.compareTo(new BigDecimal("9999999999.99")) > 0
                    || limit.stripTrailingZeros().scale() > 2) {
                return invalid(amount, "Enter a limit up to 9,999,999,999.99 with at most two decimal places.");
            }
        } catch (NumberFormatException exception) {
            return invalid(amount, "Enter a valid monthly limit.");
        }
        String currencyCode = Ui.value(currency).toUpperCase(Locale.ROOT);
        if (!currencyCode.matches("[A-Z]{3}")) return invalid(currency, "Use a three-letter currency code.");
        int monthNumber = 0;
        for (int candidate = 1; candidate <= 12; candidate++) {
            if (monthLabel(candidate).equals(Ui.value(month))) monthNumber = candidate;
        }
        if (monthNumber == 0) return invalid(month, "Choose a calendar month.");
        int yearNumber;
        try {
            yearNumber = Integer.parseInt(Ui.value(year));
            if (yearNumber < 2000 || yearNumber > 9999) return invalid(year, "Enter a year from 2000 to 9999.");
        } catch (NumberFormatException exception) {
            return invalid(year, "Enter a valid year.");
        }
        try {
            return BudgetDraft.builder().setLimitAmount(limit).setCurrency(currencyCode)
                    .setMonth(monthNumber).setYear(yearNumber).build();
        } catch (IllegalArgumentException exception) {
            String field = exception.getMessage() == null ? "" : exception.getMessage();
            if (field.contains("limitAmount")) {
                return invalid(amount, "Enter a limit up to 9,999,999,999.99 with at most two decimal places.");
            }
            if (field.contains("currency")) return invalid(currency, "Use a three-letter currency code.");
            return invalid(year, "Choose a valid budget period.");
        }
    }

    public boolean isDirty() {
        Bundle current = state();
        for (String key : new String[]{KEY_AMOUNT, KEY_CURRENCY, KEY_MONTH, KEY_YEAR}) {
            if (!java.util.Objects.equals(current.getString(key), initialState.getString(key))) return true;
        }
        return false;
    }

    public Bundle state() {
        Bundle state = new Bundle();
        state.putString(KEY_AMOUNT, Ui.value(amount));
        state.putString(KEY_CURRENCY, Ui.value(currency));
        state.putString(KEY_MONTH, Ui.value(month));
        state.putString(KEY_YEAR, Ui.value(year));
        return state;
    }

    private void restore(Bundle state) {
        setText(amount, state.getString(KEY_AMOUNT));
        setText(currency, state.getString(KEY_CURRENCY));
        setSelection(month, state.getString(KEY_MONTH));
        setText(year, state.getString(KEY_YEAR));
    }

    private TextInputLayout addField(Context context, String label, String value, int inputType) {
        TextInputLayout field = Ui.field(context, label, value, inputType);
        addView(field, margin(context, 0, 0, 0, 12));
        return field;
    }

    private TextInputLayout addDropDown(Context context, String label, List<String> values, String selected) {
        TextInputLayout field = Ui.dropdown(context, label, values, selected);
        addView(field, margin(context, 0, 0, 0, 12));
        return field;
    }

    private void clearErrors() {
        amount.setError(null);
        currency.setError(null);
        month.setError(null);
        year.setError(null);
    }

    private BudgetDraft invalid(TextInputLayout field, String message) throws FormValidationException {
        field.setError(message);
        field.requestFocus();
        throw new FormValidationException(message);
    }

    private static String monthLabel(int number) {
        return Month.of(number).getDisplayName(TextStyle.FULL, Locale.getDefault());
    }

    private static void setText(TextInputLayout field, String value) {
        if (field.getEditText() != null) field.getEditText().setText(value == null ? "" : value);
    }

    private static void setSelection(TextInputLayout field, String value) {
        if (field.getEditText() instanceof android.widget.AutoCompleteTextView && value != null) {
            ((android.widget.AutoCompleteTextView) field.getEditText()).setText(value, false);
        }
    }

    private static LayoutParams margin(Context context, int start, int top, int end, int bottom) {
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.setMargins(Ui.dp(context, start), Ui.dp(context, top), Ui.dp(context, end), Ui.dp(context, bottom));
        return params;
    }

    public static final class FormValidationException extends Exception {
        public FormValidationException(String message) { super(message); }
    }
}
