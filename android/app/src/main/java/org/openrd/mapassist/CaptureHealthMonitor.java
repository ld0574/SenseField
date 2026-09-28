package org.openrd.mapassist;

/** Pure state machine for MediaProjection frame starvation and bounded recovery. */
final class CaptureHealthMonitor {
    enum State { HEALTHY, STARVED, RECOVERING, FAILED, REVOKED, PAUSED }

    static final long STARTUP_GRACE_MS = 2000;
    static final long STARVATION_MS = 1000;
    static final long STABLE_RESET_MS = 10000;
    private static final long[] BACKOFF_MS = {500, 1000, 2000};

    private State state = State.HEALTHY;
    private long startedAtMs;
    private long lastFrameAtMs = -1;
    private long lastProcessedAtMs = -1;
    private long healthySinceMs = -1;
    private long recoveryRebuiltAtMs = -1;
    private int consecutiveAttempts;

    void start(long nowMs) {
        state = State.HEALTHY;
        startedAtMs = nowMs;
        lastFrameAtMs = -1;
        lastProcessedAtMs = -1;
        healthySinceMs = nowMs;
        recoveryRebuiltAtMs = -1;
        consecutiveAttempts = 0;
    }

    void frameArrived(long nowMs) {
        lastFrameAtMs = nowMs;
        recoveryRebuiltAtMs = -1;
        if (state == State.STARVED || state == State.RECOVERING) healthySinceMs = nowMs;
        if (state != State.REVOKED && state != State.FAILED && state != State.PAUSED)
            state = State.HEALTHY;
        if (healthySinceMs >= 0 && nowMs - healthySinceMs >= STABLE_RESET_MS)
            consecutiveAttempts = 0;
    }

    void frameProcessed(long nowMs) {
        lastProcessedAtMs = nowMs;
    }

    State check(long nowMs) {
        if (state == State.RECOVERING) {
            if (recoveryRebuiltAtMs >= 0 &&
                    nowMs - recoveryRebuiltAtMs >= STARTUP_GRACE_MS)
                state = State.STARVED;
            return state;
        }
        if (state == State.REVOKED || state == State.FAILED || state == State.PAUSED)
            return state;
        long reference = lastFrameAtMs >= 0 ? lastFrameAtMs : startedAtMs;
        if (nowMs - startedAtMs >= STARTUP_GRACE_MS && nowMs - reference >= STARVATION_MS)
            state = State.STARVED;
        return state;
    }

    long beginRecovery() {
        if (state != State.STARVED) return -1;
        if (consecutiveAttempts >= BACKOFF_MS.length) {
            state = State.FAILED;
            return -1;
        }
        state = State.RECOVERING;
        recoveryRebuiltAtMs = -1;
        return BACKOFF_MS[consecutiveAttempts++];
    }

    void recoveryFailed() {
        recoveryRebuiltAtMs = -1;
        state = consecutiveAttempts >= BACKOFF_MS.length ? State.FAILED : State.STARVED;
    }

    void recoveryRebuilt(long nowMs) {
        if (state != State.RECOVERING) return;
        recoveryRebuiltAtMs = nowMs;
    }

    void pause(boolean paused, long nowMs) {
        if (state == State.REVOKED || state == State.FAILED) return;
        if (paused) state = State.PAUSED;
        else start(nowMs);
    }

    void revoke() { state = State.REVOKED; }
    State state() { return state; }
    int attempts() { return consecutiveAttempts; }
    long lastFrameAtMs() { return lastFrameAtMs; }
    long lastProcessedAtMs() { return lastProcessedAtMs; }
}
