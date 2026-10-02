package com.openkhub.sensefield;

import android.content.Context;
import android.content.SharedPreferences;

/** Stored output policy. Presets are materialized so a capture session is deterministic. */
final class CueSettings implements CueDispatcher.Policy {
    static final String PRESET_COMPACT = "compact";
    static final String PRESET_STANDARD = "standard";
    static final String PRESET_DETAILED = "detailed";
    static final String PRESET_CUSTOM = "custom";
    static final String PREF_CATEGORY_NEAR = "cue_category_near";
    static final String PREF_NEAR_HAPTIC = "cue_near_haptic";
    static final String PREF_FAR_APPEAR = "cue_far_appear";
    private static final String PREF_PRESET_REVISION = "cue_preset_revision";
    private static final int PRESET_REVISION = 2;

    private final SharedPreferences preferences;

    CueSettings(Context context) {
        preferences = GameProfile.settings(context);
        if (!preferences.contains("cue_preset")) applyPreset(preferences, PRESET_STANDARD);
        String preset = preferences.getString("cue_preset", PRESET_STANDARD);
        // Update named presets to their corrected defaults, while preserving
        // custom choices, recognition opt-ins, voice engine, speed and volume.
        if (preferences.getInt(PREF_PRESET_REVISION, 0) < PRESET_REVISION
                && (PRESET_STANDARD.equals(preset) || PRESET_DETAILED.equals(preset))) {
            applyPreset(preferences, preset);
        }
        // Installs that saved a preset before the near-zone category existed
        // get its defaults without rewriting any earlier choice.
        if (!preferences.contains(PREF_CATEGORY_NEAR)) {
            preferences.edit()
                    .putBoolean(PREF_CATEGORY_NEAR, true)
                    .putBoolean(PREF_NEAR_HAPTIC, !PRESET_COMPACT.equals(preset))
                    .putBoolean(PREF_FAR_APPEAR, PRESET_DETAILED.equals(preset))
                    .apply();
        }
    }

    static void applyPreset(SharedPreferences preferences, String preset) {
        SharedPreferences.Editor edit = preferences.edit().putString("cue_preset", preset)
                .putInt(PREF_PRESET_REVISION, PRESET_REVISION);
        boolean compact = PRESET_COMPACT.equals(preset);
        boolean detailed = PRESET_DETAILED.equals(preset);
        edit.putBoolean(PREF_CATEGORY_NEAR, true)
                .putBoolean(PREF_NEAR_HAPTIC, !compact)
                .putBoolean(PREF_FAR_APPEAR, detailed);
        edit.putBoolean("cue_channel_visual", true)
                .putBoolean("cue_channel_tone", !compact)
                .putBoolean("cue_channel_speech", true)
                .putBoolean("cue_channel_haptic", true)
                .putBoolean("cue_category_vision", true)
                .putBoolean("cue_category_peripheral", true)
                .putBoolean("cue_category_danger", detailed)
                .putBoolean("cue_category_player", true)
                .putBoolean("cue_category_system", true)
                .putBoolean("cue_speak_appear", detailed)
                .putInt("cue_vision_speech_gap_ms",
                        detailed ? 1000 : 2000)
                .apply();
    }

    static void markCustom(SharedPreferences preferences) {
        preferences.edit().putString("cue_preset", PRESET_CUSTOM).apply();
    }

    static boolean visionMemoryDispatchEnabled(boolean explicitlyEnabled,
                                               boolean categoryEnabled) {
        return explicitlyEnabled && categoryEnabled;
    }

    static String presetLabel(SharedPreferences preferences) {
        String preset = preferences.getString("cue_preset", PRESET_STANDARD);
        if (PRESET_COMPACT.equals(preset)) return "精简";
        if (PRESET_DETAILED.equals(preset)) return "详细";
        if (PRESET_CUSTOM.equals(preset)) return "自定义";
        return "标准";
    }

    @Override public boolean categoryEnabled(CueRequest.Category category) {
        if (category == CueRequest.Category.VISION_MEMORY)
            return preferences.getBoolean("cue_category_vision", true);
        if (category == CueRequest.Category.PERIPHERAL_THREAT)
            return preferences.getBoolean("cue_category_peripheral", true);
        if (category == CueRequest.Category.DANGER)
            return preferences.getBoolean("cue_category_danger", false);
        if (category == CueRequest.Category.PLAYER_STATE)
            return preferences.getBoolean("cue_category_player", true);
        if (category == CueRequest.Category.NEAR_ZONE)
            return preferences.getBoolean(PREF_CATEGORY_NEAR, true);
        return preferences.getBoolean("cue_category_system", true);
    }

