package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Local control contracts, no audio capture or network requests are started. */
@RunWith(AndroidJUnit4.class)
public class AssistantControlsInstrumentedTest {
    static final class Host implements AssistantController.Host {
        AssistantReply last; int spoken; boolean ready = true; String status = "";
        public void speak(AssistantReply reply) { last = reply; spoken++; }
        public boolean speechReady() { return ready; }
        public void cancelSpeech() { }
        public boolean speaking() { return false; }
        public long lastAlertAtMs() { return -1; }
        public String nearby() { return "当前观察不够新鲜，无法判断附近情况。"; }
        public void mark() { }
        public void status(String value) { status = value; }
        public void audit(String metadata) { }
    }
    private static SharedPreferences preferences(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("assistant-control-test", Context.MODE_PRIVATE);
        preferences.edit().clear().putBoolean(AssistantSettings.VOICE, true)
            .putBoolean(AssistantSettings.AUDIO_CONSENT, true).putBoolean(AssistantSettings.VISION, true)
            .putBoolean(AssistantSettings.IMAGE_CONSENT, true).putString(AssistantSettings.ENDPOINT, "https://example.invalid")
            .putString(AssistantSettings.TOKEN, "test-token-placeholder-not-for-production").commit();
        return preferences;
    }
    @Test public void revocationInvalidatesOldReplyAndCannotBeReenabledInSameSession() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            SharedPreferences preferences = preferences(context); Host host = new Host();
            AssistantController controller = new AssistantController(context, "synthetic-test", new AssistantSettings(preferences), host);
            try {
                controller.question("附近情况", false); AssistantReply old = host.last;
                assertNotNull(old); assertTrue(controller.allows(old));
                preferences.edit().putBoolean(AssistantSettings.VISION, false).commit();
                controller.settingsChanged(new AssistantSettings(preferences));
                assertFalse(controller.allows(old));
                controller.question("附近情况", false); assertTrue(controller.allows(host.last));
                preferences.edit().putBoolean(AssistantSettings.VOICE, false).commit();
                controller.settingsChanged(new AssistantSettings(preferences));
                int before = host.spoken;
                controller.question("附近情况", false); assertEquals(before, host.spoken);
                preferences.edit().putBoolean(AssistantSettings.VOICE, true).commit();
                controller.settingsChanged(new AssistantSettings(preferences));
                controller.question("附近情况", false); assertEquals(before, host.spoken);
            } finally { controller.close(); preferences.edit().clear().commit(); }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    @Test public void absentOfflineChineseTtsShowsAnExplanationInsteadOfSilentSuccess() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            SharedPreferences preferences = preferences(context); Host host = new Host(); host.ready = false;
            AssistantController controller = new AssistantController(context, "synthetic-test", new AssistantSettings(preferences), host);
            try {
                controller.question("附近情况", false);
                assertEquals(0, host.spoken); assertTrue(host.status.contains("离线中文语音"));
            } finally { controller.close(); preferences.edit().clear().commit(); }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
