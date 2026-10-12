package com.example.agentcostcontrol.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;

import com.example.agentcostcontrol.R;

import java.util.Locale;

/** Bundled, offline provider marks. Match known names exactly; never guess from a substring. */
public final class ServiceBrand {
    private ServiceBrand() { }

    @DrawableRes
    public static int iconFor(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        switch (normalized) {
            case "netflix": return R.drawable.ic_brand_netflix;
            case "chatgpt": case "chatgpt plus": case "chatgpt pro": case "openai":
                return R.drawable.ic_brand_openai;
            case "spotify": case "spotify premium": return R.drawable.ic_brand_spotify;
            case "youtube": case "youtube premium": case "youtube music":
                return R.drawable.ic_brand_youtube;
            case "apple": case "apple music": case "apple tv+": case "icloud": case "icloud+":
                return R.drawable.ic_brand_apple;
            case "amazon prime": case "prime video": return R.drawable.ic_brand_amazonprime;
            case "adobe": case "adobe creative cloud": return R.drawable.ic_brand_adobe;
            case "notion": case "notion plus": case "notion ai": return R.drawable.ic_brand_notion;
            case "dropbox": case "dropbox plus": return R.drawable.ic_brand_dropbox;
            case "discord": case "discord nitro": return R.drawable.ic_brand_discord;
            case "github": case "github copilot": return R.drawable.ic_brand_github;
            case "canva": case "canva pro": return R.drawable.ic_brand_canva;
            case "nvidia": case "nemotron": return R.drawable.ic_brand_nvidia;
            default: return 0;
        }
    }

    public static View badge(Context context, String name, int sizeDp) {
        FrameLayout badge = new FrameLayout(context);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Ui.color(context, R.color.surface_raised));
        background.setStroke(Ui.dp(context, 1), Ui.color(context, R.color.card_outline));
        badge.setBackground(background);
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        int icon = iconFor(name);
        if (icon != 0) {
            ImageView image = new ImageView(context);
            image.setImageResource(icon);
            badge.addView(image, new FrameLayout.LayoutParams(Ui.dp(context, sizeDp * .56f),
                    Ui.dp(context, sizeDp * .56f), Gravity.CENTER));
        } else {
            String trimmed = name == null ? "" : name.trim();
            String initial = trimmed.isEmpty() ? "?" : new String(Character.toChars(trimmed.codePointAt(0)))
                    .toUpperCase(Locale.ROOT);
            TextView letter = Ui.text(context, initial, sizeDp * .4f,
                    Ui.color(context, R.color.text_primary), true);
            letter.setGravity(Gravity.CENTER);
            badge.addView(letter, new FrameLayout.LayoutParams(-1, -1));
        }
        badge.setLayoutParams(new android.widget.LinearLayout.LayoutParams(
                Ui.dp(context, sizeDp), Ui.dp(context, sizeDp)));
        return badge;
    }

    public static void prepend(Context context, android.widget.LinearLayout row, String name) {
        View badge = badge(context, name, 40);
        android.widget.LinearLayout.LayoutParams params =
                (android.widget.LinearLayout.LayoutParams) badge.getLayoutParams();
        params.setMarginEnd(Ui.dp(context, 14));
        row.addView(badge, 0, params);
    }
}
