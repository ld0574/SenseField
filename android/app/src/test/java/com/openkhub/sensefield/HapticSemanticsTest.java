package com.openkhub.sensefield;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public final class HapticSemanticsTest {
    /** Count of "on" slices: odd indices carry the vibration, even ones are waits. */
    private static int pulseCount(long[] timings) {
        int pulses = 0;
        for (int index = 1; index < timings.length; index += 2) pulses++;
        return pulses;
    }

    private static long longestOn(long[] timings) {
        long longest = 0;
        for (int index = 1; index < timings.length; index += 2) {
            longest = Math.max(longest, timings[index]);
        }
        return longest;
    }

    @Test public void threeSemanticWaveformsArePairwiseDistinct() {
        long[] attention = HapticSemantics.waveform(HapticSemantics.Semantic.ATTENTION).timingsMs;
        long[] danger = HapticSemantics.waveform(HapticSemantics.Semantic.DANGER).timingsMs;
        long[] approach = HapticSemantics.waveform(HapticSemantics.Semantic.APPROACH).timingsMs;

        assertFalse(Arrays.equals(attention, danger));
        assertFalse(Arrays.equals(attention, approach));
        assertFalse(Arrays.equals(danger, approach));
    }

    @Test public void attentionIsTwoShortTapsUnderTwoHundredMillis() {
        HapticSemantics.Waveform waveform =
                HapticSemantics.waveform(HapticSemantics.Semantic.ATTENTION);

        assertEquals(2, pulseCount(waveform.timingsMs));
        assertTrue("attention must stay a quick nudge", waveform.durationMs() <= 200);
        assertArrayEquals(new long[]{0, 45, 55, 45}, waveform.timingsMs);
    }

    @Test public void dangerIsASingleHoldLongerThanAnyAttentionPulse() {
        HapticSemantics.Waveform danger =
                HapticSemantics.waveform(HapticSemantics.Semantic.DANGER);
        HapticSemantics.Waveform attention =
                HapticSemantics.waveform(HapticSemantics.Semantic.ATTENTION);

        assertEquals(1, pulseCount(danger.timingsMs));
        assertTrue("a danger hold must outlast every attention pulse",
                longestOn(danger.timingsMs) > longestOn(attention.timingsMs));
    }

    @Test public void approachSwellsWithStrictlyRisingAmplitudes() {
        HapticSemantics.Waveform waveform =
                HapticSemantics.waveform(HapticSemantics.Semantic.APPROACH);

        assertNotNull("swelling needs amplitudes, not system default", waveform.amplitudes);
        int[] onAmplitudes = new int[waveform.timingsMs.length / 2];
        int seen = 0;
        for (int index = 1; index < waveform.timingsMs.length; index += 2) {
            int amplitude = waveform.amplitudes[index];
            assertTrue("an on-slice amplitude must be a real motor value",
                    amplitude >= 1 && amplitude <= 255);
            onAmplitudes[seen++] = amplitude;
        }
        for (int index = 1; index < seen; index++) {
            assertTrue("amplitudes must rise pulse over pulse",
                    onAmplitudes[index] > onAmplitudes[index - 1]);
        }
    }

    @Test public void unknownAndNullTagsFallBackToTheSameDefaultWaveform() {
        // The only "unknown" a Java enum admits is null; the contract is that it lands on the
        // same neutral shape every time rather than on any of the three real semantics.
        HapticSemantics.Waveform first = HapticSemantics.waveform(null);
        HapticSemantics.Waveform second = HapticSemantics.waveform(null);

        assertNotNull(first.timingsMs);
        assertTrue("a fallback must actually vibrate", first.durationMs() > 0);
        assertArrayEquals("a null tag must be deterministic", first.timingsMs, second.timingsMs);
        assertEquals(first.durationMs(), second.durationMs());

        // It must not silently become one of the real semantics.
        assertFalse("the fallback must not be DANGER's single long hold",
                Arrays.equals(first.timingsMs,
                        HapticSemantics.waveform(HapticSemantics.Semantic.DANGER).timingsMs));
        assertFalse("the fallback must not be APPROACH's swelling pair",
                Arrays.equals(first.timingsMs,
                        HapticSemantics.waveform(HapticSemantics.Semantic.APPROACH).timingsMs));
    }

    @Test public void everyWaveformStaysInsideTheChannelBudget() {
        for (HapticSemantics.Semantic tag : HapticSemantics.Semantic.values()) {
            HapticSemantics.Waveform waveform = HapticSemantics.waveform(tag);
            assertTrue(tag + " exceeds the bounded channel budget",
                    HapticSemantics.withinBudget(waveform));
        }
        assertTrue("the fallback must be bounded too",
                HapticSemantics.withinBudget(HapticSemantics.waveform(null)));
    }

    @Test public void auditNamesTheSemanticAndItsDuration() {
        HapticSemantics.Waveform waveform =
                HapticSemantics.waveform(HapticSemantics.Semantic.APPROACH);

        String line = HapticSemantics.audit(HapticSemantics.Semantic.APPROACH, waveform);

        assertTrue("audit must name the semantic", line.contains("APPROACH"));
        assertTrue("audit must carry the duration", line.contains("durationMs="
                + waveform.durationMs()));
        assertTrue("audit must not be a bare label", line.length() > 20);
    }

    @Test public void auditSurvivesANullTag() {
        String line = HapticSemantics.audit(null, HapticSemantics.waveform(null));

        assertTrue("a null tag must still produce a readable line",
                line.contains("tag=UNKNOWN"));
    }
}
