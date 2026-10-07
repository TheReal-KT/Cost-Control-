package com.example.agentcostcontrol.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.AutoCompleteTextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.model.BillingCycle;
import com.example.agentcostcontrol.model.ReminderDraft;
import com.example.agentcostcontrol.model.Subscription;
import com.example.agentcostcontrol.model.SubscriptionDraft;
import com.example.agentcostcontrol.model.SubscriptionImportance;
import com.example.agentcostcontrol.model.SubscriptionStatus;

import com.google.android.material.textfield.TextInputLayout;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Validates user-visible form contracts on Android without exercising layouts or pixels. */
@RunWith(AndroidJUnit4.class)
public final class FormContractTest {
    @Test
    public void subscriptionFormRestoresAndBuildsExactAnnualDraft() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themedContext();
            SubscriptionForm edited = new SubscriptionForm(context, null, null);
            setField(edited, "Service name", "Synthetic plan");
            setField(edited, "Plan name (optional)", "Annual Pro");
            setField(edited, "Category", "productivity");
            setField(edited, "Amount", "123456789.01");
            setField(edited, "Currency", "usd");
            setField(edited, "Billing cycle", "Annual");
            setField(edited, "Start date", "2026-10-01");
            setField(edited, "Renewal date", "2027-10-01");

            SubscriptionForm restored = new SubscriptionForm(context, null, edited.state());
            try {
                SubscriptionDraft draft = restored.createDraft();
                assertEquals(new BigDecimal("123456789.01"), draft.getPrice());
                assertEquals("USD", draft.getCurrency());
                assertEquals(BillingCycle.ANNUAL, draft.getBillingCycle());
                assertEquals("Synthetic plan", draft.getName());
            } catch (SubscriptionForm.FormValidationException error) {
                fail("Valid restored subscription fields should create a draft: " + error.getMessage());
            }
        });
    }

    @Test
    public void invalidMoneyAndLongNameFailWithoutLosingRestoredDraft() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themedContext();
            SubscriptionForm edited = new SubscriptionForm(context, null, null);
            setValidSubscriptionFields(edited);
            setField(edited, "Amount", "15.999");
            SubscriptionForm invalidMoney = new SubscriptionForm(context, null, edited.state());

            try {
                invalidMoney.createDraft();
                fail("Amounts with more than two decimal places must be rejected");
            } catch (SubscriptionForm.FormValidationException expected) {
                assertNotNull(expected.getMessage());
            }
            assertEquals("15.999", fieldValue(invalidMoney, "Amount"));

            SubscriptionForm afterMoneyFailure = new SubscriptionForm(context, null, invalidMoney.state());
            assertEquals("15.999", fieldValue(afterMoneyFailure, "Amount"));
            setField(afterMoneyFailure, "Amount", "89.00");
            setField(afterMoneyFailure, "Service name", repeated('x', 151));
            SubscriptionForm invalidLength = new SubscriptionForm(context, null, afterMoneyFailure.state());

            try {
                invalidLength.createDraft();
                fail("Service names over the supported length must be rejected");
            } catch (SubscriptionForm.FormValidationException expected) {
                assertNotNull(expected.getMessage());
            }
            assertEquals(repeated('x', 151), fieldValue(invalidLength, "Service name"));

            SubscriptionForm afterLengthFailure = new SubscriptionForm(context, null, invalidLength.state());
            assertEquals(repeated('x', 151), fieldValue(afterLengthFailure, "Service name"));
            assertEquals("89.00", fieldValue(afterLengthFailure, "Amount"));
        });
    }

    @Test
    public void reminderFormRejectsNonexistentLocalTimeAndRestoresTheInput() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themedContext();
            Subscription subscription = sampleSubscription();
            ReminderForm edited = new ReminderForm(context, Collections.singletonList(subscription), null,
                    subscription.getId(), null);
            setField(edited, "Reminder date", "2026-03-08");
            setField(edited, "Local time", "02:30");
            setField(edited, "Time zone", "America/New_York");

            ReminderForm restored = new ReminderForm(context, Collections.singletonList(subscription), null,
                    subscription.getId(), edited.state());
            try {
                ReminderDraft ignored = restored.createDraft();
                fail("A clock time skipped by the DST transition must be rejected");
            } catch (ReminderForm.FormValidationException expected) {
                assertTrue(expected.getMessage().contains("does not occur"));
            }

            ReminderForm afterFailure = new ReminderForm(context, Collections.singletonList(subscription), null,
                    subscription.getId(), restored.state());
            assertEquals("2026-03-08", fieldValue(afterFailure, "Reminder date"));
            assertEquals("02:30", fieldValue(afterFailure, "Local time"));
            assertEquals("America/New_York", fieldValue(afterFailure, "Time zone"));
        });
    }

    private static Context themedContext() {
        return new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                R.style.Theme_AgentCostControl);
    }

    private static void setValidSubscriptionFields(SubscriptionForm form) {
        setField(form, "Service name", "Synthetic plan");
        setField(form, "Plan name (optional)", "Standard");
        setField(form, "Category", "productivity");
        setField(form, "Amount", "89.00");
        setField(form, "Currency", "ZAR");
        setField(form, "Billing cycle", "Monthly");
        setField(form, "Start date", "2026-10-01");
        setField(form, "Renewal date", "2026-11-01");
    }

    private static Subscription sampleSubscription() {
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-10-01T00:00:00Z");
        return new Subscription(901, 42, null, "Synthetic service", null, "productivity",
                new BigDecimal("89.00"), "USD", BillingCycle.MONTHLY, SubscriptionStatus.ACTIVE,
                null, SubscriptionImportance.MEDIUM, true, LocalDate.parse("2026-03-01"),
                LocalDate.parse("2026-03-15"), createdAt, createdAt);
    }

    private static void setField(android.view.ViewGroup form, String label, String value) {
        TextInputLayout field = findField(form, label);
        if (field.getEditText() instanceof AutoCompleteTextView) {
            ((AutoCompleteTextView) field.getEditText()).setText(value, false);
        } else {
            field.getEditText().setText(value);
        }
    }

    private static String fieldValue(android.view.ViewGroup form, String label) {
        TextInputLayout field = findField(form, label);
        return field.getEditText().getText().toString();
    }

    private static TextInputLayout findField(android.view.ViewGroup form, String label) {
        for (int index = 0; index < form.getChildCount(); index++) {
            View child = form.getChildAt(index);
            if (child instanceof TextInputLayout && label.contentEquals(((TextInputLayout) child).getHint())) {
                return (TextInputLayout) child;
            }
        }
        throw new AssertionError("Could not find form field: " + label);
    }

    private static String repeated(char value, int length) {
        char[] result = new char[length];
        java.util.Arrays.fill(result, value);
        return new String(result);
    }
}
