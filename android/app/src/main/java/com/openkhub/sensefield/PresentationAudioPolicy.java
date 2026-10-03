package com.openkhub.sensefield;

import android.content.SharedPreferences;

/** User-controlled gates for the experimental spatial and distance presentation. */
final class PresentationAudioPolicy {
    static final String PREF_SPATIAL = "presentation_spatial";
    static final String PREF_DISTANCE_HAPTIC = "presentation_distance_haptic";
    static final String PREF_NEAR_TWO_WORD = "near_two_word";

    final boolean spatial;
    final boolean distanceHaptic;
    final boolean nearTwoWord;

    PresentationAudioPolicy(boolean spatial, boolean distanceHaptic, boolean nearTwoWord) {
        this.spatial = spatial;
        this.distanceHaptic = distanceHaptic;
        this.nearTwoWord = nearTwoWord;
    }

    static PresentationAudioPolicy from(SharedPreferences preferences) {
        return new PresentationAudioPolicy(
                preferences.getBoolean(PREF_SPATIAL, false),
                preferences.getBoolean(PREF_DISTANCE_HAPTIC, false),
                preferences.getBoolean(PREF_NEAR_TWO_WORD, false));
    }

    static PresentationAudioPolicy defaults() {
        return new PresentationAudioPolicy(false, false, false);
    }

    static int distanceHapticAmplitude(float distance, float urgency) {
        return Math.max(1, Math.min(255,
                Math.round(80f + 175f * distanceHapticIntensity(distance, urgency))));
    }

    static long distanceHapticDurationMs(float distance, float urgency) {
        return Math.round(50f + 60f * distanceHapticIntensity(distance, urgency));
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
