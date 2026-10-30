package com.openkhub.sensefield;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

/**
 * Crescendo lock for one haptic burst: the open slots of a burst rise pulse over pulse instead
 * of repeating one constant, while silence slots, the shared 1..255 window, the timing frame and
 * the "no amplitude control" and "system strength" escape hatches stay exactly as they were.
 *
 * <p>JVM only. Whether a wrist actually feels the swell is a real-device question and is not
 * claimed here.
 */
public final class HapticCrescendoTest {

    /** 1 lock · two or more open slots rise strictly, at the function level and through create(). */
    @Test public void burstAmplitudesRiseStrictlyPulseOverPulse() {
        assertArrayEquals(new int[]{0, 48, 0, 96}, HapticPolicy.rampAmplitudes(96, 4));
        assertArrayEquals(new int[]{0, 96, 0, 128, 0, 160, 0, 192},
                HapticPolicy.rampAmplitudes(192, 8));

        assertStrictlyRisingOnOpenSlots(HapticPolicy.rampAmplitudes(96, 4));
        assertStrictlyRisingOnOpenSlots(HapticPolicy.rampAmplitudes(96, 6));
        assertStrictlyRisingOnOpenSlots(HapticPolicy.rampAmplitudes(231, 8));
        assertStrictlyRisingOnOpenSlots(HapticPolicy.rampAmplitudes(1, 6));

        HapticPolicy.Pattern twoPulses = HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, true, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_LIGHT);
        assertStrictlyRisingOnOpenSlots(twoPulses.amplitudes);
        assertTrue("the later pulse must be the louder one",
                twoPulses.amplitudes[3] > twoPulses.amplitudes[1]);
    }

    /** 2 lock · a single-pulse burst keeps the pre-ramp value verbatim, it is never boosted. */
    @Test public void singlePulseBurstIsByteIdenticalToTheFlatFill() {
        for (int selected : new int[]{1, 48, 96, 192, 231, 255}) {
            assertArrayEquals("a lone pulse must stay at the selected amplitude",
                    new int[]{0, selected}, HapticPolicy.rampAmplitudes(selected, 2));
            assertArrayEquals(new int[]{0, selected, 0}, HapticPolicy.rampAmplitudes(selected, 3));
        }

        // One open slot through create(): direction 3 is the {0,35} single pulse.
        HapticPolicy.Pattern lone = HapticPolicy.create(request(
                        CueRequest.Category.DANGER, 3, Float.NaN, 0f),
                false, true, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_LIGHT);
        assertArrayEquals(new long[]{0, 35}, lone.timingsMs);
        assertArrayEquals("a single pulse keeps the flat 96", new int[]{0, 96}, lone.amplitudes);
    }

    /** 3 lock · silence slots stay 0 for every reachable length. */
    @Test public void silenceSlotsAlwaysStayZero() {
        for (int length = 0; length <= 12; length++) {
            int[] amplitudes = HapticPolicy.rampAmplitudes(192, length);
            for (int index = 0; index < length; index += 2) {
                assertEquals("odd length " + length + " silence slot " + index,
                        0, amplitudes[index]);
            }
        }
    }

    /** 4 lock · every written slot stays inside the existing clamp window, including saturation. */
    @Test public void everyOpenSlotStaysInsideTheClampedWindow() {
        for (int selected : new int[]{1, 2, 96, 192, 231, 255, 256, 300, 1000}) {
            for (int length : new int[]{2, 3, 4, 6, 8, 12}) {
                int[] amplitudes = HapticPolicy.rampAmplitudes(selected, length);
                for (int index = 1; index < length; index += 2) {
                    assertTrue("slot " + index + " of " + Arrays.toString(amplitudes)
                                    + " must be at least 1", amplitudes[index] >= 1);
                    assertTrue("slot " + index + " of " + Arrays.toString(amplitudes)
                                    + " must not pass the 255 ceiling", amplitudes[index] <= 255);
                }
            }
        }

        // Saturating case: the loudest slot lands exactly on the ceiling, nothing above it.
        assertArrayEquals(new int[]{0, 127, 0, 255}, HapticPolicy.rampAmplitudes(255, 4));
        assertArrayEquals("an over-range base goes through the same clamp",
                new int[]{0, 127, 0, 255}, HapticPolicy.rampAmplitudes(300, 4));
        HapticPolicy.Pattern loudest = HapticPolicy.create(nearRequest(0f, 2f),
                true, true, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_SYSTEM);
        assertEquals(255, loudest.amplitudes[3]);
    }

    /** 5 lock · without amplitude control the array stays null and the timings are untouched. */
    @Test public void withoutAmplitudeControlStillNoArrayAndVerbatimTimings() {
        HapticPolicy.Pattern lighter = HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, false, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_LIGHT);
        HapticPolicy.Pattern stronger = HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, false, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_STRONG);

        assertNull(lighter.amplitudes);
        assertNull(stronger.amplitudes);
        assertArrayEquals("the pulse-width path keeps its pre-change frame",
                new long[]{0, 65, 140, 65}, lighter.timingsMs);
        assertArrayEquals(new long[]{0, 120, 140, 120}, stronger.timingsMs);
    }

    /** 6 lock · system strength on a device with amplitude control still yields no array. */
    @Test public void systemStrengthStillProducesNoAmplitudeArray() {
        HapticPolicy.Pattern system = HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, true, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_SYSTEM);
        assertNull(system.amplitudes);
        assertArrayEquals(new long[]{0, 100, 140, 100}, system.timingsMs);

        for (String mode : new String[]{HapticPolicy.MODE_ORIGINAL, HapticPolicy.MODE_SHORT,
                HapticPolicy.MODE_DIRECTIONAL}) {
            assertNull("system strength stays platform default in " + mode,
                    HapticPolicy.create(request(CueRequest.Category.DANGER, 2, Float.NaN, 0f),
                            false, true, mode, HapticPolicy.STRENGTH_SYSTEM).amplitudes);
        }
    }

    /** 7 lock · output length equals timings length for 0, 1, odd and even lengths alike. */
    @Test public void arrayLengthAlwaysMatchesTimingsLength() {
        for (int length = 0; length <= 13; length++) {
            assertEquals(length, HapticPolicy.rampAmplitudes(96, length).length);
        }
        assertArrayEquals(new int[]{}, HapticPolicy.rampAmplitudes(96, 0));
        assertArrayEquals("a length-1 frame has no open slot to ramp",
                new int[]{0}, HapticPolicy.rampAmplitudes(96, 1));
        assertArrayEquals(new int[]{0, 48, 0, 72, 0, 96, 0}, HapticPolicy.rampAmplitudes(96, 7));
    }

    /** 8 lock · the audit line still narrates the array it was handed, ramp included. */
    @Test public void auditLineNarratesTheRampedArray() {
        CueRequest light = nearRequest(Float.NaN, 0f);
        HapticPolicy.Pattern pattern = HapticPolicy.create(light, false, true,
                HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_LIGHT);
        String audit = HapticPolicy.audit(light, pattern, true, false,
                HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_LIGHT);

        assertTrue(audit, audit.contains("timingsMs=[0,100,140,100]"));
        assertTrue(audit, audit.contains("amplitudes=" + Arrays.toString(pattern.amplitudes)
                .replace(" ", "")));
        assertAuditAgreesWithArray(light, pattern, true, false,
                HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_LIGHT);

        // Same for the distance-driven burst and for the platform-default escape hatch.
        assertAuditAgreesWithArray(nearRequest(0.05f, 2f), HapticPolicy.create(
                nearRequest(0.05f, 2f), true, true, HapticPolicy.MODE_SHORT,
                HapticPolicy.STRENGTH_SYSTEM), true, true, HapticPolicy.MODE_SHORT,
                HapticPolicy.STRENGTH_SYSTEM);
        assertAuditAgreesWithArray(light, pattern, false, false,
                HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_SYSTEM);
        assertTrue(HapticPolicy.audit(light, HapticPolicy.create(light, false, false,
                        HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_SYSTEM),
                        false, false, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_SYSTEM)
                .contains("amplitudes=system"));
    }

    /** The ramp must not leak into the timing frame in either direction. */
    @Test public void crescendoNeverChangesTheTimingFrame() {
        HapticPolicy.Pattern lighter = HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, true, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_LIGHT);
        HapticPolicy.Pattern stronger = HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, true, HapticPolicy.MODE_SHORT, HapticPolicy.STRENGTH_STRONG);

        assertArrayEquals(new long[]{0, 100, 140, 100}, lighter.timingsMs);
        assertArrayEquals(new long[]{0, 35, 80, 35}, stronger.timingsMs);
    }

    /** The last pulse of a burst keeps the selected strength, only the lead-ins swell. */
    @Test public void loudestPulseKeepsTheSelectedAmplitude() {
        HapticPolicy.Pattern lighter = HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, true, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_LIGHT);
        HapticPolicy.Pattern stronger = HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, true, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_STRONG);

        assertEquals(96, lighter.amplitudes[3]);
        assertEquals(192, stronger.amplitudes[3]);
        assertTrue("a light burst still ends quieter than a strong one",
                lighter.amplitudes[3] < stronger.amplitudes[3]);
    }

    private static void assertAuditAgreesWithArray(CueRequest request,
                                                   HapticPolicy.Pattern pattern,
                                                   boolean amplitudeControl,
                                                   boolean distanceEnabled, String mode,
                                                   String strength) {
        String audit = HapticPolicy.audit(request, pattern, amplitudeControl, distanceEnabled,
                mode, strength);
        String expected = "amplitudes=" + (pattern.amplitudes == null ? "system"
                : Arrays.toString(pattern.amplitudes).replace(" ", ""));
        assertTrue(audit + " / expected " + expected, audit.contains(expected));
        assertTrue(audit, audit.contains("timingsMs="
                + Arrays.toString(pattern.timingsMs).replace(" ", "")));
    }

    private static void assertStrictlyRisingOnOpenSlots(int[] amplitudes) {
        int previous = -1;
        int seen = 0;
        for (int index = 1; index < amplitudes.length; index += 2) {
            if (seen > 0) {
                assertTrue("pulse " + seen + " must be louder than the one before in "
                        + Arrays.toString(amplitudes), amplitudes[index] > previous);
            }
            previous = amplitudes[index];
            seen++;
        }
        assertTrue("a two-pulse burst has two open slots", seen >= 1);
    }

    private static CueRequest nearRequest(float distance, float urgency) {
        return request(CueRequest.Category.NEAR_ZONE, 0, distance, urgency);
    }

    private static CueRequest request(CueRequest.Category category, int hapticCode,
                                      float distance, float urgency) {
        return new CueRequest("test", "test", "test", "TEST", category,
                80, 0, 1000, CueRequest.CHANNEL_HAPTIC, 0, 0, hapticCode,
                null, Float.NaN, distance, urgency, -1, () -> true);
    }
}
