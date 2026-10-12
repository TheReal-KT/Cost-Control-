package com.example.agentcostcontrol.preview;

import com.example.agentcostcontrol.model.BillingCycle;
import com.example.agentcostcontrol.model.Subscription;
import com.example.agentcostcontrol.model.SubscriptionImportance;
import com.example.agentcostcontrol.model.SubscriptionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Fictional display fixtures, compiled into debug builds only. Never sent to a repository. */
final class PreviewSubscriptions {
    private PreviewSubscriptions() { }

    static List<Subscription> create() {
        return Collections.unmodifiableList(Arrays.asList(
                service(1, "Netflix", "Entertainment", "199.00", SubscriptionStatus.ACTIVE),
                service(2, "ChatGPT Plus", "AI tools", "399.00", SubscriptionStatus.ACTIVE),
                service(3, "Spotify Premium", "Music", "69.99", SubscriptionStatus.ACTIVE),
                service(4, "YouTube Premium", "Entertainment", "89.99", SubscriptionStatus.ACTIVE),
                service(5, "Amazon Prime", "Entertainment", "79.00", SubscriptionStatus.ACTIVE),
                service(6, "Apple Music", "Music", "69.99", SubscriptionStatus.ACTIVE),
                service(7, "Adobe Creative Cloud", "Design", "549.00", SubscriptionStatus.PAUSED),
                service(8, "Notion Plus", "Productivity", "199.00", SubscriptionStatus.ACTIVE),
                service(9, "Dropbox Plus", "Storage", "229.00", SubscriptionStatus.ACTIVE),
                service(10, "Discord Nitro", "Community", "189.00", SubscriptionStatus.CANCELLED),
                service(11, "GitHub Copilot", "Developer tools", "199.00", SubscriptionStatus.ACTIVE),
                service(12, "Canva Pro", "Design", "129.00", SubscriptionStatus.ACTIVE),
                service(13, "Independent design tools", "Design", "120.00", SubscriptionStatus.ACTIVE)));
    }

    private static Subscription service(long id, String name, String category, String price,
                                        SubscriptionStatus status) {
        LocalDate today = LocalDate.now();
        OffsetDateTime now = OffsetDateTime.now();
        return new Subscription(id, 1, null, name, null, category, new BigDecimal(price), "ZAR",
                BillingCycle.MONTHLY, status, null, SubscriptionImportance.MEDIUM, true,
                today.minusMonths(2), today.plusDays(id + 1), now, now);
    }
}
