package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Local control contracts, no audio capture or network requests are started. */
@RunWith(AndroidJUnit4.class)
public class AssistantControlsInstrumentedTest {
    static final class Host implements AssistantController.Host {
        AssistantReply last; int spoken; int cancelled; boolean ready = true; boolean assistantSpeaking; String status = "";
        final List<String> audits = new ArrayList<>();
        public void speak(AssistantReply reply) { last = reply; spoken++; }
        public boolean speechReady() { return ready; }
        public void cancelSpeech() { cancelled++; }
        public boolean speaking() { return false; }
        public boolean assistantSpeaking() { return assistantSpeaking; }
        public long lastAlertAtMs() { return -1; }
        public String nearby() { return "当前观察不够新鲜，无法判断附近情况。"; }
        public void mark() { }
        public void status(String value) { status = value; }
        public void audit(String metadata) { audits.add(metadata); }
    }
    private static final String EXPIRED_FRAME_PROMPT = "画面返回太慢，旧画面已丢弃。";

    private static SharedPreferences preferences(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("assistant-control-test", Context.MODE_PRIVATE);
        preferences.edit().clear().putBoolean(AssistantSettings.VOICE, true)
            .putBoolean(AssistantSettings.AUDIO_CONSENT, true).putBoolean(AssistantSettings.VISION, true)
            .putBoolean(AssistantSettings.IMAGE_CONSENT, true).putString(AssistantSettings.ENDPOINT, "https://example.invalid")
            .putString(AssistantSettings.TOKEN, "test-token-placeholder-not-for-production").commit();
        return preferences;
    }

    private static AssistantSession session(AssistantController controller) throws Exception {
        Field field = AssistantController.class.getDeclaredField("session");
        field.setAccessible(true);
        return (AssistantSession) field.get(controller);
    }

    private static void markAwaitingAsrResult(AssistantController controller) throws Exception {
        Field field = AssistantController.class.getDeclaredField("awaitingFinal");
        field.setAccessible(true);
        field.setBoolean(controller, true);
    }

    private static FrameHistory frameHistory(AssistantController controller) throws Exception {
        Field field = AssistantController.class.getDeclaredField("frameHistory");
        field.setAccessible(true);
        return (FrameHistory) field.get(controller);
    }

    private static void addHistoryPair(FrameHistory history, long nowMs) {
        assertTrue(history.add(new FrameSnapshot(901, nowMs - 4_000, 1, 0, null)));
        assertTrue(history.add(new FrameSnapshot(902, nowMs - 2_000, 1, 0, null)));
    }

    private static FrameSnapshot staleHudFrame(long frameId, long capturedAtMs,
                                                long generation) {
        int width = 64, height = 36, stride = width * 4;
        return FrameSnapshot.copy(ByteBuffer.allocate(stride * height), width, height,
                stride, 1280, frameId, capturedAtMs, generation, 7, null);
    }

    private static JSONObject visualResult(AssistantSession session, long generation,
            String turn, long frameId, String answer) throws Exception {
        return new JSONObject().put("session_id", session.sessionId)
                .put("generation", generation).put("turn_id", turn)
                .put("frame_id", frameId).put("kind", "hud")
                .put("answer", answer).put("uncertain", false);
    }

    private static void installVisualTask(AssistantController controller, long id,
            String turn, FrameSnapshot frame, boolean proactive) throws Exception {
        Class<?> taskType = Class.forName(AssistantController.class.getName() + "$VisualTask");
        Constructor<?> constructor = taskType.getDeclaredConstructor(long.class, String.class,
                FrameSnapshot.class, List.class, boolean.class);
        constructor.setAccessible(true);
        Object task = constructor.newInstance(id, turn, frame, Collections.emptyList(), proactive);
        Field inFlight = AssistantController.class.getDeclaredField("inFlight");
        inFlight.setAccessible(true);
        inFlight.set(controller, task);
    }

