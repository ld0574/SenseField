package com.openkhub.sensefield;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Opt-in real-device flow. It uses synthetic assets and never starts live capture. */
@RunWith(AndroidJUnit4.class)
public final class AssistantLivePipelineInstrumentedTest {
    private static final String CONFIG_NAME = "assistant-live-pipeline-test.json";
    private static final String SUMMARY_NAME = "assistant-live-pipeline-summary.json";
    private static final String GREETING_SUMMARY_NAME = "assistant-live-pipeline-greeting-summary.json";
    private static final String TEST_PREFERENCES = "assistant_live_pipeline_test";
    private static final long TTS_READY_TIMEOUT_MS = 10_000;
    private static final long ASR_READY_TIMEOUT_MS = 60_000;
    private static final long ASR_RESULT_TIMEOUT_MS = 30_000;
    private static final long CONTROLLER_QUESTION_TIMEOUT_MS = 5_000;
    private static final long GATEWAY_REQUEST_TIMEOUT_MS = 12_000;
    private static final long GATEWAY_RESULT_TIMEOUT_MS = 18_000;
    private static final long AUDIO_FIRST_WRITE_TIMEOUT_MS = 20_000;
    private static final long AUDIO_FINISH_TIMEOUT_MS = 40_000;

    private Context target;
    private Context testContext;
    private File configFile;
    private File summaryFile;
    private File greetingSummaryFile;
    private String endpoint;
    private String token;
    private SharedPreferences testPreferences;
    private CuePlayer cuePlayer;
    private CueDispatcher cueDispatcher;
    private AssistantController controller;
    private OnDeviceAsr asr;
    private RuntimeEvidence evidence;
    private TestHost host;

