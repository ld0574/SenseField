package com.openkhub.sensefield;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Optional, paired precision regression on unchanged diagnostic minimap pixels. */
@RunWith(AndroidJUnit4.class)
public final class DetectorPrecisionInstrumentedTest {
    @Test public void halfStoragePreservesDetectionsAndDirections() throws Exception {
        Assume.assumeTrue("Run explicitly with -e detectorPrecisionFixture true and the fixture assets",
                "true".equals(InstrumentationRegistry.getArguments().getString("detectorPrecisionFixture")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context fixture = InstrumentationRegistry.getInstrumentation().getContext();
        JSONObject manifest;
        try (InputStream input = fixture.getAssets().open("capture-scale/manifest.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = input.read(chunk)) != -1) bytes.write(chunk, 0, count);
            manifest = new JSONObject(bytes.toString("UTF-8"));
        }
        int width = manifest.getInt("width"), height = manifest.getInt("height");
        boolean halfArithmetic = "true".equals(InstrumentationRegistry.getArguments().getString("fp16Arithmetic"));
        boolean pooling = "true".equals(InstrumentationRegistry.getArguments().getString("pooling"));
        GameProfile p = GameProfile.load(context);
        long session = create(context, p);
        assertTrue(session != 0);
        NativeBridge.nativeSetDetectorCache(session, false);
        Bitmap source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(source);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
        List<Long> originalNs = new ArrayList<>(), candidateNs = new ArrayList<>();
        JSONArray rows = manifest.getJSONArray("images");
        int maxBoxDelta = 0, maxConfidenceDelta = 0, nonempty = 0;
        List<String> differences = new ArrayList<>();
        try {
            for (int index = 0; index < rows.length(); index++) {
                JSONObject row = rows.getJSONObject(index);
                Bitmap map;
                try (InputStream input = fixture.getAssets().open("capture-scale/" + row.getString("file"))) {
                    map = BitmapFactory.decodeStream(input);
                }
                assertNotNull(map);
                source.eraseColor(android.graphics.Color.BLACK);
                JSONArray crop = row.getJSONArray("crop");
                canvas.drawBitmap(map, null, new Rect(crop.getInt(0), crop.getInt(1),
                        crop.getInt(0) + crop.getInt(2), crop.getInt(1) + crop.getInt(3)), paint);
                rgba(source, pixels);
                map.recycle();
                // Alternate order to avoid making one route always the cold run.
                long[][] original = null, scaled = null;
                for (int order = 0; order < 2; order++) {
                    boolean smaller = (index + order) % 2 == 0;
                    assertTrue("Runtime must support the requested storage precision",
                            NativeBridge.nativeReloadDetectorPrecision(session, context.getAssets(),
                                    smaller && !pooling, halfArithmetic, smaller && pooling));
                    NativeBridge.nativeReset(session);
                    process(session, pixels, width, height); // Warm this shape before measuring.
                    long started = System.nanoTime();
                    process(session, pixels, width, height);
                    long elapsed = System.nanoTime() - started;
                    long[][] observations = observations(session);
                    if (smaller) { scaled = observations; candidateNs.add(elapsed); }
                    else { original = observations; originalNs.add(elapsed); }
                }
                if (original.length > 0) nonempty++;
                if (original.length != scaled.length) {
                    differences.add(index + ": count " + original.length + " -> " + scaled.length);
                    continue;
                }
                for (int n = 0; n < original.length; n++) {
                    if (original[n][0] != scaled[n][0] || original[n][1] != scaled[n][1]) {
                        differences.add(index + ": class/direction changed at observation " + n);
                    }
                    for (int field = 2; field < 6; field++) {
                        maxBoxDelta = Math.max(maxBoxDelta, (int) Math.abs(original[n][field] - scaled[n][field]));
                    }
                    maxConfidenceDelta = Math.max(maxConfidenceDelta,
                            (int) Math.abs(original[n][6] - scaled[n][6]));
                }
            }
            assertTrue("Compare real nonempty detections", nonempty >= 8);
            long[] original = originalNs.stream().mapToLong(Long::longValue).sorted().toArray();
            long[] scaled = candidateNs.stream().mapToLong(Long::longValue).sorted().toArray();
            Bundle result = new Bundle();
            result.putString("stream", "\nDETECTOR_PRECISION_DIAGNOSTIC samples=" + rows.length()
                    + " nonempty=" + nonempty + " original=" + width + "x" + height
                    + " modelEdge=" + p.yoloxInputSize + " pixelsUnchanged=true halfStorage=" + !pooling
                    + " fp16Arithmetic=" + halfArithmetic + " pooling=" + pooling + " differences=" + differences
                    + " maxBoxDeltaPpm=" + maxBoxDelta + " maxConfidenceDeltaMilli=" + maxConfidenceDelta
                    + " originalP50Micros=" + percentile(original, 0.5) / 1000.0
                    + " originalP95Micros=" + percentile(original, 0.95) / 1000.0
                    + " halfStorageP50Micros=" + percentile(scaled, 0.5) / 1000.0
                    + " halfStorageP95Micros=" + percentile(scaled, 0.95) / 1000.0
                    + " source=diagnostic_jpeg synthetic_background=true compositorProof=false thermalProof=false\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, result);
            assertTrue("Paired detection counts/classes/directions must agree: " + differences, differences.isEmpty());
            assertTrue("Bounding-box drift must remain <= 0.2% of screen", maxBoxDelta <= 2000);
            assertTrue("Confidence drift must remain <= 0.05", maxConfidenceDelta <= 50);
            if (pooling) {
                assertEquals("FP32 allocation reuse must preserve coordinates exactly", 0, maxBoxDelta);
                assertEquals("FP32 allocation reuse must preserve confidence exactly", 0, maxConfidenceDelta);
            }
        } finally { NativeBridge.nativeDestroy(session); source.recycle(); }
    }

    private static void rgba(Bitmap bitmap, ByteBuffer result) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        result.clear();
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1);
            for (int value : row) result.put((byte) (value >> 16)).put((byte) (value >> 8))
                    .put((byte) value).put((byte) 255);
        }
        result.rewind();
    }

    private static void process(long session, ByteBuffer pixels, int width, int height) {
        long now = SystemClock.elapsedRealtime();
        int[] result = NativeBridge.nativeProcess(session, pixels, width, height, width * 4, now, now);
        assertNotNull(result);
        assertTrue(result.length >= 24 && result[7] >= 0);
    }

    private static long[][] observations(long session) {
        long[] raw = NativeBridge.nativeReadDiagnosticSnapshot(session);
        assertNotNull(raw);
        long[][] result = new long[(int) raw[1]][7];
        for (int n = 0; n < result.length; n++) System.arraycopy(raw, 2 + n * 8, result[n], 0, 7);
        Arrays.sort(result, Comparator.<long[]>comparingLong(row -> row[0])
                .thenComparingLong(row -> row[2] + row[4]).thenComparingLong(row -> row[3] + row[5]));
        return result;
    }

    private static long percentile(long[] sorted, double fraction) {
        return sorted[Math.max(0, (int) Math.ceil(sorted.length * fraction) - 1)];
    }

    private static long create(Context context, GameProfile p) {
        return NativeBridge.nativeCreate(context.getAssets(), p.rois, p.flags, p.tuning,
                p.eventInts, p.minConfidence, null, 0, 0, null, 0, 0, p.minimapYolox, p.yoloxInputSize,
                p.yoloxParamAsset, p.yoloxBinAsset, p.yoloxConfidence, p.yoloxNms,
                p.yoloxClassKinds, p.yoloxClassThresholds, false, p.minimapLocatorFloats,
                p.minimapLocatorInts, p.minimapLocatorDescriptor, false, null, 0, null, null, null, null);
    }
}
