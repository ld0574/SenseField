package com.openkhub.sensefield;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Asynchronous optional assistant. No network/JPEG/ASR work runs on MapAssistFrames. */
final class AssistantController implements AutoCloseable, AssistantGatewayClient.Listener {
    interface Host {
        void speak(AssistantReply reply);
        boolean speechReady();
        void cancelSpeech();
        boolean speaking();
        long lastAlertAtMs();
        String nearby();
        void mark();
        void status(String status);
        void audit(String metadata);
    }
    private final Context context;
    private final AssistantSettings settings;
    private final Host host;
    private final AssistantSession session;
    private final AssistantPolicy policy = new AssistantPolicy();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService encoder = Executors.newSingleThreadExecutor();
    private final AssistantGatewayClient client;
    private final ArrayDeque<String> history = new ArrayDeque<>();
    private volatile AssistantVoiceInput voice;
    private volatile AssistantOverlay overlay;
    private volatile boolean closed, paused, voicePaused, userSpeaking, awaitingFinal;
    private volatile boolean voiceEnabled, visionEnabled, proactiveEnabled;
    private volatile FrameProcessingPolicy.Mode mode = FrameProcessingPolicy.Mode.NORMAL;
    private volatile String audioTurn = "";
    private volatile long audioGeneration;
    private volatile boolean uploading;
    private volatile VoiceInputSafetyPolicy.InputState inputState;
    private volatile long latestFrameAtMs = -1;
    private volatile long lastReconnectMs = -1;
    private String pendingQuestion;
    private String pendingTurn;
    private boolean pendingProactive;
    private long frameNotBeforeMs;
    private long nextRequestId;
    private VisualTask inFlight;
    private AssistantReply lastReply;
    private String lastAutomaticAnswer = "";
    private String currentStatus = "助手正在连接";
    private static final String EXPIRED_FRAME_PROMPT = "画面返回太慢，旧画面已丢弃。";
    private static final class VisualTask {
        final long id; final String turn; final FrameSnapshot frame; final boolean proactive;
        VisualTask(long id, String turn, FrameSnapshot frame, boolean proactive) {
            this.id = id; this.turn = turn; this.frame = frame; this.proactive = proactive;
        }
    }
    AssistantController(Context context, String sessionId, AssistantSettings settings, Host host) {
        this.context = context; this.settings = settings; this.host = host;
        voiceEnabled = settings.voice; visionEnabled = settings.vision; proactiveEnabled = settings.proactive;
        session = new AssistantSession(sessionId); client = new AssistantGatewayClient(settings, this);
    }
    void start() {
        main.post(() -> {
            if (closed) return;
            overlay = new AssistantOverlay(context, new AssistantOverlay.Listener() {
                @Override public void readScreen() {
                    host.audit("AssistantInteraction event=READ_SCREEN");
                    question("请读出当前界面的清晰文字和比分。", false);
                }
                @Override public void repeat() { repeatLast(); }
                @Override public void mark() { host.mark(); setStatus("已标记问题"); }
                @Override public void pauseVoice() { setVoicePaused(!voicePaused); }
            });
            overlay.show();
            if (voiceEnabled) {
                client.connectAudio(session.sessionId, session.generation());
                voice = new AssistantVoiceInput(context, new AssistantVoiceInput.Listener() {
                    @Override public void started() { voiceStarted(); }
                    @Override public void pcm(short[] pcm) {
                        if (!voiceEnabled || !uploading || !session.owns(audioGeneration, audioTurn)) return;
                        byte[] bytes = new byte[pcm.length * 2];
                        for (int i = 0; i < pcm.length; i++) { bytes[2*i] = (byte) pcm[i]; bytes[2*i+1] = (byte) (pcm[i] >>> 8); }
                        if (!client.sendPcm(bytes)) uploading = false;
                    }
                    @Override public void ended() {
                        userSpeaking = false;
                        if (!uploading) return;
                        uploading = false; awaitingFinal = true;
                        String turn = audioTurn; long generation = audioGeneration;
                        client.sendJson(AssistantGatewayClient.json("type", "speech_end", "turn_id", turn, "generation", generation));
                        main.postDelayed(() -> {
                            if (awaitingFinal && session.owns(generation, turn)) {
                                awaitingFinal = false; setStatus("语音识别超时，请重说");
                                session.invalidate(); client.resetAudio(session.generation());
                            }
                        }, 5000);
                    }
                    @Override public void inputStateChanged(VoiceInputSafetyPolicy.InputState state) {
                        voiceInputStateChanged(state);
                    }
                    @Override public void safetyMetadata(int flags, int routedDeviceType) {
                        host.audit("AssistantVoice event=INPUT_STATE flags=" + flags
                                + " routedDeviceType=" + routedDeviceType);
                    }
                    @Override public void unavailable(String value) { main.post(() -> voiceStatus(value)); }
                });
                voice.start();
            }
            setStatus(settings.voice ? "请戴耳机并确认游戏声音也从耳机播放后再说话" : "可从小圆点读取画面");
        });
    }
    void feedRender(short[] frame) { AssistantVoiceInput input = voice; if (input != null) input.feedRender(frame); }
    private void voiceStarted() {
        if (closed || paused || voicePaused || !voiceEnabled) return;
        userSpeaking = true; awaitingFinal = false;
        audioTurn = session.newTurn(); audioGeneration = session.generation();
        host.cancelSpeech(); abandonVisual();
        synchronized (this) { pendingQuestion = null; inFlight = null; }
        if (!client.ready()) {
            uploading = false;
            long now = SystemClock.elapsedRealtime();
            if (lastReconnectMs < 0 || now - lastReconnectMs >= 5000) {
                lastReconnectMs = now; client.connectAudio(session.sessionId, session.generation());
            }
            main.post(() -> setStatus("语音服务器未连接，请稍后重说")); return;
        }
        uploading = client.sendJson(AssistantGatewayClient.json("type", "speech_start", "turn_id", audioTurn, "generation", audioGeneration));
        host.audit("AssistantVoice event=START generation=" + audioGeneration);
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
        main.post(() -> setStatus(state.status));
    }
    void setVoicePaused(boolean value) {
        if (!voiceEnabled) { setStatus("连续语音尚未开启"); return; }
        voicePaused = value; invalidate("voice_pause");
        if (overlay != null) overlay.voicePaused(value);
        if (voice != null) voice.pause(paused || value);
        setStatus(value ? "助手语音已暂停，预警继续" : "助手语音已恢复");
    }
    /** Revocation is immediate; enabling new capabilities requires a fresh, foreground start. */
    void settingsChanged(AssistantSettings current) {
        boolean disconnect = !settings.endpoint.equals(current.endpoint) || !settings.token.equals(current.token);
        boolean revokeVoice = voiceEnabled && (!current.voice || disconnect);
        boolean revokeVision = visionEnabled && (!current.vision || disconnect);
        boolean revokeProactive = proactiveEnabled && (!current.proactive || revokeVision);
        if (!revokeVoice && !revokeVision && !revokeProactive) return;
        if (revokeVoice) voiceEnabled = false;
        if (revokeVision) visionEnabled = false;
        if (revokeProactive) proactiveEnabled = false;
        invalidate("consent_revoked");
        if (revokeVoice) {
            AssistantVoiceInput input = voice; voice = null;
            if (input != null) input.close();
            client.stopAudio();
        }
        setStatus(disconnect ? "连接已更改，请重新开始辅助；本地预警继续"
                : "已停止关闭功能的数据上传；重新开启需重新开始辅助");
    }
    void pause(boolean value) {
        paused = value; invalidate("capture_pause");
        if (voice != null) voice.pause(value || voicePaused);
        setStatus(value ? "助手已暂停" : "助手已恢复");
    }
    void invalidate(String reason) {
        session.invalidate(); client.resetAudio(session.generation()); abandonVisual();
        host.cancelSpeech(); userSpeaking = false; uploading = false; awaitingFinal = false;
        synchronized (this) { pendingQuestion = null; inFlight = null; }
        host.audit("AssistantInvalidated reason=" + reason + " generation=" + session.generation());
    }
    void thermal(FrameProcessingPolicy.Mode value) {
        boolean changed = mode != value; mode = value;
        if (changed && value == FrameProcessingPolicy.Mode.HOT) {
            captureInvalidated("hot"); main.post(() -> setStatus("温度较高，画面理解暂停；本地预警继续"));
        }
    }
    /** Atomic invalidation under the capture lock; controls execute later outside it. */
    void captureInvalidated(String reason) {
        session.invalidate(); userSpeaking = false; uploading = false; awaitingFinal = false;
        synchronized (this) { pendingQuestion = null; inFlight = null; }
        long generation = session.generation();
        main.post(() -> {
            if (closed || generation != session.generation()) return;
            client.resetAudio(generation); client.cancelVisual(); host.cancelSpeech();
            host.audit("AssistantInvalidated reason=" + reason + " generation=" + generation);
        });
    }
    void question(String text, boolean proactive) {
        if (closed || paused || text == null || text.trim().isEmpty()) {
            host.audit("AssistantInteraction event=QUESTION_IGNORED reason="
                    + (closed ? "closed" : paused ? "paused" : "empty"));
            return;
        }
        host.audit("AssistantInteraction event=QUESTION proactive=" + proactive);
        text = text.trim(); if (text.length() > 240) text = text.substring(0, 240);
        session.invalidate();
        String turn = session.newTurn(); host.cancelSpeech(); abandonVisual();
        uploading = false; userSpeaking = false; awaitingFinal = false;
        client.resetAudio(session.generation());
        synchronized (this) { pendingQuestion = null; }
        if (!proactive) append("你：" + text);
        if (localCommand(text, turn)) return;
        if (!visionEnabled) { sayLocal(turn, "画面理解尚未开启。", false); return; }
        if (!policy.manualAllowed(SystemClock.elapsedRealtime(), mode)) {
            long retryMs = policy.retryAfterMs(SystemClock.elapsedRealtime());
            String reason = mode == FrameProcessingPolicy.Mode.HOT ? "hot"
                    : retryMs > 0 ? "rate_limited" : "session_budget";
            host.audit("AssistantVisual event=DENIED reason=" + reason + " retryAfterMs=" + retryMs);
            sayLocal(turn, mode == FrameProcessingPolicy.Mode.HOT ? "温度较高，画面理解暂时暂停。"
                    : retryMs > 0 ? "画面服务暂时繁忙，请在" + ((retryMs + 999) / 1000) + "秒后重试。"
                    : "本次会话的画面问答次数已用完，请稍后再试。", false); return;
        }
        if (overlay != null && overlay.isExpanded()) overlay.setExpanded(false);
        text = visualQuestion(text);
        AssistantReply previous = lastReply;
        if (previous != null && !"local".equals(previous.kind) && !previous.uncertain
                && previous.freshAt(SystemClock.elapsedRealtime())) {
            String contextText = previous.answer.substring(0, Math.min(120, previous.answer.length()));
            text = "对话参考（不是当前画面事实）：" + contextText + "\n本轮问题：" + text
                    + "\n只以新截图为准，不清楚就回答没看清。";
        }
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
        String command = text.replaceAll("[\\s，。！？!?]", "");
        if (command.matches("(听野|助手)?(你好|您好|在吗|你在吗|在不在|听得到吗|你听得到吗)")) {
            sayLocal(turn, "我在。可以说读取画面，或附近情况。", false); return true;
        }
        if (command.matches("(听野|助手)?(你是谁|你叫什么|能做什么|你能做什么)")) {
            sayLocal(turn, "我是听野，可以读画面中的清晰文字和比分。", false); return true;
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
        AssistantReply previous = lastReply;
        session.invalidate(); client.resetAudio(session.generation());
        String turn = session.newTurn(); host.cancelSpeech(); abandonVisual();
        if (previous == null) { sayLocal(turn, "还没有可重复的回答。", false); return; }
        if (!"local".equals(previous.kind) && !previous.freshAt(SystemClock.elapsedRealtime())) {
            sayLocal(turn, "上一条画面已经过期，请重新问当前情况。", true); return;
        }
        AssistantReply reply = new AssistantReply(session.generation(), turn, previous.frameId,
                "local".equals(previous.kind) ? SystemClock.elapsedRealtime() : previous.capturedAtMs,
                previous.kind, previous.answer, previous.uncertain);
        playReply(reply);
    }
    /** Called only after native processing has released its lock, while the Image is still owned. */
    void offerFrame(ByteBuffer rgba, int width, int height, int stride, long frameId, long capturedAt) {
        if (closed || paused || !visionEnabled || mode == FrameProcessingPolicy.Mode.HOT) return;
        long now = SystemClock.elapsedRealtime(); latestFrameAtMs = capturedAt;
        if (now < capturedAt || now - capturedAt > 1000 || (overlay != null && overlay.isExpanded())) return;
        boolean speaking = userSpeaking || awaitingFinal || host.speaking();
        Rect bounds = overlay == null ? null : overlay.bounds();
        final long signature;
        try { signature = FrameSnapshot.signature(rgba, width, height, stride, bounds); }
        catch (RuntimeException error) { return; }
        final String question, turn; final boolean proactive; final long generation, id;
        synchronized (this) {
            if (inFlight != null) return;
            if (pendingQuestion != null) {
                if (capturedAt < frameNotBeforeMs || !policy.manualAllowed(now, mode)) return;
                question = pendingQuestion; turn = pendingTurn; proactive = pendingProactive; pendingQuestion = null;
            } else {
                if (!proactiveEnabled || !policy.automaticDue(now, mode, speaking,
                        overlay != null && overlay.isExpanded(), host.lastAlertAtMs(), signature)) return;
                question = "只描述这张画面中新出现且清晰的界面文字、比分或界面状态；没有有用变化时回答不确定。";
                turn = session.newTurn(); proactive = true;
            }
            generation = session.generation(); id = ++nextRequestId;
            // Reserve the slot before copying, so no encoder/frame queue can accumulate.
            inFlight = new VisualTask(id, turn, null, proactive);
        }
        final FrameSnapshot frame;
        try { frame = FrameSnapshot.copy(rgba, width, height, stride, proactive ? 960 : 1280,
                frameId, capturedAt, generation, signature, bounds); }
        catch (RuntimeException | OutOfMemoryError error) { visualFailure(id, "frame_unavailable"); return; }
        VisualTask task = new VisualTask(id, turn, frame, proactive);
        synchronized (this) { if (inFlight != null && inFlight.id == id) inFlight = task; }
        try { encoder.execute(() -> {
          try {
            if (closed || frame == null || !session.owns(generation, turn)) { visualFailure(id, "cancelled"); return; }
            byte[] jpeg = frame.jpeg(); long sentAt = SystemClock.elapsedRealtime();
            if (jpeg == null || !visionEnabled || (proactive && !proactiveEnabled) || !session.owns(generation, turn) || mode == FrameProcessingPolicy.Mode.HOT
                    || sentAt - capturedAt > 1000) { visualFailure(id, "frame_unavailable"); return; }
            policy.started(sentAt, proactive, signature);
            host.audit("AssistantVisual event=REQUEST frameId=" + frameId + " generation=" + generation
                    + " frameAgeMs=" + (sentAt - capturedAt) + " proactive=" + proactive);
            client.visual(id, session, turn, frame, jpeg, question, proactive, sentAt);
          } catch (RuntimeException | OutOfMemoryError error) { visualFailure(id, "frame_unavailable"); }
        }); } catch (java.util.concurrent.RejectedExecutionException closedExecutor) { takeTask(id); }
    }
    @Override public void audioMessage(JSONObject message) { main.post(() -> {
        if (closed || !session.sessionId.equals(message.optString("session_id"))) return;
        long generation = message.optLong("generation", -1); String turn = message.optString("turn_id");
        if ("status".equals(message.optString("type")) && generation == session.generation()
                && (turn.isEmpty() || turn.startsWith("reset-g"))) {
            String reason = message.optString("reason");
            setStatus("ready".equals(reason) || "reset".equals(reason) ? "语音服务器已连接" : "语音识别正在恢复，请稍后重说"); return;
        }
        if (!session.owns(generation, turn)) return;
        String type = message.optString("type"); String text = message.optString("text").trim();
        if ("partial".equals(type)) { if (overlay != null) overlay.status(text.isEmpty() ? currentStatus : text); }
        else if ("final".equals(type)) {
            awaitingFinal = false;
            host.audit("AssistantVoice event=FINAL generation=" + generation + " asrMs=" + message.optLong("asr_ms", -1)
                    + " finalizationMs=" + message.optLong("finalization_ms", -1) + " inferenceMs=" + message.optLong("inference_ms", -1)
                    + " textChars=" + text.length() + " requestLike=" + isRequest(text));
            if (text.isEmpty()) { setStatus("没有听清，请重说"); return; }
            // Ordinary statements do not automatically upload screenshots.
            if (isRequest(text)) question(text, false); else setStatus("已听到，等待你的问题");
        } else if ("status".equals(type)) {
            String reason = message.optString("reason");
            if ("asr_busy".equals(reason) || "asr_backlog_reset".equals(reason) || "asr_error".equals(reason)) {
                invalidate("asr_unavailable"); setStatus("语音服务忙，本轮音频已丢弃，请稍后重说");
            } else setStatus("语音识别正在恢复，请稍后重说");
        }
    }); }
    static boolean isRequest(String text) {
        if (text.replaceAll("[\\s，。！？!?]", "").matches(
                "(听野|助手)?(你好|您好|在不在|你是谁|你叫什么|能做什么|你能做什么)")) return true;
        return text.matches(".*(什么|怎么|多少|哪(一)?(个|项|种|些|位|款|边|里)|在哪|有没有|是不是|吗|么|呢|读|看看|告诉|解释|重复|重说|再说|别说|停止播报|停说|安静|暂停助手|附近情况|提醒|提示).*" );
    }
    /** Selection questions read visible options; the assistant does not choose tactics. */
    static String visualQuestion(String text) {
        if (text.matches("(?s).*((选|选择).*哪|哪.*(选|选择|合适|适合|好)).*"))
            return "请读出当前画面中清晰可见的选项名称，不推荐选择，不猜测。";
        return text;
    }
    @Override public void audioUnavailable(String reason) { main.post(() -> {
        if (closed || client.hasAudioConnection()) return;
        invalidate("audio_disconnected"); setStatus("语音服务器未连接，预警继续运行");
    }); }
    @Override public void visualResult(long id, JSONObject result) { main.post(() -> {
        VisualTask task = takeTask(id); if (task == null || task.frame == null || closed) return;
        if (!session.owns(task.frame.generation, task.turn)
                || !session.sessionId.equals(result.optString("session_id"))
                || result.optLong("generation", -1) != task.frame.generation
                || !task.turn.equals(result.optString("turn_id"))
                || result.optLong("frame_id", -1) != task.frame.frameId) return;
        String kind = result.optString("kind", "unknown"), answer = result.optString("answer").trim();
        boolean uncertain = result.optBoolean("uncertain", true);
        if (answer.length() > 400 || (!"hud".equals(kind) && !"ui_text".equals(kind) && !"unknown".equals(kind))) { setStatus("画面回答不可用"); return; }
        AssistantReply reply = new AssistantReply(task.frame.generation, task.turn, task.frame.frameId,
                task.frame.capturedAtMs, kind, answer, uncertain, task.proactive);
        long now = SystemClock.elapsedRealtime();
        if (!reply.freshAt(now)) {
            if (!task.proactive && now > reply.expiresAtMs()) {
                explainExpiredFrame(task.turn, task.frame.frameId,
                        task.frame.capturedAtMs, "result");
            } else {
                setStatus("画面回答已过期，请重问");
            }
            return;
        }
        if (uncertain || "unknown".equals(kind) || answer.isEmpty()) {
            if (!task.proactive) sayLocal(task.turn, "没看清，请重新提问。", true); return;
        }
        if (task.proactive && answer.equals(lastAutomaticAnswer)) return;
        if (task.proactive) lastAutomaticAnswer = answer;
        if (now - reply.capturedAtMs > 2000) reply = new AssistantReply(reply.generation, reply.turnId,
                reply.frameId, reply.capturedAtMs, reply.kind, "约" + ((now - reply.capturedAtMs) / 1000) + "秒前截图显示，" + answer, false, task.proactive);
        lastReply = reply; append("听野：" + reply.answer); setStatus("助手可用"); playReply(reply);
        host.audit("AssistantVisual event=RESULT frameId=" + task.frame.frameId + " frameAgeMs=" + (now - task.frame.capturedAtMs)
                + " elapsedMs=" + result.optLong("elapsed_ms", -1) + " proactive=" + task.proactive);
    }); }
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
    @Override public void visualFailure(long id, String code) { main.post(() -> {
        VisualTask task = takeTask(id); if (task == null || closed) return;
        if ("rate_limited".equals(code) || "1302".equals(code) || "1305".equals(code) || "provider_busy".equals(code)) {
            long now = SystemClock.elapsedRealtime();
            if (policy.retryAfterMs(now) <= 0) policy.throttled(now,
                    "provider_busy".equals(code) || "1305".equals(code) ? AssistantPolicy.OVERLOAD_RETRY_MS : 60_000);
        }
        if (task.frame != null && session.owns(task.frame.generation, task.turn) && !task.proactive && !"cancelled".equals(code)) {
            String explanation = "timeout".equals(code) ? "画面读取超时，请重试。"
                    : "provider_busy".equals(code) || "1305".equals(code) ? "画面模型繁忙，请稍后重试；本地预警正常。"
                    : "rate_limited".equals(code) ? "画面服务繁忙，请稍后重试。"
                    : "画面理解暂时不可用，请稍后再试。";
            setStatus(explanation);
            sayLocal(task.turn, explanation, true);
        }
        host.audit("AssistantVisual event=FAILED code=" + safeCode(code));
    }); }
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
    private void abandonVisual() { synchronized (this) { inFlight = null; } client.cancelVisual(); }
    private void playReply(AssistantReply reply) {
        if (!allows(reply)) return;
        if (!host.speechReady()) { setStatus("缺少可用的离线中文语音，请安装语音包；回答保留在面板"); return; }
        host.speak(reply);
    }
    boolean allows(AssistantReply reply) { return !closed && !paused && (voiceEnabled || visionEnabled) && session.owns(reply.generation, reply.turnId)
            && reply.freshAt(SystemClock.elapsedRealtime()) && ("local".equals(reply.kind) || mode != FrameProcessingPolicy.Mode.HOT)
            && (!reply.proactive || (!userSpeaking && !awaitingFinal && (overlay == null || !overlay.isExpanded())
                && (host.lastAlertAtMs() < 0 || SystemClock.elapsedRealtime() - host.lastAlertAtMs() >= AssistantPolicy.ALERT_QUIET_MS))); }
    private void append(String line) { while (history.size() >= 12) history.removeFirst(); history.addLast(line);
        if (overlay != null) overlay.history(String.join("\n\n", history)); }
    private void setStatus(String value) {
        if (closed) return;
        VoiceInputSafetyPolicy.InputState state = inputState;
        if (voiceEnabled && state != null && state != VoiceInputSafetyPolicy.InputState.READY
                && !value.equals(state.status)) value += "；" + state.status;
        currentStatus = value;
        host.status(value);
        if (overlay != null) overlay.status(value);
    }
    @Override public void close() {
        closed = true; uploading = false; session.close();
        main.removeCallbacksAndMessages(null); main.post(() -> {
            client.close(); if (voice != null) voice.close(); encoder.shutdownNow();
            if (overlay != null) overlay.close(); history.clear();
        });
    }
}
