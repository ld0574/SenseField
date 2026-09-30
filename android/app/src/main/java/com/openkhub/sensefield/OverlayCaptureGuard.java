package com.openkhub.sensefield;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Detects a secure overlay that has unexpectedly returned in a captured frame.
 *
 * <p>The overlay draws a tiny opaque two-colour probe at the centre of every
 * marker.  A secure window should never contribute that pattern to a
 * MediaProjection frame.  Two consecutive frames are required before the
 * visual channel is latched off, which filters a single stale or coincidental
 * pixel.  This class deliberately owns no audio or native state: callers only
 * close the overlay when {@link #observeFrame(ByteBuffer, int, int, int)}
 * returns {@code true}.</p>
 */
final class OverlayCaptureGuard {
    // Keep this colour out of the normal marker palette so a captured probe is
    // distinguishable from game content and from the red/cyan marker strokes.
    static final int PROBE_LEFT_RED = 255;
    static final int PROBE_LEFT_GREEN = 0;
    static final int PROBE_LEFT_BLUE = 255;
    static final int PROBE_RIGHT_RED = 0;
    static final int PROBE_RIGHT_GREEN = 255;
    static final int PROBE_RIGHT_BLUE = 0;
    private static final int REQUIRED_CONSECUTIVE_FRAMES = 2;
    private static final int CHANNEL_TOLERANCE = 16;
    static final int PROBE_HALF_WIDTH_PX = 4;
    static final int PROBE_HALF_HEIGHT_PX = 2;
    private static final int SAMPLE_OFFSET_X_PX = 2;
    private static final int SAMPLE_RADIUS_PX = 1;

    private static final class Marker {
        final float x;
        final float y;

        Marker(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    private List<Marker> renderedMarkers = Collections.emptyList();
    private int consecutiveProbeFrames;
    private boolean suppressed;

    /** Forget the markers associated with a closed or disabled overlay. */
    void clearMarkers() {
        renderedMarkers = Collections.emptyList();
        consecutiveProbeFrames = 0;
    }

    /** Reset the session latch when a new capture session starts. */
    void reset() {
        clearMarkers();
        suppressed = false;
    }

    boolean isSuppressed() {
        return suppressed;
    }

    /**
     * Remember the locations drawn after the current frame was processed.
     * Coordinates are normalized to the complete captured frame.
     */
    void recordRenderedFrame(NativeFrameResult frame) {
        if (suppressed || frame == null) return;
        List<Marker> next = new ArrayList<>();
        for (TrackedEntity entity : frame.entities) {
            if (!entity.isMinimapTrack() ||
                    (entity.state != TrackedEntity.STATE_VISIBLE &&
                    entity.state != TrackedEntity.STATE_LOST) ||
                    !validNormalizedBox(entity)) continue;
            // Read RectF's public fields so this platform-neutral guard also
            // behaves in local JVM tests, where Android framework methods are
            // configured to return default values.
            next.add(new Marker(
                    (entity.bbox.left + entity.bbox.right) * 0.5f,
                    (entity.bbox.top + entity.bbox.bottom) * 0.5f));
            if (next.size() >= MinimapOverlay.MAX_MARKERS) break;
        }
        renderedMarkers = Collections.unmodifiableList(next);
        if (next.isEmpty()) consecutiveProbeFrames = 0;
    }

    /**
     * Inspect the frame that follows the most recently rendered marker set.
     * Returns true exactly once, when visual output must be disabled for this
     * capture session.
     */
    boolean observeFrame(ByteBuffer pixels, int width, int height, int rowStride) {
        if (suppressed) return false;
        if (pixels == null || width <= 0 || height <= 0 ||
                rowStride < width * 4 || renderedMarkers.isEmpty()) {
            consecutiveProbeFrames = 0;
            return false;
        }
        int matches = 0;
        for (Marker marker : renderedMarkers) {
            if (probePatternAt(pixels, width, height, rowStride, marker.x, marker.y)) {
                matches++;
            }
        }
        if (matches == 0) {
            consecutiveProbeFrames = 0;
            return false;
        }
        consecutiveProbeFrames++;
        if (consecutiveProbeFrames < REQUIRED_CONSECUTIVE_FRAMES) return false;
        suppressed = true;
        clearMarkers();
        return true;
    }

    static boolean probePatternAt(ByteBuffer pixels, int width, int height, int rowStride,
                                  float normalizedX, float normalizedY) {
        if (pixels == null || width <= 0 || height <= 0 ||
                rowStride < width * 4 || !Float.isFinite(normalizedX) ||
                !Float.isFinite(normalizedY)) return false;
        int centerX = Math.round(normalizedX * width);
        int centerY = Math.round(normalizedY * height);
        return colorNear(pixels, width, height, rowStride,
                centerX - SAMPLE_OFFSET_X_PX, centerY,
                PROBE_LEFT_RED, PROBE_LEFT_GREEN, PROBE_LEFT_BLUE)
                && colorNear(pixels, width, height, rowStride,
                centerX + SAMPLE_OFFSET_X_PX, centerY,
                PROBE_RIGHT_RED, PROBE_RIGHT_GREEN, PROBE_RIGHT_BLUE);
    }

    private static boolean colorNear(ByteBuffer pixels, int width, int height, int rowStride,
                                     int expectedX, int expectedY,
                                     int red, int green, int blue) {
        for (int dy = -SAMPLE_RADIUS_PX; dy <= SAMPLE_RADIUS_PX; dy++) {
            for (int dx = -SAMPLE_RADIUS_PX; dx <= SAMPLE_RADIUS_PX; dx++) {
                if (colorAt(pixels, width, height, rowStride,
                        expectedX + dx, expectedY + dy, red, green, blue)) return true;
            }
        }
        return false;
    }

    private static boolean colorAt(ByteBuffer pixels, int width, int height, int rowStride,
                                   int x, int y, int red, int green, int blue) {
        if (x < 0 || x >= width || y < 0 || y >= height) return false;
        long offset = (long) y * rowStride + (long) x * 4;
        if (offset < 0 || offset + 2 >= pixels.limit() || offset > Integer.MAX_VALUE)
            return false;
        int index = (int) offset;
        return near(pixels.get(index) & 255, red) &&
                near(pixels.get(index + 1) & 255, green) &&
                near(pixels.get(index + 2) & 255, blue);
    }

    private static boolean validNormalizedBox(TrackedEntity entity) {
        return Float.isFinite(entity.bbox.left) && Float.isFinite(entity.bbox.top)
                && Float.isFinite(entity.bbox.right) && Float.isFinite(entity.bbox.bottom)
                && entity.bbox.left >= 0f && entity.bbox.top >= 0f
                && entity.bbox.right > entity.bbox.left
                && entity.bbox.bottom > entity.bbox.top
                && entity.bbox.right <= 1.001f && entity.bbox.bottom <= 1.001f;
    }

    private static boolean near(int actual, int expected) {
        return Math.abs(actual - expected) <= CHANNEL_TOLERANCE;
    }
}
