package com.openkhub.sensefield;

/** One coordinate system for sampling, touch reading and visual hints. */
final class BoardGeometry {
    final int frameWidth, frameHeight, left, top, right, bottom, rows, cols;

    BoardGeometry(int width, int height, int left, int top, int right, int bottom,
                  int rows, int cols) {
        if (width <= 0 || height <= 0 || left < 0 || top < 0 || right > width
                || bottom > height || right <= left || bottom <= top || rows <= 0 || cols <= 0)
            throw new IllegalArgumentException("Invalid board geometry");
        this.frameWidth = width; this.frameHeight = height;
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        this.rows = rows; this.cols = cols;
    }

    static BoardGeometry fromPercent(int width, int height, int rows, int cols, int[] bounds) {
        return new BoardGeometry(width, height, width * bounds[0] / 100,
                height * bounds[1] / 100, width * bounds[2] / 100,
                height * bounds[3] / 100, rows, cols);
    }

    float cellLeft(int col) { return left + (right - left) * col / (float) cols; }
    float cellTop(int row) { return top + (bottom - top) * row / (float) rows; }
    int centerX(int col) { return (int) (left + (right - left) * (col + .5f) / cols); }
    int centerY(int row) { return (int) (top + (bottom - top) * (row + .5f) / rows); }
    int cellWidth() { return (right - left) / cols; }
    int cellHeight() { return (bottom - top) / rows; }

    int[] cellAt(int x, int y) {
        if (x < left || x >= right || y < top || y >= bottom) return null;
        return new int[]{(y - top) * rows / (bottom - top), (x - left) * cols / (right - left)};
    }

    int[] percentages() {
        return new int[]{left * 100 / frameWidth, top * 100 / frameHeight,
                Math.min(100, (right * 100 + frameWidth - 1) / frameWidth),
                Math.min(100, (bottom * 100 + frameHeight - 1) / frameHeight)};
    }

    boolean sameGrid(BoardGeometry other) {
        if (other == null || frameWidth != other.frameWidth || frameHeight != other.frameHeight
                || rows != other.rows || cols != other.cols) return false;
        int tolerance = Math.max(1, Math.min(frameWidth, frameHeight) / 160); // one mask sample
        return Math.abs(left - other.left) <= tolerance && Math.abs(top - other.top) <= tolerance
                && Math.abs(right - other.right) <= tolerance && Math.abs(bottom - other.bottom) <= tolerance;
    }

    boolean mapsToDisplay(int width, int height) {
        // Scaling is safe only for a full-display capture with the same aspect ratio.
        return width > 0 && height > 0 &&
                Math.abs((long) width * frameHeight - (long) height * frameWidth)
                        <= Math.max(frameWidth, frameHeight);
    }

    @Override public String toString() {
        return rows + "x" + cols + " bounds=" + left + "," + top + "," + right + "," + bottom
                + " frame=" + frameWidth + "x" + frameHeight;
    }
}
