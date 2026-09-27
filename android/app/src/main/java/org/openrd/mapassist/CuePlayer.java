package org.openrd.mapassist;

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

final class CuePlayer {
    private static final String TAG = "MapAssistAudio";

    private final Context context;
    private final SoundPool pool;
    private final Object audioLock = new Object();
    private final Map<Integer, Integer> tones = new HashMap<>();
    private final Map<String, Integer> voices = new ConcurrentHashMap<>();
    private final Set<Integer> ready = ConcurrentHashMap.newKeySet();
    private final Set<Integer> failed = ConcurrentHashMap.newKeySet();
    private TextToSpeech tts;
    private PendingCue pendingTone;
    private volatile boolean closed;
    private volatile boolean ttsReady;
    private long lastVisionVoiceMs = Long.MIN_VALUE / 2;
    private long lastVisionHapticMs = Long.MIN_VALUE / 2;

    private static final class PendingCue {
        final int kind;
        final int direction;
        final long expiresAtMs;

        PendingCue(int kind, int direction, long expiresAtMs) {
            this.kind = kind;
            this.direction = direction;
            this.expiresAtMs = expiresAtMs;
        }
    }

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
                else failed.add(sampleId);
                PendingCue pending = pendingTone;
                Integer pendingSample = pending == null ? null : tones.get(pending.kind);
                if (pendingSample == null || pendingSample != sampleId) return;
                pendingTone = null;
                if (status != 0) {
                    Log.w(TAG, "Could not load pending cue tone kind=" + pending.kind);
                } else if (SystemClock.elapsedRealtime() <= pending.expiresAtMs) {
                    boolean played = playToneLocked(pending.kind, pending.direction);
                    Log.i(TAG, "Played cue after SoundPool load kind=" + pending.kind
                            + " queued=" + played);
                } else {
                    Log.i(TAG, "Dropped expired cue while SoundPool was loading kind="
                            + pending.kind);
                }
            }
        });
        try {
            loadTone(1, "main", 840);
            loadTone(2, "map", 600);
            loadTone(3, "ping", 1100);
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
        Handler main = new Handler(Looper.getMainLooper());
        try {
            tts = new TextToSpeech(context, status -> main.post(() -> {
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
                            @Override public void onStart(String id) {}
                            @Override public void onDone(String id) {
                                synchronized (audioLock) {
                                    if (closed) return;
                                    File file = voiceFile(id);
                                    if (!file.exists()) return;
                                    try {
                                        int sample = pool.load(file.getAbsolutePath(), 1);
                                        if (sample != 0) voices.put(id, sample);
                                    } catch (RuntimeException error) {
                                        Log.w(TAG, "Could not load synthesized cue " + id, error);
                                    }
                                }
                            }
                            @Override public void onError(String id) {
                                Log.w(TAG, "TTS failed for " + id);
                            }
                        });
                        Map<String, String> phrases = new HashMap<>();
                        phrases.put("danger", "危险信号");
                        phrases.put("main_up", "上方有敌人");
                        phrases.put("main_down", "下方有敌人");
                        phrases.put("map_up", "小地图上方有敌人");
                        phrases.put("map_down", "小地图下方有敌人");
                        for (Map.Entry<String, String> entry : phrases.entrySet()) {
                            tts.synthesizeToFile(entry.getValue(), new Bundle(),
                                    voiceFile(entry.getKey()), entry.getKey());
                        }
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

    private File voiceFile(String id) {
        return new File(context.getCacheDir(), "voice_" + id + ".wav");
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

    boolean play(int kind, int direction, long expiresAtMs) {
        synchronized (audioLock) {
            if (closed) return false;
            // A newer event supersedes any cue that is still waiting for its
            // tone sample to load.
            pendingTone = null;
            float volume = volume();
            boolean queued = playToneLocked(kind, direction);
            Integer tone = tones.get(kind);
            if (!queued && tone != null && !ready.contains(tone) && !failed.contains(tone)
                    && SystemClock.elapsedRealtime() <= expiresAtMs) {
                pendingTone = new PendingCue(kind, direction, expiresAtMs);
                queued = true;
            }
            String phrase = null;
            if (kind == 3) phrase = "danger";
            else if (direction == 3) phrase = kind == 1 ? "main_up" : "map_up";
            else if (direction == 4) phrase = kind == 1 ? "main_down" : "map_down";
            if (phrase != null) {
                Integer voice = voices.get(phrase);
                if (voice != null && ready.contains(voice)) {
                    queued = pool.play(voice, volume, volume, 3, 0, 1f) != 0 || queued;
                }
            }
            return queued;
        }
    }

    /**
     * Re-encode a new vision-memory transition. APPEAR is already represented
     * by the ordinary spatial tone, so it only adds a short directional
     * haptic. DISAPPEAR is spoken only when the tracker has a reliable motion
     * vector; stationary detector loss remains visual to avoid audio overload.
     */
    boolean playVisionEvent(int event, int position, int movementDirection) {
        synchronized (audioLock) {
            if (closed || (event != 1 && event != 2)) return false;
            long now = SystemClock.elapsedRealtime();
            int hapticDirection = movementDirection != 0
                    ? movementDirection : cardinalForPosition(position);
            boolean queued = false;
            if (now - lastVisionHapticMs >= 500) {
                queued = vibrate(hapticDirection);
                if (queued) lastVisionHapticMs = now;
            }
            if (event == 2 && movementDirection != 0 && ttsReady && tts != null
                    && now - lastVisionVoiceMs >= 2000) {
                String phrase = positionText(position) + "敌人消失，"
                        + movementText(movementDirection) + "移动";
                Bundle parameters = new Bundle();
                parameters.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume());
                try {
                    int result = tts.speak(phrase, TextToSpeech.QUEUE_ADD, parameters,
                            "vision-memory-" + now);
                    if (result == TextToSpeech.SUCCESS) {
                        queued = true;
                        lastVisionVoiceMs = now;
                    }
                } catch (RuntimeException error) {
                    Log.w(TAG, "Could not queue vision-memory speech", error);
                }
            }
            return queued;
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

    private static int cardinalForPosition(int position) {
        if (position == 1 || position == 5 || position == 7) return 1;
        if (position == 2 || position == 6 || position == 8) return 2;
        if (position == 3) return 3;
        if (position == 4) return 4;
        return 0;
    }

    private static String positionText(int position) {
        if (position == 1) return "左侧";
        if (position == 2) return "右侧";
        if (position == 3) return "上方";
        if (position == 4) return "下方";
        if (position == 5) return "左上";
        if (position == 6) return "右上";
        if (position == 7) return "左下";
        if (position == 8) return "右下";
        return "附近";
    }

    private static String movementText(int direction) {
        if (direction == 1) return "向左";
        if (direction == 2) return "向右";
        if (direction == 3) return "向上";
        if (direction == 4) return "向下";
        return "";
    }

    void close() {
        synchronized (audioLock) {
            if (closed) return;
            closed = true;
            ttsReady = false;
            if (tts != null) {
                try {
                    tts.shutdown();
                } catch (RuntimeException error) {
                    Log.w(TAG, "Could not shut down TTS cleanly", error);
                }
                tts = null;
            }
            pool.release();
            pendingTone = null;
            ready.clear();
            failed.clear();
            voices.clear();
        }
    }
}
