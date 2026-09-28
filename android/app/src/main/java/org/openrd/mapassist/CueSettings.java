package org.openrd.mapassist;

import android.content.Context;
import android.content.SharedPreferences;

/** Stored output policy. Presets are materialized so a capture session is deterministic. */
final class CueSettings implements CueDispatcher.Policy {
    static final String PRESET_COMPACT = "compact";
    static final String PRESET_STANDARD = "standard";
    static final String PRESET_DETAILED = "detailed";
    static final String PRESET_CUSTOM = "custom";

    private final SharedPreferences preferences;

    CueSettings(Context context) {
        preferences = GameProfile.settings(context);
        if (!preferences.contains("cue_preset")) applyPreset(preferences, PRESET_STANDARD);
    }

    static void applyPreset(SharedPreferences preferences, String preset) {
        SharedPreferences.Editor edit = preferences.edit().putString("cue_preset", preset);
        boolean compact = PRESET_COMPACT.equals(preset);
        edit.putBoolean("cue_channel_visual", true)
                .putBoolean("cue_channel_tone", !compact)
                .putBoolean("cue_channel_speech", true)
                .putBoolean("cue_channel_haptic", true)
                .putBoolean("cue_category_vision", true)
                .putBoolean("cue_category_danger", false)
                .putBoolean("cue_category_player", true)
                .putBoolean("cue_category_system", true)
                .putBoolean("cue_speak_appear", PRESET_DETAILED.equals(preset))
                .putInt("cue_vision_speech_gap_ms",
                        PRESET_DETAILED.equals(preset) ? 1000 : 2000)
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
        if (category == CueRequest.Category.DANGER)
            return preferences.getBoolean("cue_category_danger", false);
        if (category == CueRequest.Category.PLAYER_STATE)
            return preferences.getBoolean("cue_category_player", true);
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
        return enabledChannels & presetCategoryChannels(preset, category);
    }

    static int presetCategoryChannels(String preset, CueRequest.Category category) {
        if (PRESET_COMPACT.equals(preset) && category == CueRequest.Category.VISION_MEMORY)
            return CueRequest.CHANNEL_VISUAL;
        return CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH |
                CueRequest.CHANNEL_HAPTIC | CueRequest.CHANNEL_VISUAL;
    }

    @Override public long dedupeWindowMs(CueRequest.Category category) {
        if (category == CueRequest.Category.VISION_MEMORY) {
            return preferences.getInt("cue_vision_speech_gap_ms", 2000);
        }
        return category == CueRequest.Category.SYSTEM ? 1000 : 500;
    }

    boolean speakAppear() {
        return preferences.getBoolean("cue_speak_appear", false);
    }
}
