package com.openkhub.sensefield;

/** Monotonic ownership for asynchronous audio, frame, network and playback callbacks. */
final class AssistantSession {
    final String sessionId;
    private long generation = 1;
    private long sequence;
    private String currentTurn = "";
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
    synchronized void invalidate() { generation++; currentTurn = ""; }
    synchronized void close() { invalidate(); closed = true; }
    synchronized boolean isClosed() { return closed; }
}
