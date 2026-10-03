package com.openkhub.sensefield;

import java.util.function.BooleanSupplier;

/** Tracks the active Match3 projection so callbacks from replaced sessions are ignored. */
final class Match3ProjectionSession {
    private long generation;
    private int latestStartId;
    private Object projection;

    synchronized long beginStart(int startId) {
        generation++;
        latestStartId = startId;
        projection = null;
        return generation;
    }

    synchronized boolean attach(long expectedGeneration, Object candidate) {
        if (candidate == null || expectedGeneration != generation) return false;
        projection = candidate;
        return true;
    }

    synchronized boolean isCurrent(long expectedGeneration, Object candidate) {
        return candidate != null && expectedGeneration == generation
                && projection == candidate;
    }

    /** Runs frame work atomically with respect to start/stop invalidation. */
    synchronized boolean runIfCurrent(long expectedGeneration, Object candidate,
                                      BooleanSupplier action) {
        if (!isCurrent(expectedGeneration, candidate)) return false;
        return action.getAsBoolean();
    }

    /** Runs state reset only if this is still the latest start request. */
    synchronized boolean runIfGeneration(long expectedGeneration, Runnable action) {
        if (expectedGeneration != generation) return false;
        action.run();
        return true;
    }

    /** Returns the start ID to stop, or zero for a stale/duplicate callback. */
    synchronized int stopIfCurrent(long expectedGeneration, Object candidate) {
        if (!isCurrent(expectedGeneration, candidate)) return 0;
        projection = null;
        return latestStartId;
    }

    synchronized void invalidate(int startId) {
        generation++;
        latestStartId = startId;
        projection = null;
    }
}
