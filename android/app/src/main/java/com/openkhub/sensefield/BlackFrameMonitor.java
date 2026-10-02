package com.openkhub.sensefield;

/** Distinguish loading/overlay blackouts from an unusable capture stream. */
final class BlackFrameMonitor {
    enum Action { PROCESS, WAIT, DISABLE_OVERLAY, STOP }
    static final long OVERLAY_WAIT_MS = 1000;
    static final long STOP_AFTER_MS = 30000;
    private long blackSinceMs = -1;

    Action update(boolean black, boolean overlayPresent, long nowMs) {
        if (!black) {
            reset();
            return Action.PROCESS;
        }
        if (blackSinceMs < 0) blackSinceMs = nowMs;
        long elapsed = Math.max(0, nowMs - blackSinceMs);
        if (overlayPresent && elapsed >= OVERLAY_WAIT_MS) {
            // Give frames captured after removal a fresh recovery window.
            blackSinceMs = nowMs;
            return Action.DISABLE_OVERLAY;
        }
        return elapsed >= STOP_AFTER_MS ? Action.STOP : Action.WAIT;
    }

    boolean isBlack() { return blackSinceMs >= 0; }
    void reset() { blackSinceMs = -1; }
}
