package com.openkhub.sensefield;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Asynchronous optional assistant. No network/JPEG/ASR work runs on MapAssistFrames. */
final class AssistantController implements AutoCloseable, AssistantGatewayClient.Listener {
    interface Host {
        void speak(AssistantReply reply);
        boolean speechReady();
        void cancelSpeech();
        boolean speaking();
        default boolean assistantSpeaking() { return speaking(); }
        long lastAlertAtMs();
        String nearby();
        void mark();
        void status(String status);
        void audit(String metadata);
        /** One recomputed noise-floor reading from the speech gate, bound for the cue classifier. */
        default void noiseFloor(double floor) { }
    }
    private final Context context;
    private final AssistantSettings settings;
    private final Host host;
    private final AssistantSession session;
    private final AssistantPolicy policy = new AssistantPolicy();
    private final FrameHistory frameHistory = new FrameHistory();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService encoder = Executors.newSingleThreadExecutor();
    private final AssistantGatewayClient client;
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private volatile AssistantVoiceInput voice;
    private volatile OnDeviceAsr asr;
    private volatile AssistantOverlay overlay;
    private volatile boolean closed, paused, voicePaused, userSpeaking, voiceTurnActive, awaitingFinal;
    private volatile boolean manualVoiceActive;
    private final Object manualVoiceLock = new Object();
    private long manualVoiceSequence;
    private volatile long manualVoiceToken;
    private volatile String audioInputMode = "continuous";
    private volatile boolean voiceEnabled, visionEnabled, proactiveEnabled;
    private volatile FrameProcessingPolicy.Mode mode = FrameProcessingPolicy.Mode.NORMAL;
    private volatile String audioTurn = "";
    private volatile long audioGeneration;
    private volatile VoiceInputSafetyPolicy.InputState inputState;
    private volatile long latestFrameAtMs = -1;
    private volatile long proactiveQuietUntilMs = -1;
    private volatile long captureFrameNotBeforeMs = -1;
    private String pendingQuestion;
    private String pendingTurn;
    private boolean pendingProactive;
    private long frameNotBeforeMs;
    private long nextRequestId;
    private VisualTask inFlight;
    // One completed response may wait for provisional VAD/ASR to resolve. It is never queued
    // across a confirmed replacement question or a session reset, and keeps its original age.
    private JSONObject deferredVisualResult;
    private long deferredVisualId;
    private String deferredVisualFailure;
    private AssistantReply lastReply;
    private String lastAutomaticAnswer = "";
    private String currentStatus = "助手正在连接";
    /** Raw base last handed to {@link #setStatus}, kept so a late sound reading can re-run the chain. */
    private String statusBase = currentStatus;
    /** Latest broadcast-sound reading folded into the standing status line; empty means "none yet". */
    private String soundIndicator = "";
    private static final String EXPIRED_FRAME_PROMPT = "画面返回太慢，旧画面已丢弃。";
    static final long MANUAL_QUIET_MS = 60_000;
    static final String SCREEN_QUESTION = "请根据当前截图，说明最值得注意的一条可见信息。只有画面依据明确且与这条信息直接相关时，再补一句游戏建议；看不清就说明缺少哪条依据。";
    static final String AUTOMATIC_QUESTION = "当前画面是否有需要我重新考虑的选择？没有可靠依据就保持安静。";
    private static final class VisualTask {
        final long id; final String turn; final long generation; final FrameSnapshot frame;
        final List<FrameSnapshot> contextFrames; final boolean proactive;
        final AssistantDecisionFrameGuard decisionGuard;
        VisualTask(long id, String turn, FrameSnapshot frame, List<FrameSnapshot> contextFrames, boolean proactive) {
            this(id, turn, frame == null ? -1 : frame.generation, frame, contextFrames, proactive);
        }
        VisualTask(long id, String turn, long generation, FrameSnapshot frame,
                List<FrameSnapshot> contextFrames, boolean proactive) {
            this.id = id; this.turn = turn; this.frame = frame;
            this.generation = generation;
            this.contextFrames = contextFrames; this.proactive = proactive;
            this.decisionGuard = frame == null ? null
                    : new AssistantDecisionFrameGuard(frame.signature, frame.capturedAtMs);
        }
    }
    private static final class EncodedContextFrame {
        final FrameSnapshot frame; final byte[] jpeg;
        EncodedContextFrame(FrameSnapshot frame, byte[] jpeg) { this.frame = frame; this.jpeg = jpeg; }
    }
    AssistantController(Context context, String sessionId, AssistantSettings settings, Host host) {
        this.context = context; this.settings = settings; this.host = host;
        voiceEnabled = settings.voice;
        visionEnabled = settings.vision && settings.configured();
        proactiveEnabled = settings.proactive && visionEnabled;
        session = new AssistantSession(sessionId); client = new AssistantGatewayClient(settings, this);
    }
    void start() {
        main.post(() -> {
            if (closed || !settings.enabled()) return;
            overlay = new AssistantOverlay(context, new AssistantOverlay.Listener() {
                @Override public void readScreen() {
                    host.audit("AssistantInteraction event=READ_SCREEN");
                    AssistantController.this.readScreen();
                }
                @Override public void repeat() { repeatLast(); }
                @Override public void mark() { host.mark(); setStatus("已标记问题"); }
                @Override public void pauseVoice() { setVoicePaused(!voicePaused); }
                @Override public void voiceQuestion() { toggleManualVoice(); }
            });
            overlay.voiceEnabled(voiceEnabled);
            overlay.show();
            if (voiceEnabled) {
                final OnDeviceAsr[] ownedAsr = new OnDeviceAsr[1];
                asr = new OnDeviceAsr(context, new OnDeviceAsr.Listener() {
                    @Override public void ready() {
                        main.post(() -> {
                            if (asr != ownedAsr[0]) return;
                            host.audit("AssistantVoice event=ASR_READY source=on_device");
                            localAsrReady();
                        });
                    }
                    @Override public void result(long generation, String turn, String text, long inferenceMs) {
                        onLocalAsrResult(generation, turn, text, inferenceMs);
                    }
                    @Override public void unavailable(String reason) {
                        main.post(() -> { if (asr == ownedAsr[0]) localAsrUnavailable(reason); });
                    }
                    @Override public void resourceProgress(int percent) {
                        main.post(() -> {
                            if (!closed && voiceEnabled && asr != null && asr == ownedAsr[0] && !asr.ready())
                                setStatus(percent < 100 ? "语音资源下载中：" + percent + "%" : "正在校验并加载语音资源");
                        });
                    }
                    @Override public void dropped(String reason) {
                        host.audit("AssistantVoice event=DROPPED reason=" + safeAsrReason(reason)
                                + " monotonicMs=" + SystemClock.elapsedRealtime());
                    }
                });
                ownedAsr[0] = asr;
                asr.start();
                voice = new AssistantVoiceInput(context, new AssistantVoiceInput.Listener() {
                    @Override public void started() { voiceStarted(); }
                    @Override public void pcm(short[] pcm) {
                        OnDeviceAsr current = asr;
                        if (!voiceEnabled || !voiceTurnActive || current == null
                                || !session.ownsCapture(audioGeneration, audioTurn)) return;
                        current.accept(pcm);
                    }
                    @Override public void ended() { voiceEnded(); }
                    @Override public void inputStateChanged(VoiceInputSafetyPolicy.InputState state) {
                        voiceInputStateChanged(state);
                    }
                    @Override public void safetyMetadata(int flags, int routedDeviceType,
                            int outputCaptureCount) {
                        host.audit("AssistantVoice event=INPUT_STATE flags=" + flags
                                + " routedDeviceType=" + routedDeviceType
                                + " outputCaptureCount=" + outputCaptureCount);
                    }
                    @Override public void acousticSummary(int frames, int speechFrames, int rms, int peak,
                            int clippedSamples, int inputDeviceType) {
                        host.audit("AssistantVoice event=INPUT_AUDIO frames=" + frames
                                + " speechFrames=" + speechFrames + " rms=" + rms + " peak=" + peak
                                + " clippedSamples=" + clippedSamples + " inputDeviceType=" + inputDeviceType);
                    }
                    @Override public void manualFinished(long token, String reason, boolean hadSpeech) {
                        manualVoiceFinished(token, reason, hadSpeech);
                    }
                    @Override public void noiseFloor(double floor) { host.noiseFloor(floor); }
                    @Override public void unavailable(String value) { main.post(() -> voiceInputUnavailable(value)); }
                });
                voice.start();
            }
            setStatus(settings.voice ? "本地离线中文识别正在加载；就绪后可点“说话”提问"
                    : "可从小圆点读取画面");
        });
    }
    void feedRender(short[] frame) { AssistantVoiceInput input = voice; if (input != null) input.feedRender(frame); }
    private synchronized void voiceStarted() {
        if (closed || paused || voicePaused || !voiceEnabled) return;
        if (inputState == VoiceInputSafetyPolicy.InputState.ROUTE_UNVERIFIED && !manualVoiceActive) return;
        if (awaitingFinal || voiceTurnActive) {
            host.audit("AssistantVoice event=DROPPED reason=interrupted_by_user generation="
                    + audioGeneration + " monotonicMs=" + SystemClock.elapsedRealtime());
        }
        OnDeviceAsr current = asr;
        if (current != null) current.invalidate();
        userSpeaking = true; voiceTurnActive = false; awaitingFinal = false;
        proactiveQuietUntilMs = Math.max(proactiveQuietUntilMs,
                SystemClock.elapsedRealtime() + MANUAL_QUIET_MS);
        // Stop an answer already submitted for speech immediately, without resuming its tail.
        // Merely detecting another audio fragment must not kill a waiting visual request.
        if (host.assistantSpeaking()) {
            session.invalidate(); abandonVisual();
            synchronized (this) { pendingQuestion = null; }
            host.audit("AssistantVoice event=INTERRUPT_PLAYBACK monotonicMs=" + SystemClock.elapsedRealtime());
        }
        host.cancelSpeech();
        audioTurn = session.newCaptureTurn(); audioGeneration = session.generation();
        if (current == null || !current.ready()
                || !current.begin(audioGeneration, audioTurn)) {
            userSpeaking = false;
            session.invalidateCapture();
            String reason = current == null ? "asr_unavailable" : "asr_busy";
            host.audit("AssistantVoice event=DROPPED reason=" + reason + " generation="
                    + audioGeneration + " monotonicMs=" + SystemClock.elapsedRealtime());
            main.post(() -> setStatus(current != null && !current.ready()
                    ? "本地离线中文识别正在加载，请稍后重说" : "本地离线中文识别正忙，请稍后重说"));
            main.post(this::releaseDeferredVisual);
            return;
        }
        voiceTurnActive = true;
        audioInputMode = manualVoiceActive ? "manual" : "continuous";
        host.audit("AssistantVoice event=START source=on_device generation=" + audioGeneration
                + " turn=" + audioTurn + " inputMode=" + audioInputMode
                + " monotonicMs=" + SystemClock.elapsedRealtime());
    }
    private synchronized void voiceEnded() {
        userSpeaking = false;
        if (!voiceTurnActive) return;
        voiceTurnActive = false;
        awaitingFinal = true;
        String turn = audioTurn; long generation = audioGeneration;
        OnDeviceAsr current = asr;
        boolean accepted = current != null && current.finish();
        host.audit("AssistantVoice event=SPEECH_END source=on_device generation=" + generation
                + " turn=" + turn + " inputMode=" + audioInputMode
                + " monotonicMs=" + SystemClock.elapsedRealtime() + " accepted=" + accepted);
        if (!accepted) {
            awaitingFinal = false;
            if (current != null) current.invalidate();
            session.invalidateCapture();
            host.audit("AssistantVoice event=DROPPED reason=asr_finish_rejected generation="
                    + generation + " monotonicMs=" + SystemClock.elapsedRealtime());
            main.post(() -> setStatus("本地离线中文识别正忙，请稍后重说"));
            main.post(this::releaseDeferredVisual);
            return;
        }
        main.postDelayed(() -> {
            if (awaitingFinal && session.ownsCapture(generation, turn)) {
                host.audit("AssistantVoice event=DROPPED reason=local_asr_timeout generation="
                        + generation + " monotonicMs=" + SystemClock.elapsedRealtime());
                awaitingFinal = false;
                session.invalidateCapture();
                OnDeviceAsr timedOut = asr; if (timedOut != null) timedOut.invalidate();
                setStatus("本地语音识别超时，请重说");
                releaseDeferredVisual();
            }
        }, 5000);
    }
    void onLocalAsrResult(long generation, String turn, String rawText, long inferenceMs) {
        main.post(() -> { synchronized (this) {
            if (closed || !voiceEnabled || !awaitingFinal || !session.ownsCapture(generation, turn)) {
                host.audit("AssistantVoice event=DROPPED reason=stale_local_asr_result generation="
                        + generation + " monotonicMs=" + SystemClock.elapsedRealtime());
                return;
            }
            awaitingFinal = false;
            String text = rawText == null ? "" : rawText.trim();
            host.audit("AssistantVoice event=FINAL source=on_device inferenceMs=" + inferenceMs
                    + " generation=" + generation + " turn=" + turn
                    + " inputMode=" + audioInputMode
                    + " monotonicMs=" + SystemClock.elapsedRealtime()
                    + " textChars=" + text.length());
            String normalized = AssistantInputText.normalize(text);
            if (normalized.isEmpty()) {
                host.audit("AssistantVoice event=RESULT_UNKNOWN reason="
                        + (text.isEmpty() ? "empty_final" : "no_speech_content") + " generation="
                        + generation + " monotonicMs=" + SystemClock.elapsedRealtime());
                session.invalidateCapture();
                // Background non-speech should stay silent and must not replace
                // a real question or discard its waiting visual response.
                if ("manual".equals(audioInputMode)) setStatus("没有听清，请重说");
                releaseDeferredVisual(); return;
            }
            // Every completed linguistic message enters the conversation. Local controls are handled
            // by question(); the model interprets all other wording. PCM stays on device.
            question(normalized, false);
        } });
    }
    private void localAsrUnavailable(String reason) {
        if (closed || !voiceEnabled) return;
        host.audit("AssistantVoice event=ASR_UNAVAILABLE source=on_device");
        invalidate("local_asr_unavailable");
        AssistantVoiceInput input = voice; voice = null;
        if (input != null) input.close();
        OnDeviceAsr localAsr = asr; asr = null;
        if (localAsr != null) localAsr.close();
        setStatus(reason);
    }
    /** Model readiness cannot clear microphone safety or the user's pause. */
    void localAsrReady() {
        if (closed || !voiceEnabled) return;
        if (paused || voicePaused) {
            setStatus(VoiceInputSafetyPolicy.InputState.PAUSED.status);
        } else if (inputState != null && inputState != VoiceInputSafetyPolicy.InputState.READY) {
            setStatus(inputState.status);
        } else {
            setStatus("语音已就绪，可点“说话”提问；耳机下也可直接说话");
        }
    }

