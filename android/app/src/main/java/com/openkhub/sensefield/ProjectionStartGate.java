package com.openkhub.sensefield;

/** Hands a fresh, one-use consent result to a service only from a foreground activity. */
final class ProjectionStartGate<T> {
    private boolean requested;
    private boolean consumed;
    private T result;

    boolean begin() {
        if (pending()) return false;
        requested = true;
        consumed = false;
        return true;
    }

    boolean complete(T grantedResult) {
        if (!requested || consumed || result != null) return false;
        requested = false;
        result = grantedResult;
        return result != null;
    }

    T take(boolean resumed, boolean focused) {
        if (!resumed || !focused || result == null || consumed) return null;
        T fresh = result;
        result = null;
        consumed = true;
        return fresh;
    }

    boolean pending() { return requested || result != null; }
    boolean requested() { return requested; }
    T waitingResult() { return result; }

    void restore(boolean requestPending, T waitingResult) {
        requested = requestPending && waitingResult == null;
        result = waitingResult;
        consumed = false;
    }

    void cancel() { requested = false; result = null; consumed = true; }
}
