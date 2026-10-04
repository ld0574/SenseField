package com.openkhub.sensefield;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small in-memory cache of recent owned frames for manually requested visual context. */
final class FrameHistory {
    static final int CAPACITY = 2;
    static final long MAX_AGE_MS = 6_000;
    static final long NORMAL_INTERVAL_MS = 2_000;
    static final long WARM_INTERVAL_MS = 4_000;
    static final int CONTEXT_EDGE = 640;

    private final ArrayDeque<FrameSnapshot> frames = new ArrayDeque<>(CAPACITY);
    private long lastCapturedAtMs = -1;
    private long captureEpoch;

    synchronized boolean due(long nowMs, FrameProcessingPolicy.Mode mode) {
        if (mode == FrameProcessingPolicy.Mode.HOT) {
            clear();
            return false;
        }
        discardExpired(nowMs);
        if (nowMs < lastCapturedAtMs) return false;
        long interval = mode == FrameProcessingPolicy.Mode.WARM ? WARM_INTERVAL_MS : NORMAL_INTERVAL_MS;
        return lastCapturedAtMs < 0 || nowMs - lastCapturedAtMs >= interval;
    }

    synchronized boolean add(FrameSnapshot frame) {
        return add(frame, captureEpoch);
    }

    synchronized boolean add(FrameSnapshot frame, long expectedEpoch) {
        if (expectedEpoch != captureEpoch) return false;
        if (frame == null || frame.capturedAtMs < 0 || frame.frameId < 0
                || (lastCapturedAtMs >= 0 && frame.capturedAtMs < lastCapturedAtMs)) return false;
        for (java.util.Iterator<FrameSnapshot> iterator = frames.iterator(); iterator.hasNext();) {
            if (iterator.next().frameId == frame.frameId) iterator.remove();
        }
        frames.addLast(frame);
        while (frames.size() > CAPACITY) frames.removeFirst();
        lastCapturedAtMs = frame.capturedAtMs;
        return true;
    }

    /** Returns distinct eligible frames oldest first; the primary image is never repeated. */
    synchronized List<FrameSnapshot> context(long nowMs, FrameSnapshot primary) {
        discardExpired(nowMs);
        if (primary == null) return Collections.emptyList();
        ArrayList<FrameSnapshot> result = new ArrayList<>(CAPACITY);
        Set<Long> ids = new HashSet<>();
        for (FrameSnapshot frame : frames) {
            if (isEligibleContext(frame.frameId, frame.capturedAtMs,
                    primary.frameId, primary.capturedAtMs, nowMs) && ids.add(frame.frameId)) {
                result.add(frame);
            }
        }
        result.sort(Comparator.comparingLong(frame -> frame.capturedAtMs));
        if (result.size() > CAPACITY) return new ArrayList<>(result.subList(result.size() - CAPACITY, result.size()));
        return result;
    }

    synchronized void clear() {
        frames.clear();
        lastCapturedAtMs = -1;
        captureEpoch++;
    }

    synchronized long epoch() { return captureEpoch; }
    synchronized int size() { return frames.size(); }

    static boolean isEligibleContext(long frameId, long capturedAtMs,
            long primaryFrameId, long primaryCapturedAtMs, long nowMs) {
        if (frameId < 0 || primaryFrameId < 0 || frameId == primaryFrameId
                || capturedAtMs < 0 || primaryCapturedAtMs < 0 || nowMs < capturedAtMs
                || nowMs < primaryCapturedAtMs || capturedAtMs >= primaryCapturedAtMs) return false;
        long ageMs = nowMs - capturedAtMs;
        long primaryAgeMs = nowMs - primaryCapturedAtMs;
        return ageMs > primaryAgeMs && ageMs <= MAX_AGE_MS;
    }

    private void discardExpired(long nowMs) {
        while (!frames.isEmpty()) {
            long ageMs = nowMs - frames.peekFirst().capturedAtMs;
            if (ageMs >= 0 && ageMs <= MAX_AGE_MS) break;
            if (ageMs < 0) break;
            frames.removeFirst();
        }
    }
}
