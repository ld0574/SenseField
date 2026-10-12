package com.openkhub.sensefield;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HapticPolicyTest {
    @Test public void defaultNearPatternKeepsEstablishedTimingAndNativeAmplitude() {
        HapticPolicy.Pattern pattern = HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, false, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_SYSTEM);

        assertArrayEquals(new long[]{0, 100, 140, 100}, pattern.timingsMs);
        assertNull(pattern.amplitudes);
        assertEquals(340, pattern.durationMs());
    }

    @Test public void originalModeKeepsExistingDirectionalPattern() {
        HapticPolicy.Pattern pattern = HapticPolicy.create(request(
                        CueRequest.Category.DANGER, 1, Float.NaN, 0f),
                false, false, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_SYSTEM);

        assertArrayEquals(new long[]{0, 35, 45, 80}, pattern.timingsMs);
        assertNull(pattern.amplitudes);
    }

    @Test public void shortNearModeKeepsTwoPulsesAndShortensTheirGap() {
        HapticPolicy.Pattern pattern = HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, true, HapticPolicy.MODE_SHORT, HapticPolicy.STRENGTH_SYSTEM);

        assertArrayEquals(new long[]{0, 35, 80, 35}, pattern.timingsMs);
        assertNull(pattern.amplitudes);
    }

    @Test public void shortDirectionalModePreservesOriginalPulseOrder() {
        HapticPolicy.Pattern pattern = HapticPolicy.create(request(
                        CueRequest.Category.DANGER, 2, Float.NaN, 0f),
                false, false, HapticPolicy.MODE_SHORT, HapticPolicy.STRENGTH_SYSTEM);

        // Direction 2 starts with the long pulse; the short pattern keeps that ordering.
        assertArrayEquals(new long[]{0, 28, 45, 12}, pattern.timingsMs);
        assertNull(pattern.amplitudes);
    }

    @Test public void selectedStrengthChangesAmplitudeWhenDeviceSupportsIt() {
        HapticPolicy.Pattern lighter = HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, true, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_LIGHT);
        HapticPolicy.Pattern stronger = HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, true, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_STRONG);

        assertArrayEquals(new int[]{0, 96, 0, 96}, lighter.amplitudes);
        assertArrayEquals(new int[]{0, 192, 0, 192}, stronger.amplitudes);
        assertArrayEquals(new long[]{0, 100, 140, 100}, lighter.timingsMs);
    }

    @Test public void selectedStrengthChangesDurationWithoutAmplitudeControl() {
        HapticPolicy.Pattern lighter = HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, false, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_LIGHT);
        HapticPolicy.Pattern stronger = HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, false, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_STRONG);

        assertArrayEquals(new long[]{0, 65, 140, 65}, lighter.timingsMs);
        assertArrayEquals(new long[]{0, 120, 140, 120}, stronger.timingsMs);
        assertNull(lighter.amplitudes);
        assertNull(stronger.amplitudes);
    }

    @Test public void distanceExperimentStillAppliesOnlyWhenEnabled() {
        CueRequest request = nearRequest(0.05f, 2f);
        HapticPolicy.Pattern defaultPattern = HapticPolicy.create(request,
                false, true, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_SYSTEM);
        HapticPolicy.Pattern amplitudePattern = HapticPolicy.create(request,
                true, true, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_SYSTEM);
        HapticPolicy.Pattern durationPattern = HapticPolicy.create(request,
                true, false, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_SYSTEM);

        assertArrayEquals(new long[]{0, 100, 140, 100}, defaultPattern.timingsMs);
        assertNull(defaultPattern.amplitudes);
        assertArrayEquals(new int[]{0, 231, 0, 231}, amplitudePattern.amplitudes);
        assertArrayEquals(new long[]{0, 102, 140, 102}, durationPattern.timingsMs);
    }

    @Test public void shortDistancePatternKeepsBothPulsesAndStacksStrength() {
        CueRequest request = nearRequest(0.05f, 2f);
        HapticPolicy.Pattern timed = HapticPolicy.create(request,
                true, false, HapticPolicy.MODE_SHORT, HapticPolicy.STRENGTH_SYSTEM);
        HapticPolicy.Pattern timedStrong = HapticPolicy.create(request,
                true, false, HapticPolicy.MODE_SHORT, HapticPolicy.STRENGTH_STRONG);
        HapticPolicy.Pattern amplitude = HapticPolicy.create(request,
                true, true, HapticPolicy.MODE_SHORT, HapticPolicy.STRENGTH_SYSTEM);

        assertArrayEquals(new long[]{0, 36, 80, 36}, timed.timingsMs);
        assertArrayEquals(new long[]{0, 43, 80, 43}, timedStrong.timingsMs);
        assertArrayEquals(new long[]{0, 35, 80, 35}, amplitude.timingsMs);
        assertArrayEquals(new int[]{0, 231, 0, 231}, amplitude.amplitudes);
    }

    @Test public void unknownSelectionsFallBackToExistingDefault() {
        HapticPolicy.Pattern pattern = HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, false, "unknown-mode", "unknown-strength");

        assertArrayEquals(new long[]{0, 100, 140, 100}, pattern.timingsMs);
        assertNull(pattern.amplitudes);
    }

    @Test public void directionIsOptInAndDefaultNearTimingDoesNotDependOnIt() {
        CueRequest left = request(CueRequest.Category.NEAR_ZONE, 1, .08f, 1f);
        assertArrayEquals(new long[]{0, 100, 140, 100}, HapticPolicy.create(left,
                false, false, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_SYSTEM).timingsMs);
        assertArrayEquals(new long[]{0, 35, 80, 35}, HapticPolicy.create(left,
                false, false, HapticPolicy.MODE_SHORT, HapticPolicy.STRENGTH_SYSTEM).timingsMs);
        long[][] expected = {{0, 35, 45, 80}, {0, 80, 45, 35}, {0, 35}, {0, 35, 45, 35}};
        for (int direction = 1; direction <= 4; direction++) {
            HapticPolicy.Pattern pattern = HapticPolicy.create(request(
                    CueRequest.Category.NEAR_ZONE, direction, .08f, 1f), false, false,
                    HapticPolicy.MODE_DIRECTIONAL, HapticPolicy.STRENGTH_SYSTEM);
            assertArrayEquals(expected[direction - 1], pattern.timingsMs);
            assertNull(pattern.amplitudes);
        }
    }

    @Test public void unknownDirectionRemainsNeutralInDirectionalMode() {
        for (int unknown : new int[]{-1, 0, 5, 99}) {
            assertArrayEquals(new long[]{0, 100, 140, 100}, HapticPolicy.create(request(
                    CueRequest.Category.NEAR_ZONE, unknown, .08f, 1f), false, false,
                    HapticPolicy.MODE_DIRECTIONAL, HapticPolicy.STRENGTH_SYSTEM).timingsMs);
        }
    }

    @Test public void distanceAndStrengthKeepDirectionalPulseOrderWithoutAmplitudeControl() {
        for (String strength : new String[]{HapticPolicy.STRENGTH_SYSTEM,
                HapticPolicy.STRENGTH_LIGHT, HapticPolicy.STRENGTH_STRONG}) {
            for (int direction : new int[]{1, 2}) {
                long[] close = HapticPolicy.create(request(CueRequest.Category.NEAR_ZONE,
                        direction, .02f, 2f), true, false, HapticPolicy.MODE_DIRECTIONAL,
                        strength).timingsMs;
                long[] far = HapticPolicy.create(request(CueRequest.Category.NEAR_ZONE,
                        direction, .18f, 2f), true, false, HapticPolicy.MODE_DIRECTIONAL,
                        strength).timingsMs;
                assertEquals(direction == 1, close[1] < close[3]);
                assertEquals(direction == 1, far[1] < far[3]);
                assertTrue(close[1] > far[1]);
                assertTrue(close[3] > far[3]);
                assertEquals(45, close[2]);
            }
        }
    }

    @Test public void amplitudeDistancePreservesTheSelectedRhythm() {
        CueRequest closeRequest = request(CueRequest.Category.NEAR_ZONE, 2, .02f, 2f);
        HapticPolicy.Pattern close = HapticPolicy.create(closeRequest, true, true,
                HapticPolicy.MODE_DIRECTIONAL, HapticPolicy.STRENGTH_SYSTEM);
        HapticPolicy.Pattern far = HapticPolicy.create(request(CueRequest.Category.NEAR_ZONE,
                2, .18f, 2f), true, true, HapticPolicy.MODE_DIRECTIONAL, HapticPolicy.STRENGTH_SYSTEM);
        assertArrayEquals(new long[]{0, 80, 45, 35}, close.timingsMs);
        assertArrayEquals(close.timingsMs, far.timingsMs);
        assertTrue(close.amplitudes[1] > far.amplitudes[1]);
        assertEquals(close.amplitudes[1], close.amplitudes[3]);
        assertEquals(0, close.amplitudes[2]);
    }

    @Test public void hapticAuditContainsFinalWaveformAndFiniteSettings() {
        CueRequest left = request(CueRequest.Category.NEAR_ZONE, 1, .08f, 1f);
        HapticPolicy.Pattern pattern = HapticPolicy.create(left, false, false,
                HapticPolicy.MODE_DIRECTIONAL, HapticPolicy.STRENGTH_SYSTEM);
        String audit = HapticPolicy.audit(left, pattern, false, false,
                HapticPolicy.MODE_DIRECTIONAL, HapticPolicy.STRENGTH_SYSTEM);
        assertTrue(audit.contains("hapticCode=1"));
        assertTrue(audit.contains("timingsMs=[0,35,45,80] amplitudes=system"));
        assertTrue(audit.contains("mode=directional"));
        assertTrue(audit.contains("durationMs=160"));
        assertTrue(HapticPolicy.audit(left, pattern, false, false, "bad", "bad")
                .contains("mode=original strength=system"));
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
