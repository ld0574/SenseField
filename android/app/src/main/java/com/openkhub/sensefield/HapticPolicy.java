package com.openkhub.sensefield;

import java.util.Arrays;

/** Pure Java pulse and strength policy shared by every CuePlayer haptic. */
final class HapticPolicy {
    static final String PREF_MODE = "haptic_pattern_mode";
    static final String PREF_STRENGTH = "haptic_strength";

    static final String MODE_ORIGINAL = "original";
    static final String MODE_SHORT = "short";
    static final String MODE_DIRECTIONAL = "directional";
    /**
     * Threat rhythm: the two structural signals this repository can actually read,
     * {@link CueRequest#urgency} and {@link CueRequest#freshEnemyCount}, decide how closely and how
     * heavily one burst repeats.  Opt-in only; it is not the default for anybody.
     */
    static final String MODE_THREAT_RHYTHM = "threat_rhythm";

    static final String STRENGTH_SYSTEM = "system";
    static final String STRENGTH_LIGHT = "light";
    static final String STRENGTH_STRONG = "strong";

    static final float SHORT_ON_FACTOR = 0.35f;
    static final long SHORT_MIN_ON_MS = 12;
    static final long SHORT_MAX_GAP_MS = 80;
    static final long NEAR_DEFAULT_ON_MS = 100;
    static final long NEAR_DEFAULT_GAP_MS = 140;
    private static final int LIGHT_AMPLITUDE = 96;
    private static final int STRONG_AMPLITUDE = 192;
    /** Share of the selected amplitude the first pulse of a burst gives up to reach the top. */
    private static final float CRESCENDO_SPAN = 0.5f;

    static final class Pattern {
        final long[] timingsMs;
        /** Null means the platform's default amplitude. */
        final int[] amplitudes;

        Pattern(long[] timingsMs, int[] amplitudes) {
            this.timingsMs = timingsMs;
            this.amplitudes = amplitudes;
        }

        long durationMs() {
            long duration = 0;
            for (long part : timingsMs) duration += part;
            return duration;
        }
    }

    private HapticPolicy() {}

    static Pattern create(CueRequest request, boolean distanceHaptic,
                          boolean amplitudeControl, String mode, String strength) {
        if (MODE_THREAT_RHYTHM.equals(mode)) {
            Pattern threat = threatRhythm(request, amplitudeControl);
            // Null is returned only when neither threat signal is present, and the untouched path
            // below is exactly the waveform MODE_ORIGINAL produces for this request.
            if (threat != null) return threat;
        }
        boolean shortMode = MODE_SHORT.equals(mode);
        boolean useDistance = request.category == CueRequest.Category.NEAR_ZONE
                && distanceHaptic && Float.isFinite(request.distance);
        String selectedStrength = validStrength(strength);

        long[] timings;
        if (request.category == CueRequest.Category.NEAR_ZONE) {
            if (MODE_DIRECTIONAL.equals(mode) && request.hapticCode >= 1
                    && request.hapticCode <= 4) {
                timings = directionPattern(request.hapticCode);
                if (useDistance && !amplitudeControl) {
                    // Scale both pulses together to preserve left/right order.
                    timings = scaleOnDurations(timings,
                            distanceHapticDurationMs(request.distance, request.urgency) / 100f);
                }
            } else if (useDistance && !amplitudeControl) {
                long pulse = distanceHapticDurationMs(request.distance, request.urgency);
                timings = new long[]{0, pulse, NEAR_DEFAULT_GAP_MS, pulse};
            } else {
                // Keep the established default exactly at 100 ms twice with a 140 ms gap.
                timings = new long[]{0, NEAR_DEFAULT_ON_MS, NEAR_DEFAULT_GAP_MS,
                        NEAR_DEFAULT_ON_MS};
            }
        } else {
            timings = directionPattern(request.hapticCode);
        }
        if (shortMode) timings = shortenPattern(timings);

        int[] amplitudes = null;
        if (amplitudeControl) {
            int baseAmplitude = 0;
            if (useDistance) {
                baseAmplitude = distanceHapticAmplitude(request.distance, request.urgency);
            } else if (!STRENGTH_SYSTEM.equals(selectedStrength)) {
                baseAmplitude = STRENGTH_LIGHT.equals(selectedStrength)
                        ? LIGHT_AMPLITUDE : STRONG_AMPLITUDE;
            }
            if (baseAmplitude > 0) {
                float multiplier = useDistance ? strengthMultiplier(selectedStrength) : 1f;
                int amplitude = clampAmplitude(Math.round(baseAmplitude * multiplier));
                amplitudes = rampAmplitudes(amplitude, timings.length);
            }
        } else if (!STRENGTH_SYSTEM.equals(selectedStrength)) {
            // Devices without amplitude control can still vary pulse duration.
            timings = scaleOnDurations(timings, strengthMultiplier(selectedStrength));
        }
        return new Pattern(timings, amplitudes);
    }

