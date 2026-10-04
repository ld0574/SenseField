package com.openkhub.sensefield;

/** Per-utterance scalar diagnostics; never retains microphone samples. */
final class VoiceInputMetrics {
    int frames, speechFrames, peak, clippedSamples;
    private long samples;
    private double squared;

    void accept(short[] pcm, boolean speech) {
        frames++;
        if (speech) speechFrames++;
        for (short value : pcm) {
            int magnitude = Math.abs((int) value);
            peak = Math.max(peak, magnitude);
            if (magnitude >= 32760) clippedSamples++;
            squared += (double) value * value;
            samples++;
        }
    }

    int rms() { return samples == 0 ? 0 : (int) Math.round(Math.sqrt(squared / samples)); }

    void reset() {
        frames = speechFrames = peak = clippedSamples = 0;
        samples = 0;
        squared = 0;
    }
}
