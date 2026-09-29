package com.openkhub.sensefield;

/** Single-slot queue for a tone whose SoundPool sample has not loaded yet. */
final class PendingToneQueue {
    static final class Pending {
        final int sampleId;
        final int kind;
        final int direction;
        final long expiresAtMs;
        final CueDispatcher.PlaybackCallback callback;

        Pending(int sampleId, int kind, int direction, long expiresAtMs,
                CueDispatcher.PlaybackCallback callback) {
            this.sampleId = sampleId;
            this.kind = kind;
            this.direction = direction;
            this.expiresAtMs = expiresAtMs;
            this.callback = callback;
        }
    }

    private Pending pending;

    boolean enqueue(int sampleId, int kind, int direction, long expiresAtMs, long nowMs) {
        return enqueue(sampleId, kind, direction, expiresAtMs, nowMs, null);
    }

    boolean enqueue(int sampleId, int kind, int direction, long expiresAtMs, long nowMs,
                    CueDispatcher.PlaybackCallback callback) {
        if (sampleId <= 0 || nowMs > expiresAtMs) return false;
        pending = null;
        pending = new Pending(sampleId, kind, direction, expiresAtMs, callback);
        return true;
    }

    Pending take(int sampleId) {
        if (pending == null || pending.sampleId != sampleId) return null;
        Pending completed = pending;
        pending = null;
        return completed;
    }

    Pending completeLoad(int sampleId, long nowMs, boolean success) {
        if (pending == null || pending.sampleId != sampleId) return null;
        Pending completed = pending;
        pending = null;
        return success && nowMs <= completed.expiresAtMs ? completed : null;
    }

    Pending clear() {
        Pending cleared = pending;
        pending = null;
        return cleared;
    }
}
