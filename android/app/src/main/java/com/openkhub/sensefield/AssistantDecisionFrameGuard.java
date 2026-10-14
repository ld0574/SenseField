package com.openkhub.sensefield;

/** Bounded coarse page-change guard; this does not identify heroes or equipment. */
final class AssistantDecisionFrameGuard {
    private final long signature;
    private long lastFrameAtMs;
    private long changedAtMs = -1;
    private boolean invalid;

    AssistantDecisionFrameGuard(long signature, long capturedAtMs) {
        this.signature = signature;
        lastFrameAtMs = capturedAtMs;
    }
    synchronized void observe(long currentSignature, long capturedAtMs) {
        if (invalid || capturedAtMs <= lastFrameAtMs) return;
        lastFrameAtMs = capturedAtMs;
        if (Long.bitCount(signature ^ currentSignature) > 12) {
            if (changedAtMs >= 0 && capturedAtMs - changedAtMs >= 100) invalid = true;
            else if (changedAtMs < 0) changedAtMs = capturedAtMs;
        } else changedAtMs = -1;
    }
    synchronized boolean validAt(long nowMs) {
        return !invalid && nowMs >= lastFrameAtMs && nowMs - lastFrameAtMs <= 1000;
    }
}