    @Before public void readOptInConfiguration() throws Exception {
        target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        testContext = InstrumentationRegistry.getInstrumentation().getContext();
        configFile = new File(target.getFilesDir(), CONFIG_NAME);
        summaryFile = new File(target.getFilesDir(), SUMMARY_NAME);
        greetingSummaryFile = new File(target.getFilesDir(), GREETING_SUMMARY_NAME);
        assumeTrue("Opt-in file assistant-live-pipeline-test.json is absent",
                configFile.isFile());
        JSONObject config = new JSONObject(new String(readBounded(configFile, 8192),
                StandardCharsets.UTF_8));
        endpoint = config.optString("endpoint", "").trim().replaceAll("/+$", "");
        token = config.optString("token", "").trim();
        if (!AssistantSettings.validEndpoint(endpoint) || !endpoint.startsWith("https://")
                || token.length() < 24 || token.length() > 256) {
            throw new AssertionError("opt_in_config: endpoint or token is invalid");
        }
        testPreferences = target.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE);
    }

    @After public void releaseRuntime() throws Exception {
        if (asr != null) asr.close();
        if (controller != null) {
            controller.close();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        }
        if (cueDispatcher != null) cueDispatcher.close();
        if (cuePlayer != null) cuePlayer.close();
        if (testPreferences != null) testPreferences.edit().clear().commit();
    }

    @Test public void syntheticScoreFlowsFromLocalAsrThroughLiveVisionToAudioTrack()
            throws Exception {
        long testStartedAt = SystemClock.elapsedRealtime();
        String stage = "runtime_configuration";
        JSONObject summary = new JSONObject();
        summary.put("test", "synthetic_score_live_pipeline");
        summary.put("started_elapsed_realtime_ms", testStartedAt);
        summary.put("opt_in_config_file", CONFIG_NAME);
        summary.put("frame_width", 640);
        summary.put("frame_height", 360);
        summary.put("frame_description", "王者荣耀 合成测试 / 比分 3 : 2 / 红队 3 / 蓝队 2");
        summary.put("audio_output_evidence",
                "CuePlayer first positive AudioTrack PCM write callback; this is software output evidence, not an acoustic measurement.");
        try {
            // The real game already renders its screen before the user asks. Build the
            // synthetic fixture before input, rather than charging font/Canvas creation
            // to the snapshot age and reply latency. Encoding/copying still use production.
            ByteBuffer scorePixels = scoreboardRgba();
            summary.put("fixture_pixels_prepared_before_input", true);
            testPreferences.edit().clear()
                    .putBoolean(AssistantSettings.VOICE, true)
                    .putBoolean(AssistantSettings.AUDIO_CONSENT, true)
                    .putBoolean(AssistantSettings.VISION, true)
                    .putBoolean(AssistantSettings.IMAGE_CONSENT, true)
                    .putBoolean(AssistantSettings.PROACTIVE, false)
                    .putBoolean(AssistantSettings.CUSTOM_SERVICE, true).putString(AssistantSettings.ENDPOINT, endpoint)
                    .putString(AssistantSettings.TOKEN, token)
                    .putBoolean("live_test_speech_channel", true)
                    .commit();
            AssistantSettings settings = new AssistantSettings(testPreferences);
            require(settings.voice && settings.vision && settings.configured(), stage,
                    "isolated test preferences did not enable voice and vision");

            evidence = new RuntimeEvidence();
            cuePlayer = new CuePlayer(target);
            cueDispatcher = new CueDispatcher(cuePlayer, new LiveAssistantSpeechPolicy(settings,
                    testPreferences), evidence, SystemClock::elapsedRealtime);
            host = new TestHost(cuePlayer, cueDispatcher, evidence);
            controller = new AssistantController(target,
                    "assistant-live-pipeline-" + UUID.randomUUID(), settings, host);
            host.controller.set(controller);
            summary.put("runtime_created_elapsed_realtime_ms", SystemClock.elapsedRealtime());

            stage = "offline_tts_ready";
            require(awaitAssistantTts(cuePlayer, TTS_READY_TIMEOUT_MS), stage,
                    "offline Chinese assistant TTS did not become ready within 10 seconds");
            summary.put("offline_tts_ready_elapsed_realtime_ms", SystemClock.elapsedRealtime());

            stage = "asr_initialization";
            CountDownLatch asrReady = new CountDownLatch(1);
            CountDownLatch asrFinished = new CountDownLatch(1);
            AtomicReference<String> asrFailure = new AtomicReference<>();
            AtomicReference<String> recognizedQuestion = new AtomicReference<>();
            AtomicReference<String> captureTurn = new AtomicReference<>();
            AtomicLong asrInferenceMs = new AtomicLong(-1);
            AtomicLong asrInputStartedAt = new AtomicLong(-1);
            AtomicLong asrResultAt = new AtomicLong(-1);
            AssistantSession session = session(controller);
            long generation = session.generation();
            String inputTurn = session.newCaptureTurn();
            captureTurn.set(inputTurn);
            setAwaitingFinal(controller, true);
            asr = new OnDeviceAsr(target, new OnDeviceAsr.Listener() {
                @Override public void ready() { asrReady.countDown(); }
                @Override public void result(long returnedGeneration, String returnedTurn,
                        String text, long inferenceMs) {
                    recognizedQuestion.set(text == null ? "" : text.trim());
                    asrInferenceMs.set(inferenceMs);
                    asrResultAt.set(SystemClock.elapsedRealtime());
                    if (returnedGeneration != generation || !inputTurn.equals(returnedTurn)) {
                        asrFailure.set("asr_ownership_mismatch");
                    }
                    controller.onLocalAsrResult(returnedGeneration, returnedTurn, text,
                            inferenceMs);
                    asrFinished.countDown();
                }
                @Override public void unavailable(String reason) {
                    asrFailure.set("local_asr_unavailable");
                    asrReady.countDown();
                    asrFinished.countDown();
                }
                @Override public void dropped(String reason) {
                    asrFailure.set("local_asr_dropped");
                    asrFinished.countDown();
                }
            });
            asr.start();
            require(asrReady.await(ASR_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS), stage,
                    "on-device ASR initialization exceeded 60 seconds");
            require(asrFailure.get() == null && asr.ready(), stage,
                    "on-device ASR did not initialize");

            stage = "synthetic_asr_result";
            byte[] pcmBytes;
            try (InputStream source = testContext.getAssets().open("asr-smoke-score.pcm")) {
                pcmBytes = readBounded(source, AsrUtteranceBuffer.MAX_SAMPLES * 2);
            }
            require(pcmBytes.length > 0 && (pcmBytes.length & 1) == 0, stage,
                    "synthetic score PCM is empty or malformed");
            short[] pcm = new short[pcmBytes.length / 2];
            for (int i = 0; i < pcm.length; i++) {
                pcm[i] = (short) ((pcmBytes[i * 2] & 0xff) | (pcmBytes[i * 2 + 1] << 8));
            }
            Arrays.fill(pcmBytes, (byte) 0);
            require(asr.begin(generation, inputTurn), stage, "ASR rejected the capture turn");
            asrInputStartedAt.set(SystemClock.elapsedRealtime());
            asr.accept(pcm);
            Arrays.fill(pcm, (short) 0);
            require(asr.finish(), stage, "ASR rejected the synthetic utterance");
            require(asrFinished.await(ASR_RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS), stage,
                    "synthetic score ASR result exceeded 30 seconds");
            require(asrFailure.get() == null, stage, "synthetic score ASR failed");
            String questionText = recognizedQuestion.get();
            require(questionText != null && questionText.contains("比分")
                            && AssistantController.isRequest(questionText), stage,
                    "synthetic score question was not recognized as an actionable request");
            summary.put("asr_generation", generation);
            summary.put("asr_capture_turn", captureTurn.get());
            summary.put("asr_question", questionText);
            summary.put("asr_inference_ms", asrInferenceMs.get());
            summary.put("asr_wall_to_result_ms", asrResultAt.get() - asrInputStartedAt.get());
            summary.put("asr_result_elapsed_realtime_ms", asrResultAt.get());

            stage = "controller_question_acceptance";
            require(evidence.questionSeen.await(CONTROLLER_QUESTION_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS), stage,
                    "Controller did not accept the local ASR question");
            String controllerQuestion = awaitPendingQuestion(controller, 2_000);
            require(controllerQuestion != null && !controllerQuestion.isEmpty(), stage,
                    "Controller did not retain a visual question");
            summary.put("visual_question", controllerQuestion);

            stage = "manual_score_frame_submission";
            SystemClock.sleep(300);
            long frameCapturedAt = SystemClock.elapsedRealtime();
            controller.offerFrame(scorePixels, 640, 360, 640 * 4, 1, frameCapturedAt);
            require(evidence.visualRequest.await(GATEWAY_REQUEST_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS), stage,
                    "Controller did not submit the single manual screenshot request");
            summary.put("frame_captured_elapsed_realtime_ms", frameCapturedAt);

            stage = "live_vision_result";
            require(evidence.visualCallback.await(GATEWAY_RESULT_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS), stage,
                    "Live visual request did not produce a result callback");
            require(evidence.replySeen.await(3, TimeUnit.SECONDS), stage,
                    "Controller did not deliver its result or unknown-result feedback");
            AssistantReply reply = evidence.firstReply.get();
            require(reply != null, stage, "Controller did not deliver a visual reply");
            summary.put("visual_request_count", evidence.visualRequestCount.get());
            summary.put("visual_request_elapsed_realtime_ms", evidence.visualRequestAt.get());
            summary.put("visual_callback_elapsed_realtime_ms", evidence.visualCallbackAt.get());
            summary.put("visual_request_to_callback_ms",
                    evidence.visualRequestAt.get() >= 0 && evidence.visualCallbackAt.get() >= 0
                            ? evidence.visualCallbackAt.get() - evidence.visualRequestAt.get() : -1);
            summary.put("assistant_reply_kind", reply.kind);
            summary.put("assistant_reply_text", reply.answer);
            if (!"local".equals(reply.kind)) {
                summary.put("visual_kind", reply.kind);
                summary.put("visual_answer", reply.answer);
                summary.put("visual_frame_id", reply.frameId);
            }
            summary.put("visual_generation", reply.generation);
            summary.put("visual_turn", reply.turnId);
            require(evidence.visualRequestCount.get() == 1, stage,
                    "the pipeline must issue exactly one visual request");
            require("hud".equals(reply.kind) || "ui_text".equals(reply.kind), stage,
                    "live model returned no grounded scoreboard category");
            require(answerContainsScore(reply.answer), stage,
                    "live model answer did not identify the displayed 3 to 2 score");

            stage = "assistant_cue_submission";
            require(evidence.speechSubmitted.await(5, TimeUnit.SECONDS), stage,
                    "Controller did not submit the validated answer through CueDispatcher");
            require(evidence.dispatchAcceptedChannels.get() == CueRequest.CHANNEL_SPEECH,
                    stage, "CueDispatcher did not accept assistant speech");
            summary.put("speech_submit_elapsed_realtime_ms", evidence.speechSubmittedAt.get());
            summary.put("cue_dispatch_outcome", evidence.dispatchOutcome.get());
            summary.put("cue_dispatch_accepted_channels", evidence.dispatchAcceptedChannels.get());

            stage = "audio_track_first_write";
            require(evidence.firstAudioWrite.await(AUDIO_FIRST_WRITE_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS), stage,
                    "CuePlayer did not report a positive AudioTrack PCM write within 20 seconds");
            summary.put("audio_track_first_pcm_write_elapsed_realtime_ms",
                    evidence.firstAudioWriteAt.get());
            summary.put("tts_to_first_audio_write_ms",
                    evidence.firstAudioWriteAt.get() - evidence.speechSubmittedAt.get());

            stage = "assistant_speech_completion";
            require(evidence.speechFinished.await(AUDIO_FINISH_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS), stage,
                    "assistant speech did not finish within 40 seconds after first output");
            summary.put("speech_finished_elapsed_realtime_ms", evidence.speechFinishedAt.get());
            summary.put("speech_finish_result", evidence.speechFinishResult.get());
            require("COMPLETED".equals(evidence.speechFinishResult.get()), stage,
                    "CuePlayer did not complete assistant audio playback");
            summary.put("outcome", "passed");
            summary.put("completed_stage", stage);
        } catch (Exception | AssertionError failure) {
            summary.put("outcome", "failed");
            summary.put("failed_stage", stage);
            summary.put("failure_class", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            summary.put("ended_elapsed_realtime_ms", SystemClock.elapsedRealtime());
            summary.put("total_elapsed_ms", SystemClock.elapsedRealtime() - testStartedAt);
            if (evidence != null) {
                summary.put("visual_request_count", evidence.visualRequestCount.get());
                summary.put("visual_request_elapsed_realtime_ms", evidence.visualRequestAt.get());
                summary.put("visual_callback_elapsed_realtime_ms", evidence.visualCallbackAt.get());
                summary.put("visual_request_to_callback_ms",
                        evidence.visualRequestAt.get() >= 0 && evidence.visualCallbackAt.get() >= 0
                                ? evidence.visualCallbackAt.get() - evidence.visualRequestAt.get() : -1);
                summary.put("speech_submit_elapsed_realtime_ms", evidence.speechSubmittedAt.get());
                summary.put("tts_to_first_audio_write_ms",
                        evidence.firstAudioWriteAt.get() >= 0 && evidence.speechSubmittedAt.get() >= 0
                                ? evidence.firstAudioWriteAt.get() - evidence.speechSubmittedAt.get() : -1);
                summary.put("audio_track_first_pcm_write_elapsed_realtime_ms",
                        evidence.firstAudioWriteAt.get());
                summary.put("speech_finished_elapsed_realtime_ms", evidence.speechFinishedAt.get());
                summary.put("cue_dispatch_outcome", evidence.dispatchOutcome.get());
                summary.put("cue_dispatch_accepted_channels",
                        evidence.dispatchAcceptedChannels.get());
                summary.put("cue_suppressed_by_freshness_guard", evidence.suppressedReplies.get());
                if (evidence.firstReply.get() != null) {
                    summary.put("assistant_reply_kind", evidence.firstReply.get().kind);
                    summary.put("assistant_reply_text", evidence.firstReply.get().answer);
                    if (!"local".equals(evidence.firstReply.get().kind)) {
                        summary.put("visual_kind", evidence.firstReply.get().kind);
                        summary.put("visual_answer", evidence.firstReply.get().answer);
                    } else {
                        summary.put("assistant_fallback", evidence.firstReply.get().answer);
                    }
                }
            }
            writeSummary(summaryFile, summary);
            sendSummaryStatus(summary);
        }
    }

    @Test public void localGreetingUsesRealOfflineTtsWithoutVisualNetworkRequest()
            throws Exception {
        long startedAt = SystemClock.elapsedRealtime();
        String stage = "runtime_configuration";
        JSONObject summary = new JSONObject();
        summary.put("test", "local_greeting_tts");
        summary.put("started_elapsed_realtime_ms", startedAt);
        summary.put("audio_output_evidence",
                "CuePlayer first positive AudioTrack PCM write callback; this is software output evidence, not an acoustic measurement.");
        try {
            testPreferences.edit().clear()
                    .putBoolean(AssistantSettings.VOICE, true)
                    .putBoolean(AssistantSettings.AUDIO_CONSENT, true)
                    .putBoolean(AssistantSettings.VISION, false)
                    .putBoolean(AssistantSettings.IMAGE_CONSENT, false)
                    .putBoolean("live_test_speech_channel", true)
                    .commit();
            AssistantSettings settings = new AssistantSettings(testPreferences);
            evidence = new RuntimeEvidence();
            cuePlayer = new CuePlayer(target);
            cueDispatcher = new CueDispatcher(cuePlayer, new LiveAssistantSpeechPolicy(settings,
                    testPreferences), evidence, SystemClock::elapsedRealtime);
            host = new TestHost(cuePlayer, cueDispatcher, evidence);
            controller = new AssistantController(target, "assistant-live-greeting-" + UUID.randomUUID(),
                    settings, host);
            host.controller.set(controller);

            stage = "offline_tts_ready";
            require(awaitAssistantTts(cuePlayer, TTS_READY_TIMEOUT_MS), stage,
                    "offline Chinese assistant TTS did not become ready within 10 seconds");
            stage = "local_greeting_reply";
            controller.question("你好", false);
            require(evidence.replySeen.await(5, TimeUnit.SECONDS), stage,
                    "Controller did not reply to the local greeting");
            AssistantReply reply = evidence.firstReply.get();
            assertNotNull(reply);
            summary.put("greeting_answer", reply.answer);
            require("local".equals(reply.kind) && reply.answer.contains("我在"), stage,
                    "greeting was not answered locally");

            stage = "audio_track_first_write";
            require(evidence.firstAudioWrite.await(AUDIO_FIRST_WRITE_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS), stage,
                    "CuePlayer did not report a positive AudioTrack PCM write within 20 seconds");
            summary.put("audio_track_first_pcm_write_elapsed_realtime_ms",
                    evidence.firstAudioWriteAt.get());
            summary.put("tts_to_first_audio_write_ms",
                    evidence.firstAudioWriteAt.get() - evidence.speechSubmittedAt.get());
            summary.put("visual_request_count", evidence.visualRequestCount.get());
            require(evidence.visualRequestCount.get() == 0, stage,
                    "local greeting unexpectedly opened a visual network request");
            summary.put("outcome", "passed");
            summary.put("completed_stage", stage);
        } catch (Exception | AssertionError failure) {
            summary.put("outcome", "failed");
            summary.put("failed_stage", stage);
            summary.put("failure_class", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            summary.put("ended_elapsed_realtime_ms", SystemClock.elapsedRealtime());
            summary.put("total_elapsed_ms", SystemClock.elapsedRealtime() - startedAt);
            if (evidence != null) {
                summary.put("audio_track_first_pcm_write_elapsed_realtime_ms",
                        evidence.firstAudioWriteAt.get());
                summary.put("speech_finished_elapsed_realtime_ms", evidence.speechFinishedAt.get());
                summary.put("visual_request_count", evidence.visualRequestCount.get());
                if (evidence.firstReply.get() != null)
                    summary.put("greeting_answer", evidence.firstReply.get().answer);
            }
            writeSummary(greetingSummaryFile, summary);
            sendSummaryStatus(summary);
        }
    }

    private static final class RuntimeEvidence implements CueDispatcher.Listener {
        final CountDownLatch questionSeen = new CountDownLatch(1);
        final CountDownLatch visualRequest = new CountDownLatch(1);
        final CountDownLatch visualCallback = new CountDownLatch(1);
        final CountDownLatch replySeen = new CountDownLatch(1);
        final CountDownLatch speechSubmitted = new CountDownLatch(1);
        final CountDownLatch firstAudioWrite = new CountDownLatch(1);
        final CountDownLatch speechFinished = new CountDownLatch(1);
        final AtomicInteger visualRequestCount = new AtomicInteger();
        final AtomicInteger dispatchAcceptedChannels = new AtomicInteger();
        final AtomicInteger suppressedReplies = new AtomicInteger();
        final AtomicLong visualRequestAt = new AtomicLong(-1);
        final AtomicLong visualCallbackAt = new AtomicLong(-1);
        final AtomicLong speechSubmittedAt = new AtomicLong(-1);
        final AtomicLong firstAudioWriteAt = new AtomicLong(-1);
        final AtomicLong speechFinishedAt = new AtomicLong(-1);
        final AtomicReference<String> dispatchOutcome = new AtomicReference<>("not_submitted");
        final AtomicReference<String> speechFinishResult = new AtomicReference<>("pending");
        final AtomicReference<AssistantReply> firstReply = new AtomicReference<>();

        @Override public void onDispatch(CueRequest request, CueDispatcher.DispatchResult result) {
            dispatchAcceptedChannels.set(result.acceptedChannels);
            dispatchOutcome.set(result.outcome + ":" + result.reason);
        }

        @Override public void onPlayback(CueRequest request, String channel, long atMs,
                String result) {
            if (request.category != CueRequest.Category.ASSISTANT || !"SPEECH".equals(channel)) return;
            if ("STARTED".equals(result)) {
                firstAudioWriteAt.compareAndSet(-1, atMs);
                firstAudioWrite.countDown();
            } else if ("COMPLETED".equals(result) || "FAILED".equals(result)
                    || "EXPIRED".equals(result) || "UNAVAILABLE".equals(result)) {
                speechFinishResult.set(result);
                speechFinishedAt.compareAndSet(-1, atMs);
                speechFinished.countDown();
            }
        }

        void audit(String metadata) {
            long now = SystemClock.elapsedRealtime();
            if (metadata.contains("AssistantInteraction event=QUESTION proactive=false"))
                questionSeen.countDown();
            if (metadata.contains("AssistantVisual event=REQUEST ")) {
                visualRequestCount.incrementAndGet();
                visualRequestAt.compareAndSet(-1, now);
                visualRequest.countDown();
            }
            if (metadata.contains("AssistantVisual event=RESULT ")
                    || metadata.contains("AssistantVisual event=RESULT_UNKNOWN ")
                    || metadata.contains("AssistantVisual event=EXPIRED ")
                    || metadata.contains("AssistantVisual event=FAILED ")) {
                visualCallbackAt.compareAndSet(-1, now);
                visualCallback.countDown();
            }
        }
    }

    private static final class TestHost implements AssistantController.Host {
        final CuePlayer player;
        final CueDispatcher dispatcher;
        final RuntimeEvidence evidence;
        final AtomicReference<AssistantController> controller = new AtomicReference<>();
        volatile String status = "";

        TestHost(CuePlayer player, CueDispatcher dispatcher, RuntimeEvidence evidence) {
            this.player = player;
            this.dispatcher = dispatcher;
            this.evidence = evidence;
        }

        @Override public void speak(AssistantReply reply) {
            evidence.firstReply.compareAndSet(null, reply);
            evidence.replySeen.countDown();
            AssistantController current = controller.get();
            if (current == null || !current.allows(reply)) {
                evidence.suppressedReplies.incrementAndGet();
                return;
            }
            long now = SystemClock.elapsedRealtime();
            evidence.speechSubmittedAt.compareAndSet(-1, now);
            evidence.speechSubmitted.countDown();
            String cueId = "assistant-live:" + reply.turnId;
            CueRequest request = new CueRequest("assistant-live-pipeline", cueId, cueId,
                    "ASSISTANT", CueRequest.Category.ASSISTANT, 20, now,
                    reply.expiresAtMs(), CueRequest.CHANNEL_SPEECH, 0, 0, 0,
                    reply.answer, Float.NaN, Float.NaN, 0f, -1,
                    () -> controller.get() == current && current.allows(reply));
            dispatcher.submit(request);
        }

        @Override public boolean speechReady() { return player.assistantSpeechReady(); }
        @Override public void cancelSpeech() { dispatcher.cancelAssistantSpeech(); }
        @Override public boolean speaking() { return dispatcher.isSpeaking(); }
        @Override public boolean assistantSpeaking() { return dispatcher.hasAssistantSpeech(); }
        @Override public long lastAlertAtMs() { return dispatcher.recentAlertAtMs(); }
        @Override public String nearby() { return "当前观察不够新鲜，无法判断附近情况。"; }
        @Override public void mark() { }
        @Override public void status(String value) { status = value; }
        @Override public void audit(String metadata) { evidence.audit(metadata); }
    }

    /** Isolated assistant-speech policy; CueSettings itself reads the game's preferences. */
    private static final class LiveAssistantSpeechPolicy implements CueDispatcher.Policy {
        private final AssistantSettings settings;
        private final SharedPreferences preferences;

        LiveAssistantSpeechPolicy(AssistantSettings settings, SharedPreferences preferences) {
            this.settings = settings;
            this.preferences = preferences;
        }

        @Override public boolean categoryEnabled(CueRequest.Category category) {
            return category == CueRequest.Category.ASSISTANT && settings.voice;
        }

        @Override public int enabledChannels() {
            return preferences.getBoolean("live_test_speech_channel", false)
                    ? CueRequest.CHANNEL_SPEECH : 0;
        }

        @Override public int enabledChannels(CueRequest.Category category) {
            return CueSettings.channelsForCategory(enabledChannels(), CueSettings.PRESET_STANDARD,
                    category);
        }

        @Override public long dedupeWindowMs(CueRequest.Category category) { return 500; }
    }

    private static boolean awaitAssistantTts(CuePlayer player, long timeoutMs)
            throws InterruptedException {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            if (player.assistantSpeechReady()) return true;
            SystemClock.sleep(100);
        } while (SystemClock.elapsedRealtime() < deadline);
        return player.assistantSpeechReady();
    }

    private static boolean answerContainsScore(String answer) {
        if (answer == null) return false;
        String compact = answer.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        return (compact.contains("3") && compact.contains("2"))
                || (compact.contains("三") && compact.contains("二"));
    }

    private static void require(boolean condition, String stage, String message) {
        if (!condition) throw new AssertionError(stage + ": " + message);
    }

    private static AssistantSession session(AssistantController controller) throws Exception {
        java.lang.reflect.Field field = AssistantController.class.getDeclaredField("session");
        field.setAccessible(true);
        return (AssistantSession) field.get(controller);
    }

    private static void setAwaitingFinal(AssistantController controller, boolean value)
            throws Exception {
        java.lang.reflect.Field field = AssistantController.class.getDeclaredField("awaitingFinal");
        field.setAccessible(true);
        field.setBoolean(controller, value);
    }

    private static String pendingQuestion(AssistantController controller) throws Exception {
        java.lang.reflect.Field field = AssistantController.class.getDeclaredField("pendingQuestion");
        field.setAccessible(true);
        return (String) field.get(controller);
    }

    private static String awaitPendingQuestion(AssistantController controller, long timeoutMs)
            throws Exception {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        String value;
        do {
            value = pendingQuestion(controller);
            if (value != null && !value.isEmpty()) return value;
            SystemClock.sleep(25);
        } while (SystemClock.elapsedRealtime() < deadline);
        return value;
    }

    private static ByteBuffer scoreboardRgba() {
        int width = 640, height = 360;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.rgb(8, 19, 36));
        Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
        panel.setColor(Color.rgb(18, 52, 78));
        canvas.drawRoundRect(24, 28, width - 24, height - 28, 20, 20, panel);
        Paint divider = new Paint(Paint.ANTI_ALIAS_FLAG);
        divider.setColor(Color.rgb(71, 119, 147));
        divider.setStrokeWidth(3);
        canvas.drawLine(50, 190, width - 50, 190, divider);
        Paint score = new Paint(Paint.ANTI_ALIAS_FLAG);
        score.setColor(Color.WHITE);
        score.setTextAlign(Paint.Align.CENTER);
        score.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD));
        score.setTextSize(34);
        canvas.drawText("王者荣耀 合成测试", width / 2f, 87, score);
        score.setTextSize(78);
        canvas.drawText("比分 3 : 2", width / 2f, 178, score);
        score.setTextSize(38);
        score.setColor(Color.rgb(255, 114, 102));
        canvas.drawText("红队 3", width * 0.28f, 275, score);
        score.setColor(Color.rgb(101, 190, 255));
        canvas.drawText("蓝队 2", width * 0.72f, 275, score);

        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        bitmap.recycle();
        ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
        for (int pixel : pixels) {
            rgba.put((byte) (pixel >> 16)).put((byte) (pixel >> 8))
                    .put((byte) pixel).put((byte) 255);
        }
        rgba.rewind();
        return rgba;
    }

    private static boolean readAll(InputStream source, ByteArrayOutputStream output,
            int maxBytes) throws Exception {
        byte[] block = new byte[8192];
        int count;
        while ((count = source.read(block)) != -1) {
            if (output.size() + count > maxBytes) return false;
            output.write(block, 0, count);
        }
        return true;
    }

    private static byte[] readBounded(InputStream source, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        require(readAll(source, output, maxBytes), "input_read", "input exceeded its size limit");
        return output.toByteArray();
    }

    private static byte[] readBounded(File file, int maxBytes) throws Exception {
        try (InputStream source = new FileInputStream(file)) {
            return readBounded(source, maxBytes);
        }
    }

    private static void writeSummary(File file, JSONObject summary) throws Exception {
        Files.write(file.toPath(), summary.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private static void sendSummaryStatus(JSONObject summary) {
        Bundle status = new Bundle();
        status.putString("outcome", summary.optString("outcome", "unknown"));
        status.putString("failed_stage", summary.optString("failed_stage", ""));
        status.putLong("total_elapsed_ms", summary.optLong("total_elapsed_ms", -1));
        status.putLong("asr_inference_ms", summary.optLong("asr_inference_ms", -1));
        status.putLong("visual_request_to_callback_ms",
                summary.optLong("visual_request_to_callback_ms", -1));
        status.putLong("tts_to_first_audio_write_ms",
                summary.optLong("tts_to_first_audio_write_ms", -1));
        InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
    }
}
