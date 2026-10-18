package com.openkhub.sensefield;

/** A turn-based hint may finish speaking through a board refresh, never through a session reset. */
final class Match3HintPlaybackPolicy {
    private Match3HintPlaybackPolicy() { }

    static boolean finishStartedSentence(String reason) {
        return "HINT_EVIDENCE_CHANGED".equals(reason) || "GOAL_CHANGED".equals(reason)
                || "GOAL_STALE".equals(reason) || "BOARD_MOTION".equals(reason)
                || "VISUAL_MOTION".equals(reason) || "CASCADE".equals(reason)
                || "OBSERVATION_INVALID".equals(reason);
    }
}
