package com.openkhub.sensefield;

import java.nio.ByteBuffer;

/** Copies a bounded thumbnail before ImageReader releases its buffer. */
final class DiagnosticPixels {
    final int width;
    final int height;
    final int[] argb;

    private DiagnosticPixels(int width, int height, int[] argb) {
        this.width = width;
        this.height = height;
        this.argb = argb;
    }

    static DiagnosticPixels copy(ByteBuffer rgba, int width, int height, int rowStride,
                                 int x0, int y0, int x1, int y1, int maxEdge) {
        if (rgba == null || width <= 0 || height <= 0 || maxEdge <= 0
                || rowStride < (long) width * 4
                || (long) (height - 1) * rowStride + (long) width * 4 > rgba.limit())
            return null;
        x0 = Math.max(0, Math.min(width, x0));
        y0 = Math.max(0, Math.min(height, y0));
        x1 = Math.max(0, Math.min(width, x1));
        y1 = Math.max(0, Math.min(height, y1));
        int cw = x1 - x0, ch = y1 - y0;
        if (cw <= 0 || ch <= 0) return null;
        double scale = Math.min(1.0, (double) Math.min(maxEdge, 1280) / Math.max(cw, ch));
        int ow = Math.max(1, (int) Math.round(cw * scale));
        int oh = Math.max(1, (int) Math.round(ch * scale));
        int[] pixels = new int[ow * oh];
        for (int y = 0; y < oh; y++) {
            int sy = y0 + (int) ((long) y * ch / oh);
            for (int x = 0; x < ow; x++) {
                int sx = x0 + (int) ((long) x * cw / ow);
                int at = sy * rowStride + sx * 4;
                pixels[y * ow + x] = 0xff000000 | ((rgba.get(at) & 255) << 16)
                        | ((rgba.get(at + 1) & 255) << 8) | (rgba.get(at + 2) & 255);
            }
        }
        return new DiagnosticPixels(ow, oh, pixels);
    }
}
