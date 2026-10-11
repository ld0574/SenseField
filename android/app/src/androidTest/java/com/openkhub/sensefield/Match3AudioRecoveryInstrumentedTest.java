package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Injects terminal engine callbacks without synthesizing speech, playing audio or changing preferences. */
@RunWith(AndroidJUnit4.class)
public final class Match3AudioRecoveryInstrumentedTest {
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private static Object field(CuePlayer player, String name) throws Exception {
        Field field = CuePlayer.class.getDeclaredField(name); field.setAccessible(true); return field.get(player);
    }
    private static void field(CuePlayer player, String name, Object value) throws Exception {
        Field field = CuePlayer.class.getDeclaredField(name); field.setAccessible(true); field.set(player, value);
    }
    @SuppressWarnings("unchecked") private static <T> Map<String,T> map(CuePlayer player, String name) throws Exception {
        return (Map<String,T>) field(player, name);
    }
    private static CueRequest request(String key) {
        long now = SystemClock.elapsedRealtime();
        return new CueRequest("audio-fault", "fault", key, "fault", CueRequest.Category.SYSTEM,
                70, now, now + 10000, CueRequest.CHANNEL_SPEECH, 0, 0, 0, "交换提示");
    }
    private static final class Callback implements CueDispatcher.PlaybackCallback {
        int failures; String reason;
        public void onStarted(long at) { fail("No synthesis or playback was requested"); }
        public void onFinished(long at, boolean success) { fail("Injected failures must stay failures"); }
        public void onFailed(long at, String reason) { failures++; this.reason = reason; }
    }
    private static TextToSpeech install(CuePlayer player, CueRequest request, Callback callback) throws Exception {
        TextToSpeech engine = new TextToSpeech(context(), ignored -> { }); // Bind only; never speak.
        field(player, "tts", engine); field(player, "ttsReady", true); field(player, "offlineTtsReady", true);
        field(player, "voiceEngines", Collections.singletonList("injected"));
        field(player, "activeVoiceEngineIndex", 0);
        map(player, "speechCallbacks").put("cue:fault", callback);
        map(player, "speechRequests").put("cue:fault", request);
        return engine;
    }
    private static void failEngine(CuePlayer player, TextToSpeech engine) throws Exception {
        Method method = CuePlayer.class.getDeclaredMethod("failDynamicSpeech", String.class, TextToSpeech.class, String.class);
        method.setAccessible(true); method.invoke(player, "cue:fault", engine, "ENGINE_ERROR:-5");
    }
    @Test public void engineErrorRetiresReadinessOnceAndExhaustsCandidatesWithoutLooping() throws Exception {
        Map<String,?> before = GameProfile.settings(context()).getAll();
        CuePlayer player = CuePlayer.tonePreview(context());
        CountDownLatch exhausted = new CountDownLatch(1);
        player.setAudioAuditListener(row -> { if (row.startsWith("AlertTts status=unavailable")) exhausted.countDown(); });
        try {
            Callback callback = new Callback(); TextToSpeech engine = install(player, request("m3live:hint:5"), callback);
            failEngine(player, engine); failEngine(player, engine);
            assertFalse((Boolean) field(player, "ttsReady"));
            assertFalse((Boolean) field(player, "offlineTtsReady")); assertNull(field(player, "tts"));
            assertEquals(1, callback.failures); assertEquals("ENGINE_ERROR:-5", callback.reason);
            assertTrue(exhausted.await(3, TimeUnit.SECONDS));
            assertTrue(map(player, "speechRequests").isEmpty());
            assertEquals(before, GameProfile.settings(context()).getAll());
        } finally { player.close(); }
    }
    @Test public void startTimeoutUsesTheSameRecoveryWithoutReportingCompletion() throws Exception {
        CuePlayer player = CuePlayer.tonePreview(context());
        try {
            Callback callback = new Callback(); TextToSpeech engine = install(player, request("m3live:hint:5"), callback);
            Method method = CuePlayer.class.getDeclaredMethod("timeoutDynamicSpeech", String.class,
                    CueDispatcher.PlaybackCallback.class, TextToSpeech.class, String.class);
            method.setAccessible(true); method.invoke(player, "cue:fault", callback, engine, "START_TIMEOUT");
            assertFalse((Boolean) field(player, "ttsReady"));
            assertEquals(1, callback.failures); assertEquals("START_TIMEOUT", callback.reason);
        } finally { player.close(); }
    }
    @Test public void cancelledUtteranceCannotRetireTheCurrentVoice() throws Exception {
        CuePlayer player = CuePlayer.tonePreview(context());
        try {
            Callback callback = new Callback(); TextToSpeech engine = install(player, request("m3live:hint:5"), callback);
            map(player, "speechCallbacks").clear(); map(player, "speechRequests").clear();
            failEngine(player, engine);
            assertTrue((Boolean) field(player, "ttsReady")); assertSame(engine, field(player, "tts"));
            assertEquals(0, callback.failures);
        } finally { player.close(); }
    }
    @Test public void unrelatedDynamicSpeechDoesNotChangeItsEngineSelection() throws Exception {
        CuePlayer player = CuePlayer.tonePreview(context());
        try {
            Callback callback = new Callback(); TextToSpeech engine = install(player, request("other:cue"), callback);
            failEngine(player, engine);
            assertTrue((Boolean) field(player, "ttsReady")); assertSame(engine, field(player, "tts"));
            assertEquals(1, callback.failures);
        } finally { player.close(); }
    }
    @Test public void readOnlyAudioSnapshot() throws Exception {
        Context context = context(); AudioManager audio = context.getSystemService(AudioManager.class);
        JSONObject report = new JSONObject();
        report.put("app_volume_percent", GameProfile.settings(context).getInt("volume", 45));
        report.put("speech_enabled", GameProfile.settings(context).getBoolean("cue_channel_speech", true));
        report.put("saved_engine", GameProfile.settings(context).getString("cue_tts_engine", ""));
        report.put("media_volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        report.put("media_max", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        report.put("media_muted", audio.isStreamMute(AudioManager.STREAM_MUSIC));
        JSONArray engines = new JSONArray();
        for (android.content.pm.ResolveInfo info : context.getPackageManager().queryIntentServices(
                new android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)) engines.put(info.serviceInfo.packageName);
        report.put("installed_engines", engines);
        Bundle status = new Bundle(); status.putString("audio_snapshot", report.toString());
        InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
    }
}
