package com.example.agentcostcontrol.preview;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.example.agentcostcontrol.R;
import com.example.agentcostcontrol.domain.FinancialSummary;
import com.example.agentcostcontrol.domain.FinancialSummaryCalculator;
import com.example.agentcostcontrol.model.Budget;
import com.example.agentcostcontrol.model.Reminder;
import com.example.agentcostcontrol.model.Subscription;
import com.example.agentcostcontrol.ui.HomeScreen;
import com.example.agentcostcontrol.ui.MoneyText;
import com.example.agentcostcontrol.ui.ServicesScreen;
import com.example.agentcostcontrol.ui.Ui;
import com.google.android.material.navigation.NavigationView;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;

/** Read-only, debug-only launcher that reuses the real UI without creating account dependencies. */
public final class SubscriptionPreviewActivity extends AppCompatActivity {
    private final List<Subscription> subscriptions = PreviewSubscriptions.create();
    private FrameLayout container;
    private TextView title;
    private NavigationView navigation;
    private DrawerLayout drawer;
    private String query = "";
    private String filter = "All";
    private boolean showingHome;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightNavigationBars(false);
        setContentView(R.layout.activity_main);
        drawer = findViewById(R.id.app_root);
        LinearLayout content = findViewById(R.id.main_content);
        View sidebar = findViewById(R.id.navigation_sidebar);
        ViewCompat.setOnApplyWindowInsetsListener(drawer, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            content.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            sidebar.setPadding(bars.left, bars.top, 0, bars.bottom);
            return insets;
        });
        TextView banner = Ui.text(this, "DEMO · Fictional subscriptions and prices", 12,
                Ui.color(this, R.color.text_secondary), false);
        banner.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 8));
        content.addView(banner, 1, new LinearLayout.LayoutParams(-1, -2));
        container = findViewById(R.id.screen_container);
        title = findViewById(R.id.screen_title);
        navigation = findViewById(R.id.sidebar_navigation);
        navigation.getMenu().findItem(R.id.nav_reminders).setVisible(false);
        navigation.getMenu().findItem(R.id.nav_chat).setVisible(false);
        navigation.getMenu().findItem(R.id.nav_insights).setVisible(false);
        navigation.getMenu().findItem(R.id.nav_profile).setVisible(false);
        findViewById(R.id.chat_new).setVisibility(View.GONE);
        findViewById(R.id.sidebar_history).setVisibility(View.GONE);
        findViewById(R.id.navigation_open).setVisibility(View.VISIBLE);
        findViewById(R.id.navigation_open).setOnClickListener(view -> drawer.openDrawer(GravityCompat.START));
        findViewById(R.id.navigation_close).setOnClickListener(view -> drawer.closeDrawer(GravityCompat.START));
        navigation.setNavigationItemSelectedListener(item -> {
            if (item.getItemId() == R.id.nav_subscriptions) showServices();
            else showHome();
            drawer.closeDrawer(GravityCompat.START);
            return true;
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (drawer.isDrawerVisible(GravityCompat.START)) drawer.closeDrawer(GravityCompat.START);
                else if (showingHome) finish();
                else showHome();
            }
        });
        showHome();
    }

    private void showHome() {
        showingHome = true;
        navigation.setCheckedItem(R.id.nav_dashboard);
        FinancialSummary summary = FinancialSummaryCalculator.calculate(subscriptions);
        YearMonth month = YearMonth.now();
        List<Budget> budgets = Collections.singletonList(new Budget(1, 1,
                new BigDecimal("2200.00"), "ZAR", month.getMonthValue(), month.getYear()));
        show("Home preview", HomeScreen.create(this, subscriptions, budgets, Collections.emptyList(),
                summary.getMonthlySpendByCurrency(), summary.getActiveSubscriptionCount(), true,
                new HomeScreen.Actions() {
                    @Override public void onAddService() { explainReadOnly(); }
                    @Override public void onAddReminder() { explainReadOnly(); }
                    @Override public void onOpenServices() { showServices(); }
                    @Override public void onOpenReminders() { explainReadOnly(); }
                    @Override public void onOpenService(Subscription service) { showDetail(service); }
                    @Override public void onOpenReminder(Reminder reminder) { explainReadOnly(); }
                    @Override public void onOpenGmailConnection() { showServices(); }
                }));
    }

    private void showServices() {
        showingHome = false;
        navigation.setCheckedItem(R.id.nav_subscriptions);
        show("Services preview", ServicesScreen.create(this, subscriptions, query, filter, false,
                new ServicesScreen.GmailState(true, false, false, false, false,
                        "Demo preview does not connect Gmail."),
                new ServicesScreen.Actions() {
                    @Override public void onAdd() { explainReadOnly(); }
                    @Override public void onOpen(Subscription service) { showDetail(service); }
                    @Override public void onSearchChanged(String value) { query = value; }
                    @Override public void onFilterChanged(String value) { filter = value; }
                    @Override public void onLoadMore() { }
                    @Override public void onConnectGmail() { explainReadOnly(); }
                    @Override public void onSyncSubscriptions() { explainReadOnly(); }
                    @Override public void onRefreshGmail() { explainReadOnly(); }
                }));
    }

    private void showDetail(Subscription service) {
        showingHome = false;
        String monthly = MoneyText.format(FinancialSummaryCalculator.monthlyEquivalent(service), service.getCurrency());
        show("Service preview", ServicesScreen.detail(this, service, monthly, new ServicesScreen.DetailActions() {
            @Override public void onEdit() { explainReadOnly(); }
            @Override public void onAddReminder() { explainReadOnly(); }
            @Override public void onDelete() { explainReadOnly(); }
        }));
    }

    private void show(String heading, View page) {
        title.setText(heading);
        container.removeAllViews();
        container.addView(page, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void explainReadOnly() {
        Toast.makeText(this, "Preview only. Sample data cannot be changed or saved.", Toast.LENGTH_SHORT).show();
    }
}
