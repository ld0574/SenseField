package com.openkhub.sensefield;

/** Pure Java pulse and strength policy shared by every CuePlayer haptic. */
final class HapticPolicy {
    static final String PREF_MODE = "haptic_pattern_mode";
    static final String PREF_STRENGTH = "haptic_strength";

    static final String MODE_ORIGINAL = "original";
    static final String MODE_SHORT = "short";

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
        boolean shortMode = MODE_SHORT.equals(mode);
        boolean useDistance = request.category == CueRequest.Category.NEAR_ZONE
                && distanceHaptic && Float.isFinite(request.distance);
        String selectedStrength = validStrength(strength);

        long[] timings;
        if (request.category == CueRequest.Category.NEAR_ZONE) {
            if (useDistance && !amplitudeControl) {
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
                amplitudes = new int[timings.length];
                for (int index = 0; index < timings.length; index++) {
                    if (index % 2 == 1) amplitudes[index] = amplitude;
                }
            }
        } else if (!STRENGTH_SYSTEM.equals(selectedStrength)) {
            // Devices without amplitude control can still vary pulse duration.
            timings = scaleOnDurations(timings, strengthMultiplier(selectedStrength));
        }
        return new Pattern(timings, amplitudes);
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
