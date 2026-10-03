package com.openkhub.sensefield;

/** Pure assistant admission policy. It never controls native detector admission. */
final class AssistantPolicy {
    static final long NORMAL_INTERVAL_MS = 30_000;
    static final long WARM_INTERVAL_MS = 60_000;
    static final long ALERT_QUIET_MS = 3000;
    static final long WINDOW_MS = 30 * 60_000;
    private long windowStarted = -1;
    private int automaticCalls;
    private int totalCalls;
    private long lastAutomatic = -1;
    private long throttledUntil;
    private long lastSignature = Long.MIN_VALUE;

    synchronized boolean automaticDue(long now, FrameProcessingPolicy.Mode mode,
            boolean speaking, boolean panel, long lastAlert, long signature) {
        refreshWindow(now);
        long interval = mode == FrameProcessingPolicy.Mode.WARM ? WARM_INTERVAL_MS : NORMAL_INTERVAL_MS;
        return mode != FrameProcessingPolicy.Mode.HOT && now >= throttledUntil && !speaking && !panel
                && (lastAlert < 0 || now - lastAlert >= ALERT_QUIET_MS)
                && (lastAutomatic < 0 || now - lastAutomatic >= interval)
                && signature != lastSignature && automaticCalls < 60 && totalCalls < 120;
    }
    synchronized boolean manualAllowed(long now, FrameProcessingPolicy.Mode mode) {
        refreshWindow(now);
        return mode != FrameProcessingPolicy.Mode.HOT && now >= throttledUntil && totalCalls < 120;
    }
    synchronized void started(long now, boolean proactive, long signature) {
        refreshWindow(now); totalCalls++;
        if (proactive) { automaticCalls++; lastAutomatic = now; lastSignature = signature; }
    }
    synchronized void throttled(long now) { throttledUntil = now + 60_000; }
    private void refreshWindow(long now) {
        if (windowStarted < 0 || now - windowStarted >= WINDOW_MS) {
            windowStarted = now; automaticCalls = 0; totalCalls = 0;
        }
    }
}
