package com.openkhub.sensefield;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Synthetic frames only: verifies owned snapshots and real Android JPEG/native AEC plumbing. */
@RunWith(AndroidJUnit4.class)
public class AssistantSnapshotInstrumentedTest {
    private static ByteBuffer synthetic(int width, int height) {
        ByteBuffer rgba = ByteBuffer.allocateDirect(width * height * 4);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            rgba.put((byte) 220).put((byte) 150).put((byte) 70).put((byte) 255);
        }
        rgba.rewind(); return rgba;
    }
    @Test public void copiedFrameOwnsItsPixelsAndMasksAssistantWindow() {
        ByteBuffer rgba = synthetic(160, 90);
        FrameSnapshot frame = FrameSnapshot.copy(rgba, 160, 90, 640, 1280, 7, 100, 1, 3, new Rect(0, 0, 48, 48));
        assertNotNull(frame); assertEquals(0xff000000, frame.pixels.argb[20 * 160 + 20]);
        assertEquals(0xffdc9646, frame.pixels.argb[80 * 160 + 140]);
        rgba.put(0, (byte) 0); rgba.put(1, (byte) 0);
        assertEquals(0xffdc9646, frame.pixels.argb[80 * 160 + 140]);
        byte[] jpeg = frame.jpeg(); assertNotNull(jpeg); assertTrue(jpeg.length <= 512 * 1024);
        Bitmap decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        assertEquals(160, decoded.getWidth()); assertEquals(90, decoded.getHeight()); decoded.recycle();
    }
    @Test public void automaticAndManualCopiesRespectTheirPixelBudgets() {
        ByteBuffer rgba = synthetic(1920, 1080);
        FrameSnapshot automatic = FrameSnapshot.copy(rgba, 1920, 1080, 7680, 960, 1, 1, 1, 1, null);
        FrameSnapshot manual = FrameSnapshot.copy(rgba, 1920, 1080, 7680, 1280, 2, 1, 1, 1, null);
        assertEquals(960, automatic.pixels.width); assertEquals(540, automatic.pixels.height);
        assertEquals(1280, manual.pixels.width); assertEquals(720, manual.pixels.height);
        assertNotNull(automatic.jpeg()); assertNotNull(manual.jpeg());
    }
    @Test public void offscreenOldOverlayBoundsCannotBreakTheFrameWorker() {
        ByteBuffer rgba = synthetic(160, 90);
        FrameSnapshot frame = FrameSnapshot.copy(rgba, 160, 90, 640, 960, 1, 1, 1, 1,
                new Rect(1000, 1000, 1100, 1100));
        assertNotNull(frame); assertEquals(0xffdc9646, frame.pixels.argb[0]);
    }
    @Test public void packagedSpeexCanProcessAndResetWithoutPlaybackReference() {
        assertTrue(VoiceEchoProcessor.isNativeAvailable());
        VoiceEchoProcessor echo = new VoiceEchoProcessor();
        try {
            assertTrue(echo.isAvailable());
            short[] input = new short[160];
            echo.feedRender(input); assertEquals(160, echo.process(input).length);
            echo.reset(); assertEquals(160, echo.process(input).length);
            assertTrue(VoiceEchoProcessor.resampleTo16k(new short[480], 48000).length > 0);
        } finally { echo.close(); }
    }
}
