package com.example.agentcostcontrol.chat;

import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.agentcostcontrol.BuildConfig;
import com.example.agentcostcontrol.data.SupabaseRepository;
import com.example.agentcostcontrol.model.Session;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Opt-in end-to-end check of the signed-in app's real model request path. */
@RunWith(AndroidJUnit4.class)
public final class LiveAiModelTest {
    @Test
    public void streamsAnAnswerFromTheConfiguredModelWhenExplicitlyRequested() throws Exception {
        Assume.assumeTrue("true".equals(
                InstrumentationRegistry.getArguments().getString("verifyAi")));

        Session session = new SupabaseRepository(
                InstrumentationRegistry.getInstrumentation().getTargetContext()).restoreSession();
        Assume.assumeTrue("Sign in on the device before live AI verification", session != null);

        StringBuilder streamedAnswer = new StringBuilder();
        ChatClient.Reply reply = new ChatClient().askStream(BuildConfig.AI_ENDPOINT_URL,
                session.getAccessToken(), "Reply with exactly one word: READY.", null,
                streamedAnswer::append);
        assertFalse("The model must return a non-empty answer", reply.answer.isEmpty());
        assertFalse("The app must receive streamed answer text", streamedAnswer.toString().isEmpty());
        assertEquals("The completed answer must match the streamed text",
                streamedAnswer.toString().trim(), reply.answer);
        assertTrue("The answer must respond to the actual question",
                reply.answer.contains("READY"));

        Bundle result = new Bundle();
        result.putString("model", ChatClient.MODEL_ID);
        result.putInt("answer_length", reply.answer.length());
        result.putInt("streamed_length", streamedAnswer.length());
        InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
    }
}
