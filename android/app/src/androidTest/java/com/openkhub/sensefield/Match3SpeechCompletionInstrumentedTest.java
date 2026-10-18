package com.openkhub.sensefield;

import static org.junit.Assert.*;

import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real installed Chinese TTS, without a game, projection, microphone or preference changes. */
@RunWith(AndroidJUnit4.class)
public final class Match3SpeechCompletionInstrumentedTest {
    private static CueRequest request(String id, String speech) {
        long now = SystemClock.elapsedRealtime();
        return new CueRequest("speech-completion-test", id, "m3live:hint:" + id,
                "消消乐交换提示", CueRequest.Category.SYSTEM, 70, now, now + 20000,
                CueRequest.CHANNEL_SPEECH, 0, 0, 0, speech);
    }

    @Test public void realVoiceFinishesRetiredHintAndStillHonorsExplicitStop() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Map<String, ?> preferencesBefore = GameProfile.settings(context).getAll();
        AudioManager audio = context.getSystemService(AudioManager.class);
        assertTrue("Media output must be audible without changing the user's volume",
                audio.getStreamVolume(AudioManager.STREAM_MUSIC) > 0
                        && !audio.isStreamMute(AudioManager.STREAM_MUSIC));
        assertTrue("App output must be audible", GameProfile.settings(context).getInt("volume", 45) > 0);
        CuePlayer player = CuePlayer.speechProbe(context);
        CueDispatcher dispatcher = null;
        List<String> audit = Collections.synchronizedList(new ArrayList<>());
        List<String> playback = Collections.synchronizedList(new ArrayList<>());
        CueRequest first = request("real-first", "左上方，标记的两颗棋子向右交换。请先找到高亮的交换位置。");
        CountDownLatch firstStarted = new CountDownLatch(1), firstCompleted = new CountDownLatch(1);
        CountDownLatch nextStarted = new CountDownLatch(1);
        long[] times = new long[3];
        player.setAudioAuditListener(audit::add);
        try {
            long deadline = SystemClock.elapsedRealtime() + 12000;
            while (!player.speechPreparationFinished() && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(40);
            assertTrue("Installed offline Chinese voice must become ready: " + audit, player.speechReady());
            dispatcher = new CueDispatcher(player, new CueDispatcher.Policy() {
                public boolean categoryEnabled(CueRequest.Category category) { return true; }
                public int enabledChannels() { return CueRequest.CHANNEL_SPEECH; }
                public long dedupeWindowMs(CueRequest.Category category) { return 0; }
            }, new CueDispatcher.Listener() {
                public void onDispatch(CueRequest request, CueDispatcher.DispatchResult result) { }
                public void onPlayback(CueRequest request, String channel, long atMs, String result) {
                    if (!"SPEECH".equals(channel)) return;
                    boolean isFirst = request == first;
                    playback.add((isFirst ? "first:" : "next:") + result);
                    if (isFirst && "STARTED".equals(result)) {
                        times[0] = atMs; firstStarted.countDown();
                    } else if (isFirst && "COMPLETED".equals(result)) {
                        times[1] = atMs; firstCompleted.countDown();
                    } else if (!isFirst && "STARTED".equals(result)) {
                        times[2] = atMs; nextStarted.countDown();
                    }
                }
            }, SystemClock::elapsedRealtime);
            assertTrue(dispatcher.submit(first).audioQueued());
            assertTrue("Real engine STARTED callback must arrive: " + playback,
                    firstStarted.await(8, TimeUnit.SECONDS));
            assertTrue("A board update must retain the started sentence",
                    dispatcher.retireCue("real-first", "BOARD_MOTION", true));
            assertTrue(dispatcher.isCueSpeaking("real-first"));
            assertTrue("Real engine must finish that sentence: " + playback + " " + audit,
                    firstCompleted.await(18, TimeUnit.SECONDS));
            assertFalse(dispatcher.isCueSpeaking("real-first"));
            assertEquals(1, Collections.frequency(playback, "first:COMPLETED"));
            assertEquals(2, playback.size());

            CueRequest next = request("real-next", "右下方，标记的两颗棋子向上交换。请先找到高亮的交换位置。");
            assertTrue(dispatcher.submit(next).audioQueued());
            assertTrue("Next sentence must start after completion", nextStarted.await(8, TimeUnit.SECONDS));
            assertTrue(times[2] >= times[1]);
            assertTrue(dispatcher.retireCue("real-next", "HINT_EVIDENCE_CHANGED", true));
            dispatcher.cancelCue("real-next", "USER_STOP");
            assertFalse("Explicit stop must still interrupt a finishing sentence",
                    dispatcher.isCueSpeaking("real-next"));
            SystemClock.sleep(300);
            assertTrue(playback.contains("next:CANCELLED_USER_STOP"));
            assertFalse(playback.contains("next:COMPLETED"));
            assertEquals(preferencesBefore, GameProfile.settings(context).getAll());

            JSONObject report = new JSONObject();
            report.put("first_duration_ms", times[1] - times[0]);
            report.put("next_after_completion_ms", times[2] - times[1]);
            report.put("playback", new JSONArray(playback));
            report.put("voice_audit", new JSONArray(audit));
            report.put("preferences_unchanged", true);
            Bundle status = new Bundle(); status.putString("speech_completion", report.toString());
            InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
        } finally {
            if (dispatcher != null) dispatcher.close();
            player.close();
        }
    }
}
