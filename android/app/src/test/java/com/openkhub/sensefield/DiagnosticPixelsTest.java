package com.openkhub.sensefield;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class DiagnosticPixelsTest {
    @Test public void paddedHeapFramesMatchPreviousPerPixelCopyForCropAndScale() {
        int width = 19;
        int height = 13;
        int rowStride = width * 4 + 9;
        ByteBuffer source = frame(width, height, rowStride, false);
        source.position(11);

        assertMatchesReference(source, width, height, rowStride, 3, 2, 16, 11, 6);
        assertMatchesReference(source, width, height, rowStride, -5, 3, 99, 40, 12);
        assertMatchesReference(source, width, height, rowStride, 0, 0, width, height, 1280);
    }

    @Test public void directPaddedFramesUsePixelStrideFourAndPreserveBufferState() {
        int width = 23;
        int height = 9;
        int rowStride = width * 4 + 13;
        ByteBuffer source = frame(width, height, rowStride, true);
        source.order(ByteOrder.LITTLE_ENDIAN);
        source.position(17);

        assertMatchesReference(source, width, height, rowStride, 2, 1, 22, 9, 8);
    }

    @Test public void exactStrideReadOnlyLittleEndianBufferMatchesReference() {
        int width = 11;
        int height = 7;
        int rowStride = width * 4;
        ByteBuffer writable = frame(width, height, rowStride, true);
        ByteBuffer readOnly = writable.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        readOnly.position(4);

        assertMatchesReference(readOnly, width, height, rowStride, 1, 0, 10, 7, 5);
    }

    @Test public void largePaddingAndLimitAtLastPixelRemainInBounds() {
        int width = 5;
        int height = 4;
        int rowStride = width * 4 + 4096;
        ByteBuffer writable = frame(width, height, rowStride, true);
        writable.limit((height - 1) * rowStride + width * 4);
        ByteBuffer source = writable.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
        source.position(19);

        assertMatchesReference(source, width, height, rowStride, -3, 1, 9, 4, 2);
    }

    @Test public void invalidGeometryAndTruncatedRowsReturnNullWithoutChangingPosition() {
        ByteBuffer source = frame(8, 5, 8 * 4 + 7, true);
        source.position(6);
        int position = source.position();

        assertNull(DiagnosticPixels.copy(null, 8, 5, 32, 0, 0, 8, 5, 4));
        assertNull(DiagnosticPixels.copy(source, 0, 5, 32, 0, 0, 8, 5, 4));
        assertNull(DiagnosticPixels.copy(source, 8, 5, 31, 0, 0, 8, 5, 4));
        assertNull(DiagnosticPixels.copy(source, 8, 5, Integer.MAX_VALUE,
                0, 0, 8, 5, 4));
        assertNull(DiagnosticPixels.copy(source, 8, Integer.MAX_VALUE, 40,
                0, 0, 8, 5, 4));
        assertNull(DiagnosticPixels.copy(source, 8, 5, 39, 0, 0, 8, 5, 0));
        assertNull(DiagnosticPixels.copy(source, 8, 5, 39, 5, 2, 2, 4, 4));
        assertNull(DiagnosticPixels.copy(source, Integer.MAX_VALUE, 1, 39,
                0, 0, 8, 1, 4));

        assertEquals(position, source.position());
    }

    @Test public void bufferLimitIsHonoredWithoutDependingOnCurrentPosition() {
        ByteBuffer source = frame(10, 6, 10 * 4 + 5, false);
        source.position(40);
        source.limit(source.limit() - 6);
        int position = source.position();
        int limit = source.limit();

        assertNull(DiagnosticPixels.copy(source, 10, 6, 45, 0, 0, 10, 6, 5));
        assertEquals(position, source.position());
        assertEquals(limit, source.limit());
    }

    private static void assertMatchesReference(ByteBuffer source, int width, int height,
                                               int rowStride, int x0, int y0, int x1, int y1,
                                               int maxEdge) {
        int position = source.position();
        int limit = source.limit();
        ReferencePixels expected = referenceCopy(source, width, height, rowStride,
                x0, y0, x1, y1, maxEdge);
        DiagnosticPixels actual = DiagnosticPixels.copy(source, width, height, rowStride,
                x0, y0, x1, y1, maxEdge);

        assertNotNull(actual);
        assertEquals(expected.width, actual.width);
        assertEquals(expected.height, actual.height);
        assertArrayEquals(expected.argb, actual.argb);
        assertEquals(position, source.position());
        assertEquals(limit, source.limit());
    }

    /** The pre-optimization absolute-get implementation, kept as a test oracle. */
    private static ReferencePixels referenceCopy(ByteBuffer rgba, int width, int height,
                                                 int rowStride, int x0, int y0, int x1, int y1,
                                                 int maxEdge) {
        if (rgba == null || width <= 0 || height <= 0 || maxEdge <= 0
                || rowStride < (long) width * 4
                || (long) (height - 1) * rowStride + (long) width * 4 > rgba.limit())
            return null;
        x0 = Math.max(0, Math.min(width, x0));
        y0 = Math.max(0, Math.min(height, y0));
        x1 = Math.max(0, Math.min(width, x1));
        y1 = Math.max(0, Math.min(height, y1));
        int cropWidth = x1 - x0;
        int cropHeight = y1 - y0;
        if (cropWidth <= 0 || cropHeight <= 0) return null;
        double scale = Math.min(1.0,
                (double) Math.min(maxEdge, 1280) / Math.max(cropWidth, cropHeight));
        int outputWidth = Math.max(1, (int) Math.round(cropWidth * scale));
        int outputHeight = Math.max(1, (int) Math.round(cropHeight * scale));
        int[] pixels = new int[outputWidth * outputHeight];
        for (int y = 0; y < outputHeight; y++) {
            int sourceY = y0 + (int) ((long) y * cropHeight / outputHeight);
            for (int x = 0; x < outputWidth; x++) {
                int sourceX = x0 + (int) ((long) x * cropWidth / outputWidth);
                int at = sourceY * rowStride + sourceX * 4;
                pixels[y * outputWidth + x] = 0xff000000 | ((rgba.get(at) & 255) << 16)
                        | ((rgba.get(at + 1) & 255) << 8) | (rgba.get(at + 2) & 255);
            }
        }
        return new ReferencePixels(outputWidth, outputHeight, pixels);
    }

    private static ByteBuffer frame(int width, int height, int rowStride, boolean direct) {
        ByteBuffer buffer = direct
                ? ByteBuffer.allocateDirect(rowStride * height)
                : ByteBuffer.allocate(rowStride * height);
        for (int i = 0; i < buffer.capacity(); i++) buffer.put(i, (byte) 0x5a);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int at = y * rowStride + x * 4;
                buffer.put(at, (byte) (x * 37 + y * 11 + 5));
                buffer.put(at + 1, (byte) (x * 19 + y * 29 + 7));
                buffer.put(at + 2, (byte) (x * 13 + y * 43 + 9));
                buffer.put(at + 3, (byte) (x * 3 + y * 17 + 11));
            }
        }
        return buffer;
    }

    private static final class ReferencePixels {
        final int width;
        final int height;
        final int[] argb;

        ReferencePixels(int width, int height, int[] argb) {
            this.width = width;
            this.height = height;
            this.argb = argb;
        }
    }
}
