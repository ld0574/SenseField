package com.openkhub.sensefield;

/** Stable event-key formatting shared by capture dispatch and pure tests. */
final class CueEventKeys {
    private CueEventKeys() {}

    static String visionMemory(long nativeResetGeneration, int trackId, int event) {
        return "vision:" + nativeResetGeneration + ":" + trackId + ":" + event;
    }

    /** One key per native appearance batch, including each confirmed return. */
    static String nearZone(long nativeResetGeneration, int episodeId) {
        return "near:" + nativeResetGeneration + ":" + episodeId;
    }

    /** Radar status tones are unique per announcement. */
    static String radarStatus(long nativeResetGeneration, long sequence, int event) {
        return "radar:" + nativeResetGeneration + ":" + sequence + ":" + event;
    }

    static String nativeCue(long nativeResetGeneration, int kind, int direction) {
        if (kind >= 4) return "player:" + nativeResetGeneration + ":" + kind;
        return "native:" + nativeResetGeneration + ":" + kind + ":" + direction;
    }
}
