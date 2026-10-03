package com.openkhub.sensefield;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import okio.Buffer;
import org.junit.Test;

public class AssistantContractsTest {
    @Test public void appGameRouteProbeRejectsUnknownSpeakerAndUnstartedTrack() {
        assertFalse(VoiceInputSafetyPolicy.verifiedHeadphoneRoute(28, true,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES));
        assertFalse(VoiceInputSafetyPolicy.verifiedHeadphoneRoute(34, false,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES));
        assertFalse(VoiceInputSafetyPolicy.verifiedHeadphoneRoute(34, true, -1));
        assertFalse(VoiceInputSafetyPolicy.verifiedHeadphoneRoute(34, true,
                android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER));
        assertTrue(VoiceInputSafetyPolicy.verifiedHeadphoneRoute(29, true,
                android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES));
        assertTrue(VoiceInputSafetyPolicy.verifiedHeadphoneRoute(30, true,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET));
        assertFalse(VoiceInputSafetyPolicy.verifiedHeadphoneRoute(30, true,
                android.media.AudioDeviceInfo.TYPE_BLE_HEADSET));
        assertTrue(VoiceInputSafetyPolicy.verifiedHeadphoneRoute(31, true,
                android.media.AudioDeviceInfo.TYPE_BLE_HEADSET));
    }
    @Test public void anotherRecorderBlocksUploadEvenWhenItsSessionIsAnonymized() {
        assertFalse(VoiceInputSafetyPolicy.otherRecorder(42, new int[]{42}));
        assertTrue(VoiceInputSafetyPolicy.otherRecorder(42, new int[]{42, 0}));
        assertTrue(VoiceInputSafetyPolicy.otherRecorder(42, new int[]{43}));
    }
    @Test public void newerQuestionAndCaptureResetInvalidateLateCallbacks() {
        AssistantSession session = new AssistantSession("test");
        long generation = session.generation();
        String first = session.newTurn();
        assertTrue(session.owns(generation, first));
        String second = session.newTurn();
        assertFalse(session.owns(generation, first));
        assertTrue(session.owns(generation, second));
        session.invalidate();
        assertFalse(session.owns(generation, second));
        String third = session.newTurn();
        assertTrue(session.owns(session.generation(), third));
        session.close();
        assertFalse(session.owns(session.generation(), third));
    }
    @Test public void dynamicHudExpiresEarlierThanStaticText() {
        AssistantReply hud = new AssistantReply(1, "t", 1, 1000, "hud", "1:0", false);
        AssistantReply menu = new AssistantReply(1, "t", 1, 1000, "ui_text", "开始", false);
        assertFalse(hud.freshAt(999));
        assertTrue(hud.freshAt(6000));
        assertFalse(hud.freshAt(6001));
        assertTrue(menu.freshAt(16000));
        assertFalse(menu.freshAt(16001));
    }
    @Test public void automaticObservationObeysWarmHotQuietAndChangeGates() {
        AssistantPolicy policy = new AssistantPolicy();
        assertTrue(policy.automaticDue(0, FrameProcessingPolicy.Mode.NORMAL, false, false, -1, 5));
        policy.started(0, true, 5);
        assertFalse(policy.automaticDue(30_000, FrameProcessingPolicy.Mode.NORMAL, false, false, -1, 5));
        assertTrue(policy.automaticDue(30_000, FrameProcessingPolicy.Mode.NORMAL, false, false, -1, 6));
        assertFalse(policy.automaticDue(30_000, FrameProcessingPolicy.Mode.WARM, false, false, -1, 6));
        assertTrue(policy.automaticDue(60_000, FrameProcessingPolicy.Mode.WARM, false, false, -1, 6));
        assertFalse(policy.automaticDue(60_000, FrameProcessingPolicy.Mode.HOT, false, false, -1, 6));
        assertFalse(policy.manualAllowed(60_000, FrameProcessingPolicy.Mode.HOT));
        assertFalse(policy.automaticDue(60_000, FrameProcessingPolicy.Mode.NORMAL, true, false, -1, 6));
        assertFalse(policy.automaticDue(60_000, FrameProcessingPolicy.Mode.NORMAL, false, true, -1, 6));
        assertFalse(policy.automaticDue(60_000, FrameProcessingPolicy.Mode.NORMAL, false, false, 58_000, 6));
        assertTrue(policy.automaticDue(61_000, FrameProcessingPolicy.Mode.NORMAL, false, false, 58_000, 6));
    }
    @Test public void throttleAndBoundedSessionBudgetDoNotBlockLocalMode() {
        AssistantPolicy policy = new AssistantPolicy();
        policy.throttled(1000);
        assertFalse(policy.manualAllowed(60_999, FrameProcessingPolicy.Mode.NORMAL));
        assertTrue(policy.manualAllowed(61_000, FrameProcessingPolicy.Mode.NORMAL));
        for (int i = 0; i < 120; i++) policy.started(61_000, false, i);
        assertFalse(policy.manualAllowed(61_000, FrameProcessingPolicy.Mode.NORMAL));
        assertTrue(policy.manualAllowed(AssistantPolicy.WINDOW_MS + 60_999, FrameProcessingPolicy.Mode.NORMAL));
    }
    @Test public void shortGatewayResponseDoesNotRequireExactLength() throws IOException {
        Buffer source = new Buffer().writeUtf8("{\"answer\":\"比分是二比一\"}");
        assertEquals("{\"answer\":\"比分是二比一\"}", AssistantGatewayClient.readBounded(source, 32_768));
    }
    @Test public void responseLimitCountsUtf8Bytes() throws IOException {
        Buffer source = new Buffer().writeUtf8("听野听野");
        try { AssistantGatewayClient.readBounded(source, 8); fail("oversize UTF-8 accepted"); }
        catch (IOException expected) { }
    }
    @Test public void localSpeechRequestsAreNotLostByQuestionFiltering() {
        assertTrue(AssistantController.isRequest("停止播报"));
        assertTrue(AssistantController.isRequest("附近情况"));
        assertTrue(AssistantController.isRequest("那这个呢"));
        assertFalse(AssistantController.isRequest("我刚刚跑过去了"));
    }
    @Test public void endpointRejectsPlaintextCredentialsAndExtraPath() {
        assertTrue(AssistantSettings.validEndpoint("https://assistant.example:8443"));
        assertFalse(AssistantSettings.validEndpoint("http://assistant.example"));
        assertFalse(AssistantSettings.validEndpoint("https://secret@assistant.example"));
        assertFalse(AssistantSettings.validEndpoint("https://assistant.example/key?token=secret"));
    }
    private static class Sink implements VoiceActivityGate.Output {
        int starts, ends; final List<short[]> audio = new ArrayList<>();
        public void started() { starts++; }
        public void pcm(short[] samples) { audio.add(samples.clone()); }
        public void ended() { ends++; }
    }
    private static short[] frame(int amplitude) { short[] result = new short[160]; Arrays.fill(result, (short) amplitude); return result; }
    @Test public void vadUploadsOnlySpeechWithBoundedPrerollAndEndpoints() {
        Sink sink = new Sink(); VoiceActivityGate gate = new VoiceActivityGate(sink);
        for (int i = 0; i < 100; i++) gate.accept(frame(0));
        assertEquals(0, sink.starts); assertTrue(sink.audio.isEmpty());
        for (int i = 0; i < 14; i++) gate.accept(frame(1000));
        assertEquals(0, sink.starts);
        gate.accept(frame(1000));
        assertEquals(1, sink.starts); assertEquals(30, sink.audio.size());
        assertEquals(0, sink.audio.get(0)[0]); assertEquals(1000, sink.audio.get(29)[0]);
        for (int i = 0; i < 44; i++) gate.accept(frame(0));
        assertEquals(0, sink.ends);
        gate.accept(frame(0)); assertEquals(1, sink.ends); assertFalse(gate.active());
    }
    @Test public void vadResetDiscardsInterruptedAudioAndRequiresNewOnset() {
        Sink sink = new Sink(); VoiceActivityGate gate = new VoiceActivityGate(sink);
        for (int i = 0; i < 15; i++) gate.accept(frame(1000));
        gate.reset(); assertFalse(gate.active());
        sink.audio.clear();
        for (int i = 0; i < 14; i++) gate.accept(frame(1000));
        assertTrue(sink.audio.isEmpty());
        gate.accept(frame(1000)); assertEquals(2, sink.starts); assertEquals(15, sink.audio.size());
    }
    @Test public void vadBoundsAnUninterruptedUtterance() {
        Sink sink = new Sink(); VoiceActivityGate gate = new VoiceActivityGate(sink);
        for (int i = 0; i < 1515; i++) gate.accept(frame(1000));
        assertEquals(1, sink.starts); assertEquals(1, sink.ends); assertFalse(gate.active());
    }
    @Test public void rejectedNonSpeechDoesNotUploadEvenWhenLoud() {
        Sink sink = new Sink(); VoiceActivityGate gate = new VoiceActivityGate(sink);
        for (int i = 0; i < 3000; i++) gate.accept(frame(4000), false);
        assertEquals(0, sink.starts); assertTrue(sink.audio.isEmpty());
        for (int i = 0; i < 15; i++) gate.accept(frame(1000), true);
        assertEquals(1, sink.starts);
    }
}
