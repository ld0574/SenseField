package com.openkhub.sensefield;

import org.json.JSONException;
import org.json.JSONObject;

/** Adds heartbeat ages without mutating the frame worker's published snapshot. */
final class DiagnosticCheckpoint {
    static final long PERIOD_MS = 5000;

    private DiagnosticCheckpoint() {}

    static JSONObject at(JSONObject snapshot, long nowMs) throws JSONException {
        JSONObject result = new JSONObject(snapshot.toString());
        result.put("snapshot_age_ms", age(nowMs, snapshot.optLong("snapshot_at_ms", -1)));
        long arrived = snapshot.optLong("last_frame_arrived_at_ms", -1);
        long completed = snapshot.optLong("last_frame_completed_at_ms", -1);
        boolean expected = snapshot.optBoolean("frames_expected", false);
        result.put("frame_arrival_age_ms", age(nowMs, arrived));
        result.put("frame_completion_age_ms", age(nowMs, completed));
        result.put("frame_stream_status", !expected ? "not_expected"
                : arrived < 0 ? "no_frame_received"
                : nowMs - arrived >= CaptureHealthMonitor.STARVATION_MS ? "stale" : "recent");
        result.put("open_processing_gap_ms", expected ? age(nowMs, completed) : JSONObject.NULL);
        return result;
    }

    private static Object age(long now, long at) {
        return at >= 0 && now >= at ? now - at : JSONObject.NULL;
    }
}
