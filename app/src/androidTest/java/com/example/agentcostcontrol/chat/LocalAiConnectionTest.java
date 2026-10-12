package com.example.agentcostcontrol.chat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.agentcostcontrol.BuildConfig;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/** Opt-in check of the real emulator/TLS/backend/Auth path; no model call or account write. */
@RunWith(AndroidJUnit4.class)
public final class LocalAiConnectionTest {
    @Test public void localHttpsBackendRejectsInvalidSessionBeforeInference() {
        Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("verifyLocalAi")));
        Assume.assumeTrue(BuildConfig.AI_ENDPOINT_URL.equals("https://127.0.0.1:8443/ai/chat"));
        ChatClient.Failure error = assertThrows(ChatClient.Failure.class, () -> new ChatClient().askStream(
                BuildConfig.AI_ENDPOINT_URL, "invalid-test-session", "monthly spend", null,
                text -> { throw new AssertionError("Unauthorized requests cannot stream answers"); }));
        assertEquals(401, error.httpStatus);
    }
}
