package com.example.agentcostcontrol.ui;

import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.LinearLayout;

import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.model.Reminder;
import com.example.agentcostcontrol.model.ReminderDraft;
import com.example.agentcostcontrol.model.ReminderStatus;
import com.example.agentcostcontrol.model.Subscription;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReminderForm extends LinearLayout {
    private static final String KEY_SUBSCRIPTION = "subscription_id";
    private static final String KEY_TITLE = "title";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_DATE = "date";
    private static final String KEY_TIME = "time";
    private static final String KEY_ZONE = "zone";

    private final List<Subscription> subscriptions;
    private final Reminder original;
    private final Map<String, Long> subscriptionIdsByLabel = new LinkedHashMap<>();
    private final TextInputLayout service;
    private final TextInputLayout title;
    private final TextInputLayout message;
    private final TextInputLayout date;
    private final TextInputLayout time;
    private final TextInputLayout zone;
    private final Bundle initialState;

    public ReminderForm(Context context, List<Subscription> subscriptions, Reminder original,
                        Long selectedSubscriptionId, Bundle restoredState) {
        super(context);
        this.subscriptions = subscriptions;
        this.original = original;
        setOrientation(VERTICAL);
        setPadding(Ui.dp(context, 20), Ui.dp(context, 8), Ui.dp(context, 20), Ui.dp(context, 28));

        List<String> labels = new ArrayList<>();
        Long preferredId = original == null ? selectedSubscriptionId : original.getSubscriptionId();
        String selectedLabel = null;
        for (Subscription subscription : subscriptions) {
            if (subscription.getStatus() == com.example.agentcostcontrol.model.SubscriptionStatus.CANCELLED) continue;
            String label = subscription.getName() + " · " + subscription.getCurrency();
            if (subscriptionIdsByLabel.containsKey(label)) label += " · #" + subscription.getId();
            subscriptionIdsByLabel.put(label, subscription.getId());
            labels.add(label);
            if (preferredId != null && preferredId == subscription.getId()) selectedLabel = label;
        }
        if (labels.isEmpty()) labels.add("Add a service first");
        service = addDropdown(context, "Service", labels, selectedLabel == null ? labels.get(0) : selectedLabel);

        String serviceName = findSubscriptionName(preferredId);
        title = addField(context, "Reminder title", original == null
                ? (serviceName == null ? "Review renewal" : "Review " + serviceName + " renewal")
                : original.getTitle(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        message = addField(context, "Message (optional)", original == null || original.getMessage() == null
                ? "" : original.getMessage(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);

        LocalDate localDate;
        LocalTime localTime;
        if (original == null) {
            Subscription selected = findSubscription(preferredId);
            LocalDate renewalDate = selected == null ? LocalDate.now().plusDays(7) : selected.getRenewalDate();
            localDate = renewalDate.minusDays(7);
            if (!localDate.isAfter(LocalDate.now())) localDate = LocalDate.now().plusDays(1);
            localTime = LocalTime.of(9, 0);
        } else {
            ZonedDateTime local = original.getRemindAt().atZoneSameInstant(ZoneId.systemDefault());
            localDate = local.toLocalDate();
            localTime = local.toLocalTime().truncatedTo(ChronoUnit.MINUTES);
        }
        date = addDate(context, "Reminder date", localDate);
        time = addTime(context, "Local time", localTime);
        zone = addField(context, "Time zone", original == null ? ZoneId.systemDefault().getId()
                : ZoneId.systemDefault().getId(), InputType.TYPE_CLASS_TEXT);
        zone.setHelperText("Repeated times use the first occurrence.");

        initialState = state();
        if (restoredState != null) restore(restoredState);
    }

    public ReminderDraft createDraft() throws FormValidationException {
        clearErrors();
        String selectedLabel = Ui.value(service);
        Long subscriptionId = subscriptionIdsByLabel.get(selectedLabel);
        if (subscriptionId == null) return invalid(service, "Choose a service.");
        String reminderTitle = Ui.value(title);
        if (reminderTitle.isEmpty()) return invalid(title, "Enter a reminder title.");
        if (reminderTitle.length() > 150) return invalid(title, "Use 150 characters or fewer.");
        String reminderMessage = Ui.value(message);
        if (reminderMessage.length() > 1000) return invalid(message, "Use 1,000 characters or fewer.");
        LocalDate reminderDate;
        try {
            reminderDate = LocalDate.parse(Ui.value(date));
        } catch (DateTimeParseException exception) {
            return invalid(date, "Choose a valid date.");
        }
        LocalTime reminderTime;
        try {
            reminderTime = LocalTime.parse(Ui.value(time));
        } catch (DateTimeParseException exception) {
            return invalid(time, "Choose a valid time.");
        }
        ZoneId reminderZone;
        try {
            reminderZone = ZoneId.of(Ui.value(zone));
        } catch (DateTimeException exception) {
            return invalid(zone, "Enter a supported IANA time zone.");
        }
        LocalDateTime localDateTime = LocalDateTime.of(reminderDate, reminderTime);
        List<ZoneOffset> validOffsets = reminderZone.getRules().getValidOffsets(localDateTime);
        if (validOffsets.isEmpty()) {
            return invalid(time, "This local time does not occur in that time zone. Choose another time.");
        }
        ZonedDateTime zonedDateTime = ZonedDateTime.ofLocal(localDateTime, reminderZone, validOffsets.get(0));
        if (!zonedDateTime.toInstant().isAfter(java.time.Instant.now())) {
            return invalid(date, "Choose a reminder time in the future.");
        }
        OffsetDateTime remindAt = zonedDateTime.toOffsetDateTime();
        try {
            return ReminderDraft.builder()
                    .setSubscriptionId(subscriptionId)
                    .setTitle(reminderTitle)
                    .setMessage(emptyToNull(reminderMessage))
                    .setRemindAt(remindAt)
                    .setStatus(ReminderStatus.PENDING)
                    .build();
        } catch (IllegalArgumentException exception) {
            String field = exception.getMessage() == null ? "" : exception.getMessage();
            if (field.contains("message")) return invalid(message, "Use 1,000 characters or fewer.");
            if (field.contains("subscriptionId")) return invalid(service, "Choose a service.");
            if (field.contains("remindAt")) return invalid(time, "Choose a valid reminder time.");
            return invalid(title, "Use 150 characters or fewer.");
        }
    }

    public boolean isDirty() {
        Bundle current = state();
        for (String key : new String[]{KEY_SUBSCRIPTION, KEY_TITLE, KEY_MESSAGE, KEY_DATE, KEY_TIME, KEY_ZONE}) {
            if (!java.util.Objects.equals(current.getString(key), initialState.getString(key))) return true;
        }
        return false;
    }

    public Bundle state() {
        Bundle state = new Bundle();
        state.putString(KEY_SUBSCRIPTION, Ui.value(service));
        state.putString(KEY_TITLE, Ui.value(title));
        state.putString(KEY_MESSAGE, Ui.value(message));
        state.putString(KEY_DATE, Ui.value(date));
        state.putString(KEY_TIME, Ui.value(time));
        state.putString(KEY_ZONE, Ui.value(zone));
        return state;
    }

    private void restore(Bundle state) {
        select(service, state.getString(KEY_SUBSCRIPTION));
        setText(title, state.getString(KEY_TITLE));
        setText(message, state.getString(KEY_MESSAGE));
        setText(date, state.getString(KEY_DATE));
        setText(time, state.getString(KEY_TIME));
        setText(zone, state.getString(KEY_ZONE));
    }

    private TextInputLayout addField(Context context, String label, String value, int inputType) {
        TextInputLayout field = Ui.field(context, label, value, inputType);
        addView(field, margin(context, 0, 0, 0, 12));
        return field;
    }

    private TextInputLayout addDropdown(Context context, String label, List<String> choices, String selected) {
        TextInputLayout field = Ui.dropdown(context, label, choices, selected);
        addView(field, margin(context, 0, 0, 0, 12));
        return field;
    }

    private TextInputLayout addDate(Context context, String label, LocalDate value) {
        TextInputLayout field = addField(context, label, value.toString(), InputType.TYPE_CLASS_DATETIME);
        TextInputEditText input = (TextInputEditText) field.getEditText();
        input.setShowSoftInputOnFocus(false);
        input.setOnClickListener(view -> showDatePicker(context, field));
        return field;
    }

    private TextInputLayout addTime(Context context, String label, LocalTime value) {
        TextInputLayout field = addField(context, label, value.toString(), InputType.TYPE_CLASS_DATETIME);
        TextInputEditText input = (TextInputEditText) field.getEditText();
        input.setShowSoftInputOnFocus(false);
        input.setOnClickListener(view -> showTimePicker(context, field));
        return field;
    }

    private void showDatePicker(Context context, TextInputLayout field) {
        LocalDate selected;
        try { selected = LocalDate.parse(Ui.value(field)); }
        catch (DateTimeParseException ignored) { selected = LocalDate.now().plusDays(1); }
        new DatePickerDialog(context, (view, year, month, day) ->
                field.getEditText().setText(LocalDate.of(year, month + 1, day).toString()),
                selected.getYear(), selected.getMonthValue() - 1, selected.getDayOfMonth()).show();
    }

    private void showTimePicker(Context context, TextInputLayout field) {
        LocalTime selected;
        try { selected = LocalTime.parse(Ui.value(field)); }
        catch (DateTimeParseException ignored) { selected = LocalTime.of(9, 0); }
        new TimePickerDialog(context, (view, hour, minute) ->
                field.getEditText().setText(LocalTime.of(hour, minute).toString()),
                selected.getHour(), selected.getMinute(), false).show();
    }

    private void clearErrors() {
        service.setError(null);
        title.setError(null);
        date.setError(null);
        time.setError(null);
        zone.setError(null);
    }

    private ReminderDraft invalid(TextInputLayout field, String message) throws FormValidationException {
        field.setError(message);
        field.requestFocus();
        throw new FormValidationException(message);
    }

    private Subscription findSubscription(Long id) {
        if (id == null) return null;
        for (Subscription subscription : subscriptions) if (subscription.getId() == id) return subscription;
        return null;
    }

    private String findSubscriptionName(Long id) {
        Subscription subscription = findSubscription(id);
        return subscription == null ? null : subscription.getName();
    }

    private static void setText(TextInputLayout field, String value) {
        if (field.getEditText() != null) field.getEditText().setText(value == null ? "" : value);
    }

    private static void select(TextInputLayout field, String value) {
        if (field.getEditText() instanceof android.widget.AutoCompleteTextView && value != null) {
            ((android.widget.AutoCompleteTextView) field.getEditText()).setText(value, false);
        }
    }

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
