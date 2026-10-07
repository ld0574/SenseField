package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Process-local cache of fixed, nonconversational phrases, bounded to two voice profiles. */
final class AlertSpeechCache {
    static final int SAMPLE_RATE = 16000;
    static final int MAX_SAMPLES = SAMPLE_RATE * 4;
    private static final Map<String, Map<String, short[]>> PROFILES = new LinkedHashMap<>();

    private AlertSpeechCache() {}

    static List<String> phrases(boolean twoWordFirst) {
        List<String> result = new ArrayList<>();
        for (int mode = 0; mode < 2; mode++) {
            boolean shortWords = mode == 0 ? twoWordFirst : !twoWordFirst;
            for (int sector = 0; sector <= 8; sector++)
                result.add(shortWords ? NearZoneRouting.twoWordSpeech(sector) : NearZoneRouting.speech(sector));
        }
        return Collections.unmodifiableList(result);
    }

    static synchronized short[] get(String profile, String phrase) {
        Map<String, short[]> cache = PROFILES.get(profile);
        return cache == null ? null : cache.get(phrase);
    }

    static synchronized int readyCount(String profile) {
        Map<String, short[]> cache = PROFILES.get(profile);
        return cache == null ? 0 : cache.size();
    }

    static synchronized void put(String profile, String phrase, short[] pcm) {
        if (profile == null || !phrases(false).contains(phrase)
                || pcm == null || pcm.length == 0 || pcm.length > MAX_SAMPLES)
            throw new IllegalArgumentException("Fixed alert phrase PCM is invalid");
        Map<String, short[]> cache = PROFILES.get(profile);
        if (cache == null) {
            if (PROFILES.size() == 2) PROFILES.remove(PROFILES.keySet().iterator().next());
            cache = new LinkedHashMap<>();
            PROFILES.put(profile, cache);
        }
        cache.put(phrase, pcm.clone());
    }

    /** Remove only digital zero padding, keeping 20 ms around real signal; no acoustic threshold. */
    static short[] prepare(short[] pcm, int inputRate) {
        if (pcm == null || inputRate < 8000 || inputRate > 96000 || pcm.length > inputRate * 4)
            throw new IllegalArgumentException("Alert PCM exceeds the fixed phrase budget");
        int first = 0, end = pcm.length;
        while (first < end && pcm[first] == 0) first++;
        while (end > first && pcm[end - 1] == 0) end--;
        if (first == end) throw new IllegalArgumentException("Silent alert PCM");
        first = Math.max(0, first - inputRate / 50);
        end = Math.min(pcm.length, end + inputRate / 50);
        return resample(Arrays.copyOfRange(pcm, first, end), inputRate, SAMPLE_RATE);
    }

    static short[] resample(short[] pcm, int inputRate, int outputRate) {
        if (pcm == null || pcm.length == 0 || inputRate <= 0 || outputRate <= 0)
            throw new IllegalArgumentException("PCM rate");
        int count = (int) Math.max(1, (long) pcm.length * outputRate / inputRate);
        short[] result = new short[count];
        for (int i = 0; i < count; i++) {
            double position = i * (double) inputRate / outputRate;
            int left = Math.min(pcm.length - 1, (int) position);
            int right = Math.min(pcm.length - 1, left + 1);
            double fraction = position - left;
            result[i] = (short) Math.round(pcm[left] * (1 - fraction) + pcm[right] * fraction);
        }
        return result;
    }

    /** One interleaved stereo buffer. Speech stays centered; tone has a single spatial stage. */
    static short[] mix(short[] speech16k, short[] stereoTone48k) {
        short[] speech = resample(speech16k, SAMPLE_RATE, 48000);
        int frames = Math.max(speech.length, stereoTone48k == null ? 0 : stereoTone48k.length / 2);
        short[] result = new short[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            int voice = frame < speech.length ? speech[frame] : 0;
            for (int channel = 0; channel < 2; channel++) {
                int i = frame * 2 + channel;
                // A small, simultaneous earcon must not bury the first spoken consonant.
                double tone = stereoTone48k != null && i < stereoTone48k.length
                        ? stereoTone48k[i] * 0.16 : 0;
                result[i] = (short) Math.max(Short.MIN_VALUE,
                        Math.min(Short.MAX_VALUE, Math.round(voice + tone)));
            }
        }
        return result;
    }
}
