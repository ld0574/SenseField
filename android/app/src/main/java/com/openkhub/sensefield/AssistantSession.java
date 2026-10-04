package com.openkhub.sensefield;

/** Monotonic ownership for asynchronous audio, frame, network and playback callbacks. */
final class AssistantSession {
    final String sessionId;
    private long generation = 1;
    private long sequence;
    private String currentTurn = "";
    private String captureTurn = "";
    private boolean closed;

    AssistantSession(String sessionId) { this.sessionId = sessionId; }
    synchronized long generation() { return generation; }
    synchronized String newTurn() {
        currentTurn = "g" + generation + "-t" + (++sequence);
        return currentTurn;
    }
    synchronized boolean owns(long expectedGeneration, String turn) {
        return !closed && generation == expectedGeneration && currentTurn.equals(turn);
    }
    /** VAD is provisional input, not a confirmed replacement for a pending answer. */
    synchronized String newCaptureTurn() {
        captureTurn = "g" + generation + "-v" + (++sequence);
        return captureTurn;
    }
    synchronized boolean ownsCapture(long expectedGeneration, String turn) {
        return !closed && generation == expectedGeneration && captureTurn.equals(turn);
    }
    synchronized void invalidateCapture() { captureTurn = ""; }
    synchronized void invalidate() { generation++; currentTurn = ""; captureTurn = ""; }
    synchronized void close() { invalidate(); closed = true; }
    synchronized boolean isClosed() { return closed; }
}
