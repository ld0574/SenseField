package com.openkhub.sensefield;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Synthetic input only: exact detector parity, invalidation and paired work. */
@RunWith(AndroidJUnit4.class)
public final class DetectorReuseInstrumentedTest {
    @Test public void identicalRoiSkipsForwardButChangedPixelsAndResetRecompute() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        GameProfile p = GameProfile.load(context);
        GameProfile.TemplateData e = p.enemyTemplate, ping = p.pingTemplate;
        GameProfile.PlayerLifeData life = p.playerLife;
        long session = NativeBridge.nativeCreate(context.getAssets(), p.rois, p.flags, p.tuning,
                p.eventInts, p.minConfidence, e == null ? null : e.rgba, e == null ? 0 : e.width,
                e == null ? 0 : e.height, ping == null ? null : ping.rgba, ping == null ? 0 : ping.width,
                ping == null ? 0 : ping.height, p.minimapYolox, p.yoloxInputSize, p.yoloxParamAsset,
                p.yoloxBinAsset, p.yoloxConfidence, p.yoloxNms, p.yoloxClassKinds, p.yoloxClassThresholds,
                p.minimapLocatorEnabled, p.minimapLocatorFloats, p.minimapLocatorInts, p.minimapLocatorDescriptor,
                life != null, life == null ? null : life.roiAndThresholds, life == null ? 0 : life.maxDhashDistance,
                life == null ? null : life.hashes, life == null ? null : life.states,
                life == null ? null : life.luma, life == null ? null : life.chroma);
        assertTrue(session != 0);
        try {
            int width = 1280, height = 720, stride = width * 4 + 32;
            ByteBuffer pixels = ByteBuffer.allocateDirect(stride * height);
            NativeBridge.nativeSetDetectorCache(session, false);
            for (int i = 0; i < 3; i++) process(session, pixels, width, height, stride);
            long[] before = NativeBridge.nativeReadWorkStats(session);
            assertEquals("Detector phase counters use the append-only v2 schema", 2, before[0]);
            assertEquals(9, before.length);
            long[] baselineNs = new long[15], reuseNs = new long[15];
            long[] expected = null;
            for (int i = 0; i < baselineNs.length; i++) {
                long started = System.nanoTime();
                process(session, pixels, width, height, stride);
                baselineNs[i] = System.nanoTime() - started;
                expected = NativeBridge.nativeReadDiagnosticSnapshot(session);
            }
            long[] baselineStats = NativeBridge.nativeReadWorkStats(session);
            assertEquals(15, baselineStats[1] - before[1]);
            NativeBridge.nativeSetDetectorCache(session, true);
            process(session, pixels, width, height, stride); // One successful cache population.
            long[] populated = NativeBridge.nativeReadWorkStats(session);
            assertEquals(1, populated[1] - baselineStats[1]);
            assertTrue(populated[6] > 0 && populated[6] <= 8 * 1024 * 1024);
            for (int i = 0; i < reuseNs.length; i++) {
                long started = System.nanoTime();
                long observedAt = process(session, pixels, width, height, stride);
                reuseNs[i] = System.nanoTime() - started;
                long[] actual = NativeBridge.nativeReadDiagnosticSnapshot(session);
                assertEquals(expected.length, actual.length);
                assertEquals(expected[1], actual[1]);
                for (int row = 0; row < actual[1]; row++) {
                    int base = 2 + row * 8;
                    for (int field = 0; field < 7; field++) assertEquals(expected[base + field], actual[base + field]);
                    assertEquals("Each observation belongs to the current captured frame", observedAt, actual[base + 7]);
                }
            }
            long[] reused = NativeBridge.nativeReadWorkStats(session);
            assertEquals(15, reused[2] - populated[2]);
            assertEquals(populated[1], reused[1]);
            assertTrue("Real processing reports input preparation cost", populated[7] > 0);
            assertTrue("Real processing reports decoding cost", populated[8] > 0);
            assertEquals("Exact reuse does not prepare another model input", populated[7], reused[7]);
            assertEquals("Exact reuse does not decode another model output", populated[8], reused[8]);
            pixels.put(pixels.capacity() - 1, (byte) 3); // Row padding outside the detector input.
            process(session, pixels, width, height, stride);
            assertEquals(reused[2] + 1, NativeBridge.nativeReadWorkStats(session)[2]);
            int x = Math.max(0, (int) Math.floor(p.rois[0] * width));
            int y = Math.max(0, (int) Math.floor(p.rois[1] * height));
            pixels.put(y * stride + x * 4, (byte) 1); // Even one changed ROI byte invalidates.
            process(session, pixels, width, height, stride);
            assertEquals(reused[1] + 1, NativeBridge.nativeReadWorkStats(session)[1]);
            NativeBridge.nativeReset(session);
            process(session, pixels, width, height, stride);
            assertEquals(reused[1] + 2, NativeBridge.nativeReadWorkStats(session)[1]);
            // A miss near the last ROI pixel forces a full comparison. Alternate
            // baseline/miss order so the expensive route is not always first.
            int right = Math.min(width, (int) Math.ceil((p.rois[0] + p.rois[2]) * width));
            int bottom = Math.min(height, (int) Math.ceil((p.rois[1] + p.rois[3]) * height));
            int lastPixel = (bottom - 1) * stride + (right - 1) * 4;
            long[] movingBaselineNs = new long[15], movingMissNs = new long[15];
            for (int sample = 0; sample < 15; sample++) {
                for (int order = 0; order < 2; order++) {
                    boolean miss = (sample + order) % 2 == 0;
                    NativeBridge.nativeSetDetectorCache(session, miss);
                    if (miss) {
                        process(session, pixels, width, height, stride);
                        pixels.put(lastPixel, (byte) (pixels.get(lastPixel) + 1));
                    }
                    long attempts = NativeBridge.nativeReadWorkStats(session)[1];
                    long started = System.nanoTime();
                    process(session, pixels, width, height, stride);
                    long elapsed = System.nanoTime() - started;
                    assertEquals(attempts + 1, NativeBridge.nativeReadWorkStats(session)[1]);
                    if (miss) movingMissNs[sample] = elapsed; else movingBaselineNs[sample] = elapsed;
                }
            }
            Arrays.sort(baselineNs); Arrays.sort(reuseNs);
            Arrays.sort(movingBaselineNs); Arrays.sort(movingMissNs);
            Bundle result = new Bundle();
            result.putString("stream", "\nDETECTOR_REUSE_SYNTHETIC samples=15 baselineP50Micros="
                    + baselineNs[7] / 1000.0 + " baselineP95Micros=" + baselineNs[14] / 1000.0
                    + " reuseP50Micros=" + reuseNs[7] / 1000.0 + " reuseP95Micros=" + reuseNs[14] / 1000.0
                    + " movingBaselineP50Micros=" + movingBaselineNs[7] / 1000.0
                    + " movingBaselineP95Micros=" + movingBaselineNs[14] / 1000.0
                    + " movingMissP50Micros=" + movingMissNs[7] / 1000.0
                    + " movingMissP95Micros=" + movingMissNs[14] / 1000.0
                    + " realMatchHitRate=unmeasured thermalProof=false\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
        } finally { NativeBridge.nativeDestroy(session); }
    }

    private static long process(long session, ByteBuffer pixels, int width, int height, int stride) {
        long now = SystemClock.elapsedRealtime();
        int[] result = NativeBridge.nativeProcess(session, pixels, width, height, stride, now, now);
        assertNotNull(result);
        assertTrue(result.length >= 24 && result[7] >= 0);
        return now;
    }
}
