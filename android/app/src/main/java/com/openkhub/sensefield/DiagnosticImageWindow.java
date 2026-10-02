package com.openkhub.sensefield;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded image-sampling and event-window bookkeeping for local diagnostics.
 *
 * <p>Timestamps are capture times from one monotonic clock. This class deliberately has no
 * Android dependencies so its pacing and window rules can be exercised on the JVM.</p>
 */
final class DiagnosticImageWindow<T> {
    static final long SAMPLE_PERIOD_MS = 500;
    static final long PRE_WINDOW_MS = 3000;
    static final long POST_WINDOW_MS = 2000;
    static final long MAX_WINDOW_MS = 5000;
    static final int MAX_CACHED_FRAMES = 7;
    static final long AUTO_WINDOW_GAP_MS = 10000;

    enum TriggerStatus {
        ACCEPTED,
        COALESCED,
        RATE_LIMITED
    }

    /** A sampled frame retained as event context. */
    static final class Entry<T> {
        final long frameIndex;
        final long observedAtMs;
        final T payload;

        Entry(long frameIndex, long observedAtMs, T payload) {
            this.frameIndex = frameIndex;
            this.observedAtMs = observedAtMs;
            this.payload = payload;
        }
    }

    /** Result of opening or coalescing into a diagnostic window. */
    static final class TriggerResult<T> {
        final TriggerStatus status;
        /** Zero when rate limited; accepted window IDs start at one and are never reused. */
        final long id;
        final long startMs;
        final long endMs;
        /** Snapshot of cached pre/current frames at the time of this trigger. */
        final List<Entry<T>> entries;

        TriggerResult(TriggerStatus status, long id, long startMs, long endMs,
                      List<Entry<T>> entries) {
            this.status = status;
            this.id = id;
            this.startMs = startMs;
            this.endMs = endMs;
            this.entries = immutableCopy(entries);
        }
    }

    private final long samplePeriodMs;
    private final long preWindowMs;
    private final long postWindowMs;
    private final long maxWindowMs;
    private final long autoWindowGapMs;
    private final int maxCachedFrames;
    private final ArrayDeque<Entry<T>> cache = new ArrayDeque<>();

    private long nextSampleAtMs = NO_TIME;
    private long lastClockAtMs = NO_TIME;
    private long lastWindowStartAtMs = NO_TIME;
    private long lastWindowId;
    private Window activeWindow;

    private static final long NO_TIME = Long.MIN_VALUE;

    DiagnosticImageWindow() {
        this(SAMPLE_PERIOD_MS, PRE_WINDOW_MS, POST_WINDOW_MS, MAX_WINDOW_MS,
                MAX_CACHED_FRAMES, AUTO_WINDOW_GAP_MS);
    }

    /** Package-private configurable constructor for deterministic JVM tests. */
    DiagnosticImageWindow(long samplePeriodMs, long preWindowMs, long postWindowMs,
                          long maxWindowMs, int maxCachedFrames, long autoWindowGapMs) {
        if (samplePeriodMs <= 0 || preWindowMs < 0 || postWindowMs <= 0
                || maxWindowMs < postWindowMs || maxCachedFrames <= 0 || autoWindowGapMs < 0) {
            throw new IllegalArgumentException("Invalid diagnostic image window limits");
        }
        this.samplePeriodMs = samplePeriodMs;
        this.preWindowMs = preWindowMs;
        this.postWindowMs = postWindowMs;
        this.maxWindowMs = maxWindowMs;
        this.maxCachedFrames = maxCachedFrames;
        this.autoWindowGapMs = autoWindowGapMs;
    }

    /** Returns true at most once per sample period, without scheduling catch-up bursts. */
    synchronized boolean shouldSample(long captureTimeMs) {
        observeClock(captureTimeMs);
        prune(captureTimeMs);
        if (nextSampleAtMs == NO_TIME || captureTimeMs >= nextSampleAtMs) {
            nextSampleAtMs = saturatingAdd(captureTimeMs, samplePeriodMs);
            return true;
        }
        return false;
    }

    /** Retains a sampled frame and returns the same immutable entry to the caller. */
    synchronized Entry<T> add(long frameIndex, long captureTimeMs, T payload) {
        observeClock(captureTimeMs);
        prune(captureTimeMs);
        Entry<T> entry = new Entry<>(frameIndex, captureTimeMs, payload);
        cache.addLast(entry);
        while (cache.size() > maxCachedFrames) cache.removeFirst();
        return entry;
    }

