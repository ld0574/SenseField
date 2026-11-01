package com.openkhub.sensefield;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pure reader for the other half of a stalled frame stream: frames keep arriving while the
 * processor stops consuming them, so {@code processed_frames} never moves and nothing says so.
 *
 * <p>The frame-stream status published by {@link DiagnosticCheckpoint} only answers "did a frame
 * arrive". A processor that receives frames and never finishes one still reads as a recent stream,
 * which is the shape a diagnostic bundle shows when it "looks like it dropped": the checkpoint is
 * written every five seconds and the counter underneath it is flat.</p>
 *
 * <p>{@code processed_frames} is written from four publication points onto three unrelated
 * counters, and the last writer wins, so counting "N ticks without movement" without naming the
 * writer would splice one counter's silence onto another's and invent an alert. The writer
 * identity is therefore an explicit input of every observation: when it changes, the streak
 * restarts at zero and the switching tick itself is never counted as motionless. Only ticks that
 * expect frames are counted at all.</p>
 *
 * <p>Deliberately pure JVM: no {@code android.*} type is imported, no network or signal API is
 * read, nothing is stopped, reopened or announced. This class reports a reading and stops there,
 * which is where its job ends.</p>
 */
final class FrameProgressStall {
    /**
     * How many counted motionless ticks in a row make the reader say so, once. Checkpoints run on
     * {@link DiagnosticCheckpoint#PERIOD_MS} (5000 ms), so three ticks is roughly fifteen seconds
     * of a flat counter. Written as its own literal on purpose: it must not be shared with, or
     * derived from, the frame-arrival starvation window, which answers a different question.
     */
    static final int STALL_TICKS = 3;

    /** Key the reading is published under inside the existing checkpoint payload. */
    static final String CHECKPOINT_KEY = "frame_progress_stall";
    static final String WRITER_UNKNOWN = "unknown";
    /** The capture-side publication point: the only one that carries no game id. */
    static final String WRITER_CAPTURE_SERVICE = "capture_service";
    private static final String WRITER_GAME_PREFIX = "game:";

    private static final String GAME_ID_KEY = "game_id";
    private static final String PROCESSED_FRAMES_KEY = "processed_frames";
    private static final String FRAMES_EXPECTED_KEY = "frames_expected";
    private static final String LANDSCAPE_PROCESSED_FRAMES_KEY = "landscape_processed_frames";
    private static final String FRAME_STREAM_STATUS_KEY = "frame_stream_status";
    private static final String STATUS_UNAVAILABLE = "unavailable";

    /** One tick's reading. Immutable, and it commands nothing. */
    static final class Reading {
        final String writerId;
        final boolean framesExpected;
        /** Whether this tick supplied a comparable {@code processed_frames} reading at all. */
        final boolean processedFramesKnown;
        /** How many counted ticks the current writer has been motionless, this one included. */
        final long stalledTicks;
        /** Roughly how long the counter has not moved; null while no anchor exists yet. */
        final Long stallMs;
        /** True only on the tick that reaches {@link #STALL_TICKS}, and only once per episode. */
        final boolean alert;

        private Reading(String writerId, boolean framesExpected, boolean processedFramesKnown,
                        long stalledTicks, Long stallMs, boolean alert) {
            this.writerId = writerId;
            this.framesExpected = framesExpected;
            this.processedFramesKnown = processedFramesKnown;
            this.stalledTicks = stalledTicks;
            this.stallMs = stallMs;
            this.alert = alert;
        }
    }

    private String writerId;
    private long baselineFrames = -1;
    private long lastProgressAtMs = -1;
    private long stalledTicks;
    private boolean alerted;

