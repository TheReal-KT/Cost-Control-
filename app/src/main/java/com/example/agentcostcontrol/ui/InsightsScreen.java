package com.example.agentcostcontrol.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.model.Recommendation;
import com.example.agentcostcontrol.model.Subscription;

import java.util.List;

public final class InsightsScreen {
    public interface Actions {
        void onOpen(Recommendation recommendation);
        void onLoadMore();
    }

    public interface DetailActions {
        void onApprove();
        void onDismiss();
    }

    private InsightsScreen() { }

    public static View list(Context context, View localRiskChecks, List<Recommendation> recommendations,
                            List<Subscription> subscriptions, boolean hasMore, Actions actions) {
        LinearLayout page = Ui.page(context);
        page.addView(localRiskChecks, margin(context, 0, 0, 0, 18));
        page.addView(Ui.section(context, "Recommendations"), margin(context, 0, 0, 0, 8));
        if (recommendations.isEmpty()) {
            page.addView(Ui.secondary(context, "No recommendations yet."), margin(context, 0, 12, 0, 0));
        }
        for (Recommendation recommendation : recommendations) {
            Subscription subscription = findSubscription(subscriptions, recommendation.getSubscriptionId());
            LinearLayout content = Ui.column(context);
            content.setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 14));
            content.addView(Ui.text(context, recommendation.getTitle(), 16,
                    Ui.color(context, R.color.text_primary), true));
            if (subscription != null) {
                content.addView(Ui.secondary(context, subscription.getName()), margin(context, 0, 3, 0, 0));
            }
            TextView reason = Ui.text(context, recommendation.getReason(), 14,
                    Ui.color(context, R.color.text_secondary), false);
            reason.setMaxLines(2);
            reason.setEllipsize(TextUtils.TruncateAt.END);
            content.addView(reason, margin(context, 0, 8, 0, 0));
            if (recommendation.getPotentialMonthlySaving() != null
                    && recommendation.getPotentialMonthlySaving().signum() > 0 && subscription != null) {
                content.addView(Ui.secondary(context, "Estimated "
                        + MoneyText.format(recommendation.getPotentialMonthlySaving(), subscription.getCurrency())
                        + " / month"), margin(context, 0, 8, 0, 0));
            }
            LinearLayout actionsRow = Ui.row(context);
            com.google.android.material.button.MaterialButton review = Ui.button(context, "Review", false);
            review.setOnClickListener(view -> actions.onOpen(recommendation));
            actionsRow.addView(review);
            content.addView(actionsRow, margin(context, 0, 10, 0, 0));
            View card = Ui.card(context, content);
            card.setClickable(true);
            card.setFocusable(true);
            card.setOnClickListener(view -> actions.onOpen(recommendation));
            page.addView(card, margin(context, 0, 0, 0, 12));
        }
        if (hasMore) {
            com.google.android.material.button.MaterialButton loadMore = Ui.textButton(context, "Load more insights");
            loadMore.setOnClickListener(view -> actions.onLoadMore());
            page.addView(loadMore, margin(context, 0, 8, 0, 0));
        }
        return Ui.scroll(context, page);
    }

    public static View detail(Context context, Recommendation recommendation, Subscription subscription,
                              String recordedDecision, DetailActions actions) {
        LinearLayout page = Ui.page(context);
        page.addView(Ui.heading(context, recommendation.getTitle()));
        if (subscription != null) {
            page.addView(Ui.secondary(context, subscription.getName() + " · " + subscription.getCurrency()),
                    margin(context, 0, 2, 0, 18));
        }
        page.addView(Ui.section(context, "Reason"), margin(context, 0, 0, 0, 6));
        page.addView(Ui.text(context, recommendation.getReason(), 15,
                Ui.color(context, R.color.text_primary), false), margin(context, 0, 0, 0, 16));

        if (subscription != null && recommendation.getPotentialMonthlySaving() != null) {
            addRow(context, page, "Estimated monthly savings",
                    MoneyText.format(recommendation.getPotentialMonthlySaving(), subscription.getCurrency()));
        }
        if (subscription != null && recommendation.getPotentialAnnualSaving() != null) {
            addRow(context, page, "Estimated annual savings",
                    MoneyText.format(recommendation.getPotentialAnnualSaving(), subscription.getCurrency()));
        }
        addRow(context, page, "Confidence", recommendation.getConfidenceScore().movePointRight(2)
                .stripTrailingZeros().toPlainString() + "%");
        addRow(context, page, "Action", ServicesScreen.titleCase(recommendation.getAction().name()));
        if (recordedDecision != null) {
            page.addView(Ui.text(context, ServicesScreen.titleCase(recordedDecision)
                    + " · service unchanged", 14,
                    Ui.color(context, R.color.positive), true), margin(context, 0, 18, 0, 6));
        } else {
            page.addView(Ui.secondary(context, "Saves your choice; service stays unchanged."),
                    margin(context, 0, 18, 0, 14));
            com.google.android.material.button.MaterialButton approve = Ui.button(context, "Approve idea", true);
            approve.setOnClickListener(view -> actions.onApprove());
            page.addView(approve, margin(context, 0, 0, 0, 8));
            com.google.android.material.button.MaterialButton dismiss = Ui.button(context, "Dismiss", false);
            dismiss.setOnClickListener(view -> actions.onDismiss());
            page.addView(dismiss);
        }
        return Ui.scroll(context, page);
    }

    private static Subscription findSubscription(List<Subscription> subscriptions, long id) {
        for (Subscription subscription : subscriptions) if (subscription.getId() == id) return subscription;
        return null;
    }

    private static void addRow(Context context, LinearLayout page, String key, String value) {
        LinearLayout row = Ui.row(context);
        row.setMinimumHeight(Ui.dp(context, 50));
        row.addView(Ui.secondary(context, key), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(Ui.text(context, value, 14, Ui.color(context, R.color.text_primary), false));
        page.addView(row);
    }

    private static LinearLayout.LayoutParams margin(Context context, int start, int top, int end, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(Ui.dp(context, start), Ui.dp(context, top), Ui.dp(context, end), Ui.dp(context, bottom));
        return params;
    }
}
