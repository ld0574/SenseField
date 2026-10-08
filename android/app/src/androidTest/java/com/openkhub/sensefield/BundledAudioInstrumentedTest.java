package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Also run against a minified Release. Decoder/output evidence is not an external recording. */
@RunWith(AndroidJUnit4.class)
public final class BundledAudioInstrumentedTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

    @Test public void bothVoicesAllRateExtremesAndGuideDecodeWithoutATtsEngine() throws Exception {
        for (String voice : BundledSpeechCatalog.VOICES) for (int rate : new int[]{80, 180, 240}) {
            BundledSpeechAssets assets = new BundledSpeechAssets(context(), voice, rate);
            assets.loadIndex();
            for (String text : new String[]{"左上", "右上有敌人", "截屏恢复失败，请重新授权"}) {
                short[] pcm = assets.load(text, false);
                assertTrue(pcm.length > 0 && pcm.length <= AlertSpeechCache.MAX_SAMPLES);
                assertTrue("Decoded audio contains a signal", containsSignal(pcm));
            }
        }
        BundledSpeechAssets guide = new BundledSpeechAssets(context(), "game_male", 240);
        for (String text : BundledSpeechCatalog.guide()) {
            assertTrue(guide.load(text, true).length > 0);
        }
    }

    @Test public void twentyCompletedPreviewsAndRapidReplacementRemainUsable() throws Exception {
        CuePlayer player = CuePlayer.tonePreview(context());
        try {
            for (int i = 0; i < 20; i++) {
                CountDownLatch finished = new CountDownLatch(1);
                boolean[] success = {false};
                assertTrue(player.playTone(tone("repeat:" + i, i % 2 == 0 ? 7 : 2), callback(finished, success)));
                assertTrue("Preview completion " + i, finished.await(3, TimeUnit.SECONDS));
                assertTrue(success[0]);
            }
            for (int i = 0; i < 10; i++) {
                assertTrue(player.playTone(tone("cancel:" + i, 7), new CueDispatcher.PlaybackCallback() {
                    public void onStarted(long at) {}
                    public void onFinished(long at, boolean success) {}
                }));
                player.cancelPendingTone();
            }
            CountDownLatch done = new CountDownLatch(1);
            boolean[] success = {false};
            assertTrue(player.playTone(tone("latest", 7), callback(done, success)));
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertTrue(success[0]);
        } finally { player.close(); }
    }

    @Test public void fixedSpeechPlaysFromAssetsAndDoesNotInitializeDynamicTts() throws Exception {
        CuePlayer player = new CuePlayer(context(), true);
        List<String> audit = Collections.synchronizedList(new ArrayList<>());
        player.setAudioAuditListener(audit::add);
        try {
            long deadline = SystemClock.elapsedRealtime() + 10000;
            while (!player.speechPreparationFinished() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
            assertTrue(player.speechReady());
            long now = SystemClock.elapsedRealtime();
            CueRequest request = new CueRequest("bundled-test", "fixed", "fixed", "NEAR_ZONE",
                    CueRequest.Category.NEAR_ZONE, 80, now, now + 3000,
                    CueRequest.CHANNEL_SPEECH, 7, 0, 0, "左上");
            CountDownLatch completed = new CountDownLatch(1);
            boolean[] success = {false};
            assertTrue(player.speak(request, true, callback(completed, success)));
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertTrue(success[0]);
            assertTrue(audit.stream().anyMatch(line -> line.contains("tts=false asr=false")));
            assertFalse(audit.stream().anyMatch(line -> line.startsWith("AlertTts ")));
        } finally { player.close(); }
    }

    @Test public void settingsRepeatButtonNeverHasAFixedDisabledCooldown() throws Exception {
        context().getSharedPreferences("mapassist_settings", Context.MODE_PRIVATE).edit().putBoolean("capture_active", false).apply();
        try (ActivityScenario<GameTuningActivity> scenario = ActivityScenario.launch(
                new Intent(context(), GameTuningActivity.class))) {
            for (int i = 0; i < 5; i++) {
                scenario.onActivity(activity -> {
                    Button button = findButton(activity.getWindow().getDecorView(), "测试提醒与振动", "重播提醒与振动");
                    assertNotNull(button);
                    assertTrue(button.isEnabled());
                    button.performClick();
                    assertTrue("Repeating replaces the old run", button.isEnabled());
                });
                Thread.sleep(50);
            }
            scenario.recreate();
            scenario.onActivity(activity -> assertTrue(findButton(activity.getWindow().getDecorView(),
                    "测试提醒与振动", "重播提醒与振动").isEnabled()));
        }
    }

    @Test public void repeatedFixedSpeechAndCancelledRunsCannotStopTheLatestPreview() throws Exception {
        CuePlayer player = new CuePlayer(context(), true);
        try {
            long deadline = SystemClock.elapsedRealtime() + 10000;
            while (!player.speechPreparationFinished() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
            assertTrue(player.speechReady());
            for (int i = 0; i < 20; i++) {
                CountDownLatch done = new CountDownLatch(1);
                boolean[] success = {false};
                assertTrue(player.speak(fixed("repeated-voice:" + i), true, callback(done, success)));
                assertTrue("Fixed voice completion " + i, done.await(5, TimeUnit.SECONDS));
                assertTrue(success[0]);
            }
            for (int i = 0; i < 10; i++) {
                assertTrue(player.speak(fixed("cancelled-voice:" + i), true,
                        new CueDispatcher.PlaybackCallback() {
                            public void onStarted(long at) {}
                            public void onFinished(long at, boolean success) {}
                        }));
                player.stopSpeech();
            }
            CountDownLatch done = new CountDownLatch(1);
            boolean[] success = {false};
            assertTrue(player.speak(fixed("replacement-voice"), true, callback(done, success)));
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue("Old callbacks must not end the new playback", success[0]);
        } finally { player.close(); }
    }

    @Test public void fullGuideCanPauseRecreateAndContinueUsingOnlyVisibleControls() throws Exception {
        context().getSharedPreferences("mapassist_settings", Context.MODE_PRIVATE).edit().putBoolean("capture_active", false)
                .putInt("volume", 45).apply();
        try (ActivityScenario<ReminderGuideActivity> scenario = ActivityScenario.launch(
                new Intent(context(), ReminderGuideActivity.class)
                        .putExtra(ReminderGuideActivity.EXTRA_FULL, true))) {
            scenario.onActivity(activity -> findButton(activity.getWindow().getDecorView(),
                    "播放全文", "播放全文").performClick());
            Thread.sleep(1800);
            scenario.onActivity(activity -> {
                Button pause = findButton(activity.getWindow().getDecorView(), "暂停", "暂停");
                assertNotNull(pause);
                pause.performClick();
                assertNotNull(findButton(activity.getWindow().getDecorView(), "继续播放", "继续播放"));
            });
            scenario.recreate();
            scenario.onActivity(activity -> {
                Button resume = findButton(activity.getWindow().getDecorView(), "继续播放", "继续播放");
                assertNotNull("Rotation keeps the unfinished sentence", resume);
                resume.performClick();
                assertNotNull(findButton(activity.getWindow().getDecorView(), "暂停", "暂停"));
            });
        }
    }

    private static CueRequest fixed(String id) {
        long now = SystemClock.elapsedRealtime();
        return new CueRequest("voice-test", id, id, "SAMPLE", CueRequest.Category.NEAR_ZONE,
                80, now, now + 5000, CueRequest.CHANNEL_SPEECH, 7, 0, 0, "左上");
    }

    private static CueRequest tone(String id, int kind) {
        long now = SystemClock.elapsedRealtime();
        return new CueRequest("tone-test", id, id, "SOUND_PREVIEW", CueRequest.Category.SYSTEM,
                80, now, now + 2000, CueRequest.CHANNEL_TONE, kind, 0, 0, null);
    }

    private static CueDispatcher.PlaybackCallback callback(CountDownLatch done, boolean[] success) {
        return new CueDispatcher.PlaybackCallback() {
            public void onStarted(long at) {}
            public void onFinished(long at, boolean finished) { success[0] = finished; done.countDown(); }
        };
    }

    private static boolean containsSignal(short[] pcm) {
        for (short value : pcm) if (value != 0) return true;
        return false;
    }

    private static Button findButton(View view, String first, String second) {
        if (view instanceof Button && (first.contentEquals(((Button) view).getText())
                || second.contentEquals(((Button) view).getText()))) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = findButton(((ViewGroup) view).getChildAt(i), first, second);
            if (found != null) return found;
        }
        return null;
    }
}
