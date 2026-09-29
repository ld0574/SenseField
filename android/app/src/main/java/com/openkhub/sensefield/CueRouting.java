package com.openkhub.sensefield;

/** Pure routing rules kept separate from the Android service for unit testing. */
final class CueRouting {
    private static final int MINIMAP_ENEMY = 2;
    private static final int VISION_APPEAR = 1;
    private static final long MIN_MINIMAP_APPEAR_GAP_MS = 15_000;
    static final long NO_MINIMAP_CUE = Long.MIN_VALUE;

    enum CueAccounting { STALE, QUEUED, FAILED }

    private CueRouting() {}

    /**
     * Minimap detections are state inputs, not user-facing events.  They must go
     * through the tracked APPEAR/DISAPPEAR path so a persistent marker cannot
     * be announced again whenever the native cooldown expires.
     */
    static boolean shouldDispatchDirectNativeCue(int kind) {
        return kind > 0 && kind != MINIMAP_ENEMY;
    }

    /** A confirmed appearance is a new minimap enemy; loss transitions stay quiet. */
    static boolean shouldCueMinimapAppearance(int event, long nowMs,
                                               long lastCueAtMs, long minimumGapMs) {
        if (event != VISION_APPEAR) return false;
        if (lastCueAtMs == NO_MINIMAP_CUE || nowMs < lastCueAtMs) return true;
        // Equal timestamps identify the same sampled frame, even when the
        // configured interval is zero, so multiple markers yield one cue.
        if (nowMs == lastCueAtMs) return false;
        return nowMs - lastCueAtMs >= Math.max(0, minimumGapMs);
    }

    static long effectiveMinimapAppearanceGap(long configuredGapMs) {
        return Math.max(MIN_MINIMAP_APPEAR_GAP_MS, configuredGapMs);
    }

    /** Until the player marker is located, do not encode a map-center bearing. */
    static int unlocatedMinimapEnemyDirection() {
        return 0;
    }

    static String unlocatedMinimapEnemySpeech() {
        return "小地图发现新敌方头像";
    }

    static CueAccounting cueAccounting(String dispatchReason, boolean audioQueued) {
        // The dispatcher makes the expiry decision atomically with submission.
        // Reading the clock again here could cross the TTL boundary after audio
        // has already been accepted and produce an impossible stale+queued log.
        if (audioQueued) return CueAccounting.QUEUED;
        return "expired".equals(dispatchReason)
                ? CueAccounting.STALE : CueAccounting.FAILED;
    }
}
