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

    /**
     * 合同（书 24 起）：存储的通道位原样算数，语音位恒在。
     * 旧合同「这里只回语音」已随产品要求作废，故 :51-53 与 :60 三条期望值按新合同改写。
     */
    @Test public void liveRequestsAndPolicyFollowSavedChannelsWithSpeechAlwaysOn() {
        StoredPolicy stored = new StoredPolicy();
        Match3LiveCuePolicy policy = new Match3LiveCuePolicy(stored);

        assertEquals(CueRequest.CHANNEL_SPEECH, Match3LiveCuePolicy.REQUESTED_CHANNELS);
        assertEquals(CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH
                        | CueRequest.CHANNEL_HAPTIC | CueRequest.CHANNEL_VISUAL,
                policy.enabledChannels());
        assertEquals(CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH
                        | CueRequest.CHANNEL_HAPTIC | CueRequest.CHANNEL_VISUAL,
                policy.enabledChannels(CueRequest.Category.SYSTEM));
        assertEquals(1500, policy.dedupeWindowMs(CueRequest.Category.SYSTEM));

        stored.systemEnabled = false;
        assertFalse(policy.categoryEnabled(CueRequest.Category.SYSTEM));
        stored.systemEnabled = true;
        stored.channels = CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_HAPTIC;
        assertEquals(CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH
                        | CueRequest.CHANNEL_HAPTIC,
                policy.enabledChannels(CueRequest.Category.SYSTEM));
    }

    /**
     * 合同（书 24 起）：把语音位关掉不再拦停实况提示——语音位恒在，掩码也不再回收音效／触觉位。
     * 旧合同「存储关语音 ⇒ channels_disabled」已作废，故 :72-76 四条期望值按新合同改写。
     */
    @Test public void storedSpeechOffStillAnnouncesBecauseSpeechBitIsAlwaysGranted() {
        StoredPolicy stored = new StoredPolicy();
        stored.channels = CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_HAPTIC;
        Match3LiveCuePolicy policy = new Match3LiveCuePolicy(stored);
        RecordingRenderer renderer = new RecordingRenderer();
        CueDispatcher dispatcher = new CueDispatcher(renderer, policy, new SilentEvents(),
                () -> 100);
        CueDispatcher.DispatchResult result = dispatcher.submit(request());

        assertEquals("ACCEPTED", result.outcome);
        assertEquals("none", result.reason);
        assertEquals(CueRequest.CHANNEL_SPEECH, result.acceptedChannels);
        assertEquals(0, renderer.tones);
        assertEquals(0, renderer.haptics);
        assertEquals(1, renderer.speech);
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
