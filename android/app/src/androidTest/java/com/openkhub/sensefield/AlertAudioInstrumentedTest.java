package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Synthetic local PCM and UI only. No patient recordings, remote model or microphone. */
@RunWith(AndroidJUnit4.class)
public final class AlertAudioInstrumentedTest {
    private static Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    private static CueRequest near(String id, long lifetimeMs) {
        long now = SystemClock.elapsedRealtime();
        return new CueRequest("audio-test", id, id, "NEAR_ZONE", CueRequest.Category.NEAR_ZONE,
                NearZoneRouting.NEAR_PRIORITY, now, now + lifetimeMs,
                CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH, 7, 0, 0, "左上", -1f);
    }

    private static short[] syntheticVoice(int durationMs) {
        short[] pcm = new short[16 * durationMs];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) (2500 * Math.sin(i * 0.1));
        return pcm;
    }

    private static CueDispatcher.Policy policy() {
        return new CueDispatcher.Policy() {
            public boolean categoryEnabled(CueRequest.Category category) { return true; }
            public int enabledChannels() { return CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH; }
            public long dedupeWindowMs(CueRequest.Category category) { return 0; }
        };
    }

    @Test public void pairedPcmUsesOneStartAndCompletesAfterThePlaybackHeadDrains() throws Exception {
        CuePlayer player = CuePlayer.tonePreview(context());
        try {
            player.setPreparedAlertForTest("左上", syntheticVoice(500));
            long[] starts = new long[2], ends = new long[2];
            CountDownLatch completed = new CountDownLatch(2);
            List<String> audit = Collections.synchronizedList(new ArrayList<>());
            player.setAudioAuditListener(audit::add);
            CueDispatcher.PlaybackCallback tone = callback(starts, ends, 0, completed);
            CueDispatcher.PlaybackCallback speech = callback(starts, ends, 1, completed);
            assertTrue(player.speakSynchronized(near("paired-pcm", 2000), tone, speech));
            assertTrue("Real AudioTrack callbacks finish", completed.await(3, TimeUnit.SECONDS));
            assertTrue(starts[0] > 0);
            assertEquals(starts[0], starts[1]);
            assertTrue("Speech completion follows the PCM head, not a short earcon timer",
                    ends[1] - starts[1] >= 400);
            assertTrue(ends[0] <= ends[1]);
            assertTrue(audit.stream().anyMatch(row -> row.contains("paired=true") && row.contains("cache=hit")));
            assertTrue(audit.stream().anyMatch(row -> row.contains("outputType=")));
        } finally { player.close(); }
    }

    private static CueDispatcher.PlaybackCallback callback(long[] starts, long[] ends,
            int index, CountDownLatch completed) {
        return new CueDispatcher.PlaybackCallback() {
            public void onStarted(long atMs) { starts[index] = atMs; }
            public void onFinished(long atMs, boolean success) {
                ends[index] = success ? atMs : -1;
                completed.countDown();
            }
        };
    }

    @Test public void queuedPairCancelledBeforeWorkerRunsNeverStartsOrResumes() throws Exception {
        for (boolean pause : new boolean[]{true, false}) {
            CuePlayer player = CuePlayer.tonePreview(context());
            CueDispatcher dispatcher = null;
            CountDownLatch release = new CountDownLatch(1), blocked = new CountDownLatch(1);
            List<String> events = Collections.synchronizedList(new ArrayList<>());
            try {
                player.setPreparedAlertForTest("左上", syntheticVoice(300));
                worker(player).execute(() -> {
                    blocked.countDown();
                    try { release.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
                assertTrue(blocked.await(1, TimeUnit.SECONDS));
                dispatcher = dispatcher(player, events, null);
                assertTrue(dispatcher.submit(near("cancelled-pcm", 2000)).audioQueued());
                if (pause) { dispatcher.pause(); dispatcher.resume(); }
                else dispatcher.clearCategory(CueRequest.Category.NEAR_ZONE);
                release.countDown();
                SystemClock.sleep(200);
                assertFalse(events.stream().anyMatch(row -> row.endsWith(":STARTED")));
                assertFalse(dispatcher.isSpeaking());
            } finally {
                release.countDown();
                if (dispatcher != null) dispatcher.close();
                player.close();
            }
        }
    }

    @Test public void expiredPairIsDiscardedBeforeAudioTrackStarts() throws Exception {
        CuePlayer player = CuePlayer.tonePreview(context());
        CountDownLatch release = new CountDownLatch(1), blocked = new CountDownLatch(1);
        CountDownLatch terminal = new CountDownLatch(1);
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CueDispatcher dispatcher = dispatcher(player, events, terminal);
        try {
            player.setPreparedAlertForTest("左上", syntheticVoice(300));
            worker(player).execute(() -> {
                blocked.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(blocked.await(1, TimeUnit.SECONDS));
            assertTrue(dispatcher.submit(near("expired-pcm", 100)).audioQueued());
            SystemClock.sleep(180);
            release.countDown();
            assertTrue(terminal.await(2, TimeUnit.SECONDS));
            assertFalse(events.stream().anyMatch(row -> row.endsWith(":STARTED")));
            assertTrue(events.contains("SPEECH:EXPIRED"));
            assertFalse(dispatcher.isSpeaking());
        } finally { release.countDown(); dispatcher.close(); player.close(); }
    }

    @Test public void noReadyVoiceCannotQueueSpeechButToneStillWorks() throws Exception {
        CuePlayer player = CuePlayer.tonePreview(context());
        try {
            CountDownLatch started = new CountDownLatch(1);
            CueDispatcher.PlaybackCallback callback = new CueDispatcher.PlaybackCallback() {
                public void onStarted(long atMs) { started.countDown(); }
                public void onFinished(long atMs, boolean success) { }
            };
            CueRequest request = new CueRequest("audio-test", "no-voice", "no-voice", "SAMPLE",
                    CueRequest.Category.SYSTEM, 80, SystemClock.elapsedRealtime(),
                    SystemClock.elapsedRealtime() + 2000, CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH,
                    7, 0, 0, "未缓存的测试语句");
            assertFalse(player.speechReady());
            assertFalse(player.speak(request, false, callback));
            assertTrue(player.playTone(request, callback));
            assertTrue(started.await(2, TimeUnit.SECONDS));
        } finally { player.close(); }
    }

    @Test public void speechReadinessProbeDoesNotGenerateOrPrepareSounds() {
        CuePlayer player = CuePlayer.speechProbe(context());
        try {
            assertTrue(((java.util.Map<?, ?>) field(player, "tones")).isEmpty());
            assertNull(field(player, "spatialTonePrewarmWorker"));
            assertFalse(player.playTone(near("probe-is-not-a-player", 2000),
                    new CueDispatcher.PlaybackCallback() {
                        public void onStarted(long atMs) { fail("A probe must never play audio"); }
                        public void onFinished(long atMs, boolean success) { }
                    }));
        } finally { player.close(); }
    }

    @Test public void perEventSoundChoicesSurviveRecreationWithoutChangingReminderPolicy() throws Exception {
        SharedPreferences prefs = GameProfile.settings(context());
        String nearKey = CueSoundLibrary.preferenceKey(7), portraitKey = CueSoundLibrary.preferenceKey(2);
        String oldNear = prefs.getString(nearKey, null), oldPortrait = prefs.getString(portraitKey, null);
        boolean toneBefore = prefs.getBoolean("cue_channel_tone", true);
        boolean speechBefore = prefs.getBoolean("cue_channel_speech", true);
        try (ActivityScenario<CueSoundSettingsActivity> scenario = ActivityScenario.launch(
                new Intent(context(), CueSoundSettingsActivity.class))) {
            scenario.onActivity(activity -> select(activity, 7, 2));
            scenario.onActivity(activity -> select(activity, 2, 1));
            assertEquals("bell", prefs.getString(nearKey, ""));
            assertEquals("soft", prefs.getString(portraitKey, ""));
            assertEquals(toneBefore, prefs.getBoolean("cue_channel_tone", true));
            assertEquals(speechBefore, prefs.getBoolean("cue_channel_speech", true));
            scenario.recreate();
            scenario.onActivity(activity -> {
                assertEquals("当前：清亮铃音", ((TextView) tag(activity.getWindow().getDecorView(),
                        "cue_sound_selected:7")).getText().toString());
                assertEquals("当前：柔和轻音", ((TextView) tag(activity.getWindow().getDecorView(),
                        "cue_sound_selected:2")).getText().toString());
            });
        } finally {
            SharedPreferences.Editor edit = prefs.edit();
            if (oldNear == null) edit.remove(nearKey); else edit.putString(nearKey, oldNear);
            if (oldPortrait == null) edit.remove(portraitKey); else edit.putString(portraitKey, oldPortrait);
            edit.commit();
        }
    }

    @Test public void mutedSoundPreviewDoesNotCreateAnAudioPlayer() throws Exception {
        SharedPreferences prefs = GameProfile.settings(context());
        boolean had = prefs.contains("volume"); int volume = prefs.getInt("volume", 45);
        prefs.edit().putInt("volume", 0).commit();
        try (ActivityScenario<CueSoundSettingsActivity> scenario = ActivityScenario.launch(
                new Intent(context(), CueSoundSettingsActivity.class))) {
            scenario.onActivity(activity -> {
                tag(activity.getWindow().getDecorView(), "cue_sound_preview:7").performClick();
                assertNull(field(activity, "previewPlayer"));
                assertTrue(((TextView) tag(activity.getWindow().getDecorView(), "cue_sound_preview_status"))
                        .getText().toString().contains("音量为 0"));
            });
        } finally {
            if (had) prefs.edit().putInt("volume", volume).commit(); else prefs.edit().remove("volume").commit();
        }
    }

    @Test public void selectingSoundPlaysTheNewChoiceWithoutPressingPreview() throws Exception {
        SharedPreferences prefs = GameProfile.settings(context());
        String key = CueSoundLibrary.preferenceKey(7), oldSound = prefs.getString(key, null);
        boolean hadVolume = prefs.contains("volume"), hadActive = prefs.contains("capture_active");
        int oldVolume = prefs.getInt("volume", 45);
        boolean oldActive = prefs.getBoolean("capture_active", false);
        android.media.AudioManager audio = context().getSystemService(android.media.AudioManager.class);
        int mediaVolume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                Math.max(1, mediaVolume), 0);
        prefs.edit().putInt("volume", 45).putBoolean("capture_active", false).commit();
        try (ActivityScenario<CueSoundSettingsActivity> scenario = ActivityScenario.launch(
                new Intent(context(), CueSoundSettingsActivity.class))) {
            scenario.onActivity(activity -> select(activity, 7, 2));
            assertEquals("bell", prefs.getString(key, ""));
            awaitAutomaticPreview(scenario, "清亮铃音");
            scenario.onActivity(activity -> select(activity, 7, 1));
            assertEquals("soft", prefs.getString(key, ""));
            awaitAutomaticPreview(scenario, "柔和轻音");
        } finally {
            SharedPreferences.Editor edit = prefs.edit();
            if (oldSound == null) edit.remove(key); else edit.putString(key, oldSound);
            if (hadVolume) edit.putInt("volume", oldVolume); else edit.remove("volume");
            if (hadActive) edit.putBoolean("capture_active", oldActive); else edit.remove("capture_active");
            edit.commit();
            audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, mediaVolume, 0);
        }
    }

    private static void awaitAutomaticPreview(ActivityScenario<CueSoundSettingsActivity> scenario,
            String expectedSound) {
        long deadline = SystemClock.elapsedRealtime() + 3000;
        String[] status = {""};
        do {
            scenario.onActivity(activity -> status[0] = ((TextView) tag(
                    activity.getWindow().getDecorView(), "cue_sound_preview_status")).getText().toString());
            if (status[0].equals(context().getString(R.string.cue_sound_done,
                    "附近敌人", expectedSound))) return;
            SystemClock.sleep(40);
        } while (SystemClock.elapsedRealtime() < deadline);
        fail("Selection must finish playback without a separate preview tap: " + status[0]);
    }

    @Test public void leavingSoundPreviewCancelsItsPlayerAndOldUiCallbacks() throws Exception {
        try (ActivityScenario<CueSoundSettingsActivity> scenario = ActivityScenario.launch(
                new Intent(context(), CueSoundSettingsActivity.class))) {
            scenario.onActivity(activity -> tag(activity.getWindow().getDecorView(), "cue_sound_preview:7").performClick());
            scenario.moveToState(Lifecycle.State.CREATED);
            SystemClock.sleep(300);
            scenario.onActivity(activity -> assertNull(field(activity, "previewPlayer")));
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(activity -> assertEquals("试听已停止，可选择音效并重新试听。",
                    ((TextView) tag(activity.getWindow().getDecorView(), "cue_sound_preview_status")).getText().toString()));
        }
    }

    private static void select(CueSoundSettingsActivity activity, int kind, int index) {
        tag(activity.getWindow().getDecorView(), "cue_sound_choose:" + kind).performClick();
        AlertDialog dialog = (AlertDialog) field(activity, "picker");
        assertNotNull(dialog);
        dialog.getListView().performItemClick(null, index, dialog.getListView().getAdapter().getItemId(index));
    }

    private static CueDispatcher dispatcher(CuePlayer player, List<String> events, CountDownLatch terminal) {
        return new CueDispatcher(player, policy(), new CueDispatcher.Listener() {
            public void onDispatch(CueRequest request, CueDispatcher.DispatchResult result) { }
            public void onPlayback(CueRequest request, String channel, long atMs, String result) {
                events.add(channel + ":" + result);
                if (terminal != null && "SPEECH".equals(channel) && !"STARTED".equals(result)) terminal.countDown();
            }
        }, SystemClock::elapsedRealtime);
    }

    private static ExecutorService worker(CuePlayer player) { return (ExecutorService) field(player, "alertAudioWorker"); }
    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static View tag(View root, String value) {
        if (value.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = tag(group.getChildAt(i), value); if (found != null) return found;
            }
        }
        return null;
    }
}
