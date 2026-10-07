package com.openkhub.sensefield;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

final class CuePlayer implements CueDispatcher.Renderer {
    static final String PREF_TTS_ENGINE = "cue_tts_engine";
    static final String PREF_TTS_RATE = "cue_speech_rate_percent";
    static final int DEFAULT_TTS_RATE_PERCENT = 180;
    static final long NEAR_HAPTIC_ON_MS = HapticPolicy.NEAR_DEFAULT_ON_MS;
    static final long NEAR_HAPTIC_GAP_MS = HapticPolicy.NEAR_DEFAULT_GAP_MS;
    private static final String TAG = "MapAssistAudio";
    private static final long TONE_DURATION_MS = 90;
    private static final long SPEECH_TIMEOUT_MS = 4000;
    private static final long ASSISTANT_SYNTHESIS_TIMEOUT_MS = 15_000;
    private static final long ASSISTANT_PLAYBACK_DRAIN_TIMEOUT_MS = 2_000;
    private static final Object HAPTIC_OWNER_LOCK = new Object();
    /** Vibrator.cancel() is process-wide for this app, so only the latest CuePlayer may cancel. */
    private static CuePlayer activeHapticOwner;

    private final Context context;
    private final SoundPool pool;
    private final Object audioLock = new Object();
    private final Map<Integer, Integer> tones = new HashMap<>();
    private final Map<Integer, Long> toneDurations = new HashMap<>();
    private final Map<Integer, String> toneStyles = new HashMap<>();
    private final Map<AudioTrack, SpatialTonePlayback> spatialToneTracks = new HashMap<>();
    private final SpatialToneCache spatialToneCache;
    private final boolean spatialEnabledAtStart;
    private final boolean warmAlertSpeech;
    private final String nearSoundAtStart;
    private volatile String alertVoiceKey;
    private volatile AlertWarmJob alertWarmJob;
    private volatile boolean alertWarmingStopped;
    private int alertWarmIndex;
    private List<String> alertWarmPhrases = Collections.emptyList();
    private final ExecutorService alertCacheWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "local-alert-cache");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService alertAudioWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "local-alert-audio");
        thread.setDaemon(true);
        return thread;
    });
    private PreparedAlertPlayback preparedAlert;
    private volatile boolean preparedPcmFailed;
    private final Map<String, CueDispatcher.PlaybackCallback> speechCallbacks =
            new ConcurrentHashMap<>();
    private final Map<String, AssistantReply> assistantGroups = new ConcurrentHashMap<>();
    private final Map<String, AssistantUtterance> assistantUtterances =
            new ConcurrentHashMap<>();
    private final Object assistantTtsSubmissionLock = new Object();
    private final Set<Integer> ready = ConcurrentHashMap.newKeySet();
    private final Set<Integer> failed = ConcurrentHashMap.newKeySet();
    private final PendingToneQueue pendingTones = new PendingToneQueue();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService assistantAudioWorker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "assistant-audio-output");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService spatialTonePrewarmWorker;
    private TextToSpeech tts;
    private AudioTrack assistantTrack;
    private volatile AssistantPlaybackListener assistantPlaybackListener;
    private volatile AssistantTtsTimingListener assistantTtsTimingListener;
    private volatile boolean closed;
    private volatile boolean ttsReady;
    private volatile boolean offlineTtsReady;
    private volatile boolean speechPreparationFinished;
    private volatile String speechStatus = "正在检查离线中文语音……";
    private volatile String ttsAuditState;
    private String requestedVoiceEngine = "";
    private volatile Consumer<String> audioAuditListener;
    private volatile int ttsPreparationGeneration;
    private final Set<String> attemptedVoiceEngines = ConcurrentHashMap.newKeySet();
    private List<String> voiceEngines = Collections.emptyList();

    interface AssistantPlaybackListener {
        /** Receives a copied 160-sample mono render frame at 16 kHz. */
        void onPcmReference(short[] frame16kMono);
    }

    interface AssistantTtsTimingListener {
        /** Receives a text-free phase timestamp measured with elapsedRealtime(). */
        void onAssistantTtsTiming(String cueId, int segmentIndex, String phase, long monoMs);
    }

    private static final class AssistantReply {
        final CueRequest request;
        final CueDispatcher.PlaybackCallback callback;
        final AssistantTtsPipeline.Group group;

        AssistantReply(CueRequest request, CueDispatcher.PlaybackCallback callback,
                       AssistantTtsPipeline.Group group) {
            this.request = request;
            this.callback = callback;
            this.group = group;
        }
    }

    private static final class AssistantUtterance {
        final String utteranceId;
        final AssistantReply reply;
        final AssistantTtsPipeline.Segment segment;
        final File outputFile;
        Runnable synthesisTimeout;

        AssistantUtterance(String utteranceId, AssistantReply reply,
                           AssistantTtsPipeline.Segment segment, File outputFile) {
            this.utteranceId = utteranceId;
            this.reply = reply;
            this.segment = segment;
            this.outputFile = outputFile;
        }
    }

    private static final class AssistantDropEvent {
        final String cueId;
        final AssistantTtsPipeline.DropSummary dropped;
        final String reason;

        AssistantDropEvent(String cueId, AssistantTtsPipeline.DropSummary dropped,
                           String reason) {
            this.cueId = cueId;
            this.dropped = dropped;
            this.reason = reason;
        }
    }

    private static final class AssistantFailure {
        final AssistantReply reply;
        final AssistantTtsPipeline.Transition transition;

        AssistantFailure(AssistantReply reply, AssistantTtsPipeline.Transition transition) {
            this.reply = reply;
            this.transition = transition;
        }
    }

    private static final class SpatialTonePlayback {
        final CueRequest.Category category;
        final CueDispatcher.PlaybackCallback callback;

        SpatialTonePlayback(CueRequest.Category category,
                            CueDispatcher.PlaybackCallback callback) {
            this.category = category;
            this.callback = callback;
        }
    }

    private static final class AlertWarmJob {
        final String id;
        final String phrase;
        final String voiceKey;
        final File file;
        final TextToSpeech engine;
        Runnable timeout;
        AlertWarmJob(String id, String phrase, String voiceKey, File file, TextToSpeech engine) {
            this.id = id; this.phrase = phrase; this.voiceKey = voiceKey;
            this.file = file; this.engine = engine;
        }
    }

    private static final class PreparedAlertPlayback {
        final CueRequest request;
        final short[] speech;
        final CueDispatcher.PlaybackCallback toneCallback;
        final CueDispatcher.PlaybackCallback speechCallback;
        volatile boolean cancelled;
        volatile AudioTrack track;
        PreparedAlertPlayback(CueRequest request, short[] speech,
                CueDispatcher.PlaybackCallback tone, CueDispatcher.PlaybackCallback voice) {
            this.request = request; this.speech = speech;
            this.toneCallback = tone; this.speechCallback = voice;
        }
    }

    CuePlayer(Context context) {
        this(context, false, true);
    }

    CuePlayer(Context context, boolean warmAlertSpeech) {
        this(context, warmAlertSpeech, true);
    }

    static CuePlayer tonePreview(Context context) { return new CuePlayer(context, false, false); }

    static CuePlayer speechProbe(Context context) { return new CuePlayer(context, false, true, false); }

    private CuePlayer(Context context, boolean warmAlertSpeech, boolean prepareSpeech) {
        this(context, warmAlertSpeech, prepareSpeech, true);
    }

    private CuePlayer(Context context, boolean warmAlertSpeech, boolean prepareSpeech, boolean prepareTones) {
        this.context = context.getApplicationContext();
        this.warmAlertSpeech = warmAlertSpeech;
        nearSoundAtStart = CueSoundLibrary.valid(GameProfile.settings(this.context)
                .getString(CueSoundLibrary.preferenceKey(7), "classic"));
        spatialToneCache = new SpatialToneCache(nearSoundAtStart);
        scheduleAssistantTtsRecovery();
        spatialEnabledAtStart = prepareSpeech && prepareTones && GameProfile.settings(this.context)
                .getBoolean(PresentationAudioPolicy.PREF_SPATIAL, false);
        spatialTonePrewarmWorker = spatialEnabledAtStart
                ? Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "spatial-tone-cache");
                    thread.setDaemon(true);
                    return thread;
                }) : null;
        AudioAttributes attributes = new AudioAttributes.Builder()
                // Game cues follow media volume, including when the ringer is silent.
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build();
        pool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
            synchronized (audioLock) {
                if (closed) return;
                if (status == 0) ready.add(sampleId);
                else {
                    failed.add(sampleId);
                    Log.w(TAG, "Could not load cue tone sample=" + sampleId);
                }
                long now = SystemClock.elapsedRealtime();
                PendingToneQueue.Pending pending = pendingTones.take(sampleId);
                if (pending != null) {
                    boolean played = status == 0 && now <= pending.expiresAtMs
                            && playToneLocked(pending.kind, pending.direction, pending.pan);
                    finishToneAttempt(pending.callback, now, played, pending.kind);
                    Log.i(TAG, "Played cue after SoundPool load kind=" + pending.kind
                            + " queued=" + played);
                }
            }
        });
        if (prepareTones) try {
            loadTone(1, "main", 840);
            loadTone(2, "map", 600);
            loadTone(3, "ping", 1100);
            loadTone(4, "death", 360);
            loadTone(5, "respawn", 920);
            // With speech off, the bright double beep means a nearby enemy.
            loadTone(NearZoneRouting.TONE_NEAR, "near", 900, 900, 240, 2);
        } catch (IOException error) {
            Log.e(TAG, "Cannot prepare cue tones", error);
        }
        if (prepareSpeech) prepareVoices();
        if (spatialTonePrewarmWorker != null) {
            spatialTonePrewarmWorker.execute(() -> {
                if (closed) return;
                spatialToneCache.prewarm();
                Log.i(TAG, "Prewarmed opt-in spatial tone cache entries="
                        + spatialToneCache.entryCountForTest());
            });
        }
    }

    private void scheduleAssistantTtsRecovery() {
        if (!AssistantTtsCache.claimProcessStartupSweep()) return;
        File cacheDirectory = context.getCacheDir();
        recoverAssistantTtsFiles(cacheDirectory);
        // A just-crashed TTS engine may still hold its output descriptor briefly. Retry after a
        // grace period; paths owned by live utterances are tracked and skipped by the sweeper.
        mainHandler.postDelayed(() -> recoverAssistantTtsFiles(cacheDirectory),
                AssistantTtsCache.ORPHAN_GRACE_MS);
    }

    private static void recoverAssistantTtsFiles(File cacheDirectory) {
        try {
            int deleted = AssistantTtsCache.deleteStaleFiles(
                    cacheDirectory, System.currentTimeMillis());
            if (deleted > 0)
                Log.i(TAG, "Removed stale assistant TTS cache files count=" + deleted);
        } catch (IOException error) {
            Log.w(TAG, "Could not recover stale assistant TTS cache files", error);
        }
    }

    private void loadTone(int kind, String name, int frequency) throws IOException {
        loadTone(kind, name, frequency, frequency, TONE_DURATION_MS);
    }

    private void loadTone(int kind, String name, int startFrequency, int endFrequency,
                          long durationMs) throws IOException {
        loadTone(kind, name, startFrequency, endFrequency, durationMs, 1);
    }

    private void loadTone(int kind, String name, int startFrequency, int endFrequency,
                          long durationMs, int pulses) throws IOException {
        String style = CueSoundLibrary.valid(GameProfile.settings(context)
                .getString(CueSoundLibrary.preferenceKey(kind), "classic"));
        toneStyles.put(kind, style);
        long actualDuration = "classic".equals(style) ? durationMs : CueSoundLibrary.durationMs(kind, style);
        File file = "classic".equals(style)
                ? writeTone(name, startFrequency, endFrequency, durationMs, pulses)
                : writeTonePcm(name + "_" + style, CueSoundLibrary.render(kind, style, 48000));
        int sample = pool.load(file.getAbsolutePath(), 1);
        if (sample == 0) Log.e(TAG, "SoundPool rejected cue tone kind=" + kind);
        else {
            tones.put(kind, sample);
            toneDurations.put(kind, actualDuration);
        }
    }

    private File writeTonePcm(String name, short[] samples) throws IOException {
        File file = new File(context.getCacheDir(), "cue_" + name + ".wav");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("RIFF".getBytes(StandardCharsets.US_ASCII));
            littleEndian32(output, 36 + samples.length * 2);
            output.write("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
            littleEndian32(output, 16);
            littleEndian16(output, 1); littleEndian16(output, 1);
            littleEndian32(output, 48000); littleEndian32(output, 96000);
            littleEndian16(output, 2); littleEndian16(output, 16);
            output.write("data".getBytes(StandardCharsets.US_ASCII));
            littleEndian32(output, samples.length * 2);
            byte[] data = new byte[samples.length * 2];
            for (int i = 0; i < samples.length; i++) {
                data[i * 2] = (byte) samples[i];
                data[i * 2 + 1] = (byte) (samples[i] >> 8);
            }
            output.write(data);
        }
        return file;
    }

    private File writeTone(String name, int startFrequency, int endFrequency,
                           long durationMs, int pulses) throws IOException {
        final int sampleRate = 48000;
        final int samples = (int) (sampleRate * durationMs / 1000);
        final int dataBytes = samples * 2;
        File file = new File(context.getCacheDir(), "cue_" + name + ".wav");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write("RIFF".getBytes(StandardCharsets.US_ASCII));
            littleEndian32(output, 36 + dataBytes);
            output.write("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
            littleEndian32(output, 16);
            littleEndian16(output, 1);
            littleEndian16(output, 1);
            littleEndian32(output, sampleRate);
            littleEndian32(output, sampleRate * 2);
            littleEndian16(output, 2);
            littleEndian16(output, 16);
            output.write("data".getBytes(StandardCharsets.US_ASCII));
            littleEndian32(output, dataBytes);
            // Integrate the instantaneous frequency so a sweep stays click-free;
            // equal start/end frequencies reproduce the original steady tone.
            double phase = 0.0;
            int gap = pulses > 1 ? sampleRate * 65 / 1000 : 0;
            int pulseSamples = (samples - gap * (pulses - 1)) / pulses;
            byte[] data = new byte[dataBytes];
            for (int i = 0; i < samples; i++) {
                int inPulse = i % (pulseSamples + gap);
                double envelope = inPulse >= pulseSamples ? 0.0
                        : Math.min(1.0, inPulse / 400.0)
                        * Math.min(1.0, (pulseSamples - inPulse) / 800.0);
                double frequency = startFrequency
                        + (endFrequency - startFrequency) * (double) i / samples;
                short value = (short) (Math.sin(phase) * 13000 * envelope);
                phase += 2 * Math.PI * frequency / sampleRate;
                data[i * 2] = (byte) value;
                data[i * 2 + 1] = (byte) (value >> 8);
            }
            output.write(data);
        }
        return file;
    }

    static final class SpatialToneCache {
        private static final int SAMPLE_RATE = 48_000;
        private static final int SAMPLE_COUNT = SAMPLE_RATE * 240 / 1000;
        private static final int PAN_BINS = 9;
        private static final int DISTANCE_BINS = 4;
        private static final int URGENCY_BINS = 3;
        private static final int ENTRY_COUNT = PAN_BINS * DISTANCE_BINS * URGENCY_BINS;
        private final String style;
        SpatialToneCache() { this("classic"); }
        SpatialToneCache(String style) { this.style = CueSoundLibrary.valid(style); }
        private volatile Map<Integer, short[]> pcm = Collections.emptyMap();
        private volatile boolean ready;

        /** Build the complete finite lookup off the audio thread, then publish it atomically. */
        synchronized void prewarm() {
            if (ready) return;
            Map<Integer, short[]> generated = new HashMap<>(ENTRY_COUNT);
            for (int panBin = 0; panBin < PAN_BINS; panBin++) {
                for (int distanceBin = 0; distanceBin < DISTANCE_BINS; distanceBin++) {
                    for (int urgencyBin = 0; urgencyBin < URGENCY_BINS; urgencyBin++) {
                        int key = key(panBin, distanceBin, urgencyBin);
                        generated.put(key, render(panBin / 4f - 1f,
                                distanceBin / 3f, urgencyBin / 2f));
                    }
                }
            }
            pcm = Collections.unmodifiableMap(generated);
            ready = true;
        }

        boolean isReady() { return ready; }

        /** Cache-only lookup. A miss never triggers synthesis or changes cache contents. */
        short[] get(float pan, float distance, float urgency) {
            if (!ready) return null;
            float boundedPan = Float.isFinite(pan) ? Math.max(-1f, Math.min(1f, pan)) : 0f;
            int panBin = Math.max(0, Math.min(8, Math.round((boundedPan + 1f) * 4f)));
            float boundedDistance = Float.isFinite(distance)
                    ? Math.max(0f, Math.min(1f, distance)) : 0f;
            int distanceBin = Math.round(boundedDistance * 3f);
            int urgencyBin = Math.max(0, Math.min(2, Math.round(urgency)));
            return pcm.get(key(panBin, distanceBin, urgencyBin));
        }

        int entryCountForTest() { return pcm.size(); }

        private static int key(int panBin, int distanceBin, int urgencyBin) {
            return (panBin << 4) | (distanceBin << 2) | urgencyBin;
        }

        private short[] render(float pan, float distance, float urgency) {
            double[] source;
            if (!"classic".equals(style)) {
                short[] mono = CueSoundLibrary.render(7, style, SAMPLE_RATE);
                source = new double[mono.length];
                for (int i = 0; i < mono.length; i++) source[i] = mono[i];
            } else {
                double frequency = 780.0 + urgency * 120.0;
                double phase = 0.0;
                int pulseSamples = SAMPLE_RATE * 875 / 10_000;
                int gapSamples = SAMPLE_RATE * 65 / 1000;
                source = new double[SAMPLE_COUNT];
                for (int i = 0; i < SAMPLE_COUNT; i++) {
                    int pulseOffset = i < pulseSamples ? i
                            : i >= pulseSamples + gapSamples ? i - pulseSamples - gapSamples : -1;
                    double envelope = pulseOffset < 0 || pulseOffset >= pulseSamples ? 0.0
                            : Math.min(1.0, pulseOffset / 400.0)
                            * Math.min(1.0, (pulseSamples - pulseOffset) / 800.0);
                    source[i] = Math.sin(phase) * 11_000.0 * envelope;
                    phase += 2.0 * Math.PI * frequency / SAMPLE_RATE;
                }
            }
            int sampleCount = source.length;

            double panMagnitude = Math.abs(pan);
            int interauralDelay = (int) Math.round(panMagnitude * 18.0);
            double farAlpha = 1.0 - Math.exp(-2.0 * Math.PI
                    * (8_000.0 - 5_000.0 * distance) / SAMPLE_RATE);
            double[] farFiltered = new double[sampleCount];
            double previous = 0.0;
            for (int i = 0; i < sampleCount; i++) {
                previous += farAlpha * (source[i] - previous);
                farFiltered[i] = previous;
            }

            double distanceGain = 1.0 - 0.22 * distance;
            double farGain = 1.0 - 0.78 * panMagnitude;
            short[] stereo = new short[sampleCount * 2];
            for (int i = 0; i < sampleCount; i++) {
                int farIndex = i - interauralDelay;
                double near = source[i] * distanceGain;
                double far = (farIndex < 0 ? 0.0 : farFiltered[farIndex])
                        * distanceGain * farGain;
                double left = pan < 0f ? near : pan > 0f ? far : near;
                double right = pan > 0f ? near : pan < 0f ? far : near;
                stereo[i * 2] = saturate(left);
                stereo[i * 2 + 1] = saturate(right);
            }
            return stereo;
        }

        private static short saturate(double value) {
            return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(value)));
        }
    }

    private static void littleEndian16(FileOutputStream output, int value) throws IOException {
        output.write(value & 255);
        output.write((value >> 8) & 255);
    }

    private static void littleEndian32(FileOutputStream output, int value) throws IOException {
        littleEndian16(output, value);
        littleEndian16(output, value >> 16);
    }

    private void prepareVoices() {
        List<String> candidates = new ArrayList<>();
        String selected = GameProfile.settings(context).getString(PREF_TTS_ENGINE, "");
        requestedVoiceEngine = selected;
        List<String> installed = new ArrayList<>();
        for (android.content.pm.ResolveInfo info : context.getPackageManager().queryIntentServices(
                new android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)) {
            String name = info.serviceInfo.packageName;
            if (!installed.contains(name)) installed.add(name);
        }
        if (!selected.isEmpty() && installed.contains(selected)) candidates.add(selected);
        candidates.add(""); // Try the system default before other installed engines.
        for (String name : installed) if (!candidates.contains(name)) candidates.add(name);
        voiceEngines = candidates;
        prepareVoiceEngine(0);
    }

    private void prepareVoiceEngine(int index) {
        if (closed) return;
        if (index >= voiceEngines.size()) {
            speechPreparationFinished = true;
            speechStatus = "未找到已安装的离线中文语音。请安装语音数据；提示音和振动仍可用。";
            ttsAuditState = "AlertTts status=unavailable reason=no_installed_offline_chinese_voice";
            auditAudio(ttsAuditState);
            return;
        }
        String selected = voiceEngines.get(index);
        int generation = ++ttsPreparationGeneration;
        final TextToSpeech[] holder = new TextToSpeech[1];
        try {
            TextToSpeech engine = new TextToSpeech(context, status -> mainHandler.post(() -> {
                TextToSpeech voice = holder[0];
                if (closed || generation != ttsPreparationGeneration || voice == null) return;
                if (status != TextToSpeech.SUCCESS) {
                    advanceVoiceEngine(voice, index, generation);
                    return;
                }
                try {
                    alertCacheWorker.execute(() -> configureOfflineVoice(voice, selected, index, generation));
                } catch (RuntimeException stopped) {
                    if (!closed) advanceVoiceEngine(voice, index, generation);
                }
            }), selected.isEmpty() ? null : selected);
            holder[0] = engine;
            synchronized (audioLock) { if (!closed) tts = engine; }
            if (closed) { engine.shutdown(); return; }
            mainHandler.postDelayed(() -> {
                if (!closed && generation == ttsPreparationGeneration && !ttsReady) {
                    auditAudio("AlertTts status=engine_timeout candidate=" + index);
                    advanceVoiceEngine(engine, index, generation);
                }
            }, 5000);
        } catch (RuntimeException error) {
            auditAudio("AlertTts status=init_failed candidate=" + index);
            prepareVoiceEngine(index + 1);
        }
    }

    private static boolean offlineChinese(Voice voice) {
        return voice != null && OfflineTtsPolicy.allows(voice.getLocale(),
                voice.isNetworkConnectionRequired(), voice.getFeatures() != null
                        && voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));
    }

    /** Voice discovery and synthesis never hold the lock used by the recognition thread. */
    private void configureOfflineVoice(TextToSpeech engine, String selected, int index, int generation) {
        try {
            String engineName = selected.isEmpty() ? engine.getDefaultEngine() : selected;
            if (!attemptedVoiceEngines.add(engineName == null ? "" : engineName)) {
                mainHandler.post(() -> advanceVoiceEngine(engine, index, generation));
                return;
            }
            Set<Voice> voices = engine.getVoices();
            Voice offline = voices == null ? null : voices.stream()
                    .filter(CuePlayer::offlineChinese)
                    .sorted(java.util.Comparator.<Voice>comparingInt(
                            value -> OfflineTtsPolicy.localeOrder(value.getLocale()))
                            .thenComparing(Voice::getName)).findFirst().orElse(null);
            if (offline == null && offlineChinese(engine.getVoice())) offline = engine.getVoice();
            if (offline == null || engine.setVoice(offline) != TextToSpeech.SUCCESS
                    || !offlineChinese(engine.getVoice())) {
                mainHandler.post(() -> advanceVoiceEngine(engine, index, generation));
                return;
            }
            int speechRate = Math.max(80, Math.min(240, GameProfile.settings(context)
                    .getInt(PREF_TTS_RATE, DEFAULT_TTS_RATE_PERCENT)));
            if (engine.setSpeechRate(speechRate / 100f) != TextToSpeech.SUCCESS)
                throw new IllegalStateException("Offline speech rate rejected");
            engine.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            installTtsListener(engine);
            String voiceName = engine.getVoice().getName();
            String label = engineName;
            try {
                label = context.getPackageManager().getApplicationLabel(
                        context.getPackageManager().getApplicationInfo(engineName, 0)).toString();
            } catch (android.content.pm.PackageManager.NameNotFoundException | NullPointerException ignored) {}
            final String engineLabel = label;
            final boolean usedFallback = index > 0 || !requestedVoiceEngine.isEmpty()
                    && !requestedVoiceEngine.equals(engineName);
            mainHandler.post(() -> {
                synchronized (audioLock) {
                    if (closed || generation != ttsPreparationGeneration || tts != engine) return;
                    alertVoiceKey = engineName + ":" + voiceName + ":" + speechRate;
                    alertWarmPhrases = AlertSpeechCache.phrases(GameProfile.settings(context)
                            .getBoolean(PresentationAudioPolicy.PREF_NEAR_TWO_WORD, false));
                    offlineTtsReady = true;
                    ttsReady = true;
                    speechPreparationFinished = true;
                    speechStatus = "已就绪：离线中文语音（" + engineLabel
                            + (usedFallback ? "，自动选用" : "") + "）。";
                    ttsAuditState = "AlertTts status=ready offline=true engine=" + engineName
                            + " voice=" + voiceName + " ratePercent=" + speechRate
                            + " fallback=" + usedFallback;
                }
                auditAudio(ttsAuditState);
                if (warmAlertSpeech) scheduleAlertWarm(0);
            });
        } catch (RuntimeException error) {
            auditAudio("AlertTts status=voice_failed candidate=" + index
                    + " reason=" + error.getClass().getSimpleName());
            mainHandler.post(() -> advanceVoiceEngine(engine, index, generation));
        }
    }

    private void advanceVoiceEngine(TextToSpeech engine, int index, int generation) {
        if (closed || generation != ttsPreparationGeneration || ttsReady) return;
        ttsPreparationGeneration++;
        synchronized (audioLock) { if (tts == engine) tts = null; }
        try { engine.shutdown(); } catch (RuntimeException ignored) {}
        prepareVoiceEngine(index + 1);
    }

    private void installTtsListener(TextToSpeech engine) {
        engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {
                if (id.startsWith("near-cache:")) return;
                CueDispatcher.PlaybackCallback callback = speechCallbacks.get(id);
                if (callback != null) callback.onStarted(SystemClock.elapsedRealtime());
            }
            @Override public void onDone(String id) {
                if (id.startsWith("near-cache:")) { finishAlertWarm(id, true); return; }
                AssistantUtterance assistant = assistantUtterances.get(id);
                if (assistant != null) {
                    boolean readyForPlayback;
                    synchronized (audioLock) {
                        readyForPlayback = isCurrentAssistantLocked(assistant)
                                && assistant.reply.group.synthesisReady(
                                assistant.segment.index);
                        if (readyForPlayback)
                            mainHandler.removeCallbacks(
                                    assistant.synthesisTimeout);
                    }
                    if (!readyForPlayback) return;
                    logAssistantSegment("ready", assistant);
                    notifyAssistantTtsTiming(assistant, "synthesis-ready");
                    try {
                        assistantAudioWorker.execute(
                                () -> playAssistantFile(assistant));
                    } catch (RuntimeException rejected) {
                        failAssistantSegment(assistant, "worker_rejected", false, true);
                    }
                    return;
                }
                CueDispatcher.PlaybackCallback callback = speechCallbacks.remove(id);
                if (callback != null)
                    callback.onFinished(SystemClock.elapsedRealtime(), true);
            }
            @Override public void onError(String id) {
                if (id.startsWith("near-cache:")) { finishAlertWarm(id, false); return; }
                AssistantUtterance assistant = assistantUtterances.get(id);
                if (assistant != null) {
                    failAssistantSegment(assistant, "synthesis_error", false, true);
                    Log.w(TAG, "Offline assistant TTS synthesis failed for " + id);
                    return;
                }
                CueDispatcher.PlaybackCallback callback = speechCallbacks.remove(id);
                if (callback != null)
                    callback.onFinished(SystemClock.elapsedRealtime(), false);
                Log.w(TAG, "TTS failed for " + id);
            }
            @Override public void onStop(String id, boolean interrupted) {
                if (id.startsWith("near-cache:")) { finishAlertWarm(id, false); return; }
                AssistantUtterance assistant = assistantUtterances.get(id);
                if (assistant != null) {
                    failAssistantSegment(assistant, "synthesis_stopped", false, true);
                    return;
                }
                CueDispatcher.PlaybackCallback callback = speechCallbacks.remove(id);
                if (callback != null)
                    callback.onFinished(SystemClock.elapsedRealtime(), false);
            }
        });
    }

    void setAudioAuditListener(Consumer<String> listener) {
        audioAuditListener = listener;
        if (listener != null && ttsAuditState != null) listener.accept(ttsAuditState);
    }

    private void auditAudio(String message) {
        Log.i(TAG, message);
        Consumer<String> listener = audioAuditListener;
        if (listener != null) {
            try { listener.accept(message); }
            catch (RuntimeException ignored) { Log.w(TAG, "Audio audit listener unavailable"); }
        }
    }

    private void scheduleAlertWarm(long delayMs) {
        if (!warmAlertSpeech || closed || alertWarmingStopped) return;
        mainHandler.postDelayed(() -> {
            if (closed || alertWarmingStopped) return;
            try { alertCacheWorker.execute(this::warmNextAlert); }
            catch (RuntimeException stopped) { /* Closing a player cancels cache work. */ }
        }, delayMs);
    }

    private void warmNextAlert() {
        if (closed || alertWarmingStopped || !offlineTtsReady || alertWarmJob != null) return;
        synchronized (audioLock) {
            if (!speechCallbacks.isEmpty() || !assistantGroups.isEmpty() || preparedAlert != null) {
                scheduleAlertWarm(500);
                return;
            }
        }
        List<String> phrases = alertWarmPhrases;
        while (alertWarmIndex < phrases.size()) {
            String phrase = phrases.get(alertWarmIndex++);
            if (AlertSpeechCache.get(alertVoiceKey, phrase) != null) continue;
            try {
                File file = File.createTempFile("near-tts-", ".wav", context.getCacheDir());
                AlertWarmJob job = new AlertWarmJob("near-cache:" + System.nanoTime(),
                        phrase, alertVoiceKey, file, tts);
                synchronized (audioLock) {
                    if (closed || !ttsReady || !offlineTtsReady || tts == null) { file.delete(); return; }
                    alertWarmJob = job;
                }
                // Exactly one fixed-phrase synthesis is in flight, never a backlog of 18 jobs.
                int result = job.engine.synthesizeToFile(phrase, new Bundle(), file, job.id);
                if (result != TextToSpeech.SUCCESS) { finishAlertWarm(job.id, false); return; }
                job.timeout = () -> {
                    if (alertWarmJob != job) return;
                    alertWarmingStopped = true;
                    finishAlertWarm(job.id, false);
                    // Do not stop the engine here: an urgent live utterance may now own it.
                    auditAudio("AlertCache stopped=engine_timeout ready="
                            + AlertSpeechCache.readyCount(alertVoiceKey));
                };
                mainHandler.postDelayed(job.timeout, 5000);
                return;
            } catch (IOException | RuntimeException error) {
                alertWarmingStopped = true;
                AlertWarmJob active = alertWarmJob;
                if (active != null) finishAlertWarm(active.id, false);
                Log.w(TAG, "AlertCache unavailable=" + error.getClass().getSimpleName());
                return;
            }
        }
        auditAudio("AlertCache prepared attempted=" + alertWarmIndex + " ready="
                + AlertSpeechCache.readyCount(alertVoiceKey) + " expected=" + phrases.size());
    }

    private void finishAlertWarm(String id, boolean success) {
        final AlertWarmJob job;
        synchronized (audioLock) {
            job = alertWarmJob;
            if (job == null || !job.id.equals(id)) return;
            alertWarmJob = null;
        }
        if (job.timeout != null) mainHandler.removeCallbacks(job.timeout);
        try {
            alertCacheWorker.execute(() -> {
                try {
                    if (success && !closed) {
                        if (job.file.length() > 1_540_000)
                            throw new IOException("Fixed phrase WAV exceeds budget");
                        PcmWav wav = readPcmWav(job.file);
                        AlertSpeechCache.put(job.voiceKey, job.phrase,
                                AlertSpeechCache.prepare(wav.samples, wav.sampleRateHz));
                    }
                } catch (IOException | IllegalArgumentException error) {
                    Log.w(TAG, "AlertCache phrase_unavailable=" + error.getClass().getSimpleName());
                } finally { job.file.delete(); }
                // Let ordinary speech use the engine before preparing another phrase.
                scheduleAlertWarm(60);
            });
        } catch (RuntimeException stopped) { job.file.delete(); }
    }

    @Override public boolean hasPreparedSpeech(CueRequest request) {
        return !closed && !preparedPcmFailed && request.category == CueRequest.Category.NEAR_ZONE
                && AlertSpeechCache.get(alertVoiceKey, request.speech) != null;
    }

    @Override public boolean speakSynchronized(CueRequest request,
            CueDispatcher.PlaybackCallback tone, CueDispatcher.PlaybackCallback speech) {
        return enqueuePreparedAlert(request, tone, speech);
    }

    private boolean enqueuePreparedAlert(CueRequest request,
            CueDispatcher.PlaybackCallback tone, CueDispatcher.PlaybackCallback speech) {
        short[] pcm = AlertSpeechCache.get(alertVoiceKey, request.speech);
        if (closed || pcm == null || !request.playbackAllowedAt(SystemClock.elapsedRealtime())) return false;
        PreparedAlertPlayback playback = new PreparedAlertPlayback(request, pcm, tone, speech);
        synchronized (audioLock) {
            if (closed || preparedAlert != null) return false;
            preparedAlert = playback;
        }
        try {
            alertAudioWorker.execute(() -> playPreparedAlert(playback));
            return true;
        } catch (RuntimeException rejected) {
            synchronized (audioLock) { if (preparedAlert == playback) preparedAlert = null; }
            return false;
        }
    }

    private short[] preparedTone(CueRequest request) {
        String style = toneStyles.getOrDefault(request.toneKind, "classic");
        if (spatialEnabledAtStart && style.equals(nearSoundAtStart)
                && PresentationAudioPolicy.from(GameProfile.settings(context)).spatial) {
            short[] spatial = spatialToneCache.get(request.pan,
                    NearZoneRouting.presentationDistanceLevel(request.distance), request.urgency);
            if (spatial != null) return spatial;
        }
        short[] mono = CueSoundLibrary.render(request.toneKind, style, 48000);
        float pan = request.hasPan() ? request.pan : request.direction == 1 ? -1f
                : request.direction == 2 ? 1f : 0f;
        float[] gains = NearZoneRouting.stereoGains(pan, 1f);
        short[] stereo = new short[mono.length * 2];
        for (int i = 0; i < mono.length; i++) {
            stereo[i * 2] = (short) Math.round(mono[i] * gains[0]);
            stereo[i * 2 + 1] = (short) Math.round(mono[i] * gains[1]);
        }
        return stereo;
    }

    private void playPreparedAlert(PreparedAlertPlayback playback) {
        AudioTrack track = null;
        boolean success = false, started = false, toneFinished = false;
        try {
            if (playback.cancelled || closed) return;
            short[] tone = playback.toneCallback == null ? null : preparedTone(playback.request);
            short[] mixed = AlertSpeechCache.mix(playback.speech, tone);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(mixed.length * 2).build();
            track.setVolume(volume());
            if (track.write(mixed, 0, mixed.length, AudioTrack.WRITE_BLOCKING) != mixed.length)
                throw new IllegalStateException("Incomplete alert PCM write");
            synchronized (audioLock) {
                if (closed || playback.cancelled || preparedAlert != playback
                        || !playback.request.playbackAllowedAt(SystemClock.elapsedRealtime())) return;
                playback.track = track;
                track.play();
            }
            long at = SystemClock.elapsedRealtime();
            started = true;
            if (playback.toneCallback != null) playback.toneCallback.onStarted(at);
            playback.speechCallback.onStarted(at);
            auditAudio("AlertPcm started cueId=" + playback.request.cueId + " cache=hit paired="
                    + (tone != null) + " style=" + toneStyles.getOrDefault(playback.request.toneKind, "classic")
                    + " speechStyle=" + (playback.request.speech.length() <= 2 ? "two_word" : "phrase")
                    + " frames=" + mixed.length / 2);
            long deadline = at + mixed.length * 1000L / (48000 * 2) + 1000;
            boolean routeLogged = false;
            while (!closed && !playback.cancelled && SystemClock.elapsedRealtime() <= deadline) {
                long head = Integer.toUnsignedLong(track.getPlaybackHeadPosition());
                if (!routeLogged && head > 0) {
                    AudioDeviceInfo routed = track.getRoutedDevice();
                    auditAudio("AlertPcm route cueId=" + playback.request.cueId
                            + " outputType=" + (routed == null ? "unknown" : routed.getType()));
                    routeLogged = true;
                }
                if (tone != null && !toneFinished && head >= tone.length / 2) {
                    toneFinished = true;
                    playback.toneCallback.onFinished(SystemClock.elapsedRealtime(), true);
                }
                if (head >= mixed.length / 2) { success = true; break; }
                Thread.sleep(10);
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            preparedPcmFailed = true;
            auditAudio("AlertPcm unavailable cueId=" + playback.request.cueId
                    + " next_cue=standard_offline_path");
            Log.w(TAG, "AlertPcm failed cueId=" + playback.request.cueId, error);
        } finally {
            synchronized (audioLock) { if (preparedAlert == playback) preparedAlert = null; }
            if (track != null) {
                stopAssistantTrack(track);
                try { track.release(); } catch (RuntimeException ignored) {}
            }
            if (!playback.cancelled && !closed) {
                long at = SystemClock.elapsedRealtime();
                if (playback.toneCallback != null && !toneFinished)
                    playback.toneCallback.onFinished(at, success && started);
                playback.speechCallback.onFinished(at, success && started);
            }
        }
    }

    private void cancelPreparedAlert(CueRequest.Category category) {
        PreparedAlertPlayback playback;
        synchronized (audioLock) {
            playback = preparedAlert;
            if (playback == null || category != null && playback.request.category != category) return;
            playback.cancelled = true;
            preparedAlert = null;
        }
        stopAssistantTrack(playback.track);
    }

    void setPreparedAlertForTest(String phrase, short[] pcm) {
        alertVoiceKey = "instrumented-fixed-phrases";
        AlertSpeechCache.put(alertVoiceKey, phrase, pcm);
    }

    private float volume() {
        int volumePercent = GameProfile.settings(context).getInt("volume", 45);
        return Math.max(0f, Math.min(1f, volumePercent / 100f));
    }

    private boolean playToneLocked(int kind, int direction, float pan) {
        Integer tone = tones.get(kind);
        if (tone == null || !ready.contains(tone)) return false;
        float volume = volume();
        float left = volume;
        float right = volume;
        if (Float.isFinite(pan)) {
            float[] gains = NearZoneRouting.stereoGains(pan, volume);
            left = gains[0];
            right = gains[1];
        } else {
            if (direction == 1) right *= 0.12f;
            if (direction == 2) left *= 0.12f;
        }
        return pool.play(tone, left, right, kind, 0, 1f) != 0;
    }

    private boolean enqueueToneLocked(CueRequest request,
                                      CueDispatcher.PlaybackCallback callback) {
        long now = SystemClock.elapsedRealtime();
        if (closed || now > request.expiresAtMs) return false;
        PresentationAudioPolicy presentation = PresentationAudioPolicy.from(
                GameProfile.settings(context));
        if (spatialEnabledAtStart && presentation.spatial
                && request.category == CueRequest.Category.NEAR_ZONE
                && request.hasPan()) {
            short[] samples = spatialToneCache.get(request.pan,
                    NearZoneRouting.presentationDistanceLevel(request.distance), request.urgency);
            if (samples != null) return enqueueSpatialToneLocked(request, callback, now, samples);
            // The cache is populated off-thread during startup. Urgent events
            // arriving first use the established SoundPool tone immediately.
        }
        Integer tone = tones.get(request.toneKind);
        if (tone == null || failed.contains(tone)) return false;
        // A valid newer event replaces anything waiting for its SoundPool
        // sample. Invalid or expired requests must leave that event intact.
        PendingToneQueue.Pending replaced = pendingTones.clear();
        if (replaced != null && replaced.callback != null)
            replaced.callback.onFinished(now, false);
        if (ready.contains(tone)) {
            boolean played = playToneLocked(request.toneKind, request.direction, request.pan);
            finishToneAttempt(callback, now, played, request.toneKind);
            return played;
        }
        return pendingTones.enqueue(tone, request.toneKind, request.direction,
                request.expiresAtMs, now, request.category, callback, request.pan);
    }

    private boolean enqueueSpatialToneLocked(CueRequest request,
                                             CueDispatcher.PlaybackCallback callback,
                                             long now, short[] samples) {
        AudioTrack track = null;
        try {
            int minBuffer = AudioTrack.getMinBufferSize(48_000,
                    AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) return false;
            int bufferBytes = Math.max(minBuffer, samples.length * 2);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(48_000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(bufferBytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.setVolume(volume());
            int written = track.write(samples, 0, samples.length, AudioTrack.WRITE_BLOCKING);
            if (written != samples.length || closed
                    || SystemClock.elapsedRealtime() > request.expiresAtMs) {
                track.release();
                return false;
            }
            track.play();
            spatialToneTracks.put(track, new SpatialTonePlayback(request.category, callback));
            if (callback != null) callback.onStarted(now);
            AudioTrack active = track;
            mainHandler.postDelayed(() -> finishSpatialTone(active), samples.length * 1000L / (48000 * 2));
            return true;
        } catch (RuntimeException error) {
            if (track != null) track.release();
            Log.w(TAG, "Could not play opt-in spatial tone", error);
            return false;
        }
    }

    private void finishSpatialTone(AudioTrack track) {
        SpatialTonePlayback playback;
        synchronized (audioLock) {
            playback = spatialToneTracks.remove(track);
        }
        if (playback == null) return;
        try {
            if (track.getState() == AudioTrack.STATE_INITIALIZED) track.stop();
        } catch (RuntimeException ignored) {
            // The platform may already have completed the static buffer.
        }
        track.release();
        if (playback.callback != null)
            playback.callback.onFinished(SystemClock.elapsedRealtime(), true);
    }

    private void cancelSpatialTones(CueRequest.Category category) {
        Iterator<Map.Entry<AudioTrack, SpatialTonePlayback>> entries =
                spatialToneTracks.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<AudioTrack, SpatialTonePlayback> entry = entries.next();
            if (category == null || entry.getValue().category == category) {
                AudioTrack track = entry.getKey();
                entries.remove();
                try {
                    if (track.getState() == AudioTrack.STATE_INITIALIZED) track.stop();
                } catch (RuntimeException ignored) {
                    // The platform may already have completed the static buffer.
                }
                track.release();
            }
        }
    }

    private void finishToneAttempt(CueDispatcher.PlaybackCallback callback, long now,
                                   boolean played, int kind) {
        if (callback == null) return;
        if (!played) {
            callback.onFinished(now, false);
            return;
        }
        callback.onStarted(now);
        Long duration = toneDurations.get(kind);
        mainHandler.postDelayed(() -> callback.onFinished(
                SystemClock.elapsedRealtime(), true),
                duration == null ? TONE_DURATION_MS : duration);
    }

    @Override public boolean playTone(CueRequest request,
                                      CueDispatcher.PlaybackCallback callback) {
        synchronized (audioLock) {
            return enqueueToneLocked(request, callback);
        }
    }

    @Override public boolean vibrate(CueRequest request) {
        synchronized (audioLock) {
            return !closed && SystemClock.elapsedRealtime() <= request.expiresAtMs
                    && vibrateEffect(request);
        }
    }

    /** Duration of the configured effect on this device, for preview stop timers. */
    long hapticDurationMs(CueRequest request) {
        try {
            Vibrator vibrator = defaultVibrator();
            if (vibrator == null || !vibrator.hasVibrator()) return 0;
            android.content.SharedPreferences preferences = GameProfile.settings(context);
            HapticPolicy.Pattern pattern = HapticPolicy.create(request,
                    PresentationAudioPolicy.from(preferences).distanceHaptic,
                    vibrator.hasAmplitudeControl(),
                    preferences.getString(HapticPolicy.PREF_MODE, HapticPolicy.MODE_ORIGINAL),
                    preferences.getString(HapticPolicy.PREF_STRENGTH,
                            HapticPolicy.STRENGTH_SYSTEM));
            return pattern.durationMs();
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not estimate accessibility haptic duration", error);
            return 0;
        }
    }

    /** Stops a haptic only while this player still owns the app's latest request. */
    void cancelHaptics() {
        synchronized (HAPTIC_OWNER_LOCK) {
            if (activeHapticOwner != this) return;
            activeHapticOwner = null;
            try {
                Vibrator vibrator = defaultVibrator();
                if (vibrator != null) vibrator.cancel();
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not cancel accessibility haptic", error);
            }
        }
    }

    @Override public boolean speak(CueRequest request, boolean interrupt,
                                   CueDispatcher.PlaybackCallback callback) {
        if (request.category == CueRequest.Category.ASSISTANT)
            return synthesizeAssistant(request, callback);
        if (hasPreparedSpeech(request)) return enqueuePreparedAlert(request, null, callback);
        return speakUncached(request, interrupt, callback);
    }

    private boolean speakUncached(CueRequest request, boolean interrupt,
                                  CueDispatcher.PlaybackCallback callback) {
        TextToSpeech voice;
        String utteranceId = "cue:" + request.cueId;
        Bundle parameters = new Bundle();
        synchronized (audioLock) {
            if (closed || !ttsReady || !offlineTtsReady || tts == null || request.speech == null ||
                    SystemClock.elapsedRealtime() > request.expiresAtMs) return false;
            voice = tts;
            parameters.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume());
            speechCallbacks.put(utteranceId, callback);
        }
        // Keep the remote engine call outside audioLock.  TTS progress
        // callbacks can re-enter the dispatcher and otherwise invert the
        // dispatcher/audio lock order during pause or preemption.
        try {
            if (request.category == CueRequest.Category.NEAR_ZONE)
                auditAudio("AlertTts cueId=" + request.cueId + " cache=miss offline=true");
            int result = voice.speak(request.speech,
                    alertQueueMode(request, interrupt, alertWarmJob != null),
                    parameters, utteranceId);
            if (result == TextToSpeech.SUCCESS) {
                mainHandler.postDelayed(() -> {
                    // A remote engine can report onStart while waiting tens of
                    // seconds for synthesis. Bound that wait and release the queue.
                    if (!speechCallbacks.remove(utteranceId, callback)) return;
                    Log.w(TAG, "TTS timed out for " + utteranceId);
                    try { voice.stop(); }
                    catch (RuntimeException error) {
                        Log.w(TAG, "Could not stop timed-out speech", error);
                    }
                    callback.onFinished(SystemClock.elapsedRealtime(), false);
                }, speechTimeoutMs(request));
                return true;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not queue accessibility speech", error);
        }
        speechCallbacks.remove(utteranceId, callback);
        return false;
    }

    private boolean synthesizeAssistant(CueRequest request,
                                        CueDispatcher.PlaybackCallback callback) {
        if (request.speech == null || request.speech.isEmpty()) return false;
        AssistantTtsPipeline.Plan plan = AssistantTtsPipeline.split(request.speech);
        if (plan.segments.isEmpty()) {
            if (plan.droppedChars > 0)
                logAssistantDrop(request.cueId, 0, 0, plan.droppedChars, "limit");
            callback.onFinished(SystemClock.elapsedRealtime(), false);
            return true;
        }
        String groupId = "assistant:" + request.cueId + ":" + System.nanoTime();
        AssistantReply reply = new AssistantReply(request, callback,
                new AssistantTtsPipeline.Group(groupId, request.cueId, plan));
        synchronized (audioLock) {
            if (closed || !ttsReady || !offlineTtsReady || tts == null) return false;
            assistantGroups.put(groupId, reply);
        }
        if (plan.droppedChars > 0)
            logAssistantDrop(request.cueId, plan.segments.size(), 0,
                    plan.droppedChars, "limit");
        return startAssistantSegment(reply, true);
    }

    private boolean startAssistantSegment(AssistantReply reply, boolean initial) {
        TextToSpeech voice = null;
        AssistantUtterance utterance = null;
        AssistantTtsPipeline.Transition transition = null;
        boolean setupFailed = false;
        synchronized (audioLock) {
            if (closed || assistantGroups.get(reply.group.groupId) != reply
                    || reply.group.isCancelled()) return !initial;
            AssistantTtsPipeline.Segment segment = reply.group.reserveNext();
            if (segment == null) return !initial;
            if (!reply.request.playbackAllowedAt(SystemClock.elapsedRealtime())) {
                transition = reply.group.expiredBeforePlayback(segment.index);
                assistantGroups.remove(reply.group.groupId, reply);
            } else {
                try {
                    File outputFile = AssistantTtsCache.createOutputFile(context.getCacheDir());
                    String utteranceId = reply.group.groupId + ":segment:" + segment.index;
                    utterance = new AssistantUtterance(utteranceId, reply, segment, outputFile);
                    assistantUtterances.put(utteranceId, utterance);
                    voice = tts;
                } catch (IOException error) {
                    Log.w(TAG, "Could not allocate assistant TTS cache file", error);
                    transition = reply.group.failed(segment.index);
                    assistantGroups.remove(reply.group.groupId, reply);
                    setupFailed = true;
                }
            }
        }

        if (transition != null) {
            if (!setupFailed)
                notifyAssistantTtsTiming(reply.request.cueId, transition.dropped.index,
                        "expired");
            if (!transition.dropped.isEmpty())
                logAssistantDrop(reply.request.cueId, transition.dropped.index,
                        transition.dropped.segments, transition.dropped.chars,
                        setupFailed ? "failed" : "expired");
            if (!setupFailed || !initial) finishAssistantReply(reply, transition.success);
            return !setupFailed;
        }
        if (utterance == null || voice == null) return !initial;

        AssistantUtterance active = utterance;
        utterance.synthesisTimeout = () -> {
            if (failAssistantSegment(active, "synthesis_timeout", true, true))
                Log.w(TAG, "Offline assistant TTS synthesis timed out for "
                        + active.utteranceId);
        };
        mainHandler.postDelayed(utterance.synthesisTimeout, ASSISTANT_SYNTHESIS_TIMEOUT_MS);
        logAssistantSegment("synthesis-started", utterance);

        Bundle parameters = new Bundle();
        parameters.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
        int result = TextToSpeech.ERROR;
        AssistantTtsPipeline.Transition expired = null;
        synchronized (assistantTtsSubmissionLock) {
            synchronized (audioLock) {
                if (!isCurrentAssistantLocked(utterance)) {
                    mainHandler.removeCallbacks(utterance.synthesisTimeout);
                    AssistantTtsCache.delete(utterance.outputFile);
                    return !initial;
                }
                if (!reply.request.playbackAllowedAt(SystemClock.elapsedRealtime())) {
                    expired = reply.group.expiredBeforePlayback(utterance.segment.index);
                    assistantUtterances.remove(utterance.utteranceId, utterance);
                    assistantGroups.remove(reply.group.groupId, reply);
                    mainHandler.removeCallbacks(utterance.synthesisTimeout);
                    AssistantTtsCache.delete(utterance.outputFile);
                }
            }
            if (expired == null) {
                try {
                    notifyAssistantTtsTiming(utterance, "synthesis-submit");
                    result = voice.synthesizeToFile(utterance.segment.text, parameters,
                            utterance.outputFile, utterance.utteranceId);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Could not synthesize assistant speech offline", error);
                }
            }
        }
        if (expired != null) {
            notifyAssistantTtsTiming(utterance, "expired");
            if (!expired.dropped.isEmpty())
                logAssistantDrop(reply.request.cueId, expired.dropped.index,
                        expired.dropped.segments, expired.dropped.chars, "expired");
            finishAssistantReply(reply, expired.success);
            return true;
        }
        if (result == TextToSpeech.SUCCESS) return true;
        failAssistantSegment(utterance, "synthesis_rejected", false, !initial);
        return false;
    }

    private void playAssistantFile(AssistantUtterance utterance) {
        notifyAssistantTtsTiming(utterance, "worker-start");
        AudioTrack track = null;
        boolean trackStarted = false;
        boolean completed = false;
        boolean expired = false;
        try {
            if (isCurrentAssistant(utterance)) {
                PcmWav wav = readPcmWav(utterance.outputFile);
                short[] pcm16k = VoiceEchoProcessor.resampleTo16k(wav.samples, wav.sampleRateHz);
                notifyAssistantTtsTiming(utterance, "pcm-ready");
                if (pcm16k.length > 0 && isCurrentAssistant(utterance)) {
                    int minBuffer = AudioTrack.getMinBufferSize(VoiceEchoProcessor.SAMPLE_RATE_HZ,
                            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                    if (minBuffer <= 0)
                        throw new IOException("AudioTrack buffer size unavailable");
                    // Keep the output queue at the device minimum and retain the established
                    // mono 16 kHz PCM/AEC reference path for each independently synthesized part.
                    int bufferBytes = Math.max(minBuffer,
                            VoiceEchoProcessor.FRAME_SAMPLES * 2 * 2);
                    track = new AudioTrack.Builder()
                            .setAudioAttributes(new AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_GAME)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(VoiceEchoProcessor.SAMPLE_RATE_HZ)
                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                            .setBufferSizeInBytes(bufferBytes)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build();
                    notifyAssistantTtsTiming(utterance, "audio-track-ready");
                    track.setVolume(volume());
                    synchronized (audioLock) {
                        if (isCurrentAssistantLocked(utterance)
                                && utterance.reply.group.isReadyToPlay(
                                utterance.segment.index)) {
                            if (!utterance.reply.request.playbackAllowedAt(
                                    SystemClock.elapsedRealtime())) {
                                expired = true;
                            } else {
                                assistantTrack = track;
                                track.play();
                                trackStarted = true;
                            }
                        }
                    }
                    if (expired) notifyAssistantTtsTiming(utterance, "expired");
                    if (trackStarted) {
                        AssistantPlaybackListener reference = assistantPlaybackListener;
                        short[] referenceFrame = reference == null ? null
                                : new short[VoiceEchoProcessor.FRAME_SAMPLES];
                        int referenceSamples = 0;
                        boolean firstWrite = true;
                        for (int offset = 0; offset < pcm16k.length
                                && isCurrentAssistant(utterance);) {
                            int count = Math.min(VoiceEchoProcessor.FRAME_SAMPLES,
                                    pcm16k.length - offset);
                            int written = track.write(pcm16k, offset, count,
                                    AudioTrack.WRITE_BLOCKING);
                            if (written <= 0)
                                throw new IOException("Assistant PCM output stopped");
                            if (firstWrite) {
                                boolean current;
                                boolean notifyStarted = false;
                                synchronized (audioLock) {
                                    current = isCurrentAssistantLocked(utterance);
                                    if (current) notifyStarted = utterance.reply.group
                                            .markFirstWrite(utterance.segment.index);
                                }
                                if (!current) break;
                                firstWrite = false;
                                logAssistantSegment("playback-first-write", utterance);
                                notifyAssistantTtsTiming(utterance, "first-write");
                                if (notifyStarted)
                                    utterance.reply.callback.onStarted(
                                            SystemClock.elapsedRealtime());
                            }
                            if (reference != null) {
                                int copied = 0;
                                while (copied < written) {
                                    int take = Math.min(written - copied,
                                            VoiceEchoProcessor.FRAME_SAMPLES - referenceSamples);
                                    System.arraycopy(pcm16k, offset + copied, referenceFrame,
                                            referenceSamples, take);
                                    copied += take;
                                    referenceSamples += take;
                                    if (referenceSamples == VoiceEchoProcessor.FRAME_SAMPLES) {
                                        if (isCurrentAssistant(utterance))
                                            emitPcmReference(reference, referenceFrame);
                                        referenceFrame = new short[
                                                VoiceEchoProcessor.FRAME_SAMPLES];
                                        referenceSamples = 0;
                                    }
                                }
                            }
                            offset += written;
                        }
                        if (reference != null && referenceSamples > 0
                                && isCurrentAssistant(utterance))
                            emitPcmReference(reference, referenceFrame);
                        completed = isCurrentAssistant(utterance) && !firstWrite;
                        if (completed) {
                            // Let the final queued frame reach output before advancing the group.
                            long drainDeadline = SystemClock.elapsedRealtime()
                                    + ASSISTANT_PLAYBACK_DRAIN_TIMEOUT_MS;
                            while (isCurrentAssistant(utterance)
                                    && track.getPlaybackHeadPosition() < pcm16k.length
                                    && SystemClock.elapsedRealtime() < drainDeadline) {
                                try { Thread.sleep(5); }
                                catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    completed = false;
                                    break;
                                }
                            }
                            completed = completed && isCurrentAssistant(utterance);
                            if (completed && track.getPlaybackHeadPosition() < pcm16k.length) {
                                completed = false;
                                Log.w(TAG, "Assistant PCM playback drain timed out cueId="
                                        + utterance.reply.request.cueId + " index="
                                        + utterance.segment.index);
                            }
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException | LinkageError error) {
            Log.w(TAG, "Could not play assistant PCM output", error);
        } finally {
            if (track != null) {
                try {
                    if (track.getState() == AudioTrack.STATE_INITIALIZED) track.stop();
                } catch (RuntimeException ignored) {
                    // Releasing a stopped or interrupted track is still safe.
                }
                track.release();
            }
            synchronized (audioLock) {
                if (assistantTrack == track) assistantTrack = null;
            }
        }
        if (expired) expireAssistantSegment(utterance);
        else if (trackStarted && completed) completeAssistantSegment(utterance);
        else failAssistantSegment(utterance, "playback_failed", false, true);
    }

    private void emitPcmReference(AssistantPlaybackListener listener, short[] frame) {
        try {
            listener.onPcmReference(frame);
        } catch (RuntimeException error) {
            Log.w(TAG, "Assistant PCM reference listener failed", error);
        }
    }

    private boolean isCurrentAssistant(AssistantUtterance utterance) {
        synchronized (audioLock) { return isCurrentAssistantLocked(utterance); }
    }

    private boolean isCurrentAssistantLocked(AssistantUtterance utterance) {
        return !closed && assistantGroups.get(utterance.reply.group.groupId) == utterance.reply
                && assistantUtterances.get(utterance.utteranceId) == utterance
                && !utterance.reply.group.isCancelled();
    }

    private void completeAssistantSegment(AssistantUtterance utterance) {
        AssistantTtsPipeline.Transition transition;
        synchronized (audioLock) {
            if (!assistantUtterances.remove(utterance.utteranceId, utterance)) return;
            mainHandler.removeCallbacks(utterance.synthesisTimeout);
            AssistantTtsCache.delete(utterance.outputFile);
            if (assistantGroups.get(utterance.reply.group.groupId) != utterance.reply) return;
            transition = utterance.reply.group.playbackCompleted(utterance.segment.index);
            if (transition.action == AssistantTtsPipeline.Action.FINISH)
                assistantGroups.remove(utterance.reply.group.groupId, utterance.reply);
        }
        handleAssistantTransition(utterance.reply, transition, "failed");
    }

    private void expireAssistantSegment(AssistantUtterance utterance) {
        AssistantTtsPipeline.Transition transition;
        synchronized (audioLock) {
            if (!assistantUtterances.remove(utterance.utteranceId, utterance)) return;
            mainHandler.removeCallbacks(utterance.synthesisTimeout);
            AssistantTtsCache.delete(utterance.outputFile);
            if (assistantGroups.get(utterance.reply.group.groupId) != utterance.reply) return;
            transition = utterance.reply.group.expiredBeforePlayback(utterance.segment.index);
            assistantGroups.remove(utterance.reply.group.groupId, utterance.reply);
        }
        handleAssistantTransition(utterance.reply, transition, "expired");
    }

    private boolean failAssistantSegment(AssistantUtterance utterance, String reason,
                                         boolean stopVoice, boolean notify) {
        AssistantFailure failure;
        if (stopVoice) {
            synchronized (assistantTtsSubmissionLock) {
                failure = detachFailedAssistantSegment(utterance, true);
                if (failure == null) return false;
                TextToSpeech activeVoice = tts;
                if (activeVoice != null) {
                    try { activeVoice.stop(); }
                    catch (RuntimeException error) {
                        Log.w(TAG, "Could not stop timed-out assistant synthesis", error);
                    }
                }
            }
        } else {
            failure = detachFailedAssistantSegment(utterance, false);
            if (failure == null) return false;
        }
        if (!failure.transition.dropped.isEmpty())
            logAssistantDrop(failure.reply.request.cueId, failure.transition.dropped.index,
                    failure.transition.dropped.segments, failure.transition.dropped.chars, reason);
        if (notify) finishAssistantReply(failure.reply, false);
        return true;
    }

    private AssistantFailure detachFailedAssistantSegment(AssistantUtterance utterance,
                                                          boolean requireSynthesis) {
        synchronized (audioLock) {
            if (!isCurrentAssistantLocked(utterance)) return null;
            if (requireSynthesis && !utterance.reply.group.isSynthesizing(
                    utterance.segment.index)) return null;
            if (!assistantUtterances.remove(utterance.utteranceId, utterance)) return null;
            mainHandler.removeCallbacks(utterance.synthesisTimeout);
            AssistantTtsCache.delete(utterance.outputFile);
            AssistantTtsPipeline.Transition transition = utterance.reply.group
                    .failed(utterance.segment.index);
            assistantGroups.remove(utterance.reply.group.groupId, utterance.reply);
            if (transition.action == AssistantTtsPipeline.Action.IGNORED) return null;
            return new AssistantFailure(utterance.reply, transition);
        }
    }

    private void handleAssistantTransition(AssistantReply reply,
                                          AssistantTtsPipeline.Transition transition,
                                          String dropReason) {
        if (transition.action == AssistantTtsPipeline.Action.IGNORED) return;
        if (!transition.dropped.isEmpty())
            logAssistantDrop(reply.request.cueId, transition.dropped.index,
                    transition.dropped.segments, transition.dropped.chars, dropReason);
        if (transition.action == AssistantTtsPipeline.Action.NEXT) {
            startAssistantSegment(reply, false);
        } else {
            finishAssistantReply(reply, transition.success);
        }
    }

    private void finishAssistantReply(AssistantReply reply, boolean success) {
        if (reply.group.claimFinishCallback())
            reply.callback.onFinished(SystemClock.elapsedRealtime(), success);
    }

    private static void logAssistantSegment(String event, AssistantUtterance utterance) {
        Log.i(TAG, "Assistant TTS event=" + event + " cueId=" + utterance.reply.request.cueId
                + " index=" + utterance.segment.index + " chars=" + utterance.segment.chars
                + " monoMs=" + SystemClock.elapsedRealtime()
                + " timeMs=" + System.currentTimeMillis());
    }

    private static void logAssistantDrop(String cueId, int index, int segments, int chars,
                                         String reason) {
        if (chars <= 0 && segments <= 0) return;
        Log.i(TAG, "Assistant TTS event=remaining-dropped cueId=" + cueId
                + " index=" + index + " segments=" + segments + " chars=" + chars
                + " reason=" + reason + " monoMs=" + SystemClock.elapsedRealtime()
                + " timeMs=" + System.currentTimeMillis());
    }

    private List<AssistantDropEvent> cancelAssistantGroupsLocked(String reason) {
        List<AssistantDropEvent> dropped = new java.util.ArrayList<>();
        for (AssistantReply reply : assistantGroups.values()) {
            AssistantTtsPipeline.DropSummary summary = reply.group.cancel();
            if (!summary.isEmpty())
                dropped.add(new AssistantDropEvent(reply.request.cueId, summary, reason));
        }
        for (AssistantUtterance utterance : assistantUtterances.values()) {
            mainHandler.removeCallbacks(utterance.synthesisTimeout);
            AssistantTtsCache.delete(utterance.outputFile);
        }
        assistantUtterances.clear();
        assistantGroups.clear();
        return dropped;
    }

    private static void logAssistantDrops(List<AssistantDropEvent> dropped) {
        for (AssistantDropEvent event : dropped)
            logAssistantDrop(event.cueId, event.dropped.index, event.dropped.segments,
                    event.dropped.chars, event.reason);
    }

    private static final class PcmWav {
        final int sampleRateHz;
        final short[] samples;
        PcmWav(int sampleRateHz, short[] samples) {
            this.sampleRateHz = sampleRateHz;
            this.samples = samples;
        }
    }

    private static PcmWav readPcmWav(File file) throws IOException {
        if (!file.isFile() || file.length() < 44 || file.length() > 20_000_000)
            throw new IOException("Assistant PCM file size is invalid");
        try (InputStream input = new FileInputStream(file)) {
            byte[] riff = readFully(input, 12);
            if (!asciiEquals(riff, 0, "RIFF") || !asciiEquals(riff, 8, "WAVE"))
                throw new IOException("Assistant speech is not a WAV file");
            int format = 0;
            int channels = 0;
            int sampleRate = 0;
            int bitsPerSample = 0;
            byte[] data = null;
            while (true) {
                byte[] chunkHeader = readMaybe(input, 8);
                if (chunkHeader == null) break;
                int size = littleEndian32(chunkHeader, 4);
                if (size < 0 || size > 20_000_000) throw new IOException("Invalid WAV chunk");
                if (asciiEquals(chunkHeader, 0, "fmt ")) {
                    byte[] fmt = readFully(input, size);
                    if (fmt.length < 16) throw new IOException("WAV format chunk is short");
                    format = littleEndian16(fmt, 0);
                    channels = littleEndian16(fmt, 2);
                    sampleRate = littleEndian32(fmt, 4);
                    bitsPerSample = littleEndian16(fmt, 14);
                } else if (asciiEquals(chunkHeader, 0, "data")) {
                    data = readFully(input, size);
                } else {
                    skipFully(input, size);
                }
                if ((size & 1) != 0 && input.read() < 0) break;
                if (data != null && format != 0) break;
            }
            if (format != 1 || (channels != 1 && channels != 2) || bitsPerSample != 16
                    || sampleRate < 8000 || sampleRate > 96000 || data == null)
                throw new IOException("Unsupported assistant WAV PCM format");
            int frames = data.length / (channels * 2);
            short[] mono = new short[frames];
            for (int i = 0; i < frames; i++) {
                int left = (short) littleEndian16(data, i * channels * 2);
                if (channels == 1) mono[i] = (short) left;
                else {
                    int right = (short) littleEndian16(data, i * channels * 2 + 2);
                    mono[i] = (short) ((left + right) / 2);
                }
            }
            return new PcmWav(sampleRate, mono);
        }
    }

    private static byte[] readFully(InputStream input, int size) throws IOException {
        byte[] result = new byte[size];
        int offset = 0;
        while (offset < size) {
            int count = input.read(result, offset, size - offset);
            if (count < 0) throw new IOException("Unexpected end of assistant WAV");
            offset += count;
        }
        return result;
    }

    private static byte[] readMaybe(InputStream input, int size) throws IOException {
        int first = input.read();
        if (first < 0) return null;
        byte[] result = new byte[size];
        result[0] = (byte) first;
        byte[] rest = readFully(input, size - 1);
        System.arraycopy(rest, 0, result, 1, rest.length);
        return result;
    }

    private static void skipFully(InputStream input, int count) throws IOException {
        while (count > 0) {
            long skipped = input.skip(count);
            if (skipped <= 0) {
                if (input.read() < 0) throw new IOException("Unexpected end of assistant WAV");
                skipped = 1;
            }
            count -= (int) skipped;
        }
    }

    private static boolean asciiEquals(byte[] value, int offset, String text) {
        if (offset + text.length() > value.length) return false;
        for (int i = 0; i < text.length(); i++)
            if (value[offset + i] != (byte) text.charAt(i)) return false;
        return true;
    }

    private static int littleEndian16(byte[] value, int offset) {
        return (value[offset] & 0xff) | ((value[offset + 1] & 0xff) << 8);
    }

    private static int littleEndian32(byte[] value, int offset) {
        return (value[offset] & 0xff) | ((value[offset + 1] & 0xff) << 8)
                | ((value[offset + 2] & 0xff) << 16) | ((value[offset + 3] & 0xff) << 24);
    }

    void setAssistantPlaybackListener(AssistantPlaybackListener listener) {
        assistantPlaybackListener = listener;
    }

    void setAssistantTtsTimingListener(AssistantTtsTimingListener listener) {
        assistantTtsTimingListener = listener;
    }

    private void notifyAssistantTtsTiming(AssistantUtterance utterance, String phase) {
        notifyAssistantTtsTiming(utterance.reply.request.cueId, utterance.segment.index, phase);
    }

    private void notifyAssistantTtsTiming(String cueId, int segmentIndex, String phase) {
        AssistantTtsTimingListener listener = assistantTtsTimingListener;
        if (listener == null) return;
        long monoMs = SystemClock.elapsedRealtime();
        mainHandler.post(() -> {
            if (assistantTtsTimingListener != listener) return;
            try {
                listener.onAssistantTtsTiming(cueId, segmentIndex, phase, monoMs);
            } catch (RuntimeException error) {
                Log.w(TAG, "Assistant TTS timing listener failed", error);
            }
        });
    }

    boolean speechReady() { return ttsReady && offlineTtsReady && !closed; }

    boolean speechPreparationFinished() { return speechPreparationFinished; }

    String speechStatusText() { return speechStatus; }

    /** Assistant answers require an installed Chinese voice that needs no network. */
    boolean assistantSpeechReady() { return ttsReady && offlineTtsReady && !closed; }

    static long speechTimeoutMs(CueRequest request) {
        return request.category == CueRequest.Category.SYSTEM
                && ReminderGuide.NARRATION_KIND.equals(request.kind)
                ? ReminderGuide.NARRATION_TIMEOUT_MS : SPEECH_TIMEOUT_MS;
    }

    static int alertQueueMode(CueRequest request, boolean interrupt, boolean warming) {
        // File synthesis shares the engine's queue even on a separate worker. An urgent
        // uncached phrase must flush that silent job rather than wait behind it.
        return interrupt || warming || request.category == CueRequest.Category.NEAR_ZONE
                ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
    }

    @Override public void stopSpeech() {
        cancelPreparedAlert(null);
        TextToSpeech voice;
        AudioTrack track;
        List<AssistantDropEvent> dropped;
        synchronized (audioLock) {
            // Some TTS engines do not deliver onStop for every flushed
            // utterance.  Do not retain callbacks from a paused session.
            speechCallbacks.clear();
            dropped = cancelAssistantGroupsLocked("stopped");
            track = assistantTrack;
            assistantTrack = null;
            voice = tts;
        }
        logAssistantDrops(dropped);
        // Do not call into the remote TTS engine while holding audioLock:
        // an engine may synchronously or asynchronously deliver onStop.
        if (voice != null) {
            synchronized (assistantTtsSubmissionLock) {
                try {
                    voice.stop();
                } catch (RuntimeException error) {
                    Log.w(TAG, "Could not stop accessibility speech", error);
                }
            }
        }
        stopAssistantTrack(track);
    }

    @Override public void cancelAssistantSpeech() {
        TextToSpeech voice;
        AudioTrack track;
        boolean hadAssistant;
        List<AssistantDropEvent> dropped;
        synchronized (audioLock) {
            hadAssistant = !assistantGroups.isEmpty() || !assistantUtterances.isEmpty()
                    || assistantTrack != null;
            dropped = cancelAssistantGroupsLocked("cancelled");
            track = assistantTrack;
            assistantTrack = null;
            voice = tts;
        }
        logAssistantDrops(dropped);
        if (hadAssistant && voice != null) {
            synchronized (assistantTtsSubmissionLock) {
                try { voice.stop(); }
                catch (RuntimeException error) {
                    Log.w(TAG, "Could not stop assistant synthesis", error);
                }
            }
        }
        stopAssistantTrack(track);
    }

    private static void stopAssistantTrack(AudioTrack track) {
        if (track == null) return;
        try {
            if (track.getState() == AudioTrack.STATE_INITIALIZED) track.stop();
        } catch (RuntimeException ignored) {
            // A concurrent writer may already have stopped the track.
        }
    }

    @Override public void cancelPendingTone() {
        cancelPreparedAlert(null);
        synchronized (audioLock) {
            PendingToneQueue.Pending pending = pendingTones.clear();
            if (pending != null && pending.callback != null)
                pending.callback.onFinished(SystemClock.elapsedRealtime(), false);
            cancelSpatialTones(null);
        }
    }

    @Override public void cancelPendingTone(CueRequest.Category category) {
        cancelPreparedAlert(category);
        synchronized (audioLock) {
            PendingToneQueue.Pending pending = pendingTones.clearCategory(category);
            if (pending != null && pending.callback != null)
                pending.callback.onFinished(SystemClock.elapsedRealtime(), false);
            cancelSpatialTones(category);
        }
    }

    private boolean vibrateEffect(CueRequest request) {
        try {
            Vibrator vibrator = defaultVibrator();
            if (vibrator == null || !vibrator.hasVibrator()) {
                auditAudio("HapticRequest unavailable=no_vibrator cueId=" + request.cueId);
                return false;
            }
            android.content.SharedPreferences preferences = GameProfile.settings(context);
            HapticPolicy.Pattern pattern = HapticPolicy.create(request,
                    PresentationAudioPolicy.from(preferences).distanceHaptic,
                    vibrator.hasAmplitudeControl(),
                    preferences.getString(HapticPolicy.PREF_MODE, HapticPolicy.MODE_ORIGINAL),
                    preferences.getString(HapticPolicy.PREF_STRENGTH,
                            HapticPolicy.STRENGTH_SYSTEM));
            VibrationEffect effect = pattern.amplitudes == null
                    ? VibrationEffect.createWaveform(pattern.timingsMs, -1)
                    : VibrationEffect.createWaveform(pattern.timingsMs,
                    pattern.amplitudes, -1);
            // Untagged short effects become TOUCH and are silently suppressed
            // when touch feedback is off. These are accessibility cues, not
            // taps; keep all system interruption settings in force.
            synchronized (HAPTIC_OWNER_LOCK) {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    vibrator.vibrate(effect, VibrationAttributes.createForUsage(
                            VibrationAttributes.USAGE_ACCESSIBILITY));
                } else {
                    vibrator.vibrate(effect, new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
                }
                activeHapticOwner = this;
            }
            // The API is void: submission is not proof the user felt vibration.
            auditAudio("HapticRequest cueId=" + request.cueId
                    + " usage=ACCESSIBILITY durationMs=" + pattern.durationMs());
            return true;
        } catch (RuntimeException error) {
            auditAudio("HapticRequest unavailable=" + error.getClass().getSimpleName()
                    + " cueId=" + request.cueId);
            Log.w(TAG, "Could not request accessibility haptic", error);
            return false;
        }
    }

    private Vibrator defaultVibrator() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            VibratorManager manager = context.getSystemService(VibratorManager.class);
            return manager == null ? null : manager.getDefaultVibrator();
        }
        return context.getSystemService(Vibrator.class);
    }

    void close() {
        cancelPreparedAlert(null);
        alertWarmingStopped = true;
        AlertWarmJob warming = alertWarmJob;
        alertWarmJob = null;
        if (warming != null) {
            if (warming.timeout != null) mainHandler.removeCallbacks(warming.timeout);
            warming.file.delete();
        }
        TextToSpeech voice;
        List<AssistantDropEvent> dropped;
        synchronized (audioLock) {
            if (closed) return;
            closed = true;
            ttsReady = false;
            offlineTtsReady = false;
            audioAuditListener = null;
            voice = tts;
            tts = null;
            ready.clear();
            failed.clear();
            pendingTones.clear();
            speechCallbacks.clear();
            dropped = cancelAssistantGroupsLocked("closed");
            if (assistantTrack != null) {
                stopAssistantTrack(assistantTrack);
                assistantTrack = null;
            }
            cancelSpatialTones(null);
        }
        cancelHaptics();
        logAssistantDrops(dropped);
        if (voice != null) {
            synchronized (assistantTtsSubmissionLock) {
                try {
                    voice.shutdown();
                } catch (RuntimeException error) {
                    Log.w(TAG, "Could not shut down TTS cleanly", error);
                }
            }
        }
        alertAudioWorker.shutdownNow();
        alertCacheWorker.shutdownNow();
        assistantAudioWorker.shutdownNow();
        if (spatialTonePrewarmWorker != null) spatialTonePrewarmWorker.shutdownNow();
        pool.release();
    }
}