    private static void installPendingVisualTask(AssistantController controller, long id,
            String turn, long generation) throws Exception {
        Class<?> taskType = Class.forName(AssistantController.class.getName() + "$VisualTask");
        Constructor<?> constructor = taskType.getDeclaredConstructor(long.class, String.class,
                long.class, FrameSnapshot.class, List.class, boolean.class);
        constructor.setAccessible(true);
        Object task = constructor.newInstance(id, turn, generation, null,
                Collections.emptyList(), false);
        Field inFlight = AssistantController.class.getDeclaredField("inFlight");
        inFlight.setAccessible(true);
        inFlight.set(controller, task);
    }

    private static void setLastReply(AssistantController controller, AssistantReply reply)
            throws Exception {
        Field lastReply = AssistantController.class.getDeclaredField("lastReply");
        lastReply.setAccessible(true);
        lastReply.set(controller, reply);
    }

    private static long countAudit(Host host, String marker) {
        return host.audits.stream().filter(value -> value.contains(marker)).count();
    }

    @Test public void speechStartReservesSixtySecondQuietWindowWithoutStartingCapture() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context);
        Host host = new Host();
        AssistantController controller = new AssistantController(context, "speech-quiet-test",
                new AssistantSettings(preferences), host);
        try {
            long before = SystemClock.elapsedRealtime();
            java.lang.reflect.Method started = AssistantController.class.getDeclaredMethod("voiceStarted");
            started.setAccessible(true);
            started.invoke(controller); // No start(): no microphone, ASR model or overlay.
            Field quiet = AssistantController.class.getDeclaredField("proactiveQuietUntilMs");
            quiet.setAccessible(true);
            assertTrue(quiet.getLong(controller) >= before + 60_000);
            assertEquals(1, host.cancelled);
            assertEquals(0, host.spoken);
        } finally { controller.close(); preferences.edit().clear().commit(); }
    }

    @Test public void manualReplyExtendsQuietWindowAndBlocksAutomaticFrame() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context);
        Host host = new Host();
        AssistantController controller = new AssistantController(context, "manual-quiet-test",
                new AssistantSettings(preferences), host);
        try {
            AssistantSession session = session(controller);
            String turn = session.newTurn();
            long capturedAt = SystemClock.elapsedRealtime();
            FrameSnapshot frame = staleHudFrame(91, capturedAt, session.generation());
            installVisualTask(controller, 91, turn, frame, false);
            controller.visualResult(91, visualResult(session, session.generation(), turn, 91,
                    "先跟前排推进。"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(1, host.spoken);
            Field proactive = AssistantController.class.getDeclaredField("proactiveEnabled");
            proactive.setAccessible(true);
            proactive.setBoolean(controller, true);
            long generation = session.generation();
            controller.offerFrame(ByteBuffer.allocate(64 * 36 * 4), 64, 36, 64 * 4,
                    92, SystemClock.elapsedRealtime());
            assertEquals("Quiet frames must not open an automatic turn", generation, session.generation());
            assertEquals(0, countAudit(host, "AssistantVisual event=REQUEST"));
            Field quiet = AssistantController.class.getDeclaredField("proactiveQuietUntilMs");
            quiet.setAccessible(true);
            assertTrue(quiet.getLong(controller) >= capturedAt + 60_000);
        } finally { controller.close(); preferences.edit().clear().commit(); }
    }

    @Test public void proactiveReplyHasSpokenAndPanelLabelsWithoutChangingFrameOwnership() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context);
        Host host = new Host();
        AssistantController controller = new AssistantController(context, "proactive-label-test",
                new AssistantSettings(preferences), host);
        try {
            AssistantSession session = session(controller);
            String turn = session.newTurn();
            long capturedAt = SystemClock.elapsedRealtime();
            FrameSnapshot frame = staleHudFrame(93, capturedAt, session.generation());
            installVisualTask(controller, 93, turn, frame, true);
            controller.visualResult(93, visualResult(session, session.generation(), turn, 93,
                    "先跟前排推进。"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(1, host.spoken);
            assertTrue(host.last.answer.startsWith("画面变化，"));
            assertTrue(host.last.proactive);
            assertEquals(capturedAt, host.last.capturedAtMs);
            Field history = AssistantController.class.getDeclaredField("history");
            history.setAccessible(true);
            assertTrue(history.get(controller).toString().contains("主动观察："));
        } finally { controller.close(); preferences.edit().clear().commit(); }
    }

    @Test public void finalSelectionQuestionPreservesIntentWithoutOpeningPanel() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context);
        Host host = new Host();
        AssistantController controller = new AssistantController(context, "selection-question-test",
                new AssistantSettings(preferences), host);
        try {
            AssistantSession session = session(controller);
            String turn = session.newCaptureTurn();
            markAwaitingAsrResult(controller);
            controller.onLocalAsrResult(session.generation(), turn, "这个画面我应该选哪个", 12);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Field pending = AssistantController.class.getDeclaredField("pendingQuestion");
            pending.setAccessible(true);
            assertEquals("这个画面我应该选哪个",
                    pending.get(controller));
            assertEquals(1, countAudit(host, "AssistantInteraction event=QUESTION proactive=false"));
            assertEquals(0, host.spoken);
            assertEquals("正在等待当前画面", host.status);
        } finally { controller.close(); preferences.edit().clear().commit(); }
    }

    @Test public void ordinaryChoiceFinalDoesNotRequestScreenshot() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context);
        Host host = new Host();
        AssistantController controller = new AssistantController(context, "ordinary-choice-test",
                new AssistantSettings(preferences), host);
        try {
            AssistantSession session = session(controller);
            String turn = session.newCaptureTurn();
            markAwaitingAsrResult(controller);
            controller.onLocalAsrResult(session.generation(), turn, "我选桑启", 9);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Field pending = AssistantController.class.getDeclaredField("pendingQuestion");
            pending.setAccessible(true);
            assertNull(pending.get(controller));
            assertEquals(0, countAudit(host, "AssistantInteraction event=QUESTION"));
            assertEquals(0, host.spoken);
            assertEquals("已听到，等待你的问题", host.status);
        } finally { controller.close(); preferences.edit().clear().commit(); }
    }

    @Test public void provisionalVadDoesNotCancelPendingVisualButStopsSpeech() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context); Host host = new Host();
        AssistantController controller = new AssistantController(context, "vad-pending-test", new AssistantSettings(preferences), host);
        try {
            AssistantSession session = session(controller); String turn = session.newTurn();
            installVisualTask(controller, 301, turn, staleHudFrame(301, SystemClock.elapsedRealtime(), session.generation()), false);
            java.lang.reflect.Method start = AssistantController.class.getDeclaredMethod("voiceStarted");
            start.setAccessible(true); start.invoke(controller);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Field task = AssistantController.class.getDeclaredField("inFlight"); task.setAccessible(true);
            assertNotNull("Provisional input must preserve the pending visual task", task.get(controller));
            assertTrue(session.owns(session.generation(), turn));
            assertEquals(1, host.cancelled);
            assertEquals(0, countAudit(host, "AssistantVisual event=DROPPED reason=cancelled"));
        } finally { controller.close(); preferences.edit().clear().commit(); }
    }

    @Test public void nonRequestFinalReleasesDeferredResponseWithOriginalFrameAge() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context); Host host = new Host();
        AssistantController controller = new AssistantController(context, "vad-defer-test", new AssistantSettings(preferences), host);
        try {
            AssistantSession session = session(controller); String turn = session.newTurn();
            long capturedAt = SystemClock.elapsedRealtime();
            installVisualTask(controller, 302, turn, staleHudFrame(302, capturedAt, session.generation()), false);
            String capture = session.newCaptureTurn(); markAwaitingAsrResult(controller);
            controller.visualResult(302, visualResult(session, session.generation(), turn, 302, "先跟前排推进。"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync(); assertEquals(0, host.spoken);
            assertEquals(1, countAudit(host, "AssistantVisual event=WAITING_FOR_INPUT"));
            controller.onLocalAsrResult(session.generation(), capture, "我选桑启", 20);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(1, host.spoken); assertEquals(capturedAt, host.last.capturedAtMs);
            assertEquals(turn, host.last.turnId);
        } finally { controller.close(); preferences.edit().clear().commit(); }
    }

    @Test public void newQuestionDiscardsDeferredAnswerAndHearingCheckStaysLocal() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context); Host host = new Host();
        AssistantController controller = new AssistantController(context, "vad-replace-test", new AssistantSettings(preferences), host);
        try {
            AssistantSession session = session(controller); String turn = session.newTurn();
            installVisualTask(controller, 303, turn, staleHudFrame(303, SystemClock.elapsedRealtime(), session.generation()), false);
            long oldGeneration = session.generation(); String capture = session.newCaptureTurn(); markAwaitingAsrResult(controller);
            controller.visualResult(303, visualResult(session, oldGeneration, turn, 303, "旧建议。"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            controller.onLocalAsrResult(oldGeneration, capture, "你好你好，你能听到我说话吗？", 20);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(1, host.spoken); assertEquals("local", host.last.kind);
            assertTrue(host.last.answer.startsWith("听得到"));
            assertFalse(session.owns(oldGeneration, turn));
            Field task = AssistantController.class.getDeclaredField("inFlight"); task.setAccessible(true); assertNull(task.get(controller));
            Field pending = AssistantController.class.getDeclaredField("pendingQuestion"); pending.setAccessible(true); assertNull(pending.get(controller));
            assertEquals(0, countAudit(host, "AssistantVisual event=REQUEST"));
        } finally { controller.close(); preferences.edit().clear().commit(); }
    }

    @Test public void speechAlreadySubmittedIsCancelledAndCannotResumeAfterNonRequest() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context); Host host = new Host(); host.assistantSpeaking = true;
        AssistantController controller = new AssistantController(context, "vad-playback-test", new AssistantSettings(preferences), host);
        try {
            AssistantSession session = session(controller); String turn = session.newTurn(); long generation = session.generation();
            AssistantReply previous = new AssistantReply(generation, turn, -1, SystemClock.elapsedRealtime(), "local", "旧答复。", false);
            java.lang.reflect.Method start = AssistantController.class.getDeclaredMethod("voiceStarted");
            start.setAccessible(true); start.invoke(controller);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(1, host.cancelled); assertFalse(controller.allows(previous));
            assertFalse(session.owns(generation, turn)); assertEquals(0, host.spoken);
        } finally { controller.close(); preferences.edit().clear().commit(); }
    }

    @Test public void expiredDeferredVisualNeverRefreshesFrameOrSpeaksOldAdvice() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context); Host host = new Host();
        AssistantController controller = new AssistantController(context, "vad-expiry-test", new AssistantSettings(preferences), host);
        try {
            AssistantSession session = session(controller); String turn = session.newTurn();
            long capturedAt = SystemClock.elapsedRealtime() - 5001;
            installVisualTask(controller, 304, turn, staleHudFrame(304, capturedAt, session.generation()), false);
            String capture = session.newCaptureTurn(); markAwaitingAsrResult(controller);
            controller.visualResult(304, visualResult(session, session.generation(), turn, 304, "旧建议不应播报。"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync(); assertEquals(0, host.spoken);
            controller.onLocalAsrResult(session.generation(), capture, "我选桑启", 20);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(1, host.spoken); assertEquals("local", host.last.kind);
            assertEquals(EXPIRED_FRAME_PROMPT, host.last.answer);
            assertEquals(1, countAudit(host, "AssistantVisual event=EXPIRED stage=result"));
        } finally { controller.close(); preferences.edit().clear().commit(); }
    }

    @Test public void captureResetClearsDeferredResultAndBlocksLateSpeech() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context); Host host = new Host();
        AssistantController controller = new AssistantController(context, "vad-reset-test", new AssistantSettings(preferences), host);
        try {
            AssistantSession session = session(controller); String turn = session.newTurn(); long generation = session.generation();
            installVisualTask(controller, 305, turn, staleHudFrame(305, SystemClock.elapsedRealtime(), generation), false);
            String capture = session.newCaptureTurn(); markAwaitingAsrResult(controller);
            controller.visualResult(305, visualResult(session, generation, turn, 305, "旧建议。"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            controller.captureInvalidated("capture_reset");
            controller.onLocalAsrResult(generation, capture, "我选桑启", 20);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Field deferred = AssistantController.class.getDeclaredField("deferredVisualResult"); deferred.setAccessible(true);
            assertNull(deferred.get(controller)); assertEquals(0, host.spoken);
            assertFalse(session.owns(generation, turn));
        } finally { controller.close(); preferences.edit().clear().commit(); }
    }

    @Test public void replacedOwnerCannotRegisterTransportCallAfterEncoding() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context);
        java.util.concurrent.atomic.AtomicReference<String> failure = new java.util.concurrent.atomic.AtomicReference<>();
        AssistantGatewayClient client = new AssistantGatewayClient(new AssistantSettings(preferences), new AssistantGatewayClient.Listener() {
            public void audioMessage(JSONObject message) { }
            public void audioUnavailable(String reason) { }
            public void visualResult(long id, JSONObject result) { fail("A stale request cannot reach the network"); }
            public void visualFailure(long id, String reason) { failure.set(reason); }
        });
        try {
            AssistantSession session = new AssistantSession("encoded-stale-test"); String oldTurn = session.newTurn();
            long now = SystemClock.elapsedRealtime(); FrameSnapshot frame = staleHudFrame(306, now, session.generation());
            session.newTurn();
            client.visual(306, session, oldTurn, frame, frame.jpeg(), "请读画面", false, now);
            assertEquals("cancelled", failure.get());
            Field active = AssistantGatewayClient.class.getDeclaredField("visual"); active.setAccessible(true); assertNull(active.get(client));
        } finally { client.close(); preferences.edit().clear().commit(); }
    }

    @Test public void manualReadScreenShowsActionLabelInsteadOfServerInstruction() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context);
        Host host = new Host();
        AssistantController controller = new AssistantController(context, "read-screen-label-test",
                new AssistantSettings(preferences), host);
        try {
            controller.readScreen();
            Field pending = AssistantController.class.getDeclaredField("pendingQuestion");
            pending.setAccessible(true);
            assertEquals("The detailed instruction remains the visual request payload",
                    AssistantController.SCREEN_QUESTION, pending.get(controller));
            Field history = AssistantController.class.getDeclaredField("history");
            history.setAccessible(true);
            String visibleHistory = history.get(controller).toString();
            assertTrue(visibleHistory.contains("你：读画面"));
            assertFalse("The internal instruction must never appear as user speech",
                    visibleHistory.contains(AssistantController.SCREEN_QUESTION));
        } finally { controller.close(); preferences.edit().clear().commit(); }
    }

    @Test public void frameCopyFailureWithNoSnapshotGivesManualPanelAndVoiceFeedback() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context);
        Host host = new Host();
        AssistantController controller = new AssistantController(context, "empty-frame-failure-test",
                new AssistantSettings(preferences), host);
        try {
            AssistantSession session = session(controller);
            String turn = session.newTurn();
            installPendingVisualTask(controller, 119, turn, session.generation());
            controller.visualFailure(119, "frame_unavailable");
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            assertEquals("A manual request without a copied frame still gets voice feedback",
                    1, host.spoken);
            assertEquals("local", host.last.kind);
            assertEquals("画面理解暂时不可用，请稍后再试。", host.last.answer);
            assertEquals(host.last.answer, host.status);
            assertEquals(1, countAudit(host,
                    "AssistantVisual event=MANUAL_FEEDBACK reason=frame_unavailable frameId=-1"));
        } finally { controller.close(); preferences.edit().clear().commit(); }
    }

    @Test public void manualTurnReusesRecentFramesButCaptureLifecycleInvalidationsClearThem() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context);
        Host host = new Host();
        AssistantController controller = new AssistantController(context, "frame-history-test",
                new AssistantSettings(preferences), host);
        try {
            FrameHistory history = frameHistory(controller);
            long now = SystemClock.elapsedRealtime();
            addHistoryPair(history, now);
            AssistantSession session = session(controller);
            long oldGeneration = session.generation();
            controller.question("帮我看下出装", false);
            assertTrue(session.generation() > oldGeneration);
            long primaryAt = SystemClock.elapsedRealtime();
            assertEquals("A new question turn can use both recent same-capture frames", 2,
                    history.context(primaryAt, new FrameSnapshot(
                            903, primaryAt, session.generation(), 0, null)).size());

            controller.thermal(FrameProcessingPolicy.Mode.HOT);
            assertEquals(0, history.size());

            controller.thermal(FrameProcessingPolicy.Mode.NORMAL);
            addHistoryPair(history, SystemClock.elapsedRealtime());
            controller.pause(true);
            assertEquals(0, history.size());

            controller.pause(false);
            addHistoryPair(history, SystemClock.elapsedRealtime());
            controller.captureInvalidated("capture_reset");
            assertEquals(0, history.size());

            addHistoryPair(history, SystemClock.elapsedRealtime());
            preferences.edit().putBoolean(AssistantSettings.IMAGE_CONSENT, false).commit();
            controller.settingsChanged(new AssistantSettings(preferences));
            assertEquals("Revoking image consent clears retained pixels immediately", 0, history.size());
        } finally { controller.close(); preferences.edit().clear().commit(); }
    }

    @Test public void legacyServerAudioCallbacksCannotOverrideLocalInputState()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context);
        Host host = new Host();
        AssistantController controller = new AssistantController(context, "input-status-test",
                new AssistantSettings(preferences), host);
        try {
            AssistantSession session = session(controller);
            controller.voiceInputStateChanged(VoiceInputSafetyPolicy.InputState.ROUTE_UNVERIFIED);
            controller.audioMessage(new JSONObject().put("type", "status")
                    .put("session_id", session.sessionId).put("generation", session.generation())
                    .put("turn_id", "reset-g" + session.generation()).put("reason", "ready"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertTrue(host.status.contains(VoiceInputSafetyPolicy.InputState.ROUTE_UNVERIFIED.status));
            assertFalse(host.status.contains("语音服务器已连接"));
            String turn = session.newTurn();
            controller.audioMessage(new JSONObject().put("type", "final")
                    .put("session_id", session.sessionId).put("generation", session.generation())
                    .put("turn_id", turn).put("text", "这个画面我应该选哪个"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            Field pending = AssistantController.class.getDeclaredField("pendingQuestion");
            pending.setAccessible(true);
            assertNull("A legacy server final must not enter the live assistant", pending.get(controller));
            controller.voiceInputStateChanged(VoiceInputSafetyPolicy.InputState.READY);
            controller.audioMessage(new JSONObject().put("type", "status")
                    .put("session_id", session.sessionId).put("generation", session.generation())
                    .put("turn_id", "reset-g" + session.generation()).put("reason", "ready"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals(VoiceInputSafetyPolicy.InputState.READY.status, host.status);
        } finally { controller.close(); preferences.edit().clear().commit(); }
    }

    @Test public void localModelReadyCannotClearBlockedInputOrPause() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            SharedPreferences preferences = preferences(context);
            Host host = new Host();
            AssistantController controller = new AssistantController(context, "local-ready-gates",
                    new AssistantSettings(preferences), host);
            try {
                controller.voiceInputStateChanged(VoiceInputSafetyPolicy.InputState.OTHER_RECORDING);
                controller.localAsrReady();
                assertEquals(VoiceInputSafetyPolicy.InputState.OTHER_RECORDING.status, host.status);
                controller.setVoicePaused(true);
                controller.localAsrReady();
                assertTrue(host.status.startsWith(VoiceInputSafetyPolicy.InputState.PAUSED.status));
                assertFalse(host.status.contains("已就绪"));
                controller.thermal(FrameProcessingPolicy.Mode.HOT);
                controller.localAsrReady();
                assertTrue(host.status.contains("温度较高"));
                assertFalse(host.status.contains("已就绪"));
            } finally { controller.close(); preferences.edit().clear().commit(); }
        });
    }

    @Test public void localVoiceDoesNotRequireVisualGatewayConfiguration() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context);
        try {
            preferences.edit().putBoolean(AssistantSettings.VISION, false)
                    .putString(AssistantSettings.ENDPOINT, "")
                    .putString(AssistantSettings.TOKEN, "").commit();
            AssistantSettings localVoice = new AssistantSettings(preferences);
            assertTrue(localVoice.voice);
            assertFalse(localVoice.configured());
            assertTrue(localVoice.enabled());

            preferences.edit().putBoolean(AssistantSettings.VISION, true).commit();
            AssistantSettings visionWithoutGateway = new AssistantSettings(preferences);
            assertFalse(visionWithoutGateway.configured());
            assertTrue("Voice remains locally available while visual access is unconfigured",
                    visionWithoutGateway.enabled());
        } finally { preferences.edit().clear().commit(); }
    }

    @Test public void expiredManualVisualResultExplainsDiscardedFrameAndProactiveStaysSilent()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context);
        Host host = new Host();
        AssistantController controller = new AssistantController(context, "expired-result-test",
                new AssistantSettings(preferences), host);
        try {
            AssistantSession session = session(controller);
            long generation = session.generation();
            String manualTurn = session.newTurn();
            long capturedAt = SystemClock.elapsedRealtime() - 6001;
            FrameSnapshot manualFrame = staleHudFrame(71, capturedAt, generation);
            assertNotNull(manualFrame);
            installVisualTask(controller, 71, manualTurn, manualFrame, false);
            controller.visualResult(71, visualResult(session, generation, manualTurn, 71,
                    "比分 1:0"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            assertEquals(1, host.spoken);
            assertEquals("local", host.last.kind);
            assertEquals(EXPIRED_FRAME_PROMPT, host.last.answer);
            assertFalse("An expired score must never be announced", host.last.answer.contains("1:0"));
            assertEquals(1, countAudit(host, "AssistantVisual event=EXPIRED stage=result frameId=71"));

            String proactiveTurn = session.newTurn();
            long proactiveCapturedAt = SystemClock.elapsedRealtime() - 6001;
            FrameSnapshot proactiveFrame = staleHudFrame(72, proactiveCapturedAt,
                    session.generation());
            assertNotNull(proactiveFrame);
            installVisualTask(controller, 72, proactiveTurn, proactiveFrame, true);
            controller.visualResult(72, visualResult(session, session.generation(), proactiveTurn,
                    72, "自动比分 2:0"));
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            assertEquals("Expired proactive results remain silent", 1, host.spoken);
            assertEquals(EXPIRED_FRAME_PROMPT, host.last.answer);
            assertEquals(1, countAudit(host, "AssistantVisual event=EXPIRED stage=result"));
        } finally { controller.close(); preferences.edit().clear().commit(); }
    }

    @Test public void expiredQueuedManualPlaybackExplainsOnceAndIgnoresOldTurnAndProactiveReplies()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = preferences(context);
        Host host = new Host();
        AssistantController controller = new AssistantController(context, "expired-playback-test",
                new AssistantSettings(preferences), host);
        try {
            AssistantSession session = session(controller);
            long generation = session.generation();
            String manualTurn = session.newTurn();
            long capturedAt = SystemClock.elapsedRealtime() - 6001;
            AssistantReply expiredManual = new AssistantReply(generation, manualTurn, 81,
                    capturedAt, "hud", "过期比分 3:0", false, false);
            setLastReply(controller, expiredManual);
            String cueId = session.sessionId + ":assistant:" + manualTurn;
            long expiredAt = SystemClock.elapsedRealtime();
            controller.assistantSpeechExpired(session.sessionId, cueId, expiredAt);
            controller.assistantSpeechExpired(session.sessionId, cueId, expiredAt);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();

            assertEquals("Duplicate expiration callbacks yield one explanation", 1, host.spoken);
            assertEquals("local", host.last.kind);
            assertEquals(EXPIRED_FRAME_PROMPT, host.last.answer);
            assertFalse("The old score must not be replayed", host.last.answer.contains("3:0"));
            assertEquals(1, countAudit(host, "AssistantVisual event=EXPIRED stage=playback"));

            session.invalidate();
            session.newTurn();
            setLastReply(controller, expiredManual);
            controller.assistantSpeechExpired(session.sessionId, cueId,
                    SystemClock.elapsedRealtime());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals("An interrupted old turn must not produce a delayed explanation",
                    1, host.spoken);

            String proactiveTurn = session.newTurn();
            AssistantReply expiredProactive = new AssistantReply(session.generation(),
                    proactiveTurn, 82, capturedAt, "hud", "自动过期比分 4:0", false, true);
            setLastReply(controller, expiredProactive);
            String proactiveCueId = session.sessionId + ":assistant:" + proactiveTurn;
            controller.assistantSpeechExpired(session.sessionId, proactiveCueId,
                    SystemClock.elapsedRealtime());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertEquals("Expired proactive playback remains silent", 1, host.spoken);
            assertEquals(1, countAudit(host, "AssistantVisual event=EXPIRED stage=playback"));
        } finally { controller.close(); preferences.edit().clear().commit(); }
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
    @Test public void identityAndGreetingReplyLocallyWithoutVisualService() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            SharedPreferences preferences = preferences(context);
            preferences.edit().putBoolean(AssistantSettings.VISION, false).commit();
            Host host = new Host();
            AssistantController controller = new AssistantController(context, "local-greeting",
                    new AssistantSettings(preferences), host);
            try {
                controller.question("你是谁", false);
                assertEquals(1, host.spoken);
                assertEquals("local", host.last.kind);
                assertTrue(host.last.answer.contains("听野"));
                controller.question("你好", false);
                assertEquals(2, host.spoken);
                assertTrue(host.last.answer.startsWith("我在"));
            } finally { controller.close(); preferences.edit().clear().commit(); }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    @Test public void microphoneSafetyTransitionsDiscardOldRepliesAndKeepVoicePauseControl() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            SharedPreferences preferences = preferences(context); Host host = new Host();
            AssistantController controller = new AssistantController(context, "synthetic-safety", new AssistantSettings(preferences), host);
            try {
                controller.question("附近情况", false);
                AssistantReply beforeConflict = host.last;
                assertNotNull(beforeConflict); assertTrue(controller.allows(beforeConflict));

                controller.voiceInputStateChanged(VoiceInputSafetyPolicy.InputState.OTHER_RECORDING);
                assertFalse(controller.allows(beforeConflict));
                int afterConflictCancel = host.cancelled;
                assertTrue(afterConflictCancel > 0);

                controller.voiceInputStateChanged(VoiceInputSafetyPolicy.InputState.READY);
                assertFalse(controller.allows(beforeConflict));
                controller.question("附近情况", false);
                AssistantReply afterRecovery = host.last;
                assertNotSame(beforeConflict, afterRecovery); assertTrue(controller.allows(afterRecovery));

                controller.setVoicePaused(true);
                assertFalse(controller.allows(afterRecovery));
                controller.setVoicePaused(false);
                controller.question("附近情况", false);
                assertTrue(controller.allows(host.last));
                assertTrue(host.cancelled > afterConflictCancel);
            } finally { controller.close(); preferences.edit().clear().commit(); }
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
}
