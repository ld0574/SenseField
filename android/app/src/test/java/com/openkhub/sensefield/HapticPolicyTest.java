package com.openkhub.sensefield;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

        // Crescendo inside one burst: the last open slot keeps the selected amplitude and the
        // earlier slot steps back by the widest integer step the 1..selected window can hold.
        // Two open slots: light 96 => step round(96*0.5)=48 => first pulse 96-48=48;
        // strong 192 => step round(192*0.5)=96 => first pulse 192-96=96.
        assertArrayEquals(new int[]{0, 48, 0, 96}, lighter.amplitudes);
        assertArrayEquals(new int[]{0, 96, 0, 192}, stronger.amplitudes);
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
        // Distance burst, two open slots: selected 231 => step round(231*0.5)=116 => first
        // pulse 231-116=115, loudest slot still lands on the selected 231.
        assertArrayEquals(new int[]{0, 115, 0, 231}, amplitudePattern.amplitudes);
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
        // Short mode keeps the same two pulses, so the same crescendo applies:
        // selected 231 => step 116 => {0, 231-116, 0, 231}.
        assertArrayEquals(new int[]{0, 115, 0, 231}, amplitude.amplitudes);
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
        // Crescendo, stated as a property rather than as two literals, so a later change of the
        // step policy cannot pass by coincidence: the first open slot is still a real pulse, the
        // open slots rise strictly pulse over pulse, and the loudest one lands exactly on the
        // amplitude this burst selected. STRENGTH_SYSTEM multiplies the selected value by 1f, so
        // distanceHapticAmplitude reads it independently of the ramp (.02f / 2f => 245).
        // Filling both slots with one constant makes the strict-rise line go red.
        int closeSelected = HapticPolicy.distanceHapticAmplitude(.02f, 2f);
        assertTrue(close.amplitudes[1] >= 1);
        assertTrue(close.amplitudes[1] < close.amplitudes[3]);
        assertEquals(closeSelected, close.amplitudes[3]);
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

    // ---- brief 36: threat rhythm hung on the existing haptic_pattern_mode switch ----

    @Test public void threatRhythmIsAnOptInModeValueAndOriginalStaysTheFallbackLabel() {
        assertFalse(HapticPolicy.MODE_THREAT_RHYTHM.equals(HapticPolicy.MODE_ORIGINAL));
        CueRequest request = threatRequest(0, Float.NaN, 2f, 4);
        HapticPolicy.Pattern pattern = HapticPolicy.create(request, false, true,
                HapticPolicy.MODE_THREAT_RHYTHM, HapticPolicy.STRENGTH_SYSTEM);
        assertTrue(HapticPolicy.audit(request, pattern, false, false,
                HapticPolicy.MODE_THREAT_RHYTHM, HapticPolicy.STRENGTH_SYSTEM)
                .contains("mode=threat_rhythm"));
        // An unrecognised value must still be reported as the default, exactly as before.
        assertTrue(HapticPolicy.audit(request, pattern, false, false, "bad", "bad")
                .contains("mode=original"));
    }

    @Test public void threatRhythmFallsBackToTheOriginalWaveformWhenBothSignalsAreZero() {
        for (CueRequest.Category category : new CueRequest.Category[]{
                CueRequest.Category.NEAR_ZONE, CueRequest.Category.DANGER}) {
            for (int hapticCode : new int[]{0, 1, 2, 3, 4, 9}) {
                for (float distance : new float[]{Float.NaN, 0.05f}) {
                    boolean distanceHaptic = !Float.isNaN(distance);
                    for (boolean amplitudeControl : new boolean[]{false, true}) {
                        // -1 means "not supplied" and 0 means "no fresh enemy": both are no signal.
                        for (int freshEnemyCount : new int[]{-1, 0}) {
                            CueRequest request = new CueRequest("test", "test", "test", "TEST",
                                    category, 80, 0, 1000, CueRequest.CHANNEL_HAPTIC, 0, 0,
                                    hapticCode, null, Float.NaN, distance, 0f, freshEnemyCount,
                                    () -> true);
                            assertSameWaveform(HapticPolicy.MODE_ORIGINAL,
                                    HapticPolicy.MODE_THREAT_RHYTHM, request, distanceHaptic,
                                    amplitudeControl);
                        }
                    }
                }
            }
        }
    }

    @Test public void defaultOriginalModeWaveformsAreByteForByteWhatTheyWereBefore() {
        HapticPolicy.Pattern near = HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, false, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_SYSTEM);
        assertArrayEquals(new long[]{0, 100, 140, 100}, near.timingsMs);
        assertNull(near.amplitudes);
        assertEquals(340, near.durationMs());

        HapticPolicy.Pattern danger = HapticPolicy.create(
                request(CueRequest.Category.DANGER, 1, Float.NaN, 0f),
                false, false, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_SYSTEM);
        assertArrayEquals(new long[]{0, 35, 45, 80}, danger.timingsMs);
        assertNull(danger.amplitudes);

        HapticPolicy.Pattern distanceDriven = HapticPolicy.create(nearRequest(0.05f, 2f),
                true, false, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_SYSTEM);
        assertArrayEquals(new long[]{0, 102, 140, 102}, distanceDriven.timingsMs);
        assertNull(distanceDriven.amplitudes);

        HapticPolicy.Pattern strengthened = HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, true, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_LIGHT);
        assertArrayEquals(new long[]{0, 100, 140, 100}, strengthened.timingsMs);
        assertArrayEquals(new int[]{0, 48, 0, 96}, strengthened.amplitudes);
    }

    @Test public void threatRhythmComparesLowAndHighUrgencyTimingAtBothEnds() {
        HapticPolicy.Pattern low = probe(0, 0.4f, 0);
        HapticPolicy.Pattern high = probe(0, 2f, 3);
        // The lowest threat rung repeats the direction vocabulary with its own shared gap.
        assertArrayEquals(new long[]{0, 35, 45, 35}, low.timingsMs);
        // The top rung is five heavy ticks spaced by the shortest pulse of that same vocabulary.
        assertArrayEquals(new long[]{0, 80, 35, 80, 35, 80, 35, 80, 35, 80}, high.timingsMs);
        assertTrue(gapOf(high.timingsMs) < gapOf(low.timingsMs));
        assertTrue(high.timingsMs.length > low.timingsMs.length);
        assertTrue(topAmplitudeOf(high.amplitudes) > topAmplitudeOf(low.amplitudes));
    }

    @Test public void threatRhythmHasThreeDistinctFreshEnemyCountTiers() {
        HapticPolicy.Pattern none = probe(1, 0.4f, 0);
        HapticPolicy.Pattern one = probe(1, 0.4f, 1);
        HapticPolicy.Pattern many = probe(1, 0.4f, 5);
        assertEquals(2, pulseCount(none));
        assertEquals(3, pulseCount(one));
        assertEquals(4, pulseCount(many));
        assertTrue(gapOf(one.timingsMs) <= gapOf(none.timingsMs));
        assertTrue(gapOf(many.timingsMs) <= gapOf(one.timingsMs));
        assertTrue(topAmplitudeOf(one.amplitudes) >= topAmplitudeOf(none.amplitudes));
        assertTrue(topAmplitudeOf(many.amplitudes) >= topAmplitudeOf(one.amplitudes));
    }

    @Test public void threatRhythmIsMonotoneAcrossTheWholeUrgencyRange() {
        long previousGap = Long.MAX_VALUE;
        int previousAmplitude = Integer.MIN_VALUE;
        int previousLength = 0;
        for (int step = 0; step <= 40; step++) {
            float urgency = step / 20f;
            HapticPolicy.Pattern rung = probe(0, urgency, 2);
            assertTrue("pulse interval grew with urgency at " + urgency,
                    gapOf(rung.timingsMs) <= previousGap);
            assertTrue("amplitude shrank with urgency at " + urgency,
                    topAmplitudeOf(rung.amplitudes) >= previousAmplitude);
            assertTrue("burst shortened with urgency at " + urgency,
                    rung.timingsMs.length >= previousLength);
            previousGap = gapOf(rung.timingsMs);
            previousAmplitude = topAmplitudeOf(rung.amplitudes);
            previousLength = rung.timingsMs.length;
        }
    }

    @Test public void threatRhythmIsMonotoneAcrossTheWholeFreshEnemyCountRange() {
        long previousGap = Long.MAX_VALUE;
        int previousAmplitude = Integer.MIN_VALUE;
        int previousLength = 0;
        for (int freshEnemyCount = 0; freshEnemyCount <= 40; freshEnemyCount++) {
            HapticPolicy.Pattern rung = probe(0, 1f, freshEnemyCount);
            assertTrue("pulse interval grew with fresh enemies at " + freshEnemyCount,
                    gapOf(rung.timingsMs) <= previousGap);
            assertTrue("amplitude shrank with fresh enemies at " + freshEnemyCount,
                    topAmplitudeOf(rung.amplitudes) >= previousAmplitude);
            assertTrue("burst shortened with fresh enemies at " + freshEnemyCount,
                    rung.timingsMs.length >= previousLength);
            previousGap = gapOf(rung.timingsMs);
            previousAmplitude = topAmplitudeOf(rung.amplitudes);
            previousLength = rung.timingsMs.length;
        }
    }

    @Test public void threatRhythmIsCappedByTheExistingChannelBudgetAndByNothingElse() {
        HapticPolicy.Pattern top = probe(0, 2f, 40);
        assertTrue(HapticSemantics.withinBudget(new HapticSemantics.Waveform(
                top.timingsMs, top.amplitudes)));
        // The ceiling bites exactly one pulse further out, so the bound is the existing budget and
        // not a number this policy quietly grew somewhere else.
        long tick = top.timingsMs[1];
        long gap = top.timingsMs[2];
        long[] onePulseLonger = new long[top.timingsMs.length + 2];
        for (int index = 1; index < onePulseLonger.length; index++) {
            onePulseLonger[index] = (index % 2) == 1 ? tick : gap;
        }
        assertFalse(HapticSemantics.withinBudget(
                new HapticSemantics.Waveform(onePulseLonger, null)));
    }

    @Test public void threatRhythmEncodesDensityInTimingsWhenTheDeviceHasNoAmplitudeControl() {
        HapticPolicy.Pattern low = probeWithoutAmplitude(0, 0.4f, 0);
        HapticPolicy.Pattern high = probeWithoutAmplitude(0, 2f, 3);
        assertNull(low.amplitudes);
        assertNull(high.amplitudes);
        assertTrue(gapOf(high.timingsMs) < gapOf(low.timingsMs));
        assertTrue(high.timingsMs.length > low.timingsMs.length);
    }

    @Test public void threatRhythmReusesTheExistingAmplitudeFamilyAndKeepsTheCrescendo() {
        int light = topAmplitudeOf(HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, true, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_LIGHT).amplitudes);
        int strong = topAmplitudeOf(HapticPolicy.create(nearRequest(Float.NaN, 0f),
                false, true, HapticPolicy.MODE_ORIGINAL, HapticPolicy.STRENGTH_STRONG).amplitudes);
        for (float urgency : new float[]{0.4f, 1f, 2f}) {
            int previousTop = Integer.MIN_VALUE;
            for (int freshEnemyCount = 0; freshEnemyCount <= 4; freshEnemyCount++) {
                HapticPolicy.Pattern rung = probe(2, urgency, freshEnemyCount);
                int[] amplitudes = rung.amplitudes;
                assertNotNull(amplitudes);
                assertEquals(rung.timingsMs.length, amplitudes.length);
                int top = topAmplitudeOf(amplitudes);
                // No third amplitude may ever appear: only the two tiers this file already had.
                assertTrue("amplitude " + top + " is outside the existing family",
                        top == light || top == strong);
                assertEquals(0, amplitudes[0]);
                assertEquals(0, amplitudes[2]);
                // Monotone on a single axis, so the sweep itself never borrows the other one.
                assertTrue("amplitude fell while fresh enemies grew", top >= previousTop);
                previousTop = top;
                // The 17R crescendo: the open slots of one burst rise strictly pulse over pulse.
                int previous = 0;
                for (int slot = 1; slot < amplitudes.length; slot += 2) {
                    assertTrue("crescendo flattened at slot " + slot, amplitudes[slot] > previous);
                    assertTrue(amplitudes[slot] >= 1 && amplitudes[slot] <= 255);
                    previous = amplitudes[slot];
                }
            }
        }
    }

    @Test public void threatRhythmSurvivesOutOfBoundsSignalsWithoutThrowing() {
        assertEquals(0, HapticPolicy.threatLevel(-5f, -3));
        assertEquals(0, HapticPolicy.threatLevel(Float.NEGATIVE_INFINITY, Integer.MIN_VALUE));
        assertEquals(0, HapticPolicy.threatLevel(Float.NaN, -1));
        assertEquals(4, HapticPolicy.threatLevel(Float.MAX_VALUE, Integer.MAX_VALUE));
        assertEquals(4, HapticPolicy.threatLevel(2f, 2));
        for (float urgency : new float[]{-1f, -1e30f, Float.NaN, Float.POSITIVE_INFINITY,
                Float.MAX_VALUE, 1e30f}) {
            for (int freshEnemyCount : new int[]{Integer.MIN_VALUE, -7, Integer.MAX_VALUE,
                1 << 30}) {
                CueRequest request = threatRequest(3, Float.NaN, urgency, freshEnemyCount);
                HapticPolicy.Pattern pattern = HapticPolicy.create(request, true, true,
                        HapticPolicy.MODE_THREAT_RHYTHM, HapticPolicy.STRENGTH_SYSTEM);
                assertNotNull(pattern);
                assertTrue("out-of-range signals must not overflow the budget",
                        HapticSemantics.withinBudget(new HapticSemantics.Waveform(
                                pattern.timingsMs, pattern.amplitudes)));
            }
        }
    }

    /** One threat rung on a device that can be given an amplitude. */
    private static HapticPolicy.Pattern probe(int hapticCode, float urgency,
                                              int freshEnemyCount) {
        return HapticPolicy.create(threatRequest(hapticCode, Float.NaN, urgency, freshEnemyCount),
                false, true, HapticPolicy.MODE_THREAT_RHYTHM, HapticPolicy.STRENGTH_SYSTEM);
    }

    /** The same rung on a device that can only be given durations. */
    private static HapticPolicy.Pattern probeWithoutAmplitude(int hapticCode, float urgency,
                                                              int freshEnemyCount) {
        return HapticPolicy.create(threatRequest(hapticCode, Float.NaN, urgency, freshEnemyCount),
                false, false, HapticPolicy.MODE_THREAT_RHYTHM, HapticPolicy.STRENGTH_SYSTEM);
    }

    private static CueRequest threatRequest(int hapticCode, float distance, float urgency,
                                            int freshEnemyCount) {
        return new CueRequest("test", "test", "test", "TEST", CueRequest.Category.NEAR_ZONE,
                80, 0, 1000, CueRequest.CHANNEL_HAPTIC, 0, 0, hapticCode,
                null, Float.NaN, distance, urgency, freshEnemyCount, () -> true);
    }

    /** First inter-pulse gap of a burst, or the largest long when there is nothing to separate. */
    private static long gapOf(long[] timings) {
        return timings.length >= 4 ? timings[2] : Long.MAX_VALUE;
    }

    private static int pulseCount(HapticPolicy.Pattern pattern) {
        return pattern.timingsMs.length / 2;
    }

    /** Loudest open slot, or 0 when the burst leaves amplitude to the platform. */
    private static int topAmplitudeOf(int[] amplitudes) {
        int top = 0;
        if (amplitudes != null) for (int amplitude : amplitudes) top = Math.max(top, amplitude);
        return top;
    }

    /** Field-for-field comparison of the waveforms two mode values produce for one request. */
    private static void assertSameWaveform(String expectedMode, String actualMode,
                                           CueRequest request, boolean distanceHaptic,
                                           boolean amplitudeControl) {
        HapticPolicy.Pattern expected = HapticPolicy.create(request, distanceHaptic,
                amplitudeControl, expectedMode, HapticPolicy.STRENGTH_SYSTEM);
        HapticPolicy.Pattern actual = HapticPolicy.create(request, distanceHaptic,
                amplitudeControl, actualMode, HapticPolicy.STRENGTH_SYSTEM);
        assertArrayEquals("timings differ for " + request.category
                + " code=" + request.hapticCode + " distance=" + request.distance
                + " amplitudeControl=" + amplitudeControl, expected.timingsMs, actual.timingsMs);
        assertEquals("duration differs", expected.durationMs(), actual.durationMs());
        if (expected.amplitudes == null) {
            assertNull(actual.amplitudes);
        } else {
            assertNotNull(actual.amplitudes);
            assertArrayEquals(expected.amplitudes, actual.amplitudes);
        }
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

