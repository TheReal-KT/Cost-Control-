package com.example.agentcostcontrol.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.model.BillingCycle;
import com.example.agentcostcontrol.model.Reminder;
import com.example.agentcostcontrol.model.Subscription;
import com.example.agentcostcontrol.model.SubscriptionImportance;
import com.example.agentcostcontrol.model.SubscriptionStatus;
import com.example.agentcostcontrol.preview.SubscriptionPreviewActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

@RunWith(AndroidJUnit4.class)
public final class BrandUiTest {
    @Test public void onlyTheRealApplicationHasALauncherEntry() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.pm.PackageManager packages = context.getPackageManager();
        android.content.Intent launcher = new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(context.getPackageName());
        List<android.content.pm.ResolveInfo> entries = packages.queryIntentActivities(launcher, 0);
        assertEquals(1, entries.size());
        assertEquals(com.example.agentcostcontrol.MainActivity.class.getName(), entries.get(0).activityInfo.name);
        assertFalse(packages.getActivityInfo(new android.content.ComponentName(context,
                SubscriptionPreviewActivity.class), 0).exported);
    }

    @Test public void homeKeepsSpendVisibleForEmptyAccountsAndRoutesActionsToTheirDestination() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = themedContext(1f);
            List<String> opened = new ArrayList<>();
            HomeScreen.Actions actions = new HomeScreen.Actions() {
                @Override public void onAddService() { opened.add("add-service"); }
                @Override public void onAddReminder() { opened.add("add-reminder"); }
                @Override public void onOpenServices() { opened.add("services"); }
                @Override public void onOpenReminders() { opened.add("reminders"); }
                @Override public void onOpenService(Subscription service) { opened.add("service:" + service.getId()); }
                @Override public void onOpenReminder(Reminder reminder) { opened.add("reminder:" + reminder.getId()); }
            };
            List<Subscription> services = Arrays.asList(service(1, "Netflix", "199.00"),
                    service(2, "ChatGPT Plus", "399.00"), service(3, "Spotify", "69.99"));
            View home = HomeScreen.create(context, services, Collections.emptyList(), Collections.emptyList(),
                    Collections.singletonMap("ZAR", new BigDecimal("667.99")), 3, true, actions);
            TextView populatedAmount = (TextView) findText(home, "R 667.99");
            assertNotNull(populatedAmount);
            findDescription(home, "Add service").performClick();
            findDescription(home, "Reminder").performClick();
            findDescription(home, "Services").performClick();
            View netflix = findText(home, "Netflix");
            assertNotNull(netflix);
            ((View) netflix.getParent().getParent()).performClick();
            assertEquals(Arrays.asList("add-service", "add-reminder", "services", "service:1"), opened);
            capture(context, home, "home-branded", 390, 760);

            Context largeText = themedContext(1.3f);
            capture(largeText, HomeScreen.create(largeText, Collections.singletonList(
                    service(4, "Independent design tools subscription", "120.00")), Collections.emptyList(),
                    Collections.emptyList(), Collections.singletonMap("ZAR", new BigDecimal("120.00")),
                    1, false, actions), "home-small-large-text", 320, 640);
            View emptyHome = HomeScreen.create(context, Collections.emptyList(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyMap(), 0, true, actions);
            capture(context, emptyHome, "home-empty", 390, 760);
            assertNotNull(findText(emptyHome, "Monthly spend"));
            TextView zeroAmount = (TextView) findText(emptyHome, "0.00");
            assertNotNull(zeroAmount);
            assertEquals(View.VISIBLE, zeroAmount.getVisibility());
            assertTrue(zeroAmount.getHeight() > 0);
            assertEquals(populatedAmount.getTextSize(), zeroAmount.getTextSize(), 0f);
            for (AuthScreen.Mode mode : AuthScreen.Mode.values()) {
                AuthScreen auth = new AuthScreen(context, mode, "", null, new AuthScreen.Actions() {
                    @Override public void onSignIn(String email, String password) { }
                    @Override public void onSignUp(String email, String password, String first, String last) { }
                    @Override public void onProfileSetup(String first, String last) { }
                    @Override public void onOpenSignIn() { }
                    @Override public void onOpenCreateAccount() { }
                });
                capture(context, Ui.scroll(context, auth), "auth-" + mode.name().toLowerCase(java.util.Locale.ROOT),
                        390, 760);
            }
        });
    }

    @Test public void brandAliasesDoNotMisidentifyUnrelatedServices() {
        assertEquals(R.drawable.ic_brand_openai, ServiceBrand.iconFor("  ChatGPT   PLUS "));
        assertEquals(R.drawable.ic_brand_netflix, ServiceBrand.iconFor("Netflix"));
        assertEquals(R.drawable.ic_brand_adobe, ServiceBrand.iconFor("Adobe Creative Cloud"));
        assertEquals(R.drawable.ic_brand_notion, ServiceBrand.iconFor("Notion Plus"));
        assertEquals(R.drawable.ic_brand_dropbox, ServiceBrand.iconFor("Dropbox Plus"));
        assertEquals(R.drawable.ic_brand_discord, ServiceBrand.iconFor("Discord Nitro"));
        assertEquals(R.drawable.ic_brand_github, ServiceBrand.iconFor("GitHub Copilot"));
        assertEquals(R.drawable.ic_brand_canva, ServiceBrand.iconFor("Canva Pro"));
        assertEquals(0, ServiceBrand.iconFor("Netflix account consulting"));
        assertEquals(0, ServiceBrand.iconFor(null));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View badge = ServiceBrand.badge(themedContext(1f), "\uD83C\uDFB5 Music lessons", 40);
            assertEquals("\uD83C\uDFB5", ((TextView) ((ViewGroup) badge).getChildAt(0)).getText().toString());
        });
    }

    @Test public void debugPreviewShowsFictionalServicesAndSupportsBrandSearch() {
        try (ActivityScenario<SubscriptionPreviewActivity> scenario =
                     ActivityScenario.launch(SubscriptionPreviewActivity.class)) {
            scenario.onActivity(activity -> {
                View root = activity.findViewById(R.id.app_root);
                assertNotNull(findText(root, "DEMO · Fictional subscriptions and prices"));
                assertNotNull(findText(root, "Netflix"));
                assertNotNull(findText(root, "ChatGPT Plus"));
                capture(activity, root, "preview-home", 390, 840);
                ((com.google.android.material.navigation.NavigationView)
                        activity.findViewById(R.id.sidebar_navigation)).getMenu()
                        .performIdentifierAction(R.id.nav_subscriptions, 0);
                assertNotNull(findText(root, "Canva Pro"));
                assertNotNull(findText(root, "Independent design tools"));
                capture(activity, root, "preview-services", 390, 1200);
                android.widget.EditText search = findEditText(root);
                assertNotNull(search);
                search.setText("Spotify");
                assertNotNull(findText(root, "Spotify Premium"));
                assertNull(findText(root, "Netflix"));
                capture(activity, root, "preview-search", 390, 840);
            });
        }
    }

    private static android.widget.EditText findEditText(View view) {
        if (view instanceof android.widget.EditText) return (android.widget.EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.EditText found = findEditText(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Context themedContext(float fontScale) {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration configuration = new Configuration(target.getResources().getConfiguration());
        configuration.fontScale = fontScale;
        return new ContextThemeWrapper(target.createConfigurationContext(configuration), R.style.Theme_AgentCostControl);
    }

    private static Subscription service(long id, String name, String price) {
        LocalDate today = LocalDate.now();
        OffsetDateTime now = OffsetDateTime.now();
        return new Subscription(id, 1, null, name, null, "Entertainment", new BigDecimal(price), "ZAR",
                BillingCycle.MONTHLY, SubscriptionStatus.ACTIVE, null, SubscriptionImportance.MEDIUM, true,
                today, today.plusDays(id + 2), now, now);
    }

    private static View findDescription(View view, String description) {
        if (view.getContentDescription() != null && description.contentEquals(view.getContentDescription())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findDescription(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View findText(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void capture(Context context, View view, String name, int widthDp, int heightDp) {
        int width = Ui.dp(context, widthDp), height = Ui.dp(context, heightDp);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Ui.color(context, R.color.surface));
        view.draw(canvas);
        File file = new File(context.getExternalFilesDir(null), name + ".png");
        try (FileOutputStream output = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } catch (IOException error) {
            throw new AssertionError("Could not capture native preview", error);
        } finally {
            bitmap.recycle();
        }
    }
}
