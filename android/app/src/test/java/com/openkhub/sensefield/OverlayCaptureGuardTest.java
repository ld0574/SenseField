package com.openkhub.sensefield;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;

import org.junit.Test;

public final class OverlayCaptureGuardTest {
    @Test public void blackoutFallbackStaysSuppressedUntilNewSession() {
        OverlayCaptureGuard guard = new OverlayCaptureGuard();
        guard.suppressForBlackFrames();
        guard.clearMarkers();
        assertTrue(guard.isSuppressed());
        guard.reset();
        assertFalse(guard.isSuppressed());
    }

    private static NativeFrameResult frame(float x, float y) {
        int[] packet = new int[NativeFrameResult.VERSIONED_HEADER_SIZE +
                NativeFrameResult.VERSIONED_RECORD_STRIDE];
        packet[0] = NativeFrameResult.MAGIC;
        packet[1] = NativeFrameResult.VERSION_1;
        packet[2] = NativeFrameResult.VERSIONED_HEADER_SIZE;
        packet[3] = NativeFrameResult.VERSIONED_RECORD_STRIDE;
        packet[15] = 1;
        packet[16] = TrackedEntity.KIND_MINIMAP_ENEMY;
        packet[18] = TrackedEntity.STATE_VISIBLE;
        packet[21] = Math.round(x * 1_000_000f - 5_000f);
        packet[22] = Math.round(y * 1_000_000f - 5_000f);
        packet[23] = 10_000;
        packet[24] = 10_000;
        packet[25] = 900;
        return NativeFrameResult.parse(packet, 1000);
    }

    private static NativeFrameResult frameWithMarkers(int count) {
        int[] packet = new int[NativeFrameResult.VERSIONED_HEADER_SIZE +
                count * NativeFrameResult.VERSIONED_RECORD_STRIDE];
        packet[0] = NativeFrameResult.MAGIC;
        packet[1] = NativeFrameResult.VERSION_1;
        packet[2] = NativeFrameResult.VERSIONED_HEADER_SIZE;
        packet[3] = NativeFrameResult.VERSIONED_RECORD_STRIDE;
        packet[15] = count;
        for (int index = 0; index < count; index++) {
            int base = NativeFrameResult.VERSIONED_HEADER_SIZE +
                    index * NativeFrameResult.VERSIONED_RECORD_STRIDE;
            float centerX = 0.08f + 0.08f * index;
            packet[base] = TrackedEntity.KIND_MINIMAP_ENEMY;
            packet[base + 1] = index + 1;
            packet[base + 2] = TrackedEntity.STATE_VISIBLE;
            packet[base + 5] = Math.round(centerX * 1_000_000f - 5_000f);
            packet[base + 6] = 245_000;
            packet[base + 7] = 10_000;
            packet[base + 8] = 10_000;
            packet[base + 9] = 900;
        }
        return NativeFrameResult.parse(packet, 1000);
    }

    private static ByteBuffer pixelsWithProbe(int width, int height,
                                              int rowStride, float x, float y) {
        ByteBuffer pixels = ByteBuffer.allocateDirect(rowStride * height);
        int px = Math.round(x * width);
        int py = Math.round(y * height);
        putRgb(pixels, rowStride, px - 2, py,
                OverlayCaptureGuard.PROBE_LEFT_RED,
                OverlayCaptureGuard.PROBE_LEFT_GREEN,
                OverlayCaptureGuard.PROBE_LEFT_BLUE);
        putRgb(pixels, rowStride, px + 2, py,
                OverlayCaptureGuard.PROBE_RIGHT_RED,
                OverlayCaptureGuard.PROBE_RIGHT_GREEN,
                OverlayCaptureGuard.PROBE_RIGHT_BLUE);
        return pixels;
    }

    private static void putRgb(ByteBuffer pixels, int rowStride, int x, int y,
                               int red, int green, int blue) {
        int offset = y * rowStride + x * 4;
        pixels.put(offset, (byte) red);
        pixels.put(offset + 1, (byte) green);
        pixels.put(offset + 2, (byte) blue);
    }

    @Test public void twoConsecutiveCapturedProbesLatchVisualOff() {
        OverlayCaptureGuard guard = new OverlayCaptureGuard();
        guard.recordRenderedFrame(frame(0.25f, 0.25f));
        ByteBuffer pixels = pixelsWithProbe(100, 100, 400, 0.25f, 0.25f);

        assertFalse(guard.observeFrame(pixels, 100, 100, 400));
        assertTrue(guard.observeFrame(pixels, 100, 100, 400));
        assertTrue(guard.isSuppressed());
        assertFalse(guard.observeFrame(pixels, 100, 100, 400));
    }

