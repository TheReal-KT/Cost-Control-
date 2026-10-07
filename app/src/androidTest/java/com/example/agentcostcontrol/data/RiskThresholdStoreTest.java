package com.example.agentcostcontrol.data;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

@RunWith(AndroidJUnit4.class)
public final class RiskThresholdStoreTest {
    @Test
    public void explicitThresholdsPersistAndRemainScopedToAuthenticatedUuid() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String firstUserId = UUID.randomUUID().toString();
        String secondUserId = UUID.randomUUID().toString();
        RiskThresholdStore firstStore = new RiskThresholdStore(context, firstUserId);

        assertTrue(firstStore.loadMonthlyHighCostThresholds().isEmpty());
        firstStore.putMonthlyHighCostThreshold("ZAR", new BigDecimal("125.50"));
        firstStore.putMonthlyHighCostThreshold("USD", new BigDecimal("80"));

        Map<String, BigDecimal> restored = new RiskThresholdStore(context, firstUserId)
                .loadMonthlyHighCostThresholds();
        assertEquals(new BigDecimal("125.50"), restored.get("ZAR"));
        assertEquals(new BigDecimal("80"), restored.get("USD"));
        assertTrue(new RiskThresholdStore(context, secondUserId)
                .loadMonthlyHighCostThresholds().isEmpty());

        firstStore.removeMonthlyHighCostThreshold("ZAR");
        assertEquals(1, firstStore.loadMonthlyHighCostThresholds().size());
        assertTrue(firstStore.loadMonthlyHighCostThresholds().containsKey("USD"));
    }

    @Test
    public void thresholdsRequireUppercaseCurrencyAndPositiveTwoDecimalMoneyInRange() {
        RiskThresholdStore store = new RiskThresholdStore(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), UUID.randomUUID().toString());

        assertThrows(IllegalArgumentException.class,
                () -> store.putMonthlyHighCostThreshold("US", new BigDecimal("10.00")));
        assertThrows(IllegalArgumentException.class,
                () -> store.putMonthlyHighCostThreshold("zar", new BigDecimal("10.00")));
        assertThrows(IllegalArgumentException.class,
                () -> store.putMonthlyHighCostThreshold("ZAR", BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> store.putMonthlyHighCostThreshold("ZAR", new BigDecimal("10.001")));
        assertThrows(IllegalArgumentException.class,
                () -> store.putMonthlyHighCostThreshold("ZAR", new BigDecimal("10000000000.00")));
    }
}
