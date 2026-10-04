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
        assertTrue(VoiceInputSafetyPolicy.verifiedHeadphoneRoute(30, true,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP));
        assertTrue(VoiceInputSafetyPolicy.verifiedHeadphoneRoute(34, true,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP));
        assertFalse(VoiceInputSafetyPolicy.verifiedHeadphoneRoute(34, true,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO));
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
    @Test public void unsafeInputStatesAndRecoveryRequireFreshServerGenerations() {
        assertEquals(VoiceInputSafetyPolicy.InputState.PAUSED,
                VoiceInputSafetyPolicy.inputState(true, false, false, true));
        assertEquals(VoiceInputSafetyPolicy.InputState.CLIENT_SILENCED,
                VoiceInputSafetyPolicy.inputState(false, true, false, true));
        assertEquals(VoiceInputSafetyPolicy.InputState.OTHER_RECORDING,
                VoiceInputSafetyPolicy.inputState(false, false, true, true));
        assertEquals(VoiceInputSafetyPolicy.InputState.ROUTE_UNVERIFIED,
                VoiceInputSafetyPolicy.inputState(false, false, false, false));
        assertEquals(VoiceInputSafetyPolicy.InputState.READY,
                VoiceInputSafetyPolicy.inputState(false, false, false, true));

        int readyFlags = VoiceInputSafetyPolicy.safetyFlags(false, false, false, true);
        int[] singleUnsafeFlags = {
                VoiceInputSafetyPolicy.safetyFlags(true, false, false, true),
                VoiceInputSafetyPolicy.safetyFlags(false, true, false, true),
                VoiceInputSafetyPolicy.safetyFlags(false, false, true, true),
                VoiceInputSafetyPolicy.safetyFlags(false, false, false, false)
        };
        AssistantSession session = new AssistantSession("safety-transition");
        String oldTurn = session.newTurn(); long oldGeneration = session.generation();
        int previousFlags = readyFlags;
        for (int unsafeFlags : singleUnsafeFlags) {
            assertTrue(VoiceInputSafetyPolicy.requiresSessionReset(previousFlags, unsafeFlags));
            session.invalidate();
            assertTrue(VoiceInputSafetyPolicy.requiresSessionReset(unsafeFlags, readyFlags));
            session.invalidate();
            previousFlags = readyFlags;
        }
        int combinedUnsafe = VoiceInputSafetyPolicy.safetyFlags(true, true, true, false);
        assertTrue(VoiceInputSafetyPolicy.requiresSessionReset(combinedUnsafe,
                VoiceInputSafetyPolicy.safetyFlags(true, true, true, true)));
        assertTrue(VoiceInputSafetyPolicy.requiresSessionReset(combinedUnsafe,
                VoiceInputSafetyPolicy.safetyFlags(false, true, true, false)));
        assertFalse(VoiceInputSafetyPolicy.requiresSessionReset(readyFlags, readyFlags));
        assertFalse("A delayed final from before any unsafe transition must be stale",
                session.owns(oldGeneration, oldTurn));
        String recoveredTurn = session.newTurn();
        assertTrue(session.owns(session.generation(), recoveredTurn));
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
    @Test public void provisionalCaptureKeepsAnswerOwnerAndRejectsReplacedAudio() {
        AssistantSession session = new AssistantSession("provisional-capture");
        long generation = session.generation();
        String answer = session.newTurn();
        String firstAudio = session.newCaptureTurn();
        assertTrue(session.owns(generation, answer));
        assertTrue(session.ownsCapture(generation, firstAudio));
        String nextAudio = session.newCaptureTurn();
        assertFalse(session.ownsCapture(generation, firstAudio));
        assertTrue(session.owns(generation, answer));
        session.invalidateCapture();
        assertFalse(session.ownsCapture(generation, nextAudio));
        assertTrue(session.owns(generation, answer));
        String finalAudio = session.newCaptureTurn();
        session.invalidate();
        assertFalse(session.owns(generation, answer));
        assertFalse(session.ownsCapture(generation, finalAudio));
    }
    @Test public void dynamicHudExpiresEarlierThanStaticText() {
        AssistantReply hud = new AssistantReply(1, "t", 1, 1000, "hud", "1:0", false);
        AssistantReply menu = new AssistantReply(1, "t", 1, 1000, "ui_text", "开始", false);
        assertFalse(hud.freshAt(999));
        assertTrue(hud.freshAt(6000));
        assertFalse(hud.freshAt(6001));
        assertTrue(menu.freshAt(16000));
        assertFalse(menu.freshAt(16001));
        AssistantReply local = new AssistantReply(1, "t", -1, 1000, "local", "读取超时", true);
        assertTrue(local.freshAt(7000));
        assertFalse(local.freshAt(16001));
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
        assertEquals(60_000, policy.retryAfterMs(1000));
        assertEquals(1, policy.retryAfterMs(60_999));
        assertFalse(policy.manualAllowed(60_999, FrameProcessingPolicy.Mode.NORMAL));
        assertTrue(policy.manualAllowed(61_000, FrameProcessingPolicy.Mode.NORMAL));
        assertEquals(0, policy.retryAfterMs(61_000));
        for (int i = 0; i < 120; i++) policy.started(61_000, false, i);
        assertFalse(policy.manualAllowed(61_000, FrameProcessingPolicy.Mode.NORMAL));
        assertTrue(policy.manualAllowed(AssistantPolicy.WINDOW_MS + 60_999, FrameProcessingPolicy.Mode.NORMAL));
    }
    @Test public void shortGatewayResponseDoesNotRequireExactLength() throws IOException {
        Buffer source = new Buffer().writeUtf8("{\"answer\":\"比分是二比一\"}");
        assertEquals("{\"answer\":\"比分是二比一\"}", AssistantGatewayClient.readBounded(source, 32_768));
    }
    @Test public void providerOverloadRecoversAfterShortBackoffWithoutShorteningExplicitWait() {
        AssistantPolicy policy = new AssistantPolicy();
        policy.throttled(1000, AssistantPolicy.OVERLOAD_RETRY_MS);
        assertFalse(policy.manualAllowed(10_999, FrameProcessingPolicy.Mode.NORMAL));
        assertTrue(policy.manualAllowed(11_000, FrameProcessingPolicy.Mode.NORMAL));
        assertEquals(0, policy.retryAfterMs(11_000));
        policy.throttled(12_000, 120_000);
        policy.throttled(13_000, AssistantPolicy.OVERLOAD_RETRY_MS);
        assertEquals(119_000, policy.retryAfterMs(13_000));
        assertFalse(policy.manualAllowed(131_999, FrameProcessingPolicy.Mode.NORMAL));
        assertTrue(policy.manualAllowed(132_000, FrameProcessingPolicy.Mode.NORMAL));
        assertFalse(policy.manualAllowed(132_000, FrameProcessingPolicy.Mode.HOT));
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
        assertTrue(AssistantController.isRequest("你好"));
        assertTrue(AssistantController.isRequest("你是谁"));
        assertFalse(AssistantController.isRequest("我刚刚跑过去了"));
        assertFalse(AssistantController.isRequest("他们刚刚聊到听野"));
    }
    @Test public void negatedQuantityCommentDoesNotReplaceAQuestionButRealQuantityQuestionsRemain() {
        assertFalse(AssistantController.isRequest("也没多少呀。"));
        assertFalse(AssistantController.isRequest("其实没有多少。"));
        assertFalse(AssistantController.isRequest(null));
        assertFalse(AssistantController.isRequest(" "));
        assertTrue(AssistantController.isRequest("还要多少金币？"));
        assertTrue(AssistantController.isRequest("现在金币多少？"));
        assertTrue(AssistantController.isRequest("没多少血，应该怎么办？"));
        assertTrue(AssistantController.isRequest("这件装备没有多少伤害吗？"));
        assertTrue(AssistantController.isRequest("那这个呢？"));
    }
    @Test public void greetingsAndMicrophoneChecksStayLocalWithoutSwallowingVisualQuestions() {
        AssistantConversationIntent.Match repeatedGreeting =
                AssistantConversationIntent.classify("你好你好");
        assertNotNull(repeatedGreeting);
        assertEquals(AssistantConversationIntent.Type.GREETING, repeatedGreeting.type);
        assertTrue(AssistantConversationIntent.matches("你好你好"));
        assertFalse(AssistantController.isRequest("你好你好"));

        AssistantConversationIntent.Match combined =
                AssistantConversationIntent.classify("你好你好，你能听到我说话吗？");
        assertNotNull(combined);
        assertEquals(AssistantConversationIntent.Type.HEARING_CHECK, combined.type);
        assertTrue(combined.answer.contains("听得到"));
        assertTrue(AssistantConversationIntent.matches("你能听到我说话吗"));
        assertTrue(AssistantConversationIntent.matches("你能听到我说话"));

        assertNull(AssistantConversationIntent.classify("你好，我想问装备建议"));
        assertNull(AssistantConversationIntent.classify("听到这个音效了吗"));
        assertNull(AssistantConversationIntent.classify("这个英雄适合出什么装备？"));
    }
    @Test public void assistantAvailabilityAndWaitingQuestionsStayLocalWithoutSwallowingGameQuestions() {
        for (String question : Arrays.asList("怎么就又不可用了呀？", "你怎么还是看不清啊？",
                "怎么还没回答？", "还要等多久？", "你还在吗？")) {
            AssistantConversationIntent.Match match = AssistantConversationIntent.classify(question);
            assertNotNull(question, match);
            assertEquals(question, AssistantConversationIntent.Type.ASSISTANT_STATUS, match.type);
            assertTrue(question, match.answer.contains("再说一次"));
        }

        for (String question : Arrays.asList("你怎么还是看不清对面装备？",
                "你怎么还是看不清这张装备图？", "现在金币有多少？",
                "这个英雄适合出什么装备？", "也没多少呀"))
            assertNull(question, AssistantConversationIntent.classify(question));
    }
    @Test public void whichQuestionsReadOptionsWithoutPromotingOrdinaryChoiceStatements() {
        for (String question : Arrays.asList("这个画面我应该选哪个", "选哪一个", "哪项比较合适",
                "哪一种", "选哪些", "哪个按钮是开始"))
            assertTrue(question, AssistantController.isRequest(question));
        for (String question : Arrays.asList("帮我看下出装", "看一下这局出装", "帮我分析阵容",
                "推荐装备", "帮我选英雄", "这局对战策略", "对战策略",
                "你能不能帮我分析阵容", "能不能推荐装备", "我能不能推荐装备",
                "装备购买建议", "选人建议", "英雄推荐", "自然之灵出装建议"))
            assertTrue(question, AssistantController.isRequest(question));
        for (String statement : Arrays.asList("我选桑启", "选好了", "这一局我选择辅助", "我推荐装备"))
            assertFalse(statement, AssistantController.isRequest(statement));
        assertEquals("这个画面我应该选哪个", AssistantController.visualQuestion("这个画面我应该选哪个"));
        assertEquals("哪项比较合适", AssistantController.visualQuestion("哪项比较合适"));
        assertEquals("帮我推荐一个当前阵容适合的英雄", AssistantController.visualQuestion("帮我推荐一个当前阵容适合的英雄"));
        assertEquals("哪个按钮是开始", AssistantController.visualQuestion("哪个按钮是开始"));
        assertEquals("现在比分多少", AssistantController.visualQuestion("现在比分多少"));
    }
    @Test public void requestTimeoutIsReportedButExplicitCancellationStaysSilent() {
        java.io.InterruptedIOException timeout = new java.io.InterruptedIOException("timeout");
        assertEquals("timeout", AssistantGatewayClient.visualFailureCode(false, timeout));
        assertEquals("cancelled", AssistantGatewayClient.visualFailureCode(true, timeout));
        assertEquals("network_unavailable", AssistantGatewayClient.visualFailureCode(false, new IOException()));
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
