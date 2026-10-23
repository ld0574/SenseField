package com.openkhub.sensefield;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/** Real JPEG/IO work is counted off-thread without losing its output or budget. */
@RunWith(AndroidJUnit4.class)
public final class DiagnosticWorkInstrumentedTest {
    @Test public void imageJobCostIsReportedAfterActualJpegCompletes() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertFalse("Run with capture stopped", CaptureService.isRunning());
        SharedPreferences preferences = GameProfile.settings(context);
        boolean existed = preferences.contains(DiagnosticRecorder.PREF_IMAGES);
        boolean prior = preferences.getBoolean(DiagnosticRecorder.PREF_IMAGES, true);
        preferences.edit().putBoolean(DiagnosticRecorder.PREF_IMAGES, true).commit();
        long now = SystemClock.elapsedRealtime();
        DiagnosticRecorder recorder = DiagnosticRecorder.start(context,
                UUID.randomUUID().toString(), now);
        try {
            DiagnosticRecorder.IO.submit(() -> {}).get(10, TimeUnit.SECONDS);
            assertEquals("Diagnostic archive setup must succeed", "", recorder.failure);
            long[] before = recorder.imageWorkStats();
            int width = 128, height = 64;
            ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
            for (int pixel = 0; pixel < width * height; pixel++)
                rgba.put((byte) 220).put((byte) 130).put((byte) 40).put((byte) 255);
            rgba.rewind();
            recorder.frame(NativeFrameResult.empty(), DiagnosticSnapshot.parse(null),
                    rgba, width, height, width * 4, now, now, 500);
            DiagnosticRecorder.IO.submit(() -> {}).get(10, TimeUnit.SECONDS);
            assertEquals("JPEG recording must succeed", "", recorder.failure);
            long[] after = recorder.imageWorkStats();
            assertEquals(1, after[0]);
            assertTrue("The real image job completed", after[1] > before[1]);
            assertTrue("Encoding/writes have measured wall time", after[2] > before[2]);
            assertTrue("Encoding/writes consumed this IO thread's CPU", after[3] > before[3]);
            assertEquals("Completed image jobs release the shared frame budget", 0, after[4]);
            assertEquals("Completed image jobs release their raw byte budget", 0, after[5]);
            File image = new File(recorder.directory, "images/screen-1-" + now + ".jpg");
            Bitmap decoded = BitmapFactory.decodeFile(image.getAbsolutePath());
            assertNotNull("Telemetry must preserve a readable diagnostic image", decoded);
            decoded.recycle();
        } finally {
            recorder.finish("heat_work_test_complete");
            DiagnosticRecorder.IO.submit(() -> {}).get(10, TimeUnit.SECONDS);
            if (existed) preferences.edit().putBoolean(DiagnosticRecorder.PREF_IMAGES, prior).commit();
            else preferences.edit().remove(DiagnosticRecorder.PREF_IMAGES).commit();
        }
    }
}
