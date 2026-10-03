package com.openkhub.sensefield;

/** A short validated answer; capture time always belongs to the phone's clock. */
final class AssistantReply {
    final long generation;
    final String turnId;
    final long frameId;
    final long capturedAtMs;
    final String kind;
    final String answer;
    final boolean uncertain;
    final boolean proactive;

    AssistantReply(long generation, String turnId, long frameId, long capturedAtMs,
                   String kind, String answer, boolean uncertain) {
        this(generation, turnId, frameId, capturedAtMs, kind, answer, uncertain, false);
    }
    AssistantReply(long generation, String turnId, long frameId, long capturedAtMs,
                   String kind, String answer, boolean uncertain, boolean proactive) {
        this.generation = generation; this.turnId = turnId; this.frameId = frameId;
        this.capturedAtMs = capturedAtMs; this.kind = kind;
        this.answer = answer; this.uncertain = uncertain;
        this.proactive = proactive;
    }
    long expiresAtMs() {
        return capturedAtMs + ("hud".equals(kind) ? 5000 : "ui_text".equals(kind) ? 15000 : 5000);
    }
    boolean freshAt(long now) {
        return now >= capturedAtMs && now <= expiresAtMs();
    }
}