    void toggleManualVoice() {
        AssistantVoiceInput input = voice;
        if (manualVoiceActive) {
            if (input != null) input.finishManual(manualVoiceToken);
            return;
        }
        if (closed || paused || voicePaused) { setStatus("请先恢复助手语音，再点“说话”"); return; }
        if (!voiceEnabled) { setStatus("请先在助手设置中开启语音提问"); return; }
        OnDeviceAsr current = asr;
        if (current == null || !current.ready()) {
            setStatus("本地语音资源尚未就绪，请在助手设置中查看准备进度"); return;
        }
        if (awaitingFinal || current.busy()) { setStatus("正在识别上一句，请稍等"); return; }
        if (input == null || !VoiceInputSafetyPolicy.manualAllowed(input.inputState())) {
            setStatus(input == null ? "语音输入暂不可用，请重新开始辅助" : input.inputState().status);
            return;
        }
        invalidate("manual_voice_start");
        final long token;
        synchronized (manualVoiceLock) {
            token = ++manualVoiceSequence;
            manualVoiceToken = token; manualVoiceActive = true; userSpeaking = true;
        }
        proactiveQuietUntilMs = Math.max(proactiveQuietUntilMs,
                SystemClock.elapsedRealtime() + MANUAL_QUIET_MS);
        if (!input.beginManual(token)) {
            synchronized (manualVoiceLock) {
                if (manualVoiceToken == token) { manualVoiceActive = false; userSpeaking = false; }
            }
            setStatus(input.inputState().status); return;
        }
        host.audit("AssistantManualVoice event=START token=" + token
                + " maxMs=" + ManualVoiceWindow.MAX_MS);
        if (manualVoiceActive && manualVoiceToken == token) {
            if (overlay != null) overlay.manualVoiceActive(true);
            setStatus("请说话，说完自动提交；也可点“说完”");
        }
    }

