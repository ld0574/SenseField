package com.openkhub.sensefield;

/** Local WebRTC VAD for one 10 ms, 16 kHz mono PCM frame. No signal-level fallback. */
final class VoiceSpeechDetector implements AutoCloseable {
    static final int SAMPLE_RATE_HZ = 16_000;
    static final int FRAME_SAMPLES = 160;
    private static final int AGGRESSIVE_MODE = 3;
    private static final boolean NATIVE_AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("presentation_audio_jni");
            loaded = true;
        } catch (LinkageError unavailable) {
            loaded = false;
        }
        NATIVE_AVAILABLE = loaded;
    }

    private volatile long handle;

    VoiceSpeechDetector() {
        if (!NATIVE_AVAILABLE) return;
        try {
            handle = nativeCreate(AGGRESSIVE_MODE);
        } catch (LinkageError | RuntimeException unavailable) {
            handle = 0;
        }
    }

    /** False means no usable native VAD, so callers must fail closed. */
    boolean available() { return handle != 0; }

    /** Classify exactly 10 ms of 16 kHz mono PCM16; unavailable VAD is never treated as speech. */
    synchronized boolean isSpeech(short[] frame16kMono) {
        if (frame16kMono == null || frame16kMono.length != FRAME_SAMPLES)
            throw new IllegalArgumentException("WebRTC VAD requires exactly 160 PCM samples");
        long current = handle;
        if (current == 0) return false;
        try {
            return nativeIsSpeech(current, frame16kMono);
        } catch (LinkageError | RuntimeException unavailable) {
            close();
            return false;
        }
    }

    /**
     * Reset WebRTC's temporal classifier state after a capture discontinuity.
     * Recreating the native detector also reapplies the configured aggressive
     * mode, so frames from before a route or pause transition cannot influence
     * classification when capture resumes.
     */
    synchronized boolean reset() {
        close();
        if (!NATIVE_AVAILABLE) return false;
        try {
            handle = nativeCreate(AGGRESSIVE_MODE);
        } catch (LinkageError | RuntimeException unavailable) {
            handle = 0;
        }
        return handle != 0;
    }

    @Override public synchronized void close() {
        long closing = handle;
        if (closing == 0) return;
        handle = 0;
        try {
            nativeClose(closing);
        } catch (LinkageError ignored) {
            // The handle is invalidated first; a missing native close stays fail-closed.
        }
    }

    private static native long nativeCreate(int mode);
    private static native boolean nativeIsSpeech(long handle, short[] frame16kMono);
    private static native void nativeClose(long handle);
}
