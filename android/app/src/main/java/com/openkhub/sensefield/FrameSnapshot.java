package com.openkhub.sensefield;

import android.graphics.Bitmap;
import android.graphics.Rect;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** Owned bounded pixels, never an ImageReader Image or its backing ByteBuffer. */
final class FrameSnapshot {
    final long frameId;
    final long capturedAtMs;
    final long generation;
    final long signature;
    final DiagnosticPixels pixels;

    FrameSnapshot(long id, long at, long generation, long signature, DiagnosticPixels pixels) {
        this.frameId = id; this.capturedAtMs = at; this.generation = generation;
        this.signature = signature; this.pixels = pixels;
    }
    static FrameSnapshot copy(ByteBuffer source, int width, int height, int stride,
            int edge, long id, long at, long generation, long signature, Rect ownWindow) {
        DiagnosticPixels pixels = DiagnosticPixels.copy(source, width, height, stride,
                0, 0, width, height, edge);
        if (pixels == null) return null;
        if (ownWindow != null && !ownWindow.isEmpty()) {
            int left = Math.min(pixels.width, Math.max(0, (ownWindow.left - 12) * pixels.width / width));
            int right = Math.max(left, Math.min(pixels.width, (ownWindow.right + 12) * pixels.width / width + 1));
            int top = Math.min(pixels.height, Math.max(0, (ownWindow.top - 12) * pixels.height / height));
            int bottom = Math.max(top, Math.min(pixels.height, (ownWindow.bottom + 12) * pixels.height / height + 1));
            for (int y = top; y < bottom; y++)
                java.util.Arrays.fill(pixels.argb, y * pixels.width + left,
                        y * pixels.width + right, 0xff000000);
        }
        return new FrameSnapshot(id, at, generation, signature, pixels);
    }
    byte[] jpeg() {
        Bitmap bitmap = Bitmap.createBitmap(pixels.argb, pixels.width, pixels.height, Bitmap.Config.ARGB_8888);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 75, output)) return null;
            byte[] bytes = output.toByteArray();
            return bytes.length <= 512 * 1024 ? bytes : null;
        } finally { bitmap.recycle(); }
    }
    /** Low-cost luminance signature; skip the assistant's own small window. */
    static long signature(ByteBuffer rgba, int width, int height, int stride, Rect ownWindow) {
        int[] values = new int[64]; int average = 0;
        for (int i = 0; i < 64; i++) {
            int x = (2 * (i % 8) + 1) * width / 16;
            int y = (2 * (i / 8) + 1) * height / 16;
            int value = 0;
            if (ownWindow == null || !ownWindow.contains(x, y)) {
                int p = y * stride + x * 4;
                value = ((rgba.get(p) & 255) * 77 + (rgba.get(p + 1) & 255) * 150
                        + (rgba.get(p + 2) & 255) * 29) >> 8;
            }
            values[i] = value; average += value;
        }
        average /= 64; long hash = 0;
        for (int i = 0; i < 64; i++) if (values[i] > average) hash |= 1L << i;
        return hash;
    }
}
