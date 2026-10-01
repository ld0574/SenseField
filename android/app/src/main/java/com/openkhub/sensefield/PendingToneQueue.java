package com.openkhub.sensefield;

/** Single-slot queue for a tone whose SoundPool sample has not loaded yet. */
final class PendingToneQueue {
    static final class Pending {
        final int sampleId;
        final int kind;
        final int direction;
        final long expiresAtMs;
        final CueRequest.Category category;
        final CueDispatcher.PlaybackCallback callback;
        /** Continuous pan, or NaN for the legacy direction gains. */
        final float pan;

        Pending(int sampleId, int kind, int direction, long expiresAtMs,
                CueRequest.Category category, CueDispatcher.PlaybackCallback callback) {
            this(sampleId, kind, direction, expiresAtMs, category, callback, Float.NaN);
        }

        Pending(int sampleId, int kind, int direction, long expiresAtMs,
                CueRequest.Category category, CueDispatcher.PlaybackCallback callback,
                float pan) {
            this.sampleId = sampleId;
            this.kind = kind;
            this.direction = direction;
            this.expiresAtMs = expiresAtMs;
            this.category = category;
            this.callback = callback;
            this.pan = pan;
        }
    }

    private Pending pending;

    boolean enqueue(int sampleId, int kind, int direction, long expiresAtMs, long nowMs) {
        return enqueue(sampleId, kind, direction, expiresAtMs, nowMs, null);
    }

    boolean enqueue(int sampleId, int kind, int direction, long expiresAtMs, long nowMs,
                    CueDispatcher.PlaybackCallback callback) {
        return enqueue(sampleId, kind, direction, expiresAtMs, nowMs, null, callback);
    }

    boolean enqueue(int sampleId, int kind, int direction, long expiresAtMs, long nowMs,
                    CueRequest.Category category, CueDispatcher.PlaybackCallback callback) {
        return enqueue(sampleId, kind, direction, expiresAtMs, nowMs, category, callback,
                Float.NaN);
    }

    boolean enqueue(int sampleId, int kind, int direction, long expiresAtMs, long nowMs,
                    CueRequest.Category category, CueDispatcher.PlaybackCallback callback,
                    float pan) {
        if (sampleId <= 0 || nowMs > expiresAtMs) return false;
        pending = null;
        pending = new Pending(sampleId, kind, direction, expiresAtMs, category, callback, pan);
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

    Pending clearCategory(CueRequest.Category category) {
        if (pending == null || pending.category != category) return null;
        return clear();
    }
}
