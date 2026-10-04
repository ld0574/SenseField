package com.openkhub.sensefield;

import android.content.Context;
import android.media.AudioAttributes;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class CuePlayer implements CueDispatcher.Renderer {
    static final String PREF_TTS_ENGINE = "cue_tts_engine";
    static final String PREF_TTS_RATE = "cue_speech_rate_percent";
    static final int DEFAULT_TTS_RATE_PERCENT = 180;
    static final long NEAR_HAPTIC_ON_MS = 100;
    static final long NEAR_HAPTIC_GAP_MS = 140;
    private static final String TAG = "MapAssistAudio";
    private static final long TONE_DURATION_MS = 90;
    private static final long SPEECH_TIMEOUT_MS = 4000;
    private static final long ASSISTANT_SYNTHESIS_TIMEOUT_MS = 15_000;

    private final Context context;
    private final SoundPool pool;
    private final Object audioLock = new Object();
    private final Map<Integer, Integer> tones = new HashMap<>();
    private final Map<Integer, Long> toneDurations = new HashMap<>();
    private final Map<AudioTrack, SpatialTonePlayback> spatialToneTracks = new HashMap<>();
    private final SpatialToneCache spatialToneCache = new SpatialToneCache();
    private final boolean spatialEnabledAtStart;
    private final Map<String, CueDispatcher.PlaybackCallback> speechCallbacks =
            new ConcurrentHashMap<>();
    private final Map<String, AssistantUtterance> assistantUtterances =
            new ConcurrentHashMap<>();
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
    private volatile boolean closed;
    private volatile boolean ttsReady;
    private volatile boolean offlineTtsReady;

    interface AssistantPlaybackListener {
        /** Receives a copied 160-sample mono render frame at 16 kHz. */
        void onPcmReference(short[] frame16kMono);
    }

    private static final class AssistantUtterance {
        final CueRequest request;
        final CueDispatcher.PlaybackCallback callback;
        final File outputFile;
        volatile boolean cancelled;
        Runnable synthesisTimeout;

        AssistantUtterance(CueRequest request, CueDispatcher.PlaybackCallback callback,
                           File outputFile) {
            this.request = request;
            this.callback = callback;
            this.outputFile = outputFile;
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

    CuePlayer(Context context) {
        this.context = context.getApplicationContext();
        scheduleAssistantTtsRecovery();
        spatialEnabledAtStart = GameProfile.settings(this.context)
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
        try {
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
        prepareVoices();
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
        int sample = pool.load(writeTone(name, startFrequency, endFrequency, durationMs, pulses)
                .getAbsolutePath(), 1);
        if (sample == 0) Log.e(TAG, "SoundPool rejected cue tone kind=" + kind);
        else {
            tones.put(kind, sample);
            toneDurations.put(kind, durationMs);
        }
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
            for (int i = 0; i < samples; i++) {
                int inPulse = i % (pulseSamples + gap);
                double envelope = inPulse >= pulseSamples ? 0.0
                        : Math.min(1.0, inPulse / 400.0)
                        * Math.min(1.0, (pulseSamples - inPulse) / 800.0);
                double frequency = startFrequency
                        + (endFrequency - startFrequency) * (double) i / samples;
                short value = (short) (Math.sin(phase) * 13000 * envelope);
                phase += 2 * Math.PI * frequency / sampleRate;
                littleEndian16(output, value);
            }
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

        private static short[] render(float pan, float distance, float urgency) {
            double frequency = 780.0 + urgency * 120.0;
            double phase = 0.0;
            int pulseSamples = SAMPLE_RATE * 875 / 10_000;
            int gapSamples = SAMPLE_RATE * 65 / 1000;
            double[] source = new double[SAMPLE_COUNT];
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                int pulseOffset = i < pulseSamples ? i
                        : i >= pulseSamples + gapSamples ? i - pulseSamples - gapSamples : -1;
                double envelope = pulseOffset < 0 || pulseOffset >= pulseSamples ? 0.0
                        : Math.min(1.0, pulseOffset / 400.0)
                        * Math.min(1.0, (pulseSamples - pulseOffset) / 800.0);
                source[i] = Math.sin(phase) * 11_000.0 * envelope;
                phase += 2.0 * Math.PI * frequency / SAMPLE_RATE;
            }

            double panMagnitude = Math.abs(pan);
            int interauralDelay = (int) Math.round(panMagnitude * 18.0);
            double farAlpha = 1.0 - Math.exp(-2.0 * Math.PI
                    * (8_000.0 - 5_000.0 * distance) / SAMPLE_RATE);
            double[] farFiltered = new double[SAMPLE_COUNT];
            double previous = 0.0;
            for (int i = 0; i < SAMPLE_COUNT; i++) {
                previous += farAlpha * (source[i] - previous);
                farFiltered[i] = previous;
            }

            double distanceGain = 1.0 - 0.22 * distance;
            double farGain = 1.0 - 0.78 * panMagnitude;
            short[] stereo = new short[SAMPLE_COUNT * 2];
            for (int i = 0; i < SAMPLE_COUNT; i++) {
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
        try {
            String selectedEngine = GameProfile.settings(context).getString(PREF_TTS_ENGINE, "");
            tts = new TextToSpeech(context, status -> mainHandler.post(() -> {
                synchronized (audioLock) {
                    if (closed || status != TextToSpeech.SUCCESS || tts == null) return;
                    try {
                        if (tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
                                < TextToSpeech.LANG_AVAILABLE) {
                            Log.w(TAG, "Chinese TTS voice unavailable; short tones remain active");
                            return;
                        }
                        int speechRate = GameProfile.settings(context).getInt(PREF_TTS_RATE,
                                DEFAULT_TTS_RATE_PERCENT);
                        tts.setSpeechRate(Math.max(80, Math.min(240, speechRate)) / 100f);
                        tts.setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                        // Prefer an installed Chinese voice that does not require a
                        // network request. Keep the user's engine and language fallback.
                        Set<Voice> voices = tts.getVoices();
                        if (voices != null) {
                            Voice offline = voices.stream()
                                    .filter(value -> "zh".equals(value.getLocale().getLanguage())
                                            && !value.isNetworkConnectionRequired())
                                    .sorted(java.util.Comparator.comparing(Voice::getName))
                                    .findFirst().orElse(null);
                            if (offline != null)
                                offlineTtsReady = tts.setVoice(offline) == TextToSpeech.SUCCESS;
                        }
                        Log.i(TAG, "TTS prepared engine=" + (selectedEngine.isEmpty()
                                ? tts.getDefaultEngine() : selectedEngine)
                                + " voice=" + tts.getVoice());
                        ttsReady = true;
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(String id) {
                                CueDispatcher.PlaybackCallback callback = speechCallbacks.get(id);
                                if (callback != null) callback.onStarted(SystemClock.elapsedRealtime());
                            }
                            @Override public void onDone(String id) {
                                AssistantUtterance assistant = assistantUtterances.get(id);
                                if (assistant != null) {
                                    mainHandler.removeCallbacks(assistant.synthesisTimeout);
                                    try {
                                        assistantAudioWorker.execute(
                                                () -> playAssistantFile(id, assistant));
                                    } catch (RuntimeException rejected) {
                                        failAssistant(id, assistant);
                                    }
                                    return;
                                }
                                CueDispatcher.PlaybackCallback callback = speechCallbacks.remove(id);
                                if (callback != null)
                                    callback.onFinished(SystemClock.elapsedRealtime(), true);
                            }
                            @Override public void onError(String id) {
                                AssistantUtterance assistant = assistantUtterances.get(id);
                                if (assistant != null) {
                                    failAssistant(id, assistant);
                                    Log.w(TAG, "Offline assistant TTS synthesis failed for " + id);
                                    return;
                                }
                                CueDispatcher.PlaybackCallback callback = speechCallbacks.remove(id);
                                if (callback != null)
                                    callback.onFinished(SystemClock.elapsedRealtime(), false);
                                Log.w(TAG, "TTS failed for " + id);
                            }
                            @Override public void onStop(String id, boolean interrupted) {
                                AssistantUtterance assistant = assistantUtterances.get(id);
                                if (assistant != null) {
                                    failAssistant(id, assistant);
                                    return;
                                }
                                CueDispatcher.PlaybackCallback callback = speechCallbacks.remove(id);
                                if (callback != null)
                                    callback.onFinished(SystemClock.elapsedRealtime(), false);
                            }
                        });
                    } catch (RuntimeException error) {
                        // TTS is optional. A broken or unavailable engine must
                        // not crash the main thread or stop tones and haptics.
                        ttsReady = false;
                        Log.w(TAG, "Could not prepare optional TTS; short tones remain active", error);
                    }
                }
            }), selectedEngine.isEmpty() ? null : selectedEngine);
        } catch (RuntimeException error) {
            tts = null;
            ttsReady = false;
            Log.w(TAG, "Could not initialize optional TTS; short tones remain active", error);
        }
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
            mainHandler.postDelayed(() -> finishSpatialTone(active), 240);
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

    @Override public boolean speak(CueRequest request, boolean interrupt,
                                   CueDispatcher.PlaybackCallback callback) {
        if (request.category == CueRequest.Category.ASSISTANT)
            return synthesizeAssistant(request, callback);
        TextToSpeech voice;
        String utteranceId = "cue:" + request.cueId;
        Bundle parameters = new Bundle();
        synchronized (audioLock) {
            if (closed || !ttsReady || tts == null || request.speech == null ||
                    SystemClock.elapsedRealtime() > request.expiresAtMs) return false;
            voice = tts;
            parameters.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume());
            speechCallbacks.put(utteranceId, callback);
        }
        // Keep the remote engine call outside audioLock.  TTS progress
        // callbacks can re-enter the dispatcher and otherwise invert the
        // dispatcher/audio lock order during pause or preemption.
        try {
            int result = voice.speak(request.speech,
                    interrupt ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
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
        TextToSpeech voice;
        if (request.speech == null || request.speech.isEmpty()) return false;
        String utteranceId = "assistant:" + request.cueId + ":" + System.nanoTime();
        Bundle parameters = new Bundle();
        parameters.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f);
        final AssistantUtterance utterance;
        synchronized (audioLock) {
            if (closed || !ttsReady || !offlineTtsReady || tts == null) return false;
            if (!request.playbackAllowedAt(SystemClock.elapsedRealtime())) {
                callback.onFinished(SystemClock.elapsedRealtime(), false);
                return true;
            }
            voice = tts;
            File outputFile;
            try {
                outputFile = AssistantTtsCache.createOutputFile(context.getCacheDir());
            } catch (IOException error) {
                Log.w(TAG, "Could not allocate assistant TTS cache file", error);
                return false;
            }
            final AssistantUtterance created = new AssistantUtterance(request, callback, outputFile);
            created.synthesisTimeout = () -> {
                if (assistantUtterances.remove(utteranceId, created)) {
                    created.cancelled = true;
                    AssistantTtsCache.delete(created.outputFile);
                    TextToSpeech activeVoice = tts;
                    if (activeVoice != null) {
                        try { activeVoice.stop(); }
                        catch (RuntimeException error) {
                            Log.w(TAG, "Could not stop timed-out assistant synthesis", error);
                        }
                    }
                    callback.onFinished(SystemClock.elapsedRealtime(), false);
                    Log.w(TAG, "Offline assistant TTS synthesis timed out for " + utteranceId);
                }
            };
            assistantUtterances.put(utteranceId, created);
            utterance = created;
        }
        mainHandler.postDelayed(utterance.synthesisTimeout, ASSISTANT_SYNTHESIS_TIMEOUT_MS);
        if (closed || utterance.cancelled || assistantUtterances.get(utteranceId) != utterance) {
            mainHandler.removeCallbacks(utterance.synthesisTimeout);
            AssistantTtsCache.delete(utterance.outputFile);
            return false;
        }
        try {
            int result = voice.synthesizeToFile(request.speech, parameters,
                    utterance.outputFile, utteranceId);
            if (result == TextToSpeech.SUCCESS) return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not synthesize assistant speech offline", error);
        }
        assistantUtterances.remove(utteranceId, utterance);
        mainHandler.removeCallbacks(utterance.synthesisTimeout);
        AssistantTtsCache.delete(utterance.outputFile);
        return false;
    }

    private void playAssistantFile(String utteranceId, AssistantUtterance utterance) {
        AudioTrack track = null;
        boolean started = false;
        boolean completed = false;
        try {
            if (utterance.cancelled || closed) return;
            PcmWav wav = readPcmWav(utterance.outputFile);
            short[] pcm16k = VoiceEchoProcessor.resampleTo16k(wav.samples, wav.sampleRateHz);
            if (pcm16k.length == 0 || utterance.cancelled || closed) return;
            int minBuffer = AudioTrack.getMinBufferSize(VoiceEchoProcessor.SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuffer <= 0) throw new IOException("AudioTrack buffer size unavailable");
            // Keep the output queue at the device minimum (and at least two
            // 10 ms frames). Reference frames are sent immediately after the
            // matching PCM is accepted, close to the two-frame delay SpeexDSP
            // uses for its playback buffer.
            int bufferBytes = Math.max(minBuffer, VoiceEchoProcessor.FRAME_SAMPLES * 2 * 2);
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
            track.setVolume(volume());
            synchronized (audioLock) {
                boolean current = !closed && !utterance.cancelled
                        && assistantUtterances.get(utteranceId) == utterance;
                if (!current) {
                    utterance.cancelled = true;
                } else if (!utterance.request.playbackAllowedAt(SystemClock.elapsedRealtime())) {
                    // Keep it current until finishAssistant() delivers the
                    // terminal callback so CueDispatcher records EXPIRED.
                } else {
                    assistantTrack = track;
                    track.play();
                    started = true;
                }
            }
            if (!started) {
                return;
            }
            if (utterance.cancelled || assistantUtterances.get(utteranceId) != utterance)
                return;
            utterance.callback.onStarted(SystemClock.elapsedRealtime());
            AssistantPlaybackListener reference = assistantPlaybackListener;
            short[] referenceFrame = reference == null ? null
                    : new short[VoiceEchoProcessor.FRAME_SAMPLES];
            int referenceSamples = 0;
            for (int offset = 0; offset < pcm16k.length
                    && !utterance.cancelled && !closed;) {
                int count = Math.min(VoiceEchoProcessor.FRAME_SAMPLES, pcm16k.length - offset);
                int written = track.write(pcm16k, offset, count, AudioTrack.WRITE_BLOCKING);
                if (written <= 0) throw new IOException("Assistant PCM output stopped");
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
                            if (!utterance.cancelled && !closed)
                                emitPcmReference(reference, referenceFrame);
                            referenceFrame = new short[VoiceEchoProcessor.FRAME_SAMPLES];
                            referenceSamples = 0;
                        }
                    }
                }
                offset += written;
            }
            if (reference != null && referenceSamples > 0
                    && !utterance.cancelled && !closed)
                emitPcmReference(reference, referenceFrame);
            completed = !utterance.cancelled && !closed;
            if (completed) {
                // Allow the final queued frame to reach the output before reporting completion.
                while (!utterance.cancelled && !closed
                        && track.getPlaybackHeadPosition() < pcm16k.length) {
                    try { Thread.sleep(5); }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        completed = false;
                        break;
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
            finishAssistant(utteranceId, utterance, started && completed);
        }
    }

    private void emitPcmReference(AssistantPlaybackListener listener, short[] frame) {
        try {
            listener.onPcmReference(frame);
        } catch (RuntimeException error) {
            Log.w(TAG, "Assistant PCM reference listener failed", error);
        }
    }

    private void finishAssistant(String utteranceId, AssistantUtterance utterance,
                                 boolean success) {
        mainHandler.removeCallbacks(utterance.synthesisTimeout);
        boolean wasCurrent = assistantUtterances.remove(utteranceId, utterance);
        AssistantTtsCache.delete(utterance.outputFile);
        if (wasCurrent && !utterance.cancelled && !closed)
            utterance.callback.onFinished(SystemClock.elapsedRealtime(), success);
    }

    private void failAssistant(String utteranceId, AssistantUtterance utterance) {
        mainHandler.removeCallbacks(utterance.synthesisTimeout);
        if (!assistantUtterances.remove(utteranceId, utterance)) return;
        AssistantTtsCache.delete(utterance.outputFile);
        if (!utterance.cancelled && !closed)
            utterance.callback.onFinished(SystemClock.elapsedRealtime(), false);
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

    boolean speechReady() { return ttsReady && !closed; }

    /** Assistant answers require an installed Chinese voice that needs no network. */
    boolean assistantSpeechReady() { return ttsReady && offlineTtsReady && !closed; }

    static long speechTimeoutMs(CueRequest request) {
        return request.category == CueRequest.Category.SYSTEM
                && ReminderGuide.NARRATION_KIND.equals(request.kind)
                ? ReminderGuide.NARRATION_TIMEOUT_MS : SPEECH_TIMEOUT_MS;
    }

    @Override public void stopSpeech() {
        TextToSpeech voice;
        AudioTrack track;
        synchronized (audioLock) {
            // Some TTS engines do not deliver onStop for every flushed
            // utterance.  Do not retain callbacks from a paused session.
            speechCallbacks.clear();
            for (AssistantUtterance utterance : assistantUtterances.values()) {
                utterance.cancelled = true;
                mainHandler.removeCallbacks(utterance.synthesisTimeout);
                AssistantTtsCache.delete(utterance.outputFile);
            }
            assistantUtterances.clear();
            track = assistantTrack;
            assistantTrack = null;
            voice = tts;
        }
        // Do not call into the remote TTS engine while holding audioLock:
        // an engine may synchronously or asynchronously deliver onStop.
        if (voice != null) {
            try {
                voice.stop();
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not stop accessibility speech", error);
            }
        }
        stopAssistantTrack(track);
    }

    @Override public void cancelAssistantSpeech() {
        TextToSpeech voice;
        AudioTrack track;
        boolean hadAssistant;
        synchronized (audioLock) {
            hadAssistant = !assistantUtterances.isEmpty() || assistantTrack != null;
            for (AssistantUtterance utterance : assistantUtterances.values()) {
                utterance.cancelled = true;
                mainHandler.removeCallbacks(utterance.synthesisTimeout);
                AssistantTtsCache.delete(utterance.outputFile);
            }
            assistantUtterances.clear();
            track = assistantTrack;
            assistantTrack = null;
            voice = tts;
        }
        if (hadAssistant && voice != null) {
            try { voice.stop(); }
            catch (RuntimeException error) {
                Log.w(TAG, "Could not stop assistant synthesis", error);
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
        synchronized (audioLock) {
            PendingToneQueue.Pending pending = pendingTones.clear();
            if (pending != null && pending.callback != null)
                pending.callback.onFinished(SystemClock.elapsedRealtime(), false);
            cancelSpatialTones(null);
        }
    }

    @Override public void cancelPendingTone(CueRequest.Category category) {
        synchronized (audioLock) {
            PendingToneQueue.Pending pending = pendingTones.clearCategory(category);
            if (pending != null && pending.callback != null)
                pending.callback.onFinished(SystemClock.elapsedRealtime(), false);
            cancelSpatialTones(category);
        }
    }

    private boolean vibrateEffect(CueRequest request) {
        try {
            Vibrator vibrator;
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager = context.getSystemService(VibratorManager.class);
                vibrator = manager == null ? null : manager.getDefaultVibrator();
            } else {
                vibrator = context.getSystemService(Vibrator.class);
            }
            if (vibrator == null || !vibrator.hasVibrator()) {
                Log.w(TAG, "HapticRequest unavailable=no_vibrator cueId=" + request.cueId);
                return false;
            }
            int direction = request.hapticCode;
            long[] pattern;
            int[] amplitudes = null;
            if (request.category == CueRequest.Category.NEAR_ZONE
                    && PresentationAudioPolicy.from(GameProfile.settings(context)).distanceHaptic
                    && Float.isFinite(request.distance)) {
                if (vibrator.hasAmplitudeControl()) {
                    int amplitude = PresentationAudioPolicy.distanceHapticAmplitude(
                            request.distance, request.urgency);
                    pattern = new long[]{0, NEAR_HAPTIC_ON_MS, NEAR_HAPTIC_GAP_MS,
                            NEAR_HAPTIC_ON_MS};
                    amplitudes = new int[]{0, amplitude, 0, amplitude};
                } else {
                    long onMs = PresentationAudioPolicy.distanceHapticDurationMs(
                            request.distance, request.urgency);
                    pattern = new long[]{0, onMs, NEAR_HAPTIC_GAP_MS, onMs};
                }
            } else if (request.category == CueRequest.Category.NEAR_ZONE)
                pattern = new long[]{0, NEAR_HAPTIC_ON_MS, NEAR_HAPTIC_GAP_MS,
                        NEAR_HAPTIC_ON_MS};
            else if (direction == 1) pattern = new long[]{0, 35, 45, 80};
            else if (direction == 2) pattern = new long[]{0, 80, 45, 35};
            else if (direction == 3) pattern = new long[]{0, 35};
            else if (direction == 4) pattern = new long[]{0, 35, 45, 35};
            else pattern = new long[]{0, 45};
            VibrationEffect effect = amplitudes == null
                    ? VibrationEffect.createWaveform(pattern, -1)
                    : VibrationEffect.createWaveform(pattern, amplitudes, -1);
            // Untagged short effects become TOUCH and are silently suppressed
            // when touch feedback is off. These are accessibility cues, not
            // taps; keep all system interruption settings in force.
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(
                        VibrationAttributes.USAGE_ACCESSIBILITY));
            } else {
                vibrator.vibrate(effect, new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            }
            long durationMs = 0;
            for (long part : pattern) durationMs += part;
            // The API is void: submission is not proof the user felt vibration.
            Log.i(TAG, "HapticRequest cueId=" + request.cueId
                    + " usage=ACCESSIBILITY durationMs=" + durationMs);
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not request accessibility haptic", error);
            return false;
        }
    }

    void close() {
        TextToSpeech voice;
        synchronized (audioLock) {
            if (closed) return;
            closed = true;
            ttsReady = false;
            voice = tts;
            tts = null;
            ready.clear();
            failed.clear();
            pendingTones.clear();
            speechCallbacks.clear();
            for (AssistantUtterance utterance : assistantUtterances.values()) {
                utterance.cancelled = true;
                mainHandler.removeCallbacks(utterance.synthesisTimeout);
                AssistantTtsCache.delete(utterance.outputFile);
            }
            assistantUtterances.clear();
            if (assistantTrack != null) {
                stopAssistantTrack(assistantTrack);
                assistantTrack = null;
            }
            cancelSpatialTones(null);
        }
        if (voice != null) {
            try {
                voice.shutdown();
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not shut down TTS cleanly", error);
            }
        }
        assistantAudioWorker.shutdownNow();
        if (spatialTonePrewarmWorker != null) spatialTonePrewarmWorker.shutdownNow();
        pool.release();
    }
}
