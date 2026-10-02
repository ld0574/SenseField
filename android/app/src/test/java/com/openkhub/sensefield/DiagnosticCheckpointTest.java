package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class DiagnosticCheckpointTest {
    @Test public void heartbeatTracksAStalledSnapshotWithoutInventingFrames() throws Exception {
        JSONObject state = new JSONObject().put("state", "processing").put("snapshot_at_ms", 1000)
                .put("frames_expected", true).put("last_frame_arrived_at_ms", 1000)
                .put("last_frame_completed_at_ms", 950).put("processed_frames", 7);
        JSONObject heartbeat = DiagnosticCheckpoint.at(state, 6000);
        assertEquals(7, heartbeat.getInt("processed_frames"));
        assertEquals(5000, heartbeat.getLong("snapshot_age_ms"));
        assertEquals(5050, heartbeat.getLong("open_processing_gap_ms"));
        assertEquals("stale", heartbeat.getString("frame_stream_status"));
        assertFalse(state.has("snapshot_age_ms"));
    }

    @Test public void noFirstFrameHasUnknownCompletionTime() throws Exception {
        JSONObject heartbeat = DiagnosticCheckpoint.at(new JSONObject()
                .put("state", "starting").put("frames_expected", true)
                .put("snapshot_at_ms", 0), 5000);
        assertEquals("no_frame_received", heartbeat.getString("frame_stream_status"));
        assertTrue(heartbeat.isNull("frame_completion_age_ms"));
        assertTrue(heartbeat.isNull("open_processing_gap_ms"));
    }

    @Test public void pausedAndPortraitSnapshotsDoNotClaimFrameStarvation() throws Exception {
        for (String state : new String[]{"paused", "waiting_for_landscape"}) {
            JSONObject heartbeat = DiagnosticCheckpoint.at(new JSONObject().put("state", state)
                    .put("frames_expected", false).put("last_frame_completed_at_ms", 1000), 10000);
            assertEquals("not_expected", heartbeat.getString("frame_stream_status"));
            assertTrue(heartbeat.isNull("open_processing_gap_ms"));
            assertEquals(9000, heartbeat.getLong("frame_completion_age_ms"));
        }
    }

    @Test public void unavailableOrFutureTimestampsNeverBecomeNegativeAges() throws Exception {
        JSONObject heartbeat = DiagnosticCheckpoint.at(new JSONObject().put("snapshot_at_ms", 9000)
                .put("last_frame_arrived_at_ms", -1).put("last_frame_completed_at_ms", 9000), 1000);
        assertTrue(heartbeat.isNull("snapshot_age_ms"));
        assertTrue(heartbeat.isNull("frame_arrival_age_ms"));
        assertTrue(heartbeat.isNull("frame_completion_age_ms"));
    }
}
