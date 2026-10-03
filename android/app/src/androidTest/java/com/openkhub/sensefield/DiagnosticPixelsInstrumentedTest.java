package com.openkhub.sensefield;

import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

/** Synthetic pixels only; this does not measure game accuracy or phone temperature. */
@RunWith(AndroidJUnit4.class)
public class DiagnosticPixelsInstrumentedTest {
    @Test public void directReadOnlyAndSlicedBuffersMatchJavaPixels() {
        assertTrue("The JNI path must actually be loaded", NativeDiagnosticPixels.isAvailable());
        Random random = new Random(7307);
        for (int trial = 0; trial < 48; trial++) {
            int width = 17 + random.nextInt(92), height = 13 + random.nextInt(67);
            int stride = width * 4 + random.nextInt(12) * 4;
            int required = (height - 1) * stride + width * 4;
            ByteBuffer backing = ByteBuffer.allocateDirect(required + 16);
            for (int i = 0; i < backing.capacity(); i++) backing.put(i, (byte) random.nextInt());
            ByteBuffer source = backing;
            if (trial % 3 == 1) {
                backing.position(16);
                source = backing.slice();
            } else if (trial % 3 == 2) source = backing.asReadOnlyBuffer();
            source.limit(required);
            source.position(7);
            source.order(trial % 2 == 0 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            int x0 = random.nextInt(width), y0 = random.nextInt(height);
            int x1 = x0 + 1 + random.nextInt(width - x0);
            int y1 = y0 + 1 + random.nextInt(height - y0);
            int edge = 1 + random.nextInt(110);
            DiagnosticPixels actual = DiagnosticPixels.copy(source, width, height, stride,
                    x0, y0, x1, y1, edge);
            PixelData expected = javaOracle(source, width, height, stride,
                    x0, y0, x1, y1, edge);
            assertNotNull(actual);
            assertEquals(expected.width, actual.width);
            assertEquals(expected.height, actual.height);
            assertArrayEquals(expected.argb, actual.argb);
            int[] nativeOnly = new int[actual.argb.length];
            assertTrue(NativeDiagnosticPixels.copy(source, width, height, stride, x0, y0,
                    x1 - x0, y1 - y0, actual.width, actual.height, nativeOnly));
            assertArrayEquals(expected.argb, nativeOnly);
            assertEquals(7, source.position());
            assertEquals(required, source.limit());
            assertEquals(trial % 2 == 0 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN, source.order());
        }
    }

    @Test public void nativeRejectsLimitsInvalidGeometryAndHeapInput() {
        assertTrue(NativeDiagnosticPixels.isAvailable());
        ByteBuffer direct = ByteBuffer.allocateDirect(64);
        int[] output = new int[16];
        Arrays.fill(output, 0x13572468);
        direct.limit(63);
        assertFalse(NativeDiagnosticPixels.copy(direct, 4, 4, 16, 0, 0, 4, 4, 4, 4, output));
        direct.limit(64);
        assertFalse(NativeDiagnosticPixels.copy(direct, 4, 4, 16, -1, 0, 4, 4, 4, 4, output));
        assertFalse(NativeDiagnosticPixels.copy(direct, 4, 4, 16, 1, 0, 4, 4, 4, 4, output));
        assertFalse(NativeDiagnosticPixels.copy(direct, 4, 4, 16, 0, 0, 4, 4, 0, 4, output));
        assertFalse(NativeDiagnosticPixels.copy(direct, 4, 4, Integer.MAX_VALUE,
                0, 0, 4, 4, 4, 4, output));
        assertFalse(NativeDiagnosticPixels.copy(direct, 4, 4, 16,
                0, 0, 4, 4, 4, 4, new int[15]));
        assertFalse(NativeDiagnosticPixels.copy(ByteBuffer.allocate(64), 4, 4, 16,
                0, 0, 4, 4, 4, 4, output));
        for (int value : output) assertEquals(0x13572468, value);
    }

    @Test public void heapAndClampedCropsRetainFallbackBehavior() {
        ByteBuffer source = ByteBuffer.allocate(9 * 7 * 4);
        for (int i = 0; i < source.capacity(); i++) source.put(i, (byte) (i * 17));
        DiagnosticPixels actual = DiagnosticPixels.copy(source, 9, 7, 36, -4, -1, 20, 10, 5);
        PixelData expected = javaOracle(source, 9, 7, 36, 0, 0, 9, 7, 5);
        assertArrayEquals(expected.argb, actual.argb);
        assertNull(DiagnosticPixels.copy(source, 9, 7, 36, 5, 5, 2, 2, 5));
    }

    @Test public void syntheticBenchmarkReportsPairedEndToEndCopyTimes() {
        assertTrue(NativeDiagnosticPixels.isAvailable());
        int width = 2400, height = 1080, stride = 9664;
        ByteBuffer source = ByteBuffer.allocateDirect(stride * height);
        for (int i = 0; i < source.capacity(); i++) source.put(i, (byte) (i * 31));
        for (int edge : new int[]{480, 960}) {
            PixelData expected = javaOracle(source, width, height, stride, 0, 0, width, height, edge);
            int[] nativePreflight = new int[expected.argb.length];
            assertTrue("Benchmark geometry must use JNI successfully",
                    NativeDiagnosticPixels.copy(source, width, height, stride, 0, 0,
                            width, height, expected.width, expected.height, nativePreflight));
            assertArrayEquals(expected.argb, nativePreflight);
            assertArrayEquals(expected.argb,
                    DiagnosticPixels.copy(source, width, height, stride, 0, 0, width, height, edge).argb);
            for (int warm = 0; warm < 8; warm++) {
                javaOracle(source, width, height, stride, 0, 0, width, height, edge);
                DiagnosticPixels.copy(source, width, height, stride, 0, 0, width, height, edge);
            }
            long[] javaNs = new long[31], nativeNs = new long[31];
            for (int sample = 0; sample < javaNs.length; sample++) {
                // Alternate order so one route does not always follow the other.
                for (int turn = 0; turn < 2; turn++) {
                    boolean nativeTurn = (sample + turn) % 2 == 0;
                    long start = System.nanoTime();
                    int[] pixels = nativeTurn
                            ? DiagnosticPixels.copy(source, width, height, stride, 0, 0, width, height, edge).argb
                            : javaOracle(source, width, height, stride, 0, 0, width, height, edge).argb;
                    long elapsed = System.nanoTime() - start;
                    assertEquals(expected.argb.length, pixels.length);
                    if (nativeTurn) nativeNs[sample] = elapsed; else javaNs[sample] = elapsed;
                }
            }
            Arrays.sort(javaNs);
            Arrays.sort(nativeNs);
            Bundle result = new Bundle();
            result.putString("stream", "\nDIAGNOSTIC_PIXELS_BENCHMARK edge=" + edge
                    + " samples=31 javaP50Micros=" + javaNs[15] / 1000.0
                    + " nativeP50Micros=" + nativeNs[15] / 1000.0
                    + " javaP95Micros=" + javaNs[29] / 1000.0
                    + " nativeP95Micros=" + nativeNs[29] / 1000.0
                    + " synthetic=true\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
        }
    }

    // Oracle is the 0.3.6 Java implementation. It allocates its own output like production.
    private static PixelData javaOracle(ByteBuffer rgba, int width, int height, int rowStride,
                                              int x0, int y0, int x1, int y1, int maxEdge) {
        int cropWidth = x1 - x0, cropHeight = y1 - y0;
        double scale = Math.min(1.0, (double) Math.min(maxEdge, 1280) / Math.max(cropWidth, cropHeight));
        int outWidth = Math.max(1, (int) Math.round(cropWidth * scale));
        int outHeight = Math.max(1, (int) Math.round(cropHeight * scale));
        int[] argb = new int[outWidth * outHeight];
        ByteBuffer source = rgba.duplicate().order(ByteOrder.BIG_ENDIAN);
        for (int y = 0; y < outHeight; y++) {
            int sy = y0 + (int) ((long) y * cropHeight / outHeight);
            for (int x = 0; x < outWidth; x++) {
                int sx = x0 + (int) ((long) x * cropWidth / outWidth);
                int pixel = source.getInt(sy * rowStride + sx * 4);
                argb[y * outWidth + x] = 0xff000000 | ((pixel >>> 24) << 16)
                        | (((pixel >>> 16) & 255) << 8) | ((pixel >>> 8) & 255);
            }
        }
        return new PixelData(outWidth, outHeight, argb);
    }

    private static final class PixelData {
        final int width, height;
        final int[] argb;
        PixelData(int width, int height, int[] argb) {
            this.width = width;
            this.height = height;
            this.argb = argb;
        }
    }
}