    @Override public int enabledChannels() {
        int channels = 0;
        if (preferences.getBoolean("cue_channel_tone", true))
            channels |= CueRequest.CHANNEL_TONE;
        if (preferences.getBoolean("cue_channel_speech", true))
            channels |= CueRequest.CHANNEL_SPEECH;
        if (preferences.getBoolean("cue_channel_haptic", true))
            channels |= CueRequest.CHANNEL_HAPTIC;
        if (preferences.getBoolean("cue_channel_visual", true))
            channels |= CueRequest.CHANNEL_VISUAL;
        return channels;
    }

    @Override public int enabledChannels(CueRequest.Category category) {
        int channels = enabledChannels();
        String preset = preferences.getString("cue_preset", PRESET_STANDARD);
        return channelsForCategory(channels, preset, category);
    }

    static int channelsForCategory(int enabledChannels, String preset,
                                   CueRequest.Category category) {
        // The compact preset switches the shared tone channel off, but its
        // near-zone cue is exactly one spatial short tone without speech.
        if (category == CueRequest.Category.NEAR_ZONE && PRESET_COMPACT.equals(preset)) {
            return CueRequest.CHANNEL_TONE;
        }
        return enabledChannels & presetCategoryChannels(preset, category);
    }

    static int presetCategoryChannels(String preset, CueRequest.Category category) {
        if (category == CueRequest.Category.VISION_MEMORY) {
            if (PRESET_COMPACT.equals(preset)) return CueRequest.CHANNEL_VISUAL;
            // Standard visual memory is deliberately quiet: the overlay and
            // one neutral tone are available; detailed enables every output
            // supported by this event.
            if (PRESET_STANDARD.equals(preset)) {
                return CueRequest.CHANNEL_VISUAL | CueRequest.CHANNEL_TONE;
            }
            if (PRESET_DETAILED.equals(preset)) {
                return CueRequest.CHANNEL_VISUAL | CueRequest.CHANNEL_TONE |
                        CueRequest.CHANNEL_SPEECH | CueRequest.CHANNEL_HAPTIC;
            }
        }
        if (category == CueRequest.Category.PERIPHERAL_THREAT) {
            // Edge threats have no reliable screen overlay coordinate. Their
            // default feedback is one short stereo tone in every preset.
            return CueRequest.CHANNEL_TONE;
        }
        if (category == CueRequest.Category.NEAR_ZONE) {
            // The overlay is a separate opt-in layer; near-zone cues never
            // request it. Haptics follow the per-feature switch.
            if (PRESET_COMPACT.equals(preset)) return CueRequest.CHANNEL_TONE;
            return CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH |
                    CueRequest.CHANNEL_HAPTIC;
        }
        return CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH |
                CueRequest.CHANNEL_HAPTIC | CueRequest.CHANNEL_VISUAL;
    }

    @Override public long dedupeWindowMs(CueRequest.Category category) {
        if (category == CueRequest.Category.VISION_MEMORY) {
            return preferences.getInt("cue_vision_speech_gap_ms", 2000);
        }
        if (category == CueRequest.Category.PERIPHERAL_THREAT) return 2000;
        // Only guards same-frame repeats; occupancy episodes do the real dedupe.
        if (category == CueRequest.Category.NEAR_ZONE)
            return NearZoneRouting.NEAR_SPEECH_DEDUPE_MS;
        return category == CueRequest.Category.SYSTEM ? 1000 : 500;
    }

    boolean speakAppear() {
        return preferences.getBoolean("cue_speak_appear", false);
    }

    boolean nearHapticEnabled() {
        return preferences.getBoolean(PREF_NEAR_HAPTIC, !PRESET_COMPACT.equals(
                preferences.getString("cue_preset", PRESET_STANDARD)));
    }

    int nearRequestedChannels() {
        return NearZoneRouting.nearChannels(nearHapticEnabled(),
                enabledChannels(CueRequest.Category.NEAR_ZONE), false);
    }

    /** Whether the distant new-portrait tone stays on while near-zone cues run. */
    boolean farAppearPreference() {
        return preferences.getBoolean(PREF_FAR_APPEAR, PRESET_DETAILED.equals(
                preferences.getString("cue_preset", PRESET_STANDARD)));
    }
}
