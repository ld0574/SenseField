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
    final String decisionScene;
    final AssistantDecisionFrameGuard decisionGuard;
    final boolean snapshotRead;

    AssistantReply(long generation, String turnId, long frameId, long capturedAtMs,
                   String kind, String answer, boolean uncertain) {
        this(generation, turnId, frameId, capturedAtMs, kind, answer, uncertain, false);
    }
    AssistantReply(long generation, String turnId, long frameId, long capturedAtMs,
                   String kind, String answer, boolean uncertain, boolean proactive) {
        this(generation, turnId, frameId, capturedAtMs, kind, answer, uncertain, proactive, "", null);
    }
    AssistantReply(long generation, String turnId, long frameId, long capturedAtMs,
                   String kind, String answer, boolean uncertain, boolean proactive,
                   String decisionScene, AssistantDecisionFrameGuard decisionGuard) {
        this(generation, turnId, frameId, capturedAtMs, kind, answer, uncertain, proactive,
                decisionScene, decisionGuard, false);
    }
    AssistantReply(long generation, String turnId, long frameId, long capturedAtMs,
                   String kind, String answer, boolean uncertain, boolean proactive,
                   String decisionScene, AssistantDecisionFrameGuard decisionGuard, boolean snapshotRead) {
        this.generation = generation; this.turnId = turnId; this.frameId = frameId;
        this.capturedAtMs = capturedAtMs; this.kind = kind;
        this.answer = answer; this.uncertain = uncertain;
        this.proactive = proactive;
        this.decisionScene = "equipment".equals(decisionScene) || "hero_selection".equals(decisionScene)
                || "game_decision".equals(decisionScene)
                ? decisionScene : "";
        this.decisionGuard = decisionGuard;
        this.snapshotRead = snapshotRead && !proactive && !uncertain && "hud".equals(kind);
    }
    long expiresAtMs() {
        // Local control/error messages carry no screenshot facts. Their session/turn
        // guard still cancels them immediately on interruption or a newer question.
        return capturedAtMs + (snapshotRead || !decisionScene.isEmpty() ? 20000 : "hud".equals(kind) ? 5000
                : "ui_text".equals(kind) || "local".equals(kind) ? 15000 : 5000);
    }
    boolean freshAt(long now) {
        return now >= capturedAtMs && now <= expiresAtMs()
                && (snapshotRead || decisionScene.isEmpty()
                    || (decisionGuard != null && decisionGuard.validAt(now)));
    }
    String spokenAnswerAt(long now) {
        if ("local".equals(kind)) return answer;
        long ageMs = Math.max(0, now - capturedAtMs);
        String caption = snapshotRead || ageMs > 2000
                ? "约" + (ageMs / 1000) + "秒前截图显示，" : "";
        return (proactive ? "画面变化，" : "") + caption + answer;
    }
}
