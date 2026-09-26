package org.openrd.mapassist;

import java.nio.ByteBuffer;

final class NativeBridge {
    static {
        System.loadLibrary("mapassist_jni");
    }

    private NativeBridge() {}

    static native long nativeCreate(
            float[] rois, int[] flags, float[] tuning, int[] eventInts,
            float minConfidence, byte[] enemyRgba, int enemyWidth, int enemyHeight,
            byte[] pingRgba, int pingWidth, int pingHeight);

    // [kind, direction, priority, observation count, native processing microseconds]
    static native int[] nativeProcess(long session, ByteBuffer rgba,
                                      int width, int height, int rowStride,
                                      long frameTimestampMs, long processingNowMs);

    static native void nativeReset(long session);
    static native void nativeDestroy(long session);
}
