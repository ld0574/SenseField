package com.openkhub.sensefield;

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
            int[] yoloxClassKinds, float[] yoloxClassThresholds,
            boolean minimapLocatorEnabled, float[] minimapLocatorFloats,
            int[] minimapLocatorInts, byte[] minimapLocatorDescriptor,
            boolean playerLifeEnabled, float[] playerLifeRoiAndThresholds,
            int playerLifeMaxDhashDistance, long[] playerLifeHashes,
            byte[] playerLifeStates, byte[] playerLifeLuma, byte[] playerLifeChroma);

    // The JNI boundary stays a compact int[] while its payload is explicitly
    // versioned. Callers must use NativeFrameResult.parse(); it also accepts
    // the legacy 0.2.2 marker packet for local compatibility tests.
    static native int[] nativeProcess(long session, ByteBuffer rgba,
                                      int width, int height, int rowStride,
                                      long frameTimestampMs, long processingNowMs);

    static NativeFrameResult parseFrameResult(int[] packed, long frameTimestampMs) {
        return NativeFrameResult.parse(packed, frameTimestampMs);
    }

    static native void nativeReset(long session);
    static native void nativeDestroy(long session);
}
