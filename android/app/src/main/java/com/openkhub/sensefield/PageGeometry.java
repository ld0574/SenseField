package com.openkhub.sensefield;

/** Width changes affect reading layout only, never game-image coordinates. */
final class PageGeometry {
    static final int MAX_READING_WIDTH_DP = 720;
    private PageGeometry() {}

    static int readingWidthPx(int available, float density) {
        float scale = Float.isFinite(density) && density > 0f ? density : 1f;
        return Math.max(0, Math.min(Math.max(0, available), Math.round(MAX_READING_WIDTH_DP * scale)));
    }
}
