package com.openkhub.sensefield;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises native WebRTC VAD creation and recovery on an Android runtime. */
@RunWith(AndroidJUnit4.class)
public class VoiceSpeechDetectorInstrumentedTest {
    @Test public void silenceRemainsRejectedAfterCaptureDiscontinuityReset() {
        VoiceSpeechDetector detector = new VoiceSpeechDetector();
        try {
            assertTrue("packaged WebRTC VAD must be available", detector.available());
            short[] silence = new short[VoiceSpeechDetector.FRAME_SAMPLES];
            for (int i = 0; i < 20; i++) assertFalse(detector.isSpeech(silence));

            assertTrue("native VAD should be recreated in aggressive mode", detector.reset());
            assertTrue(detector.available());
            for (int i = 0; i < 20; i++) assertFalse(detector.isSpeech(silence));
        } finally {
            detector.close();
        }
    }
}
