package com.example.agentcostcontrol.ui;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.data.RiskThresholdStore;
import com.example.agentcostcontrol.model.BillingCycle;
import com.example.agentcostcontrol.model.Subscription;
import com.example.agentcostcontrol.model.SubscriptionImportance;
import com.example.agentcostcontrol.model.SubscriptionStatus;
import com.example.agentcostcontrol.model.UsageLevel;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class RiskChecksViewTest {
    @Test
    public void displaysLocalEvidenceAndOpensServiceWithoutPresentingAnAiRecommendation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themedContext();
            String userId = UUID.randomUUID().toString();
            new RiskThresholdStore(context, userId)
                    .putMonthlyHighCostThreshold("ZAR", new BigDecimal("100.00"));
            long[] openedId = {-1};

            RiskChecksView view = new RiskChecksView(context, userId,
                    Collections.singletonList(subscription(23, UsageLevel.LOW)),
                    Collections.emptyList(), true, id -> openedId[0] = id);

            String visibleText = allText(view);
            assertTrue(visibleText.contains("Local risk checks"));
            assertTrue(visibleText.contains("Low usage and high cost"));
            assertTrue(visibleText.contains("Synthetic service"));
            assertTrue(visibleText.contains("150.00 ZAR"));
            assertTrue(visibleText.contains("threshold 100.00 ZAR"));
            View openService = findText(view, "View Synthetic service");
            assertTrue(openService != null);
            openService.performClick();
            assertEquals(23L, openedId[0]);
        });
    }

    @Test
    public void marksPartialDataAndDoesNotInventAHighCostThreshold() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themedContext();
            RiskChecksView view = new RiskChecksView(context, UUID.randomUUID().toString(),
                    Collections.emptyList(), Collections.emptyList(), false, null);

            String visibleText = allText(view);
            assertTrue(visibleText.contains("Partial results"));
            assertTrue(visibleText.contains("0 services checked"));
            assertTrue(visibleText.contains("Set thresholds to check high costs"));
        });
    }

    private static Context themedContext() {
        return new ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(),
                R.style.Theme_AgentCostControl);
    }

    private static Subscription subscription(long id, UsageLevel usageLevel) {
        OffsetDateTime timestamp = OffsetDateTime.parse("2026-10-07T12:00:00Z");
        return new Subscription(id, 1L, null, "Synthetic service", null, "productivity",
                new BigDecimal("150.00"), "ZAR", BillingCycle.MONTHLY, SubscriptionStatus.ACTIVE,
                usageLevel, SubscriptionImportance.MEDIUM, true, LocalDate.parse("2026-01-01"),
                LocalDate.parse("2999-10-07"), timestamp, timestamp);
    }

    private static String allText(View view) {
        StringBuilder value = new StringBuilder();
        if (view instanceof TextView) value.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                value.append(allText(group.getChildAt(index)));
            }
        }
        return value.toString();
    }

    private static View findText(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View match = findText(group.getChildAt(index), expected);
                if (match != null) return match;
            }
        }
        return null;
    }
}
