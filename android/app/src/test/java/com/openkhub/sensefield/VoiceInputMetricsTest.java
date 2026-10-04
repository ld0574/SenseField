package com.openkhub.sensefield;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceInputMetricsTest {
    @Test public void reportsSilentInputWithoutRetainingSamples() {
        VoiceInputMetrics metrics = new VoiceInputMetrics();
        metrics.accept(new short[160], false);
        assertEquals(1, metrics.frames);
        assertEquals(0, metrics.speechFrames);
        assertEquals(0, metrics.rms());
        assertEquals(0, metrics.peak);
    }

    @Test public void includesClippedNegativePeakAndResetsBetweenUtterances() {
        VoiceInputMetrics metrics = new VoiceInputMetrics();
        metrics.accept(new short[]{-32768, 32767, 0, 0}, true);
        assertEquals(32768, metrics.peak);
        assertEquals(2, metrics.clippedSamples);
        assertEquals(23170, metrics.rms());
        assertEquals(1, metrics.speechFrames);
        metrics.reset();
        assertEquals(0, metrics.frames);
        assertEquals(0, metrics.rms());
        assertEquals(0, metrics.peak);
        assertEquals(0, metrics.clippedSamples);
    }
}
