package org.openrd.mapassist;

import android.content.res.AssetManager;

import java.nio.ByteBuffer;

final class NativeBridge {
    static {
        System.loadLibrary("mapassist_jni");
    }

    private NativeBridge() {}

    static native long nativeCreate(
            AssetManager assetManager,
            float[] rois, int[] flags, float[] tuning, int[] eventInts,
            float minConfidence, byte[] enemyRgba, int enemyWidth, int enemyHeight,
            byte[] pingRgba, int pingWidth, int pingHeight,
            boolean minimapYolox, int yoloxInputSize,
            float yoloxConfidence, float yoloxNms,
            boolean minimapLocatorEnabled, float[] minimapLocatorFloats,
            int[] minimapLocatorInts, byte[] minimapLocatorDescriptor);

    // [kind, direction, priority, observation count, native processing microseconds,
    //  locator state, locator score milli, locator x/y/w/h parts per million]
    static native int[] nativeProcess(long session, ByteBuffer rgba,
                                      int width, int height, int rowStride,
                                      long frameTimestampMs, long processingNowMs);

    static native void nativeReset(long session);
    static native void nativeDestroy(long session);
}
