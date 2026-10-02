package com.openkhub.sensefield;

import java.util.HashMap;
import java.util.Map;

/**
 * Bounds diagnostic image jobs across queued work and jobs currently being encoded.
 * Acquire before submitting a job and release its byte weight exactly once when it finishes.
 */
final class DiagnosticImageBudget {
    private final int maxFrames;
    private final long maxBytes;
    private final Map<Long, Integer> outstandingJobsByBytes = new HashMap<>();
    private int pendingFrames;
    private long pendingBytes;
    private int peakFrames;
    private long peakBytes;

    DiagnosticImageBudget(int maxFrames, long maxBytes) {
        if (maxFrames <= 0 || maxBytes <= 0) {
            throw new IllegalArgumentException("Image budget limits must be positive");
        }
        this.maxFrames = maxFrames;
        this.maxBytes = maxBytes;
    }

    /** Reserves one in-flight image job when both limits have room. */
    synchronized boolean tryAcquire(long bytes) {
        if (bytes <= 0 || pendingFrames >= maxFrames || bytes > maxBytes - pendingBytes) {
            return false;
        }

        pendingFrames++;
        pendingBytes += bytes;
        Integer count = outstandingJobsByBytes.get(bytes);
        outstandingJobsByBytes.put(bytes, count == null ? 1 : count + 1);
        peakFrames = Math.max(peakFrames, pendingFrames);
        peakBytes = Math.max(peakBytes, pendingBytes);
        return true;
    }

    /**
     * Releases one outstanding job with this exact byte weight. Invalid or unregistered
     * weights are ignored, so a duplicate release cannot underflow either counter.
     */
    synchronized void release(long bytes) {
        if (bytes <= 0) return;
        Integer count = outstandingJobsByBytes.get(bytes);
        if (count == null || count <= 0) return;

        if (count == 1) outstandingJobsByBytes.remove(bytes);
        else outstandingJobsByBytes.put(bytes, count - 1);
        pendingFrames--;
        pendingBytes -= bytes;
    }

    synchronized int pendingFrames() { return pendingFrames; }
    synchronized long pendingBytes() { return pendingBytes; }
    synchronized int peakFrames() { return peakFrames; }
    synchronized long peakBytes() { return peakBytes; }
}