    @Test public void renderingEachProcessedFrameDoesNotResetCaptureEvidence() {
        OverlayCaptureGuard guard = new OverlayCaptureGuard();
        NativeFrameResult frame = frame(0.25f, 0.25f);
        ByteBuffer pixels = pixelsWithProbe(100, 100, 400, 0.25f, 0.25f);

        // CaptureService observes the previous overlay and then renders the
        // newly processed snapshot on every frame. Evidence must survive that
        // production ordering so two captured frames can trip the latch.
        guard.recordRenderedFrame(frame);
        assertFalse(guard.observeFrame(pixels, 100, 100, 400));
        guard.recordRenderedFrame(frame);
        assertTrue(guard.observeFrame(pixels, 100, 100, 400));
    }

    @Test public void oneMissClearsEvidenceAndSecureFramesStayEnabled() {
        OverlayCaptureGuard guard = new OverlayCaptureGuard();
        guard.recordRenderedFrame(frame(0.25f, 0.25f));
        ByteBuffer probe = pixelsWithProbe(100, 100, 400, 0.25f, 0.25f);
        ByteBuffer clear = ByteBuffer.allocateDirect(400 * 100);

        assertFalse(guard.observeFrame(probe, 100, 100, 400));
        assertFalse(guard.observeFrame(clear, 100, 100, 400));
        assertFalse(guard.isSuppressed());
        assertFalse(guard.observeFrame(probe, 100, 100, 400));
    }

    @Test public void emptyRenderedFrameBreaksConsecutiveEvidence() {
        OverlayCaptureGuard guard = new OverlayCaptureGuard();
        NativeFrameResult marker = frame(0.25f, 0.25f);
        ByteBuffer probe = pixelsWithProbe(100, 100, 400, 0.25f, 0.25f);

        guard.recordRenderedFrame(marker);
        assertFalse(guard.observeFrame(probe, 100, 100, 400));
        guard.recordRenderedFrame(NativeFrameResult.empty());
        assertFalse(guard.observeFrame(probe, 100, 100, 400));
        guard.recordRenderedFrame(marker);
        assertFalse(guard.observeFrame(probe, 100, 100, 400));
        assertTrue(guard.observeFrame(probe, 100, 100, 400));
    }

    @Test public void oneProbeColourCannotDisableVisualOutput() {
        OverlayCaptureGuard guard = new OverlayCaptureGuard();
        guard.recordRenderedFrame(frame(0.25f, 0.25f));
        ByteBuffer pixels = ByteBuffer.allocateDirect(400 * 100);
        putRgb(pixels, 400, 23, 25,
                OverlayCaptureGuard.PROBE_LEFT_RED,
                OverlayCaptureGuard.PROBE_LEFT_GREEN,
                OverlayCaptureGuard.PROBE_LEFT_BLUE);

        assertFalse(guard.observeFrame(pixels, 100, 100, 400));
        assertFalse(guard.observeFrame(pixels, 100, 100, 400));
        assertFalse(guard.isSuppressed());
    }

    @Test public void markerBeyondOverlayRenderLimitIsNotProbed() {
        OverlayCaptureGuard guard = new OverlayCaptureGuard();
        guard.recordRenderedFrame(frameWithMarkers(MinimapOverlay.MAX_MARKERS + 1));
        float omittedCenterX = 0.08f + 0.08f * MinimapOverlay.MAX_MARKERS;
        ByteBuffer omittedProbe = pixelsWithProbe(
                100, 100, 400, omittedCenterX, 0.25f);

        assertFalse(guard.observeFrame(omittedProbe, 100, 100, 400));
        assertFalse(guard.observeFrame(omittedProbe, 100, 100, 400));
        assertFalse(guard.isSuppressed());
    }

    @Test public void resetAllowsASecondCaptureSessionToUseVisualOutput() {
        OverlayCaptureGuard guard = new OverlayCaptureGuard();
        guard.recordRenderedFrame(frame(0.25f, 0.25f));
        ByteBuffer probe = pixelsWithProbe(100, 100, 400, 0.25f, 0.25f);
        assertFalse(guard.observeFrame(probe, 100, 100, 400));
        assertTrue(guard.observeFrame(probe, 100, 100, 400));

        guard.reset();
        assertFalse(guard.isSuppressed());
        guard.recordRenderedFrame(frame(0.25f, 0.25f));
        assertFalse(guard.observeFrame(probe, 100, 100, 400));
    }
}
