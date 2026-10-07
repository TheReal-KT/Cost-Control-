package com.example.agentcostcontrol.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.ColorInt;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import com.example.agentcostcontrol.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.List;

/** Small shared primitives for the native screens. Domain and navigation behavior stay in their owners. */
public final class Ui {
    private Ui() { }

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static ScrollView scroll(Context context, View child) {
        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(false);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(child, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scrollView;
    }

    public static LinearLayout page(Context context) {
        LinearLayout page = column(context);
        page.setPadding(dp(context, 20), dp(context, 12), dp(context, 20), dp(context, 32));
        return page;
    }

    public static TextView text(Context context, CharSequence value, float sizeSp, @ColorInt int color,
                                boolean bold) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        text.setTextColor(color);
        text.setFontFeatureSettings("tnum");
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.setLineSpacing(dp(context, 2), 1f);
        return text;
    }

    public static TextView heading(Context context, CharSequence value) {
        TextView heading = text(context, value, 26, color(context, R.color.text_primary), true);
        ViewCompat.setAccessibilityHeading(heading, true);
        heading.setPadding(0, dp(context, 4), 0, dp(context, 4));
        return heading;
    }

    public static TextView section(Context context, CharSequence value) {
        TextView section = text(context, value, 17, color(context, R.color.text_primary), true);
        ViewCompat.setAccessibilityHeading(section, true);
        return section;
    }

    public static TextView secondary(Context context, CharSequence value) {
        return text(context, value, 13, color(context, R.color.text_secondary), false);
    }

    public static MaterialButton button(Context context, CharSequence label, boolean primary) {
        MaterialButton button = new MaterialButton(context);
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setAllCaps(false);
        button.setMinHeight(dp(context, 48));
        button.setCornerRadius(dp(context, 24));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setStrokeWidth(primary ? 0 : dp(context, 1));
        if (primary) {
            button.setBackgroundTintList(ColorStateList.valueOf(color(context, R.color.brand_blue)));
            button.setTextColor(color(context, R.color.brand_blue_on));
        } else {
            button.setBackgroundTintList(ColorStateList.valueOf(color(context, R.color.brand_blue_soft)));
            button.setStrokeColor(ColorStateList.valueOf(color(context, R.color.brand_blue_soft)));
            button.setTextColor(color(context, R.color.brand_blue));
        }
        return button;
    }

    public static MaterialButton textButton(Context context, CharSequence label) {
        MaterialButton button = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setMinHeight(dp(context, 48));
        button.setCornerRadius(dp(context, 24));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setStrokeColor(ColorStateList.valueOf(color(context, R.color.card_outline)));
        button.setTextColor(color(context, R.color.text_primary));
        return button;
    }

    public static TextInputLayout field(Context context, String label, String value, int inputType) {
        TextInputLayout container = new TextInputLayout(context);
        container.setHint(label);
        container.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        container.setBoxCornerRadii(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        container.setBoxStrokeColor(color(context, R.color.card_outline));
        container.setHintTextColor(ColorStateList.valueOf(color(context, R.color.text_secondary)));

        TextInputEditText editText = new TextInputEditText(context);
        editText.setInputType(inputType);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        editText.setSingleLine(inputType != (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        if (value != null) editText.setText(value);
        container.addView(editText, new TextInputLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.setMinimumHeight(dp(context, 58));
        return container;
    }

    public static TextInputLayout dropdown(Context context, String label, List<String> values,
                                           String selectedValue) {
        TextInputLayout container = new TextInputLayout(context);
        container.setHint(label);
        container.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        container.setBoxCornerRadii(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12));
        container.setBoxStrokeColor(color(context, R.color.card_outline));
        container.setEndIconMode(TextInputLayout.END_ICON_DROPDOWN_MENU);

        AutoCompleteTextView input = new AutoCompleteTextView(context);
        input.setInputType(InputType.TYPE_NULL);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, values));
        if (selectedValue != null) input.setText(selectedValue, false);
        container.addView(input, new TextInputLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.setMinimumHeight(dp(context, 58));
        return container;
    }

    public static MaterialCardView card(Context context, View content) {
        MaterialCardView card = new MaterialCardView(context);
        card.setCardBackgroundColor(color(context, R.color.surface));
        card.setStrokeColor(color(context, R.color.card_outline));
        card.setStrokeWidth(dp(context, 1));
        card.setCardElevation(0);
        card.setRadius(dp(context, 18));
        card.addView(content, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    public static View divider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(color(context, R.color.card_outline));
        return divider;
    }

    public static int color(Context context, int resource) {
        return ContextCompat.getColor(context, resource);
    }

    public static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static String value(AutoCompleteTextView input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    public static String value(TextInputLayout field) {
        if (field.getEditText() == null || field.getEditText().getText() == null) return "";
        return field.getEditText().getText().toString().trim();
    }
}
