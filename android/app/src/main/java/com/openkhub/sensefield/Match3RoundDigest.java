package com.openkhub.sensefield;

/**
 * Turns the transient Match3 live counters into a short, non-boastful round digest.
 *
 * <p>The inputs are instantaneous readings, not accumulators: {@code visibleRuns} is the number
 * of runs currently visible on the board and {@code cascadeWaitingFrames} is how many frames have
 * waited for a cascade to settle. The wording therefore only states what those counts support
 * (at-most-simultaneous runs, frames still waiting) and never recasts them as a cumulative combo
 * ("连击 N 次") or an effect trigger count ("连锁特效 N 回"), which would hand the user fabricated
 * evidence. {@code processedFrames} is a genuine per-frame cumulative counter, so it may be stated
 * as-is. The goal status string is quoted verbatim; its meaning is never reinterpreted here.</p>
 *
 * <p>Pure JVM: imports no Android type, and emits no coordinate marker, package name, model name,
 * phone model or identity field.</p>
 */
public final class Match3RoundDigest {
    private static final String NO_RUN = "盘面上没看到连线";

    private Match3RoundDigest() {
    }

    /**
     * @param visibleRuns          runs visible on the current board (instantaneous)
     * @param cascadeWaitingFrames frames waiting for a cascade to settle (instantaneous)
     * @param processedFrames      cumulative processed-frame count
     * @param goalState            goal status string, quoted verbatim (null treated as empty)
     * @return at most two short Chinese sentences
     */
    public static String digest(int visibleRuns, long cascadeWaitingFrames,
                                long processedFrames, String goalState) {
        int runs = Math.max(0, visibleRuns);
        long cascade = Math.max(0L, cascadeWaitingFrames);
        long frames = Math.max(0L, processedFrames);

        StringBuilder sb = new StringBuilder("这局处理了 ").append(frames).append(" 帧");
        if (runs == 0 && cascade == 0) {
            sb.append('，').append(NO_RUN).append('。');
        } else if (runs > 0 && cascade > 0) {
            sb.append('，').append("盘面上最多同时看到 ").append(runs)
              .append(" 条连线，另有 ").append(cascade).append(" 帧在等连锁落定。");
        } else if (runs > 0) {
            sb.append('，').append("盘面上最多同时看到 ").append(runs).append(" 条连线。");
        } else {
            sb.append('，').append("有 ").append(cascade).append(" 帧在等连锁落定。");
        }
        sb.append("目标状态：").append(goalState == null ? "" : goalState).append('。');
        return sb.toString();
    }
}