    private void manualVoiceFinished(long token, String reason, boolean hadSpeech) {
        synchronized (manualVoiceLock) {
            if (!manualVoiceActive || manualVoiceToken != token) return;
            manualVoiceActive = false;
            if (!hadSpeech) userSpeaking = false;
        }
        host.audit("AssistantManualVoice event=END token=" + token
                + " reason=" + safeAsrReason(reason) + " hadSpeech=" + hadSpeech);
        main.post(() -> {
            if (closed || manualVoiceToken != token || manualVoiceActive) return;
            if (overlay != null) overlay.manualVoiceActive(false);
            if (!hadSpeech) {
                setStatus("没有检测到说话，请靠近麦克风再点“说话”");
                releaseDeferredVisual();
            } else if (awaitingFinal) setStatus("正在识别这句语音");
        });
    }

    private void cancelManualVoice(String reason) {
        final long token;
        synchronized (manualVoiceLock) {
            if (!manualVoiceActive) return;
            token = manualVoiceToken;
            manualVoiceActive = false;
        }
        host.audit("AssistantManualVoice event=CANCELLED token=" + token
                + " reason=" + safeAsrReason(reason));
        AssistantVoiceInput input = voice;
        if (input != null) input.cancelManual(token, reason);
        main.post(() -> { if (overlay != null && !manualVoiceActive) overlay.manualVoiceActive(false); });
    }
    private void voiceInputUnavailable(String status) {
        if (closed) return;
        if (inputState != VoiceInputSafetyPolicy.InputState.UNAVAILABLE)
            invalidate("audio_input_unavailable");
        AssistantVoiceInput input = voice; voice = null;
        if (input != null) input.close();
        OnDeviceAsr localAsr = asr; asr = null;
        if (localAsr != null) localAsr.close();
        setStatus(status);
    }
    private static String safeAsrReason(String reason) {
        return reason != null && reason.matches("[a-z0-9_]{1,40}") ? reason : "local_asr_dropped";
    }
    private void voiceStatus(String value) {
        if (closed) return;
        setStatus(value);
    }
    /** Called on each typed input-gate transition before queued finals can reach the UI thread. */
    void voiceInputStateChanged(VoiceInputSafetyPolicy.InputState state) {
        if (closed || state == null) return;
        inputState = state;
        invalidate("audio_input_" + state.auditKey);
        main.post(() -> {
            if (closed || inputState != state) return;
            if (paused || voicePaused) localAsrReady();
            else setStatus(state.status);
        });
    }
    void setVoicePaused(boolean value) {
        if (!voiceEnabled) { setStatus("语音提问尚未开启"); return; }
        voicePaused = value; invalidate("voice_pause");
        if (overlay != null) overlay.voicePaused(value);
        if (voice != null) voice.pause(paused || value);
        setStatus(value ? "助手语音已暂停，预警继续" : "助手语音已恢复");
    }
    /** Revocation is immediate; enabling new capabilities requires a fresh, foreground start. */
    void settingsChanged(AssistantSettings current) {
        if (closed) return;
        if (!current.optedIn) {
            voiceEnabled = false; visionEnabled = false; proactiveEnabled = false;
            invalidate("consent_revoked");
            close();
            main.post(() -> host.status("实验助手已关闭，本地预警继续"));
            return;
        }
        boolean disconnect = !settings.endpoint.equals(current.endpoint) || !settings.token.equals(current.token);
        boolean revokeVoice = voiceEnabled && !current.voice;
        boolean revokeVision = visionEnabled && (!current.vision || !current.configured() || disconnect);
        boolean revokeProactive = proactiveEnabled && (!current.proactive || revokeVision);
        if (!revokeVoice && !revokeVision && !revokeProactive) return;
        if (revokeVoice) voiceEnabled = false;
        if (revokeVision) visionEnabled = false;
        if (revokeProactive) proactiveEnabled = false;
        invalidate("consent_revoked");
        if (revokeVoice) main.post(() -> { if (overlay != null) overlay.voiceEnabled(false); });
        if (revokeVoice) {
            AssistantVoiceInput input = voice; voice = null;
            if (input != null) input.close();
            OnDeviceAsr localAsr = asr; asr = null;
            if (localAsr != null) localAsr.close();
        }
        if (revokeVision) clearFrameHistory();
        setStatus(disconnect ? "连接已更改，请重新开始辅助；本地预警继续"
                : "已停止关闭功能的数据上传；重新开启需重新开始辅助");
    }
    void pause(boolean value) {
        paused = value; invalidate("capture_pause");
        if (value) clearFrameHistory();
        if (voice != null) voice.pause(value || voicePaused);
        setStatus(value ? "助手已暂停" : "助手已恢复");
    }
    void invalidate(String reason) {
        cancelManualVoice(reason);
        auditPendingAudioDrop(reason);
        session.invalidate();
        OnDeviceAsr localAsr = asr; if (localAsr != null) localAsr.invalidate();
        abandonVisual(); host.cancelSpeech(); userSpeaking = false; voiceTurnActive = false; awaitingFinal = false;
        synchronized (this) { pendingQuestion = null; inFlight = null; }
        host.audit("AssistantInvalidated reason=" + reason + " generation=" + session.generation());
    }
    void thermal(FrameProcessingPolicy.Mode value) {
        boolean changed = mode != value; mode = value;
        // The capture scheduler already reduces local work. Keep deliberate
        // voice/visual turns alive; only unattended observation is suspended.
        if (changed) host.audit("AssistantThermal mode=" + value
                + " automaticObservation=" + (value == FrameProcessingPolicy.Mode.HOT ? "paused" : "scheduled")
                + " manualVisual=allowed monotonicMs=" + SystemClock.elapsedRealtime());
    }
    /** Atomic invalidation under the capture lock; controls execute later outside it. */
    void captureInvalidated(String reason) {
        final long cancelledManualToken;
        synchronized (manualVoiceLock) {
            cancelledManualToken = manualVoiceActive ? manualVoiceToken : 0;
            manualVoiceActive = false;
        }
        auditPendingAudioDrop(reason);
        clearFrameHistory();
        session.invalidate();
        OnDeviceAsr localAsr = asr; if (localAsr != null) localAsr.invalidate();
        userSpeaking = false; voiceTurnActive = false; awaitingFinal = false;
        VisualTask canceled;
        synchronized (this) {
            pendingQuestion = null; canceled = inFlight; inFlight = null;
            deferredVisualResult = null; deferredVisualFailure = null; deferredVisualId = 0;
        }
        if (canceled != null) host.audit("AssistantVisual event=DROPPED reason=capture_invalidated requestId="
                + canceled.id + " proactive=" + canceled.proactive);
        long generation = session.generation();
        main.post(() -> {
            AssistantVoiceInput input = voice;
            if (cancelledManualToken != 0 && input != null) input.cancelManual(cancelledManualToken, reason);
            if (overlay != null && !manualVoiceActive) overlay.manualVoiceActive(false);
            if (closed || generation != session.generation()) return;
            client.cancelVisual(); host.cancelSpeech();
            host.audit("AssistantInvalidated reason=" + reason + " generation=" + generation);
        });
    }
    private void clearFrameHistory() {
        captureFrameNotBeforeMs = SystemClock.elapsedRealtime();
        frameHistory.clear();
    }
    void question(String text, boolean proactive) {
        question(text, proactive, text);
    }
    /** A screen button has a visible action label while its detailed instruction stays server-side. */
    void readScreen() {
        question(SCREEN_QUESTION, false, "读画面");
    }
    private void question(String text, boolean proactive, String visibleText) {
        if (closed || paused || text == null || text.trim().isEmpty()) {
            host.audit("AssistantInteraction event=QUESTION_IGNORED reason="
                    + (closed ? "closed" : paused ? "paused" : "empty"));
            return;
        }
        text = AssistantInputText.normalize(text);
        if (text.isEmpty()) {
            host.audit("AssistantInteraction event=QUESTION_IGNORED reason=no_speech_content");
            return;
        }
        cancelManualVoice("user_question");
        if (!proactive) proactiveQuietUntilMs = Math.max(proactiveQuietUntilMs,
                SystemClock.elapsedRealtime() + MANUAL_QUIET_MS);
        auditPendingAudioDrop("user_question");
        session.invalidate();
        OnDeviceAsr localAsr = asr; if (localAsr != null) localAsr.invalidate();
        String turn = session.newTurn(); host.cancelSpeech(); abandonVisual();
        host.audit("AssistantInteraction event=QUESTION proactive=" + proactive
                + " generation=" + session.generation() + " turn=" + turn
                + " textChars=" + text.length() + " monotonicMs=" + SystemClock.elapsedRealtime());
        userSpeaking = false; voiceTurnActive = false; awaitingFinal = false;
        synchronized (this) { pendingQuestion = null; }
        if (!proactive) append("你：" + (visibleText == null || visibleText.trim().isEmpty()
                ? text : visibleText.trim()));
        if (localCommand(text, turn)) return;
        if (!visionEnabled) {
            String explanation = settings.vision && !settings.configured()
                    ? "画面理解尚未配置有效的 HTTPS 服务器；本地语音和指令仍可用。"
                    : "画面理解尚未开启。";
            sayLocal(turn, explanation, false); return;
        }
        if (!policy.manualAllowed(SystemClock.elapsedRealtime(), mode)) {
            long retryMs = policy.retryAfterMs(SystemClock.elapsedRealtime());
            String reason = retryMs > 0 ? "rate_limited" : "session_budget";
            host.audit("AssistantVisual event=DENIED reason=" + reason + " retryAfterMs=" + retryMs);
            sayLocal(turn, retryMs > 0 ? "画面服务暂时繁忙，请在" + ((retryMs + 999) / 1000) + "秒后重试。"
                    : "本次会话的画面问答次数已用完，请稍后再试。", false); return;
        }
        if (overlay != null && overlay.isExpanded()) overlay.setExpanded(false);
        synchronized (this) {
            pendingQuestion = text; pendingTurn = turn; pendingProactive = proactive;
            frameNotBeforeMs = SystemClock.elapsedRealtime() + 250;
        }
        setStatus("正在等待当前画面");
        main.postDelayed(() -> {
            synchronized (AssistantController.this) {
                if (pendingQuestion == null || !session.owns(session.generation(), turn)) return;
                pendingQuestion = null;
            }
            sayLocal(turn, "当前没有可用画面，请确认游戏横屏并重新提问。", false);
        }, 2000);
    }
    private boolean localCommand(String text, String turn) {
        AssistantConversationIntent.Match conversation = AssistantConversationIntent.classify(text);
        if (conversation != null) {
            sayLocal(turn, conversation.answer, false); return true;
        }
        String command = text.replaceAll("[\\s，。！？!?]", "");
        if (command.matches("(听野|助手)?(你好|您好|在吗|你在吗|在不在|听得到吗|你听得到吗)")) {
            sayLocal(turn, "我在。可以说读取画面，或附近情况。", false); return true;
        }
        if (command.matches("(听野|助手)?(你是谁|你叫什么|能做什么|你能做什么)")) {
            sayLocal(turn, "我是听野，你的游戏搭子，可以帮你看清画面、解答游戏问题。", false); return true;
        }
        if (command.matches("(停止播报|别说了|停说|安静|暂停助手)")) {
            if (command.equals("暂停助手")) setVoicePaused(true); else setStatus("已停止助手播报"); return true;
        }
        if (command.matches("(再说一遍|重复一下|重说|重复)")) { repeatLast(); return true; }
        if (command.matches("(附近情况|附近怎么样|附近有没有敌人|现在附近什么情况)")) { sayLocal(turn, host.nearby(), false); return true; }
        if (command.matches("(提醒少一点|降低提示频率|精简提示)")) {
            GameProfile.settings(context).edit().putString("cue_preset", CueSettings.PRESET_COMPACT).apply();
            sayLocal(turn, "已切换为精简提示。", false); return true;
        }
        if (command.matches("(恢复标准提示|标准提示|提醒恢复标准)")) {
            GameProfile.settings(context).edit().putString("cue_preset", CueSettings.PRESET_STANDARD).apply();
            sayLocal(turn, "已恢复标准提示。", false); return true;
        }
        return false;
    }
    private void sayLocal(String turn, String text, boolean uncertain) {
        AssistantReply reply = new AssistantReply(session.generation(), turn, -1, SystemClock.elapsedRealtime(), "local", text, uncertain);
        lastReply = reply; append("听野：" + text); playReply(reply);
    }
    private void repeatLast() {
        cancelManualVoice("repeat");
        AssistantReply previous = lastReply;
        auditPendingAudioDrop("user_question");
        session.invalidate();
        OnDeviceAsr localAsr = asr; if (localAsr != null) localAsr.invalidate();
        userSpeaking = false; voiceTurnActive = false; awaitingFinal = false;
        String turn = session.newTurn(); host.cancelSpeech(); abandonVisual();
        if (previous == null) { sayLocal(turn, "还没有可重复的回答。", false); return; }
        if (!"local".equals(previous.kind) && !previous.freshAt(SystemClock.elapsedRealtime())) {
            sayLocal(turn, "上一条画面已经过期，请重新问当前情况。", true); return;
        }
        AssistantReply reply = new AssistantReply(session.generation(), turn, previous.frameId,
                "local".equals(previous.kind) ? SystemClock.elapsedRealtime() : previous.capturedAtMs,
                previous.kind, previous.answer, previous.uncertain, false,
                previous.decisionScene, previous.decisionGuard, previous.snapshotRead);
        playReply(reply);
    }
    /** Called only after native processing has released its lock, while the Image is still owned. */
    void offerFrame(ByteBuffer rgba, int width, int height, int stride, long frameId, long capturedAt) {
        if (closed || paused || !visionEnabled) return;
        long now = SystemClock.elapsedRealtime(); latestFrameAtMs = capturedAt;
        if (now < capturedAt || now - capturedAt > 1000 || capturedAt < captureFrameNotBeforeMs) return;
        long captureEpoch = frameHistory.epoch();
        boolean speaking = userSpeaking || awaitingFinal || host.speaking();
        Rect bounds = overlay == null ? null : overlay.bounds();
        final long signature;
        try { signature = FrameSnapshot.signature(rgba, width, height, stride, bounds); }
        catch (RuntimeException error) { return; }
        synchronized (this) {
            if (inFlight != null && inFlight.decisionGuard != null)
                inFlight.decisionGuard.observe(signature, capturedAt);
            if (lastReply != null && lastReply.decisionGuard != null)
                lastReply.decisionGuard.observe(signature, capturedAt);
        }
        if (frameHistory.due(now, mode)) {
            try {
                FrameSnapshot cached = FrameSnapshot.copy(rgba, width, height, stride,
                        FrameHistory.CONTEXT_EDGE, frameId, capturedAt, session.generation(), signature, bounds);
                frameHistory.add(cached, captureEpoch);
            } catch (RuntimeException | OutOfMemoryError ignored) { }
        }
        if (frameHistory.epoch() != captureEpoch) return;
        boolean panelExpanded = overlay != null && overlay.isExpanded();
        if (panelExpanded) return;
        final String question, turn; final boolean proactive; final long generation, id;
        synchronized (this) {
            if (inFlight != null) return;
            if (pendingQuestion != null) {
                if (capturedAt < frameNotBeforeMs || !policy.manualAllowed(now, mode)) return;
                question = pendingQuestion; turn = pendingTurn; proactive = pendingProactive; pendingQuestion = null;
            } else {
                if (!proactiveEnabled || now < proactiveQuietUntilMs || !policy.automaticDue(now, mode, speaking,
                        overlay != null && overlay.isExpanded(), host.lastAlertAtMs(), signature)) return;
                question = AUTOMATIC_QUESTION;
                turn = session.newTurn(); proactive = true;
            }
            generation = session.generation(); id = ++nextRequestId;
            // Reserve the slot before copying, so no encoder/frame queue can accumulate.
            inFlight = new VisualTask(id, turn, generation, null, Collections.emptyList(), proactive);
        }
        final FrameSnapshot frame;
        try { frame = FrameSnapshot.copy(rgba, width, height, stride, proactive ? 960 : 1280,
                frameId, capturedAt, generation, signature, bounds); }
        catch (RuntimeException | OutOfMemoryError error) { visualFailure(id, "frame_unavailable"); return; }
        if (frameHistory.epoch() != captureEpoch || capturedAt < captureFrameNotBeforeMs) {
            takeTask(id);
            return;
        }
        List<FrameSnapshot> contextFrames = proactive || !needsVisualHistory(question)
                ? Collections.emptyList() : frameHistory.context(now, frame);
        VisualTask task = new VisualTask(id, turn, frame, contextFrames, proactive);
        synchronized (this) { if (inFlight != null && inFlight.id == id) inFlight = task; }
        try { encoder.execute(() -> {
          try {
            if (closed || frame == null || !session.owns(generation, turn)) { visualFailure(id, "cancelled"); return; }
            byte[] jpeg = frame.jpeg();
            if (jpeg == null) { visualFailure(id, "frame_unavailable"); return; }
            ArrayList<EncodedContextFrame> encodedContextFrames = new ArrayList<>(FrameHistory.CAPACITY);
            for (FrameSnapshot oldFrame : task.contextFrames) {
                byte[] oldJpeg = oldFrame.jpeg();
                if (oldJpeg != null) encodedContextFrames.add(new EncodedContextFrame(oldFrame, oldJpeg));
            }
            long sentAt = SystemClock.elapsedRealtime();
            long primaryAgeMs = sentAt - frame.capturedAtMs;
            if (!visionEnabled || (proactive && !proactiveEnabled) || !session.owns(generation, turn)
                    || (proactive && mode == FrameProcessingPolicy.Mode.HOT)
                    || primaryAgeMs < 0 || primaryAgeMs > 1000) {
                visualFailure(id, "frame_unavailable"); return;
            }
            ArrayList<AssistantGatewayClient.ContextImage> contextImages = new ArrayList<>(FrameHistory.CAPACITY);
            for (EncodedContextFrame encoded : encodedContextFrames) {
                long ageMs = sentAt - encoded.frame.capturedAtMs;
                if (FrameHistory.isEligibleContext(encoded.frame.frameId, encoded.frame.capturedAtMs,
                        frame.frameId, frame.capturedAtMs, sentAt)) {
                    contextImages.add(new AssistantGatewayClient.ContextImage(
                            String.valueOf(encoded.frame.frameId), ageMs, encoded.jpeg));
                }
            }
            policy.started(sentAt, proactive, signature);
            host.audit("AssistantVisual event=REQUEST requestId=" + id + " frameId=" + frameId + " generation=" + generation
                    + " turn=" + turn + " monotonicMs=" + sentAt
                    + " frameAgeMs=" + primaryAgeMs + " contextFrames=" + contextImages.size()
                    + " proactive=" + proactive);
            client.visual(id, session, turn, frame, jpeg, contextImages, question, proactive, sentAt);
          } catch (RuntimeException | OutOfMemoryError error) { visualFailure(id, "frame_unavailable"); }
        }); } catch (java.util.concurrent.RejectedExecutionException closedExecutor) { takeTask(id); }
    }
    /** Legacy transport callback retained for old fixtures; server ASR cannot control live state. */
    @Override public void audioMessage(JSONObject message) { }
    private void auditPendingAudioDrop(String reason) {
        if (!voiceTurnActive && !awaitingFinal) return;
        String safeReason;
        if (reason != null && reason.startsWith("audio_input_")) safeReason = "input_safety";
        else if ("capture_pause".equals(reason) || "capture_reset".equals(reason)
                || "hot".equals(reason) || "consent_revoked".equals(reason)
                || "voice_pause".equals(reason) || "user_question".equals(reason)
                || "local_asr_timeout".equals(reason) || "local_asr_unavailable".equals(reason)) safeReason = reason;
        else safeReason = "turn_invalidated";
        host.audit("AssistantVoice event=DROPPED reason=" + safeReason + " generation="
                + audioGeneration + " monotonicMs=" + SystemClock.elapsedRealtime());
    }
    static boolean needsVisualHistory(String question) {
        return question != null && java.util.regex.Pattern.compile(
                "刚才|之前|先前|最近|前后|变化|对比|比较|previous|earlier|before|changed|compare|recent",
                java.util.regex.Pattern.CASE_INSENSITIVE).matcher(question).find();
    }
    /** Legacy transport callback retained for interface compatibility; local ASR is independent. */
    @Override public void audioUnavailable(String reason) { }
    @Override public void visualResult(long id, JSONObject result) { main.post(() -> { synchronized (this) {
        if (deferVisualDuringInput(id, result, null)) return;
        VisualTask task = takeTask(id); if (task == null || task.frame == null || closed) return;
        if (!session.owns(task.frame.generation, task.turn)
                || !session.sessionId.equals(result.optString("session_id"))
                || result.optLong("generation", -1) != task.frame.generation
                || !task.turn.equals(result.optString("turn_id"))
                || result.optLong("frame_id", -1) != task.frame.frameId) return;
        String kind = result.optString("kind", "unknown"), answer = result.optString("answer").trim();
        boolean uncertain = result.optBoolean("uncertain", true);
        if (answer.length() > 400 || (!"hud".equals(kind) && !"ui_text".equals(kind) && !"unknown".equals(kind))) {
            host.audit("AssistantVisual event=RESULT_UNKNOWN reason=invalid_result frameId=" + task.frame.frameId);
            setStatus("画面回答不可用"); return;
        }
        // A valid, owned response proves model access, even if the image is uncertain.
        policy.serviceRecovered();
        long now = SystemClock.elapsedRealtime();
        if (!task.proactive) proactiveQuietUntilMs = Math.max(proactiveQuietUntilMs, now + MANUAL_QUIET_MS);
        // This feedback describes the request outcome, so no stale game advice is admitted.
        // Do it before frame expiry: an unknown answer must not be blamed on the screenshot age.
        if (uncertain || "unknown".equals(kind) || answer.isEmpty()) {
            String reason = AssistantVisualFailure.unknownReason(
                    result.optString("unknown_reason", ""), answer.isEmpty());
            host.audit("AssistantVisual event=RESULT_UNKNOWN reason=" + reason
                    + " frameId=" + task.frame.frameId);
            reportManualFeedback(task, reason, AssistantVisualFailure.unknownMessage(reason));
            return;
        }
        AssistantReply reply = new AssistantReply(task.frame.generation, task.turn, task.frame.frameId,
                task.frame.capturedAtMs, kind, answer, uncertain, task.proactive,
                result.optString("decision_scene", ""), task.decisionGuard,
                !task.proactive && "snapshot".equals(result.optString("answer_scope", "")));
        if (!reply.freshAt(now)) {
            host.audit("AssistantVisual event=DROPPED reason=expired_result requestId=" + id
                    + " frameId=" + task.frame.frameId
                    + " frameAgeMs=" + Math.max(0, now - reply.capturedAtMs)
                    + " kind=" + reply.kind
                    + " decisionScene=" + (reply.decisionScene.isEmpty() ? "none" : reply.decisionScene)
                    + " snapshotRead=" + reply.snapshotRead
                    + " expiryPassed=" + (now > reply.expiresAtMs()));
            if (!task.proactive && now > reply.expiresAtMs()) {
                explainExpiredFrame(task.turn, task.frame.frameId,
                        task.frame.capturedAtMs, "result");
            } else {
                reportManualFeedback(task, "frame_guard", "画面已经变化或观测中断，请重新提问。");
            }
            return;
        }
        if (task.proactive && answer.equals(lastAutomaticAnswer)) return;
        if (task.proactive) lastAutomaticAnswer = answer;
        lastReply = reply;
        append((task.proactive ? "主动观察：" : "听野：") + reply.spokenAnswerAt(now));
        setStatus("助手可用"); playReply(reply);
        host.audit("AssistantVisual event=RESULT requestId=" + id + " generation=" + reply.generation
                + " turn=" + reply.turnId + " kind=" + reply.kind + " answerChars=" + reply.answer.length()
                + " decisionScene=" + (reply.decisionScene.isEmpty() ? "none" : reply.decisionScene)
                + " snapshotRead=" + reply.snapshotRead
                + " monotonicMs=" + now + " frameId=" + task.frame.frameId + " frameAgeMs=" + (now - task.frame.capturedAtMs)
                + " elapsedMs=" + result.optLong("elapsed_ms", -1) + " proactive=" + task.proactive);
    } }); }
    /** A queued assistant utterance can expire after the visual result was accepted. */
    void assistantSpeechExpired(String playbackSessionId, String cueId, long atMs) {
        main.post(() -> {
            AssistantReply reply = lastReply;
            if (closed || paused || !(voiceEnabled || visionEnabled) || reply == null
                    || "local".equals(reply.kind) || reply.proactive || reply.frameId < 0
                    || !session.sessionId.equals(playbackSessionId)
                    || !session.owns(reply.generation, reply.turnId)
                    || !(session.sessionId + ":assistant:" + reply.turnId).equals(cueId)
                    || atMs <= reply.expiresAtMs()
                    || SystemClock.elapsedRealtime() <= reply.expiresAtMs()) return;
            explainExpiredFrame(reply.turnId, reply.frameId, reply.capturedAtMs, "playback");
        });
    }
    private void explainExpiredFrame(String turn, long frameId, long capturedAtMs, String stage) {
        long ageMs = SystemClock.elapsedRealtime() - capturedAtMs;
        host.audit("AssistantVisual event=EXPIRED stage=" + stage + " frameId=" + frameId
                + " frameAgeMs=" + Math.max(0, ageMs));
        setStatus(EXPIRED_FRAME_PROMPT);
        sayLocal(turn, EXPIRED_FRAME_PROMPT, false);
    }
    private void reportManualFeedback(VisualTask task, String reason, String message) {
        if (task == null || task.proactive || !session.owns(task.generation, task.turn)) return;
        setStatus(message);
        sayLocal(task.turn, message, true);
        host.audit("AssistantVisual event=MANUAL_FEEDBACK reason=" + safeCode(reason)
                + " frameId=" + (task.frame == null ? -1 : task.frame.frameId));
    }
    @Override public void visualFailure(long id, String code) { main.post(() -> { synchronized (this) {
        if (deferVisualDuringInput(id, null, code)) return;
        VisualTask task = takeTask(id); if (task == null || closed) return;
        if (!session.owns(task.generation, task.turn)) return;
        if (!task.proactive) proactiveQuietUntilMs = Math.max(proactiveQuietUntilMs,
                SystemClock.elapsedRealtime() + MANUAL_QUIET_MS);
        if ("public_quota_exceeded".equals(code) || "rate_limited".equals(code) || "1302".equals(code) || "1305".equals(code) || "provider_busy".equals(code)) {
            long now = SystemClock.elapsedRealtime();
            if (policy.retryAfterMs(now) <= 0) policy.throttled(now,
                    "provider_busy".equals(code) || "1305".equals(code) ? AssistantPolicy.OVERLOAD_RETRY_MS : 60_000);
        }
        if (AssistantVisualFailure.requiresServiceRepair(code)) {
            policy.serviceFailed(code);
            host.audit("AssistantVisual event=AUTOMATIC_PAUSED reason=" + safeCode(code));
            if (task.proactive) setStatus(AssistantVisualFailure.message(code)
                    + "自动读屏已暂停，修复后可手动读取。");
        }
        if (!task.proactive && !"cancelled".equals(code)) {
            reportManualFeedback(task, safeCode(code), AssistantVisualFailure.message(code));
        }
        String reason = safeCode(code);
        host.audit("AssistantVisual event=DROPPED reason=" + reason + " proactive=" + task.proactive);
        host.audit("AssistantVisual event=FAILED code=" + reason);
    } }); }
    private static String safeCode(String code) { return code != null && code.matches("[a-zA-Z0-9_]{1,48}") ? code : "service_unavailable"; }
    @Override public void visualDiagnostic(long id, int status, String source,
            String providerCode, int upstreamStatus, long retryAfter) {
        if (closed) return;
        if (status == 429 && retryAfter > 0)
            policy.throttled(SystemClock.elapsedRealtime(), Math.min(86_400, retryAfter) * 1000);
        if (status == 429 && "provider".equals(source) && "1305".equals(providerCode)) {
            // A busy free provider should not spend the session's opportunities on
            // unattended requests. Manual requests obey the gateway's retry hint.
            proactiveEnabled = false;
            host.audit("AssistantVisual event=AUTOMATIC_PAUSED reason=provider_overloaded");
            main.post(() -> setStatus("画面服务繁忙，本局自动读屏已暂停；稍后可手动读取"));
        }
        host.audit("AssistantVisual event=HTTP_ERROR requestId=" + id + " httpStatus=" + status
                + " source=" + source + " providerCode=" + providerCode
                + " upstreamHttpStatus=" + upstreamStatus + " retryAfterSeconds=" + retryAfter);
    }
    private synchronized VisualTask takeTask(long id) { if (inFlight == null || inFlight.id != id) return null; VisualTask task = inFlight; inFlight = null; return task; }
    private boolean deferVisualDuringInput(long id, JSONObject result, String failure) {
        synchronized (this) {
            if (closed || inFlight == null || inFlight.id != id
                    || !(userSpeaking || voiceTurnActive || awaitingFinal)) return false;
            deferredVisualId = id;
            deferredVisualResult = result;
            deferredVisualFailure = failure;
        }
        host.audit("AssistantVisual event=WAITING_FOR_INPUT requestId=" + id
                + " monotonicMs=" + SystemClock.elapsedRealtime());
        return true;
    }
    private void releaseDeferredVisual() {
        final long id; final JSONObject result; final String failure;
        synchronized (this) {
            if (closed || userSpeaking || voiceTurnActive || awaitingFinal) return;
            id = deferredVisualId; result = deferredVisualResult; failure = deferredVisualFailure;
            deferredVisualResult = null; deferredVisualFailure = null; deferredVisualId = 0;
        }
        if (result != null) visualResult(id, result);
        else if (failure != null) visualFailure(id, failure);
    }
    private void abandonVisual() {
        VisualTask canceled;
        synchronized (this) {
            canceled = inFlight; inFlight = null;
            deferredVisualResult = null; deferredVisualFailure = null; deferredVisualId = 0;
        }
        if (canceled != null) host.audit("AssistantVisual event=DROPPED reason=cancelled requestId="
                + canceled.id + " proactive=" + canceled.proactive);
        client.cancelVisual();
    }
    private void playReply(AssistantReply reply) {
        if (!allows(reply)) return;
        if (!host.speechReady()) {
            host.audit("AssistantSpeech event=UNAVAILABLE generation=" + reply.generation
                    + " turn=" + reply.turnId + " monotonicMs=" + SystemClock.elapsedRealtime());
            setStatus("缺少可用的离线中文语音，请安装语音包；回答保留在面板"); return;
        }
        host.speak(reply);
    }
    boolean allows(AssistantReply reply) { return !closed && !paused && !userSpeaking && !awaitingFinal
            && (voiceEnabled || visionEnabled) && session.owns(reply.generation, reply.turnId)
            && reply.freshAt(SystemClock.elapsedRealtime())
            && (!reply.proactive || (mode != FrameProcessingPolicy.Mode.HOT
                && !userSpeaking && !awaitingFinal && (overlay == null || !overlay.isExpanded())
                && (host.lastAlertAtMs() < 0 || SystemClock.elapsedRealtime() - host.lastAlertAtMs() >= AssistantPolicy.ALERT_QUIET_MS))); }
    private void append(String line) { while (history.size() >= 12) history.removeFirst(); history.addLast(line);
        if (overlay != null) overlay.history(String.join("\n\n", history)); }
    /**
     * Second, persistent outlet for one broadcast-sound reading. This is not a new pathway: it
     * re-enters the very same {@link #setStatus} chain every other assistant status already uses,
     * so the reading lands on the standing overlay line instead of only flashing. Written and read
     * on the main thread, like the rest of the status writers.
     */
    void showSoundIndicator(String sentence) {
        main.post(() -> {
            soundIndicator = sentence == null ? "" : sentence;
            setStatus(statusBase);
        });
    }
    /**
     * The single place the shared status line learns about the sound indicator, and deliberately
     * free of Android types so a JVM unit test can stand on both directions of the merge. An absent
     * reading leaves the line byte-identical — the input-state segment is untouched — while a
     * present one is appended last with the existing fullwidth semicolon, so the input state can
     * never be pushed off the line. Order is fixed: base status, input state, sound indicator.
     */
    static String mergeSoundIndicator(String status, String indicator) {
        if (indicator == null || indicator.isEmpty()) return status;
        if (status == null || status.isEmpty()) return indicator;
        if (status.equals(indicator) || status.endsWith("；" + indicator)) return status;
        return status + "；" + indicator;
    }
    private void setStatus(String value) {
        if (closed) return;
        statusBase = value;
        VoiceInputSafetyPolicy.InputState state = inputState;
        if (voiceEnabled && !manualVoiceActive && state != null && state != VoiceInputSafetyPolicy.InputState.READY
                && !value.equals(state.status)) value += "；" + state.status;
        value = mergeSoundIndicator(value, soundIndicator);
        currentStatus = value;
        host.status(value);
        if (overlay != null) overlay.status(value);
    }
    @Override public void close() {
        cancelManualVoice("closed");
        closed = true; session.close(); clearFrameHistory();
        main.removeCallbacksAndMessages(null); main.post(() -> {
            client.close(); if (voice != null) voice.close();
            if (asr != null) asr.close(); encoder.shutdownNow();
            if (overlay != null) overlay.close(); history.clear();
        });
    }
}
