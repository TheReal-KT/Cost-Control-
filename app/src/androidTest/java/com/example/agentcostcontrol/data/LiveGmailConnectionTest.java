package com.example.agentcostcontrol.data;

import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.agentcostcontrol.model.GmailConnection;
import com.example.agentcostcontrol.model.Session;
import com.example.agentcostcontrol.model.SubscriptionSyncResult;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Opt-in check of the real signed-in Gmail connection; sync requires a second explicit opt-in. */
@RunWith(AndroidJUnit4.class)
public final class LiveGmailConnectionTest {
    @Test
    public void createsSafeConnectLinkWhenExplicitlyRequested() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("true".equals(arguments.getString("verifyGmailConnect")));

        SupabaseRepository repository = new SupabaseRepository(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        Session session = repository.restoreSession();
        if (session == null) {
            report("NEEDS_SIGN_IN", null);
            Assume.assumeTrue("Sign in on the device before live Gmail verification", session != null);
            return;
        }

        String redirectUrl = repository.createGmailConnectLink();
        assertTrue("The authorization link must be HTTPS",
                SupabaseRepository.isSafeConnectUrl(redirectUrl));
        report("CONNECT_LINK_CREATED", null);
    }

    @Test
    public void checksRealGmailConnectionAndOptionallySyncsOwnedSubscriptions() throws Exception {
        Bundle arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("true".equals(arguments.getString("verifyGmail")));

        SupabaseRepository repository = new SupabaseRepository(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
        Session session = repository.restoreSession();
        if (session == null) {
            report("NEEDS_SIGN_IN", null);
            Assume.assumeTrue("Sign in on the device before live Gmail verification", session != null);
            return;
        }

        GmailConnection connection = repository.getGmailConnection();
        if (!connection.isConfigured()) fail("Live Gmail integration is not configured on the backend");
        if (!connection.isConnected()) {
            String redirectUrl = repository.createGmailConnectLink();
            assertTrue("The authorization link must be HTTPS",
                    SupabaseRepository.isSafeConnectUrl(redirectUrl));
            report("CONNECT_REQUIRED", null);
            Assume.assumeTrue("Authorize Gmail before live connection verification", connection.isConnected());
            return;
        }

        if (!"true".equals(arguments.getString("syncGmail"))) {
            report("CONNECTED", null);
            return;
        }

        SubscriptionSyncResult sync = repository.syncSubscriptionsFromGmail(session.getAuthUserId());
        Session currentSession = repository.getCurrentSession();
        assertNotNull("The signed-in session must remain available after sync", currentSession);
        assertEquals("Sync must remain bound to the signed-in account",
                session.getAuthUserId(), currentSession.getAuthUserId());
        int ownedSubscriptionsLoaded = repository.listSubscriptions(100, 0).size();
        assertTrue("The Gmail scan must remain bounded to 100 messages", sync.getScanned() <= 100);
        assertEquals("Every scanned message must be imported or skipped", sync.getScanned(),
                sync.getImported() + sync.getSkipped());

        Bundle details = new Bundle();
        details.putInt("imported", sync.getImported());
        details.putInt("skipped", sync.getSkipped());
        details.putInt("scanned", sync.getScanned());
        details.putBoolean("has_more", sync.hasMore());
        details.putInt("owned_subscriptions_loaded", ownedSubscriptionsLoaded);
        report("SYNC_COMPLETE", details);
    }

    private static void report(String outcome, Bundle details) {
        Bundle status = new Bundle();
        status.putString("gmail_live_result", outcome);
        if (details != null) status.putAll(details);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, status);
    }
}