    /**
     * Threat rhythm for {@link #MODE_THREAT_RHYTHM}: one monotone density level, higher means the
     * pulses are never further apart and never weaker.
     *
     * <p>Nothing numeric is invented here. Every duration is sampled out of the direction
     * vocabulary {@link #directionPattern(int)} - its shortest pulse also serves as the densest
     * gap, its shared gap stays the reference gap, its longest pulse becomes the heavy tick - and
     * every amplitude is the existing {@link #LIGHT_AMPLITUDE} or {@link #STRONG_AMPLITUDE} tier,
     * handed to {@link #rampAmplitudes(int, int)} so the crescendo contract of one burst still
     * holds. The burst grows only while the whole waveform fits the single existing channel
     * budget, {@link HapticSemantics#withinBudget(HapticSemantics.Waveform)}; no second upper bound
     * is introduced in this file.
     *
     * <p>{@code strength} and {@code distanceHaptic} are deliberately not consulted: multiplying
     * the two existing amplitude tiers would create amplitude values this file has no basis for,
     * and the monotonicity contract has to stay checkable against one input axis.
     *
     * @return the rhythm, or null when both threat signals are zero, which hands the request back
     *         to the untouched MODE_ORIGINAL path
     */
    private static Pattern threatRhythm(CueRequest request, boolean amplitudeControl) {
        int level = threatLevel(request.urgency, request.freshEnemyCount);
        if (level == 0) return null;
        long shortTick = directionPattern(3)[1];
        long referenceGap = directionPattern(1)[2];
        long heavyTick = directionPattern(1)[3];
        long gap = level >= 4 ? shortTick : referenceGap;
        long tick = level >= 3 ? heavyTick : shortTick;
        int selectedAmplitude = level >= 3 ? STRONG_AMPLITUDE : LIGHT_AMPLITUDE;
        for (int pulses = level + 1; pulses > 0; pulses--) {
            long[] timings = new long[pulses * 2];
            for (int index = 1; index < timings.length; index++) {
                timings[index] = (index % 2) == 1 ? tick : gap;
            }
            int[] amplitudes = amplitudeControl
                    ? rampAmplitudes(selectedAmplitude, timings.length) : null;
            if (fitsChannelBudget(timings, amplitudes)) return new Pattern(timings, amplitudes);
        }
        return null;
    }

    /**
     * The one threat density level, 0..4, monotone non-decreasing in both signals; 0 means neither
     * signal is present at all. Unwrapable inputs - negative, non-finite, absurdly large - are
     * clamped instead of thrown, because a haptic policy must never take a cue down.
     */
    static int threatLevel(float urgency, int freshEnemyCount) {
        float bounded = Float.isFinite(urgency) ? Math.max(0f, Math.min(2f, urgency)) : 0f;
        int urgencyTier = bounded <= 0f ? 0 : bounded < 1f ? 1 : 2;
        int enemies = Math.max(0, freshEnemyCount);
        int countTier = enemies <= 0 ? 0 : enemies == 1 ? 1 : 2;
        return Math.min(4, urgencyTier + countTier);
    }

    /** Asks the existing budget guard, so the 600 ms ceiling stays defined in exactly one place. */
    private static boolean fitsChannelBudget(long[] timings, int[] amplitudes) {
        return HapticSemantics.withinBudget(new HapticSemantics.Waveform(timings, amplitudes));
    }

