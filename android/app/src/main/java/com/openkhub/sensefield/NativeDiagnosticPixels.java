package com.openkhub.sensefield;

import java.nio.ByteBuffer;

/** Optional native accelerator for diagnostic-only thumbnail copies. */
final class NativeDiagnosticPixels {
    private static final boolean AVAILABLE;

    static {
        boolean available;
        try {
            // Keep this loader independent from NativeBridge initialization so
            // an unavailable diagnostic accelerator cannot disable recognition.
            System.loadLibrary("mapassist_jni");
            available = true;
        } catch (UnsatisfiedLinkError unavailable) {
            available = false;
        }
        AVAILABLE = available;
    }

    private NativeDiagnosticPixels() {}

    static boolean isAvailable() {
        return AVAILABLE;
    }

    /** Returns true only when JNI validated and filled the complete output. */
    static boolean copy(ByteBuffer rgba, int width, int height, int rowStride,
                        int x0, int y0, int cropWidth, int cropHeight,
                        int outWidth, int outHeight, int[] argb) {
        if (!AVAILABLE || rgba == null || !rgba.isDirect() || argb == null) return false;
        return nativeCopy(rgba, rgba.limit(), width, height, rowStride,
                x0, y0, cropWidth, cropHeight, outWidth, outHeight, argb);
    }

    private static native boolean nativeCopy(ByteBuffer rgba, int bufferLimit,
            int width, int height, int rowStride,
            int x0, int y0, int cropWidth, int cropHeight,
            int outWidth, int outHeight, int[] argb);
}
