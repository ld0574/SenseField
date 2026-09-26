package org.openrd.mapassist;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private TextToSpeech tts;
    private volatile boolean closed;

    CuePlayer(Context context) {
        this.context = context.getApplicationContext();
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        pool = new SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build();
        pool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
            if (status == 0) ready.add(sampleId);
        });
        try {
            tones.put(1, pool.load(writeTone("main", 840).getAbsolutePath(), 1));
            tones.put(2, pool.load(writeTone("map", 600).getAbsolutePath(), 1));
            tones.put(3, pool.load(writeTone("ping", 1100).getAbsolutePath(), 1));
        } catch (IOException error) {
            Log.e(TAG, "Cannot prepare cue tones", error);
        }
        prepareVoices();
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
        tts = new TextToSpeech(context, status -> main.post(() -> {
            synchronized (audioLock) {
                if (closed || status != TextToSpeech.SUCCESS || tts == null) return;
                if (tts.setLanguage(Locale.SIMPLIFIED_CHINESE) < TextToSpeech.LANG_AVAILABLE) {
                    Log.w(TAG, "Chinese TTS voice unavailable; short tones remain active");
                    return;
                }
                tts.setSpeechRate(1.25f);
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
                    @Override public void onError(String id) { Log.w(TAG, "TTS failed for " + id); }
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
            }
        }));
    }

    private File voiceFile(String id) {
        return new File(context.getCacheDir(), "voice_" + id + ".wav");
    }

    boolean play(int kind, int direction) {
        synchronized (audioLock) {
            if (closed) return false;
            int volumePercent = GameProfile.settings(context).getInt("volume", 45);
            float volume = Math.max(0f, Math.min(1f, volumePercent / 100f));
            boolean queued = false;
            Integer tone = tones.get(kind);
            if (tone != null && ready.contains(tone)) {
                float left = volume;
                float right = volume;
                if (direction == 1) right *= 0.12f;
                if (direction == 2) left *= 0.12f;
                queued = pool.play(tone, left, right, kind, 0, 1f) != 0;
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

    void close() {
        synchronized (audioLock) {
            if (closed) return;
            closed = true;
            if (tts != null) tts.shutdown();
            pool.release();
            ready.clear();
            voices.clear();
        }
    }
}