    /**
     * Opens a new window, coalesces an overlapping trigger, or reports automatic rate limiting.
     * Manual triggers bypass the new-window gap and still establish the gap for later automatic
     * triggers. A coalesced trigger can extend the post window, but never beyond the first
     * trigger's hard deadline.
     */
    synchronized TriggerResult<T> trigger(long nowMs, boolean manual) {
        observeClock(nowMs);
        prune(nowMs);
        expireWindow(nowMs);
        List<Entry<T>> context = cachedEntries(nowMs);

        if (activeWindow != null) {
            long proposedEnd = Math.min(saturatingAdd(nowMs, postWindowMs), activeWindow.hardEndMs);
            if (proposedEnd > activeWindow.endMs) activeWindow.endMs = proposedEnd;
            return new TriggerResult<>(TriggerStatus.COALESCED, activeWindow.id,
                    activeWindow.startMs, activeWindow.endMs, context);
        }

        if (!manual && lastWindowStartAtMs != NO_TIME
                && elapsedSince(nowMs, lastWindowStartAtMs) < autoWindowGapMs) {
            return new TriggerResult<>(TriggerStatus.RATE_LIMITED, 0, -1, -1,
                    Collections.emptyList());
        }

        if (lastWindowId == Long.MAX_VALUE) {
            throw new IllegalStateException("Diagnostic window ID space exhausted");
        }
        long id = ++lastWindowId;
        long hardEndMs = saturatingAdd(nowMs, maxWindowMs);
        long endMs = Math.min(saturatingAdd(nowMs, postWindowMs), hardEndMs);
        activeWindow = new Window(id, nowMs, endMs, hardEndMs);
        lastWindowStartAtMs = nowMs;
        return new TriggerResult<>(TriggerStatus.ACCEPTED, id, nowMs, endMs, context);
    }

    /** Returns the active window ID at this capture time, or zero when no window is open. */
    synchronized long activeWindowId(long captureTimeMs) {
        observeClock(captureTimeMs);
        prune(captureTimeMs);
        expireWindow(captureTimeMs);
        return activeWindow == null ? 0 : activeWindow.id;
    }

    /** Drops cached payload references and resets pacing/window state without reusing IDs. */
    synchronized void clear() {
        clearState();
        lastClockAtMs = NO_TIME;
    }

    private void observeClock(long nowMs) {
        if (lastClockAtMs != NO_TIME && nowMs < lastClockAtMs) {
            clearState();
        }
        lastClockAtMs = nowMs;
    }

    private void clearState() {
        cache.clear();
        nextSampleAtMs = NO_TIME;
        lastWindowStartAtMs = NO_TIME;
        activeWindow = null;
    }

    private void prune(long nowMs) {
        while (!cache.isEmpty()) {
            Entry<T> oldest = cache.peekFirst();
            if (oldest.observedAtMs <= nowMs
                    && elapsedSince(nowMs, oldest.observedAtMs) <= preWindowMs) break;
            cache.removeFirst();
        }
    }

    private List<Entry<T>> cachedEntries(long nowMs) {
        ArrayList<Entry<T>> result = new ArrayList<>(cache.size());
        for (Entry<T> entry : cache) {
            if (entry.observedAtMs <= nowMs
                    && elapsedSince(nowMs, entry.observedAtMs) <= preWindowMs) result.add(entry);
        }
        return result;
    }

    private void expireWindow(long nowMs) {
        if (activeWindow != null && nowMs >= activeWindow.endMs) activeWindow = null;
    }

    private static long elapsedSince(long nowMs, long thenMs) {
        if (nowMs < thenMs) return Long.MAX_VALUE;
        long elapsed = nowMs - thenMs;
        return elapsed < 0 ? Long.MAX_VALUE : elapsed;
    }

    private static long saturatingAdd(long value, long increment) {
        if (increment > 0 && value > Long.MAX_VALUE - increment) return Long.MAX_VALUE;
        if (increment < 0 && value < Long.MIN_VALUE - increment) return Long.MIN_VALUE;
        return value + increment;
    }

    private static <T> List<Entry<T>> immutableCopy(List<Entry<T>> entries) {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    private static final class Window {
        final long id;
        final long startMs;
        long endMs;
        final long hardEndMs;

        Window(long id, long startMs, long endMs, long hardEndMs) {
            this.id = id;
            this.startMs = startMs;
            this.endMs = endMs;
            this.hardEndMs = hardEndMs;
        }
    }
}