    /**
     * Observe one tick of one named writer.
     *
     * @param suppliedWriter  who published the snapshot this tick is about; null reads as unknown
     * @param framesExpected  whether frames were expected at all on this tick
     * @param processedFrames the counter reading; null or negative means "no usable reading"
     * @param nowMs           elapsed-realtime stamp of this checkpoint tick
     */
    Reading observe(String suppliedWriter, boolean framesExpected, Long processedFrames, long nowMs) {
        String writer = suppliedWriter == null ? WRITER_UNKNOWN : suppliedWriter;
        if (processedFrames == null || processedFrames.longValue() < 0) {
            // No usable reading: do not count the tick, do not invent a number, do not alert.
            return new Reading(writer, framesExpected, false, stalledTicks, stalledFor(nowMs), false);
        }
        long frames = processedFrames.longValue();
        if (!writer.equals(writerId) || baselineFrames < 0) {
            // Another writer owns another counter. Splicing this streak onto the previous one's
            // would fabricate an alert, so the streak is zeroed and this tick is not counted.
            restart(writer, frames, nowMs);
            return new Reading(writer, framesExpected, true, 0, null, false);
        }
        if (!framesExpected) {
            // Nothing was expected to move, so motionlessness proves nothing about the processor.
            restart(writer, frames, nowMs);
            return new Reading(writer, false, true, 0, null, false);
        }
        if (frames != baselineFrames) {
            // Moving is progress. Moving backwards means the same writer restarted its counter,
            // which is re-anchored rather than guessed at; either way the streak is over.
            baselineFrames = frames;
            lastProgressAtMs = nowMs;
            stalledTicks = 0;
            alerted = false;
            return new Reading(writer, true, true, 0, null, false);
        }
        stalledTicks++;
        boolean alert = !alerted && stalledTicks == STALL_TICKS;
        if (alert) alerted = true;
        return new Reading(writer, true, true, stalledTicks, stalledFor(nowMs), alert);
    }

    /** Observe one published snapshot: the writer is read from its shape, never guessed. */
    Reading observeSnapshot(JSONObject state, long nowMs) {
        return observe(writerOf(state), state != null && state.optBoolean(FRAMES_EXPECTED_KEY, false),
                processedFramesOf(state), nowMs);
    }

    /** Add this tick's reading to the checkpoint payload the recorder already builds. */
    void recordInto(JSONObject data, long nowMs) throws JSONException {
        if (data == null) return;
        Reading reading = observeSnapshot(data, nowMs);
        JSONObject stall = new JSONObject();
        stall.put("writer", reading.writerId);
        stall.put("frames_expected", reading.framesExpected);
        stall.put("processed_frames_known", reading.processedFramesKnown);
        stall.put("stalled_ticks", reading.stalledTicks);
        stall.put("stall_ms", reading.stallMs == null ? JSONObject.NULL : reading.stallMs);
        stall.put("threshold_ticks", STALL_TICKS);
        stall.put("alert", reading.alert);
        // The frame-stream status of this same tick, so "the stream looked recent" and "the
        // processor sat still" can be read side by side instead of comparing two number strings.
        stall.put(FRAME_STREAM_STATUS_KEY,
                data.optString(FRAME_STREAM_STATUS_KEY, STATUS_UNAVAILABLE));
        data.put(CHECKPOINT_KEY, stall);
    }

    private void restart(String writer, long frames, long nowMs) {
        writerId = writer;
        baselineFrames = frames;
        lastProgressAtMs = nowMs;
        stalledTicks = 0;
        alerted = false;
    }

    private Long stalledFor(long nowMs) {
        if (stalledTicks <= 0 || lastProgressAtMs < 0 || nowMs < lastProgressAtMs) return null;
        return Long.valueOf(nowMs - lastProgressAtMs);
    }

    /**
     * The writer behind a published snapshot, from the two keys that separate the publication
     * points: the capture side is the only one published without a game id, and everything else
     * is named by the game id it carries. A snapshot that names neither is unknown, which can
     * still be counted as its own writer but never spliced onto another one.
     */
    static String writerOf(JSONObject state) {
        if (state == null) return WRITER_UNKNOWN;
        if (state.has(GAME_ID_KEY)) {
            String game = state.optString(GAME_ID_KEY);
            return game.isEmpty() ? WRITER_UNKNOWN : WRITER_GAME_PREFIX + game;
        }
        return state.has(LANDSCAPE_PROCESSED_FRAMES_KEY) ? WRITER_CAPTURE_SERVICE : WRITER_UNKNOWN;
    }

    /** The counter reading of a snapshot; anything missing, non-numeric or negative is unusable. */
    static Long processedFramesOf(JSONObject state) {
        if (state == null || !state.has(PROCESSED_FRAMES_KEY)) return null;
        Object raw = state.opt(PROCESSED_FRAMES_KEY);
        if (!(raw instanceof Number)) return null;
        long value = ((Number) raw).longValue();
        return value < 0 ? null : Long.valueOf(value);
    }
}
