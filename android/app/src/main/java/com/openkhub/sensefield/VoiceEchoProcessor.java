package com.openkhub.sensefield;

/** Optional SpeexDSP echo cancellation for assistant playback, in 10 ms PCM frames. */
final class VoiceEchoProcessor implements AutoCloseable {
    static final int SAMPLE_RATE_HZ = 16_000;
    static final int FRAME_SAMPLES = 160;

    private static final boolean NATIVE_AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("presentation_audio_jni");
            loaded = true;
        } catch (LinkageError error) {
            loaded = false;
        }
        NATIVE_AVAILABLE = loaded;
    }

    private long handle;

    VoiceEchoProcessor() {
        if (NATIVE_AVAILABLE) handle = nativeCreate();
    }

    static boolean isNativeAvailable() {
        return NATIVE_AVAILABLE;
    }

    boolean isAvailable() {
        return handle != 0;
    }

    /** Add the exact 16 kHz mono frame sent to the output device. */
    synchronized void feedRender(short[] reference) {
        requireFrame(reference);
        if (handle != 0) nativeFeedRender(handle, reference);
    }

    /** Remove the known assistant playback reference from one microphone frame. */
    synchronized short[] process(short[] microphone) {
        requireFrame(microphone);
        if (handle == 0) return microphone.clone();
        short[] result = nativeProcess(handle, microphone);
        return result == null ? microphone.clone() : result;
    }

    synchronized void reset() {
        if (handle != 0) nativeReset(handle);
    }

    @Override public synchronized void close() {
        if (handle == 0) return;
        long closing = handle;
        handle = 0;
        nativeClose(closing);
    }

    /** Resample complete mono PCM with the pinned SpeexDSP resampler. */
    static short[] resampleTo16k(short[] input, int inputRateHz) {
        if (input == null) throw new NullPointerException("input");
        if (inputRateHz == SAMPLE_RATE_HZ) return input.clone();
        if (!NATIVE_AVAILABLE) throw new IllegalStateException("SpeexDSP is unavailable");
        short[] result = nativeResampleTo16k(input, inputRateHz);
        if (result == null) throw new IllegalArgumentException("PCM resampling failed");
        return result;
    }

    private static void requireFrame(short[] pcm) {
        if (pcm == null || pcm.length != FRAME_SAMPLES)
            throw new IllegalArgumentException("PCM frames must contain exactly 160 samples");
    }

    private static native long nativeCreate();
    private static native void nativeFeedRender(long handle, short[] reference);
    private static native short[] nativeProcess(long handle, short[] microphone);
    private static native void nativeReset(long handle);
    private static native void nativeClose(long handle);
    private static native short[] nativeResampleTo16k(short[] input, int inputRateHz);
}
