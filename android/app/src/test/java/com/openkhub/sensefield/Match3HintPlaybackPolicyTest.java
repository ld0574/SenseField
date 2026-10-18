package com.openkhub.sensefield;

import static org.junit.Assert.*;
import org.junit.Test;

public final class Match3HintPlaybackPolicyTest {
    @Test public void onlyOrdinaryBoardRefreshesAllowAnAlreadyStartedSentenceToFinish() {
        for (String reason : new String[]{"HINT_EVIDENCE_CHANGED", "GOAL_CHANGED", "GOAL_STALE",
                "BOARD_MOTION", "VISUAL_MOTION", "CASCADE", "OBSERVATION_INVALID"})
            assertTrue(reason, Match3HintPlaybackPolicy.finishStartedSentence(reason));
        for (String reason : new String[]{"SERVICE_STOPPED", "PROJECTION_STOPPED", "POINT_READ_MODE",
                "POPUP", "FRAME_STALE", "FRAME_ERROR", "DISPLAY_CHANGED", "CAPTURE_SIZE",
                "GEOMETRY_CHANGED", "GEOMETRY_UNVERIFIED", "unknown"})
            assertFalse(reason, Match3HintPlaybackPolicy.finishStartedSentence(reason));
    }
}
