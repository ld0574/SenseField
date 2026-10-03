package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assume.assumeTrue;

import android.media.AudioDeviceInfo;
import android.os.Build;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Verifies that the emulator's default speaker route cannot enable continuous voice. */
@RunWith(AndroidJUnit4.class)
public final class AssistantAudioRouteProbeInstrumentedTest {
    @Test public void defaultSpeakerRouteIsRejected() {
        assumeTrue("AudioTrack route probe requires Android 10+", Build.VERSION.SDK_INT >= 29);
        AssistantAudioRouteProbe probe = new AssistantAudioRouteProbe(ignored -> { });
        try {
            // Allow the audio service to publish the route chosen immediately after play().
            SystemClock.sleep(250);
            assertEquals("Emulator probe should use its default built-in speaker",
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, probe.routedDeviceType());
            assertFalse("A speaker route must keep continuous voice gated", probe.isHeadphoneRoute());
            assertFalse(VoiceInputSafetyPolicy.verifiedHeadphoneRoute(Build.VERSION.SDK_INT,
                    true, probe.routedDeviceType()));
        } finally {
            probe.close();
        }
    }
}