    /**
     * Crescendo for one burst: the open slots of a single waveform rise pulse over pulse instead
     * of repeating one constant, so "again and again" can be felt as swelling.
     *
     * <p>Silence slots keep their 0. The loudest slot keeps the burst's selected amplitude, so a
     * strength choice still means the same thing at the end of the burst; the earlier slots step
     * down from it toward the shared lower bound. A burst with one open slot is returned exactly
     * as the flat fill used to return it, so a lone tap is never quietly amplified. Every value
     * goes through {@link #clampAmplitude(int)}, so nothing here invents a second set of bounds.
     *
     * @param selectedAmplitude the burst's clamped open-slot amplitude, already 1..255
     * @param timingsLength     length of the timings array the amplitudes must match
     * @return an array of exactly {@code timingsLength} entries, strictly rising on the open slots
     */
    static int[] rampAmplitudes(int selectedAmplitude, int timingsLength) {
        int[] amplitudes = new int[timingsLength];
        int openSlots = timingsLength / 2;
        if (openSlots == 0) return amplitudes;
        int top = clampAmplitude(selectedAmplitude);
        if (openSlots == 1) {
            amplitudes[1] = top;
            return amplitudes;
        }
        // A strict ramp needs one distinct integer per open pulse inside the same 1..255 window.
        if (top < openSlots) top = clampAmplitude(openSlots);
        int stepForFullSpan = Math.max(1, Math.round(top * CRESCENDO_SPAN / (openSlots - 1)));
        int step = Math.min(Math.max(1, (top - 1) / (openSlots - 1)), stepForFullSpan);
        for (int slot = 0; slot < openSlots; slot++) {
            amplitudes[2 * slot + 1] = clampAmplitude(top - step * (openSlots - 1 - slot));
        }
        return amplitudes;
    }

    /** Bounded final waveform parameters; platform acceptance is not perception. */
    static String audit(CueRequest request, Pattern pattern, boolean amplitudeControl,
                        boolean distanceEnabled, String mode, String strength) {
        String selectedMode = MODE_THREAT_RHYTHM.equals(mode) ? MODE_THREAT_RHYTHM
                : MODE_DIRECTIONAL.equals(mode) ? MODE_DIRECTIONAL
                : MODE_SHORT.equals(mode) ? MODE_SHORT : MODE_ORIGINAL;
        return "HapticRequest cueId=" + request.cueId + " usage=ACCESSIBILITY"
                + " durationMs=" + pattern.durationMs() + " mode=" + selectedMode
                + " strength=" + validStrength(strength)
                + " hapticCode=" + request.hapticCode
                + " amplitudeControl=" + amplitudeControl + " distanceEnabled=" + distanceEnabled
                + " timingsMs=" + Arrays.toString(pattern.timingsMs).replace(" ", "")
                + " amplitudes=" + (pattern.amplitudes == null ? "system"
                : Arrays.toString(pattern.amplitudes).replace(" ", ""));
    }

    static long distanceHapticDurationMs(float distance, float urgency) {
        return Math.round(50f + 60f * distanceHapticIntensity(distance, urgency));
    }

    static int distanceHapticAmplitude(float distance, float urgency) {
        return clampAmplitude(Math.round(80f + 175f
                * distanceHapticIntensity(distance, urgency)));
    }

    private static String validStrength(String strength) {
        if (STRENGTH_LIGHT.equals(strength) || STRENGTH_STRONG.equals(strength)) return strength;
        return STRENGTH_SYSTEM;
    }

    private static float strengthMultiplier(String strength) {
        if (STRENGTH_LIGHT.equals(strength)) return 0.65f;
        if (STRENGTH_STRONG.equals(strength)) return 1.20f;
        return 1f;
    }

    private static int clampAmplitude(int amplitude) {
        return Math.max(1, Math.min(255, amplitude));
    }

    private static long[] scaleOnDurations(long[] source, float multiplier) {
        long[] scaled = source.clone();
        for (int index = 1; index < scaled.length; index += 2) {
            scaled[index] = Math.max(1, Math.round(scaled[index] * multiplier));
        }
        return scaled;
    }

    private static long[] shortenPattern(long[] source) {
        long[] shortened = source.clone();
        for (int index = 1; index < shortened.length; index += 2) {
            shortened[index] = Math.max(SHORT_MIN_ON_MS,
                    Math.round(shortened[index] * SHORT_ON_FACTOR));
        }
        // Keep the original pulse count and order while shortening long pauses.
        for (int index = 2; index < shortened.length; index += 2) {
            shortened[index] = Math.min(shortened[index], SHORT_MAX_GAP_MS);
        }
        return shortened;
    }

    private static long[] directionPattern(int direction) {
        if (direction == 1) return new long[]{0, 35, 45, 80};
        if (direction == 2) return new long[]{0, 80, 45, 35};
        if (direction == 3) return new long[]{0, 35};
        if (direction == 4) return new long[]{0, 35, 45, 35};
        return new long[]{0, 45};
    }

    private static float distanceHapticIntensity(float distance, float urgency) {
        float distanceLevel = NearZoneRouting.presentationDistanceLevel(distance);
        float boundedUrgency = Float.isFinite(urgency)
                ? Math.max(0f, Math.min(2f, urgency)) : 0f;
        float intensity = 0.25f + 0.55f * (1f - distanceLevel)
                + 0.20f * boundedUrgency / 2f;
        return Math.max(0.25f, Math.min(1f, intensity));
    }
}
