package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.Random;
import org.junit.Test;

/** Independent line-read oracle for the batched current-pixel numerical path. */
public final class Match3GridProfileInstrumentedTest {
    private static double[] lineProfile(Bitmap frame, int[] box, boolean vertical, boolean edges) {
        int length = vertical ? box[3] - box[1] : box[2] - box[0];
        int cross = vertical ? box[2] - box[0] : box[3] - box[1];
        int stride = edges ? 1 : Math.max(1, length / 240);
        double[] result = new double[length / stride];
        int[] line = new int[length];
        for (int at = 0; at < cross; at += 2) {
            if (vertical) frame.getPixels(line, 0, 1, box[0] + at, box[1], 1, length);
            else frame.getPixels(line, 0, length, box[0], box[1] + at, length, 1);
            for (int i = 0; i < result.length; i++) {
                int pixel = line[i * stride];
                if (edges) {
                    int next = line[Math.min(length - 1, i * stride + stride)];
                    result[i] += (Math.abs(Color.red(pixel) - Color.red(next))
                            + Math.abs(Color.green(pixel) - Color.green(next))
                            + Math.abs(Color.blue(pixel) - Color.blue(next))) / 765.0;
                } else result[i] += Math.max(Color.red(pixel), Math.max(Color.green(pixel), Color.blue(pixel))) / 255.0;
            }
        }
        return result;
    }

    private static void exact(Bitmap frame, int[] box) {
        for (boolean vertical : new boolean[]{false, true}) for (boolean edges : new boolean[]{false, true}) {
            double[] expected = lineProfile(frame, box, vertical, edges);
            double[] actual = Match3Sampler.gridProfile(frame, box, vertical, edges);
            assertEquals(expected.length, actual.length);
            for (int i = 0; i < expected.length; i++)
                assertEquals("profile " + i + " vertical=" + vertical + " edges=" + edges,
                        Double.doubleToLongBits(expected[i]), Double.doubleToLongBits(actual[i]));
        }
    }

    @Test public void batchedProfilesPreserveEveryDoubleBitAcrossBandsStridesAndAlpha() {
        assertTrue("installed production native arithmetic", Match3NativeMath.AVAILABLE);
        Random random = new Random(0x4b19);
        // Odd dimensions cross band boundaries; 239/240/479/480 exercise
        // stride changes. Include both alpha modes of canonical Bitmap reads.
        for (int[] size : new int[][]{{61, 63}, {239, 241}, {479, 483}, {721, 729}, {1220, 1221}}) {
            Bitmap frame = Bitmap.createBitmap(size[0] + 8, size[1] + 10, Bitmap.Config.ARGB_8888);
            try {
                int[] pixels = new int[frame.getWidth() * frame.getHeight()];
                for (int i = 0; i < pixels.length; i++) pixels[i] = random.nextInt();
                frame.setPixels(pixels, 0, frame.getWidth(), 0, 0, frame.getWidth(), frame.getHeight());
                exact(frame, new int[]{3, 5, 3 + size[0], 5 + size[1]});
                frame.setPremultiplied(false);
                exact(frame, new int[]{1, 2, 1 + size[0], 2 + size[1]});
            } finally { frame.recycle(); }
        }
    }

    @Test public void workspaceDoesNotReusePixelsOfAMutatedBitmapOrAnotherWorker() throws Exception {
        Bitmap frame = Bitmap.createBitmap(483, 721, Bitmap.Config.ARGB_8888);
        int[] box = {0, 0, 483, 721};
        try {
            frame.eraseColor(0xff203050); exact(frame, box);
            frame.eraseColor(0xffffeeee); exact(frame, box);
            frame.eraseColor(0xff00ccee); exact(frame, box);
            java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                java.util.concurrent.Future<?> first = workers.submit(() -> exact(frame, new int[]{0, 0, 479, 719}));
                java.util.concurrent.Future<?> second = workers.submit(() -> exact(frame, new int[]{1, 1, 480, 721}));
                first.get(); second.get();
            } finally { workers.shutdownNow(); }
        } finally { frame.recycle(); }
    }

    @Test public void nativeProfileRejectsMismatchedArrayExtents() {
        for (int[] dimensions : new int[][]{{0, 1, 1}, {1, 0, 1}, {1, 1, 0}, {3, 3, 1}, {1, 1, 1}}) {
            try {
                Match3NativeMath.accumulateGridProfile(new int[1], dimensions[0], dimensions[1], false, false,
                        dimensions[2], new double[0]);
                fail("Invalid profile dimensions must be rejected");
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void nativeOccupancyMatchesTheBoardPredicateForEveryRgbColor() {
        int[] pixels = new int[65536]; boolean[] pooled = new boolean[pixels.length];
        for (int red = 0; red < 256; red++) {
            for (int green = 0; green < 256; green++) for (int blue = 0; blue < 256; blue++)
                pixels[green * 256 + blue] = Color.rgb(red, green, blue);
            Match3NativeMath.boardOccupancy(pixels, 256, 256, 1, pooled);
            for (int i = 0; i < pixels.length; i++) if (pooled[i] != Match3Sampler.boardBackground(pixels[i]))
                fail("Board predicate differs at " + Integer.toHexString(pixels[i]));
        }
    }

    @Test public void occupancyFindsThinEdgesAndOverwritesPreviousSupport() {
        for (int step : new int[]{2, 3, 7}) {
            int width = 5 * step + 1, height = 3 * step;
            int[] pixels = new int[width * height]; boolean[] pooled = new boolean[15];
            for (int y = 0; y < step; y++) for (int x = 0; x < step; x++) {
                java.util.Arrays.fill(pixels, 0xffffeeee);
                for (int row = 0; row < 3; row++) for (int col = 0; col < 5; col++)
                    pixels[(row * step + y) * width + col * step + x] = 0xff203050;
                Match3NativeMath.boardOccupancy(pixels, width, height, step, pooled);
                for (boolean supported : pooled) assertTrue("Single edge pixel must survive pooling", supported);
                java.util.Arrays.fill(pixels, 0xffffeeee);
                // The ignored remainder column cannot supply a coarse cell.
                for (int row = 0; row < height; row++) pixels[row * width + width - 1] = 0xff203050;
                Match3NativeMath.boardOccupancy(pixels, width, height, step, pooled);
                for (boolean supported : pooled) assertFalse("No old support or remainder pixel", supported);
            }
        }
    }
}
