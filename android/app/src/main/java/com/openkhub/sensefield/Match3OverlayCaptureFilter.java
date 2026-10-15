package com.openkhub.sensefield;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

/** Removes only this window's known translucent drawing from a current captured frame. */
final class Match3OverlayCaptureFilter {
    private final Context context;
    private Match3Hint maskedHint;
    private Match3Hint expectedHint, previousHint;
    private float expectedAlpha, previousAlpha;
    private boolean drawingExpected;
    private int left, top, width, height;
    private int[] drawing, row;
    private int[] first, last;

    Match3OverlayCaptureFilter(Context context) { this.context = context.getApplicationContext(); }

    void clear() {
        maskedHint = null; drawing = row = first = last = null;
        expectedHint = previousHint = null; drawingExpected = false;
    }

    void remember(Match3Hint hint, float alpha) {
        if (expectedHint != hint) {
            previousHint = expectedHint; previousAlpha = expectedAlpha;
            expectedHint = hint;
        }
        expectedAlpha = alpha; drawingExpected = true;
    }

    /** Window removal does not remove pixels from buffers already in the capture queue. */
    void retire() { drawingExpected = false; }

    boolean clean(Bitmap frame) {
        boolean cleaned = clean(frame, expectedHint, expectedAlpha);
        if (cleaned) previousHint = null;
        else if (previousHint != null) cleaned = clean(frame, previousHint, previousAlpha);
        if (!cleaned && !drawingExpected) clear();
        return cleaned;
    }

    /** Returns true only when every endpoint signature agrees with this drawing. */
    boolean clean(Bitmap frame, Match3Hint hint, float windowAlpha) {
        if (hint == null || frame.getWidth() != hint.geometry.frameWidth
                || frame.getHeight() != hint.geometry.frameHeight || !Float.isFinite(windowAlpha)
                || windowAlpha <= 0 || windowAlpha > .8f || !captured(frame, hint, windowAlpha)) return false;
        if (maskedHint != hint) prepare(hint);
        for (int y = 0; y < height; y++) {
            int start = first[y], end = last[y];
            if (end < start) continue;
            int count = end - start + 1;
            frame.getPixels(row, 0, count, left + start, top + y, count, 1);
            for (int x = start; x <= end; x++) {
                int color = drawing[y * width + x];
                float alpha = Color.alpha(color) / 255f * windowAlpha;
                if (alpha == 0) continue;
                int original = row[x - start];
                row[x - start] = Color.rgb(unblend(Color.red(original), Color.red(color), alpha),
                        unblend(Color.green(original), Color.green(color), alpha),
                        unblend(Color.blue(original), Color.blue(color), alpha));
            }
            frame.setPixels(row, 0, count, left + start, top + y, count, 1);
        }
        return true;
    }

    private void prepare(Match3Hint hint) {
        Rect bounds = Match3HintOverlay.captureBounds(hint);
        left = bounds.left; top = bounds.top; width = bounds.width(); height = bounds.height();
        Bitmap mask = Match3HintOverlay.captureMask(context, hint, left, top, width, height);
        try {
            drawing = new int[width * height]; row = new int[width];
            first = new int[height]; last = new int[height];
            mask.getPixels(drawing, 0, width, 0, 0, width, height);
            for (int y = 0; y < height; y++) {
                first[y] = width; last[y] = -1;
                for (int x = 0; x < width; x++) if (Color.alpha(drawing[y * width + x]) != 0) {
                    first[y] = Math.min(first[y], x); last[y] = x;
                }
            }
            maskedHint = hint;
        } finally { mask.recycle(); }
    }

    // Use this captured frame's values, never a previous board or cached game pixels.
    // At most 0.8 window opacity leaves recoverable current-frame source color.
    private static int unblend(int captured, int overlay, float alpha) {
        return Math.max(0, Math.min(255, Math.round((captured - alpha * overlay) / (1 - alpha))));
    }

    private static boolean captured(Bitmap frame, Match3Hint hint, float alpha) {
        for (float[] probe : Match3HintOverlay.probes(hint)) {
            int x = Math.round(probe[0] * frame.getWidth());
            int y = Math.round(probe[1] * frame.getHeight());
            if (!near(frame, x - 2, y, 255, 0, 255, alpha)
                    || !near(frame, x + 2, y, 0, 255, 0, alpha)) return false;
        }
        return true;
    }

    private static boolean near(Bitmap frame, int x, int y, int red, int green, int blue, float alpha) {
        for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
            int px = x + dx, py = y + dy;
            if (px < 0 || py < 0 || px >= frame.getWidth() || py >= frame.getHeight()) continue;
            int color = frame.getPixel(px, py);
            if (blended(Color.red(color), red, alpha) && blended(Color.green(color), green, alpha)
                    && blended(Color.blue(color), blue, alpha)) return true;
        }
        return false;
    }

    private static boolean blended(int value, int expected, float alpha) {
        return value >= expected * alpha - 16 && value <= expected * alpha + 255 * (1 - alpha) + 16;
    }
}
