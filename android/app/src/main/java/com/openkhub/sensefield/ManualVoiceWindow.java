package com.openkhub.sensefield;

/** One deliberate utterance; stale stop callbacks cannot finish a newer window. */
final class ManualVoiceWindow {
    static final long NO_SPEECH_MS = 5_000;
    static final long MAX_MS = 15_000;
    static final class End {
        final boolean hadSpeech;
        End(boolean hadSpeech) { this.hadSpeech = hadSpeech; }
    }
    private long token, startedAtMs;
    private boolean active, hadSpeech;

    synchronized boolean begin(long id, long now) {
        if (active || id <= 0) return false;
        token = id; startedAtMs = now; active = true; hadSpeech = false;
        return true;
    }
    synchronized boolean active() { return active; }
    synchronized long token() { return token; }
    synchronized void speechStarted() { if (active) hadSpeech = true; }
    synchronized String dueReason(long id, long now) {
        if (!active || token != id) return "";
        long age = now - startedAtMs;
        if (age >= MAX_MS) return "time_limit";
        return !hadSpeech && age >= NO_SPEECH_MS ? "no_speech" : "";
    }
    synchronized End end(long id) {
        if (!active || token != id) return null;
        active = false;
        return new End(hadSpeech);
    }
}
