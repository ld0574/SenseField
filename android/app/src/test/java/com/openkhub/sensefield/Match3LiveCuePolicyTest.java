package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class Match3LiveCuePolicyTest {
    private static final class RecordingRenderer implements CueDispatcher.Renderer {
        int tones;
        int haptics;
        int speech;

        @Override public boolean playTone(CueRequest request,
                                          CueDispatcher.PlaybackCallback callback) {
            tones++;
            return true;
        }
        @Override public boolean vibrate(CueRequest request) {
            haptics++;
            return true;
        }
        @Override public boolean speak(CueRequest request, boolean interrupt,
                                       CueDispatcher.PlaybackCallback callback) {
            speech++;
            callback.onStarted(request.createdAtMs);
            return true;
        }
        @Override public void stopSpeech() { }
    }

    private static final class StoredPolicy implements CueDispatcher.Policy {
        boolean systemEnabled = true;
        int channels = CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH
                | CueRequest.CHANNEL_HAPTIC | CueRequest.CHANNEL_VISUAL;

        @Override public boolean categoryEnabled(CueRequest.Category category) {
            return category != CueRequest.Category.SYSTEM || systemEnabled;
        }
        @Override public int enabledChannels() { return channels; }
        @Override public int enabledChannels(CueRequest.Category category) { return channels; }
        @Override public long dedupeWindowMs(CueRequest.Category category) { return 1200; }
    }

    @Test public void liveRequestsAndPolicyAllowSpeechOnlyUnderSavedSystemSettings() {
        StoredPolicy stored = new StoredPolicy();
        Match3LiveCuePolicy policy = new Match3LiveCuePolicy(stored);

        assertEquals(CueRequest.CHANNEL_SPEECH, Match3LiveCuePolicy.REQUESTED_CHANNELS);
        assertEquals(CueRequest.CHANNEL_SPEECH, policy.enabledChannels());
        assertEquals(CueRequest.CHANNEL_SPEECH,
                policy.enabledChannels(CueRequest.Category.SYSTEM));
        assertEquals(1200, policy.dedupeWindowMs(CueRequest.Category.SYSTEM));

        stored.systemEnabled = false;
        assertFalse(policy.categoryEnabled(CueRequest.Category.SYSTEM));
        stored.systemEnabled = true;
        stored.channels = CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_HAPTIC;
        assertEquals(0, policy.enabledChannels(CueRequest.Category.SYSTEM));
    }

    @Test public void speechSettingCanDisableLiveAnnouncementsWithoutToneOrHapticFallback() {
        StoredPolicy stored = new StoredPolicy();
        stored.channels = CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_HAPTIC;
        Match3LiveCuePolicy policy = new Match3LiveCuePolicy(stored);
        RecordingRenderer renderer = new RecordingRenderer();
        CueDispatcher dispatcher = new CueDispatcher(renderer, policy, new SilentEvents(),
                () -> 100);
        CueDispatcher.DispatchResult result = dispatcher.submit(request());

        assertEquals("DROPPED", result.outcome);
        assertEquals("channels_disabled", result.reason);
        assertEquals(0, renderer.tones);
        assertEquals(0, renderer.haptics);
        assertEquals(0, renderer.speech);
    }

    @Test public void allowedLiveAnnouncementRendersSpeechOnly() {
        StoredPolicy stored = new StoredPolicy();
        Match3LiveCuePolicy policy = new Match3LiveCuePolicy(stored);
        RecordingRenderer renderer = new RecordingRenderer();
        CueDispatcher dispatcher = new CueDispatcher(renderer, policy, new SilentEvents(),
                () -> 100);

        CueDispatcher.DispatchResult result = dispatcher.submit(request());

        assertEquals(CueRequest.CHANNEL_SPEECH, result.acceptedChannels);
        assertEquals(0, renderer.tones);
        assertEquals(0, renderer.haptics);
        assertEquals(1, renderer.speech);
    }

    private static CueRequest request() {
        return new CueRequest("m3live", "m3live:1", "m3live:announce",
                "消消乐实时播报", CueRequest.Category.SYSTEM, 70, 100, 1000,
                Match3LiveCuePolicy.REQUESTED_CHANNELS, 0, 0, 0, "棋局更新");
    }

    private static final class SilentEvents implements CueDispatcher.Listener {
        @Override public void onDispatch(CueRequest request,
                                         CueDispatcher.DispatchResult result) { }
        @Override public void onPlayback(CueRequest request, String channel,
                                        long atMs, String result) { }
    }
}
