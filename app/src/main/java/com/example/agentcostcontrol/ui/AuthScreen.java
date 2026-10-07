package com.example.agentcostcontrol.ui;

import android.content.Context;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.agentcostcontrol.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Objects;

public final class AuthScreen extends LinearLayout {
    public enum Mode { SIGN_IN, CREATE_ACCOUNT, PROFILE_SETUP }

    public interface Actions {
        void onSignIn(String email, String password);
        void onSignUp(String email, String password, String firstName, String lastName);
        void onProfileSetup(String firstName, String lastName);
        void onOpenSignIn();
        void onOpenCreateAccount();
    }

    private final Mode mode;
    private final Actions actions;
    private final TextInputLayout email;
    private final TextInputLayout password;
    private final TextInputLayout firstName;
    private final TextInputLayout lastName;
    private final TextView messageView;
    private final Bundle initialState;

    public AuthScreen(Context context, Mode mode, String message, Bundle restoredState, Actions actions) {
        super(context);
        this.mode = mode;
        this.actions = actions;
        setOrientation(VERTICAL);
        setPadding(Ui.dp(context, 24), Ui.dp(context, 24), Ui.dp(context, 24), Ui.dp(context, 32));

        addView(Ui.heading(context, heading(mode)), margin(context, 0, 0, 0, 8));
        String subtitle = mode == Mode.SIGN_IN ? "Sign in to see your services and reminders."
                : mode == Mode.CREATE_ACCOUNT ? "Create an account to track your recurring costs."
                : "Finish setting up your account.";
        addView(Ui.secondary(context, subtitle), margin(context, 0, 0, 0, 12));
        messageView = Ui.text(context, "", 14, Ui.color(context, R.color.brand_blue), false);
        messageView.setVisibility(View.GONE);
        addView(messageView, margin(context, 0, 0, 0, 16));
        setMessage(message);

        if (mode != Mode.PROFILE_SETUP) {
            email = addField(context, "Email", "", android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            email.getEditText().setId(R.id.auth_email);
            password = addField(context, "Password", "", android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            password.getEditText().setId(R.id.auth_password);
            password.getEditText().setSaveEnabled(false);
            password.getEditText().setSaveFromParentEnabled(false);
            password.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        } else {
            email = null;
            password = null;
        }
        if (mode != Mode.SIGN_IN) {
            firstName = addField(context, "First name", "", android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            firstName.getEditText().setId(R.id.auth_first_name);
            lastName = addField(context, "Last name", "", android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            lastName.getEditText().setId(R.id.auth_last_name);
        } else {
            firstName = null;
            lastName = null;
        }

        initialState = state();
        if (restoredState != null) restore(restoredState);

        MaterialButton primary = Ui.button(context, mode == Mode.SIGN_IN ? "Sign in"
                : mode == Mode.CREATE_ACCOUNT ? "Create account" : "Finish setup", true);
        primary.setId(R.id.auth_primary_action);
        primary.setOnClickListener(view -> submit());
        addView(primary, margin(context, 0, 8, 0, 8));
        if (mode == Mode.SIGN_IN) {
            MaterialButton create = Ui.textButton(context, "Create an account");
            create.setOnClickListener(view -> actions.onOpenCreateAccount());
            addView(create, margin(context, 0, 0, 0, 0));
        } else if (mode == Mode.CREATE_ACCOUNT) {
            MaterialButton signIn = Ui.textButton(context, "Already have an account? Sign in");
            signIn.setOnClickListener(view -> actions.onOpenSignIn());
            addView(signIn, margin(context, 0, 0, 0, 0));
        }
    }

    public Bundle state() {
        Bundle state = new Bundle();
        if (email != null) state.putString("email", Ui.value(email));
        if (firstName != null) state.putString("first_name", Ui.value(firstName));
        if (lastName != null) state.putString("last_name", Ui.value(lastName));
        return state;
    }

    public Mode getMode() { return mode; }

    public void setMessage(String message) {
        boolean visible = message != null && !message.isEmpty();
        messageView.setText(visible ? message : "");
        messageView.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public boolean isDirty() {
        Bundle current = state();
        for (String key : new String[]{"email", "first_name", "last_name"}) {
            if (!Objects.equals(current.getString(key), initialState.getString(key))) return true;
        }
        return false;
    }

    private void submit() {
        if (mode == Mode.SIGN_IN) {
            clearErrors();
            String emailValue = Ui.value(email);
            String passwordValue = rawValue(password);
            if (!Patterns.EMAIL_ADDRESS.matcher(emailValue).matches()) {
                invalid(email, "Enter a valid email address.");
                return;
            }
            if (passwordValue.isEmpty()) {
                invalid(password, "Enter your password.");
                return;
            }
            actions.onSignIn(emailValue, passwordValue);
            return;
        }

        clearErrors();
        String first = Ui.value(firstName);
        String last = Ui.value(lastName);
        if (first.isEmpty()) {
            invalid(firstName, "Enter your first name.");
            return;
        }
        if (last.isEmpty()) {
            invalid(lastName, "Enter your last name.");
            return;
        }
        if (first.length() > 50) {
            invalid(firstName, "Use 50 characters or fewer.");
            return;
        }
        if (last.length() > 50) {
            invalid(lastName, "Use 50 characters or fewer.");
            return;
        }
        if (mode == Mode.PROFILE_SETUP) {
            actions.onProfileSetup(first, last);
            return;
        }
        String emailValue = Ui.value(email);
        String passwordValue = rawValue(password);
        if (!Patterns.EMAIL_ADDRESS.matcher(emailValue).matches()) {
            invalid(email, "Enter a valid email address.");
            return;
        }
        if (passwordValue.isEmpty()) {
            invalid(password, "Enter a password.");
            return;
        }
        actions.onSignUp(emailValue, passwordValue, first, last);
    }

    private TextInputLayout addField(Context context, String label, String value, int inputType) {
        TextInputLayout field = Ui.field(context, label, value, inputType);
        addView(field, margin(context, 0, 0, 0, 14));
        return field;
    }

    private void clearErrors() {
        if (email != null) email.setError(null);
        if (password != null) password.setError(null);
        if (firstName != null) firstName.setError(null);
        if (lastName != null) lastName.setError(null);
    }

    private void invalid(TextInputLayout field, String message) {
        field.setError(message);
        field.requestFocus();
    }

    private static String rawValue(TextInputLayout field) {
        if (field == null || field.getEditText() == null || field.getEditText().getText() == null) return "";
        return field.getEditText().getText().toString();
    }

    private void restore(Bundle state) {
        setText(email, state.getString("email"));
        setText(firstName, state.getString("first_name"));
        setText(lastName, state.getString("last_name"));
    }

    private static void setText(TextInputLayout field, String value) {
        if (field != null && field.getEditText() != null) field.getEditText().setText(value == null ? "" : value);
    }

    private static String heading(Mode mode) {
        switch (mode) {
            case CREATE_ACCOUNT: return "Create account";
            case PROFILE_SETUP: return "Profile setup";
            default: return "Welcome back";
        }
    }

    private static LayoutParams margin(Context context, int start, int top, int end, int bottom) {
        LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.setMargins(Ui.dp(context, start), Ui.dp(context, top), Ui.dp(context, end), Ui.dp(context, bottom));
        return params;
    }
}
