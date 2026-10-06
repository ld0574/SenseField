package com.openkhub.sensefield;

/**
 * Pure mapping from native near-zone relation events to cue parameters.
 *
 * <p>Kept free of Android framework calls so JVM tests can lock the wording,
 * stereo gains and channel rules. Integer values mirror
 * {@code native/include/mapassist.h}.</p>
 */
final class NearZoneRouting {
    /** Included in diagnostics because same-version CDN builds can differ. */
    static final String EVENT_POLICY = "confirmed_absence_v2";
    static final int KIND_NEAR_ZONE = 7;
    static final int KIND_RADAR_STATUS = 8;

    static final int STATE_UNAVAILABLE = -1;
    static final int STATE_UNKNOWN = 0;
    static final int STATE_CLEAR = 1;
    static final int STATE_PENDING = 2;
    static final int STATE_OCCUPIED = 3;
    static final int STATE_REARM = 4;

    static final int EVENT_NONE = 0;
    static final int EVENT_NEAR_ENTER = 1;
    static final int EVENT_RADAR_PAUSED = 2;
    static final int EVENT_RADAR_RESUMED = 3;
    static final int EVENT_SUPPRESSED = 4;

    // CuePlayer sample ids for the three relation sounds.
    static final int TONE_NEAR = 7;
    static final int TONE_RADAR_PAUSED = 8;
    static final int TONE_RADAR_RESUMED = 9;

    static final int NEAR_PRIORITY = 80;
    static final long NEAR_TTL_MS = 1200;
    static final int RADAR_PRIORITY = 20;
    static final long RADAR_TTL_MS = 2000;
    static final long NEAR_SPEECH_DEDUPE_MS = 1000;
    /** Presentation distance scale in map-short-edge units; this does not alter native thresholds. */
    static final float PRESENTATION_DISTANCE_SCALE = 0.20f;

    private static final String[] SECTOR_NAMES = {
            null, "右方", "右上", "上方", "左上", "左方", "左下", "下方", "右下"};
    private static final String[] TWO_WORD_SECTORS = {
            null, "右侧", "右上", "上方", "左上", "左侧", "左下", "下方", "右下"};
    private static final String[] STATE_NAMES = {
            "UNKNOWN", "CLEAR", "PENDING", "OCCUPIED", "REARM"};

    private NearZoneRouting() {}

    /** Short factual phrase; no “danger”, “safe” or intent words. */
    static String speech(int sector) {
        if (sector >= 1 && sector <= 8) return SECTOR_NAMES[sector] + "有敌人";
        return "附近有敌人";
    }

    /** Short, factual sector-plus-target wording for the opt-in speech style. */
    static String twoWordSpeech(int sector) {
        if (sector >= 1 && sector <= 8) return TWO_WORD_SECTORS[sector];
        return "附近";
    }

    static float presentationDistanceLevel(float distance) {
        if (!Float.isFinite(distance)) return 0f;
        return Math.max(0f, Math.min(1f, distance / PRESENTATION_DISTANCE_SCALE));
    }

    /** Channels requested before the preset and global switches are applied. */
    static int nearChannels(boolean hapticEnabled) {
        int channels = CueRequest.CHANNEL_TONE | CueRequest.CHANNEL_SPEECH;
        return hapticEnabled ? channels | CueRequest.CHANNEL_HAPTIC : channels;
    }

    /** Standard mode carries the same event through speech, tone and haptic output. */
    static int nearChannels(boolean hapticEnabled, int enabledChannels) {
        return nearChannels(hapticEnabled, enabledChannels, false);
    }

    /**
     * Apply the selected channels. The preference flag remains for old callers
     * and compact/custom experiments; the standard path keeps all three
     * near-zone outputs so speech is not the only semantic channel.
     */
    static int nearChannels(boolean hapticEnabled, int enabledChannels, boolean preferSpeech) {
        int channels = nearChannels(hapticEnabled);
        if (preferSpeech && (enabledChannels & CueRequest.CHANNEL_SPEECH) != 0)
            channels &= ~CueRequest.CHANNEL_TONE;
        return channels & enabledChannels;
    }

    /**
     * Balance law matching the legacy hard left/right gains: the far side is
     * attenuated to 12% at |pan| = 1 and both sides stay full at the centre.
     */
    static float[] stereoGains(float pan, float volume) {
        float value = Float.isFinite(pan) ? Math.max(-1f, Math.min(1f, pan)) : 0f;
        float left = volume * (value > 0f ? 1f - 0.88f * value : 1f);
        float right = volume * (value < 0f ? 1f + 0.88f * value : 1f);
        return new float[] {left, right};
    }

    /**
     * With the relation layer active and enabled, the distant new-portrait
     * tone is replaced by near-zone cues unless the user asks for it. If the
     * near-zone category is switched off, the old tone comes back.
     */
    static boolean farAppearAudible(boolean nearZoneActive, boolean nearZoneCategoryEnabled,
                                    boolean farAppearPreference) {
        return !nearZoneActive || !nearZoneCategoryEnabled || farAppearPreference;
    }

    static String stateName(int state) {
        if (state == STATE_UNAVAILABLE) return "UNAVAILABLE";
        return state >= 0 && state < STATE_NAMES.length ? STATE_NAMES[state] : "INVALID";
    }

    static String eventName(int event) {
        if (event == EVENT_NEAR_ENTER) return "NEAR_ENTER";
        if (event == EVENT_RADAR_PAUSED) return "RADAR_PAUSED";
        if (event == EVENT_RADAR_RESUMED) return "RADAR_RESUMED";
        if (event == EVENT_SUPPRESSED) return "SUPPRESSED";
        return "NONE";
    }

    static String suppressionName(int suppression) {
        if (suppression == 1) return "rearm_pending";
        if (suppression == 2) return "short_gap";
        return "none";
    }
}
