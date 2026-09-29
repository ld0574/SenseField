package com.openkhub.sensefield;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class CuePlayer implements CueDispatcher.Renderer {
    private static final String TAG = "MapAssistAudio";
    private static final long TONE_DURATION_MS = 90;

    private final Context context;
    private final SoundPool pool;
    private final Object audioLock = new Object();
    private final Map<Integer, Integer> tones = new HashMap<>();
    private final Map<String, CueDispatcher.PlaybackCallback> speechCallbacks =
            new ConcurrentHashMap<>();
    private final Set<Integer> ready = ConcurrentHashMap.newKeySet();
    private final Set<Integer> failed = ConcurrentHashMap.newKeySet();
    private final PendingToneQueue pendingTones = new PendingToneQueue();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private volatile boolean closed;
    private volatile boolean ttsReady;

    CuePlayer(Context context) {
        this.context = context.getApplicationContext();
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
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
                            && playToneLocked(pending.kind, pending.direction);
                    finishToneAttempt(pending.callback, now, played);
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
        } catch (IOException error) {
            Log.e(TAG, "Cannot prepare cue tones", error);
        }
        prepareVoices();
    }

    private void loadTone(int kind, String name, int frequency) throws IOException {
        int sample = pool.load(writeTone(name, frequency).getAbsolutePath(), 1);
        if (sample == 0) Log.e(TAG, "SoundPool rejected cue tone kind=" + kind);
        else tones.put(kind, sample);
    }

    private File writeTone(String name, int frequency) throws IOException {
        final int sampleRate = 48000;
        final int samples = sampleRate * 9 / 100;
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
            for (int i = 0; i < samples; i++) {
                double envelope = Math.min(1.0, i / 400.0)
                        * Math.min(1.0, (samples - i) / 800.0);
                short value = (short) (Math.sin(2 * Math.PI * frequency * i / sampleRate)
                        * 13000 * envelope);
                littleEndian16(output, value);
            }
        }
        return file;
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
            tts = new TextToSpeech(context, status -> mainHandler.post(() -> {
                synchronized (audioLock) {
                    if (closed || status != TextToSpeech.SUCCESS || tts == null) return;
                    try {
                        if (tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
                                < TextToSpeech.LANG_AVAILABLE) {
                            Log.w(TAG, "Chinese TTS voice unavailable; short tones remain active");
                            return;
                        }
                        tts.setSpeechRate(1.25f);
                        ttsReady = true;
                        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                            @Override public void onStart(String id) {
                                CueDispatcher.PlaybackCallback callback = speechCallbacks.get(id);
                                if (callback != null) callback.onStarted(SystemClock.elapsedRealtime());
                            }
                            @Override public void onDone(String id) {
                                CueDispatcher.PlaybackCallback callback = speechCallbacks.remove(id);
                                if (callback != null)
                                    callback.onFinished(SystemClock.elapsedRealtime(), true);
                            }
                            @Override public void onError(String id) {
                                CueDispatcher.PlaybackCallback callback = speechCallbacks.remove(id);
                                if (callback != null)
                                    callback.onFinished(SystemClock.elapsedRealtime(), false);
                                Log.w(TAG, "TTS failed for " + id);
                            }
                            @Override public void onStop(String id, boolean interrupted) {
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
            }));
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

    private boolean playToneLocked(int kind, int direction) {
        Integer tone = tones.get(kind);
        if (tone == null || !ready.contains(tone)) return false;
        float volume = volume();
        float left = volume;
        float right = volume;
        if (direction == 1) right *= 0.12f;
        if (direction == 2) left *= 0.12f;
        return pool.play(tone, left, right, kind, 0, 1f) != 0;
    }

    private boolean enqueueToneLocked(CueRequest request,
                                      CueDispatcher.PlaybackCallback callback) {
        long now = SystemClock.elapsedRealtime();
        if (closed || now > request.expiresAtMs) return false;
        Integer tone = tones.get(request.toneKind);
        if (tone == null || failed.contains(tone)) return false;
        // A valid newer event replaces anything waiting for its SoundPool
        // sample. Invalid or expired requests must leave that event intact.
        PendingToneQueue.Pending replaced = pendingTones.clear();
        if (replaced != null && replaced.callback != null)
            replaced.callback.onFinished(now, false);
        if (ready.contains(tone)) {
            boolean played = playToneLocked(request.toneKind, request.direction);
            finishToneAttempt(callback, now, played);
            return played;
        }
        return pendingTones.enqueue(tone, request.toneKind, request.direction,
                request.expiresAtMs, now, callback);
    }

    private void finishToneAttempt(CueDispatcher.PlaybackCallback callback, long now,
                                   boolean played) {
        if (callback == null) return;
        if (!played) {
            callback.onFinished(now, false);
            return;
        }
        callback.onStarted(now);
        mainHandler.postDelayed(() -> callback.onFinished(
                SystemClock.elapsedRealtime(), true), TONE_DURATION_MS);
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
                    && vibrate(request.hapticCode);
        }
    }

    @Override public boolean speak(CueRequest request, boolean interrupt,
                                   CueDispatcher.PlaybackCallback callback) {
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
            if (result == TextToSpeech.SUCCESS) return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not queue accessibility speech", error);
        }
        speechCallbacks.remove(utteranceId, callback);
        return false;
    }

    @Override public void stopSpeech() {
        TextToSpeech voice;
        synchronized (audioLock) {
            // Some TTS engines do not deliver onStop for every flushed
            // utterance.  Do not retain callbacks from a paused session.
            speechCallbacks.clear();
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
    }

    @Override public void cancelPendingTone() {
        synchronized (audioLock) {
            PendingToneQueue.Pending pending = pendingTones.clear();
            if (pending != null && pending.callback != null)
                pending.callback.onFinished(SystemClock.elapsedRealtime(), false);
        }
    }

    private boolean vibrate(int direction) {
        try {
            Vibrator vibrator;
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                VibratorManager manager = context.getSystemService(VibratorManager.class);
                vibrator = manager == null ? null : manager.getDefaultVibrator();
            } else {
                vibrator = context.getSystemService(Vibrator.class);
            }
            if (vibrator == null || !vibrator.hasVibrator()) return false;
            long[] pattern;
            if (direction == 1) pattern = new long[]{0, 35, 45, 80};
            else if (direction == 2) pattern = new long[]{0, 80, 45, 35};
            else if (direction == 3) pattern = new long[]{0, 35};
            else if (direction == 4) pattern = new long[]{0, 35, 45, 35};
            else pattern = new long[]{0, 45};
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not play vision-memory haptic", error);
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
        }
        if (voice != null) {
            try {
                voice.shutdown();
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not shut down TTS cleanly", error);
            }
        }
        pool.release();
    }
}
