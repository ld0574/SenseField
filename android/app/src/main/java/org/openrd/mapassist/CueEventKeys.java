package org.openrd.mapassist;

/** Stable event-key formatting shared by capture dispatch and pure tests. */
final class CueEventKeys {
    private CueEventKeys() {}

    static String visionMemory(long nativeResetGeneration, int trackId, int event) {
        return "vision:" + nativeResetGeneration + ":" + trackId + ":" + event;
    }

    static String nativeCue(long nativeResetGeneration, int kind, int direction) {
        if (kind >= 4) return "player:" + nativeResetGeneration + ":" + kind;
        return "native:" + nativeResetGeneration + ":" + kind + ":" + direction;
    }
}
