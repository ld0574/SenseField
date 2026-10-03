package com.openkhub.sensefield;

/**
 * Pure scheduling policy for bounded native frame processing load.
 *
 * <p>The policy spaces real processing runs by a bounded idle interval after
 * the previous run completes. It does not decide which frames are valid or
 * alter recognition/event state. Callers should acquire the latest image,
 * check {@link #canProcess(long)}, and close the image immediately when it is
 * not yet eligible.
 */
final class FrameProcessingPolicy {
    static final long MIN_START_INTERVAL_MS = 83;
    // App load-reduction triggers, not hardware safety limits. Battery readings
    // lag surface/SoC heat, so reduce work before the former 43/45 C thresholds.
    static final int BATTERY_WARM_ENTER_TENTHS_C = 400;
    static final int BATTERY_WARM_EXIT_TENTHS_C = 390;
    static final int BATTERY_HOT_ENTER_TENTHS_C = 420;
    static final int BATTERY_HOT_EXIT_TENTHS_C = 410;

    // Android PowerManager thermal status values. Kept numeric so this class
    // remains independent of Android APIs and can be tested on the JVM.
    static final int THERMAL_STATUS_NONE = 0;
    static final int THERMAL_STATUS_LIGHT = 1;
    static final int THERMAL_STATUS_SEVERE = 3;

    enum Mode { NORMAL, WARM, HOT }

    private Mode batteryMode = Mode.NORMAL;
    private Mode thermalMode = Mode.NORMAL;
    private Mode mode = Mode.NORMAL;

    private long lastStartedAtMs = -1;
    private long lastCompletedAtMs = -1;
    private long lastCostMs = -1;
    private long nextAllowedAtMs = -1;
    private long lastRestMs;

    /** True when the initial run is allowed or the current cooldown elapsed. */
    boolean canProcess(long nowMs) {
        return lastCompletedAtMs < 0 || nowMs >= nextAllowedAtMs;
    }

    /**
     * Record one completed processing run. Invalid or out-of-order timestamps
     * are ignored so a bad measurement cannot extend or erase the schedule.
     */
    void recordProcessed(long startedAtMs, long completedAtMs) {
        if (startedAtMs < 0 || completedAtMs < startedAtMs ||
                (lastStartedAtMs >= 0 && startedAtMs < lastStartedAtMs)) return;

        long measuredCostMs = completedAtMs - startedAtMs;
        lastCostMs = measuredCostMs;
        this.lastStartedAtMs = startedAtMs;
        this.lastCompletedAtMs = completedAtMs;
        recomputeDeadline();
    }

    /**
     * Update the independently sampled device thermal signals.
     *
     * <p>Temperature is tenths of a degree Celsius; negative values mean
     * unavailable. Thermal status uses Android's ordinal values, with any
     * negative value treated as unavailable. Unknown readings retain that
     * signal's previous mode; the other known signal may still change.
     * Battery-temperature thresholds have one degree of
     * hysteresis at each transition to avoid mode chatter.
     */
    void updateDevice(int batteryTemperatureTenthsC, int thermalStatus) {
        updateBatteryMode(batteryTemperatureTenthsC);
        updateThermalMode(thermalStatus);

        Mode updated = max(batteryMode, thermalMode);
        if (updated != mode) {
            mode = updated;
            recomputeDeadline();
        }
    }

    /** Clear sample timing/cost while keeping the most recent thermal state. */
    void reset() {
        lastStartedAtMs = -1;
        lastCompletedAtMs = -1;
        lastCostMs = -1;
        nextAllowedAtMs = -1;
        lastRestMs = 0;
    }

    Mode mode() { return mode; }
    long nextAllowedAtMs() { return nextAllowedAtMs; }
    long lastRestMs() { return lastRestMs; }

    private void updateBatteryMode(int temperatureTenthsC) {
        if (temperatureTenthsC < 0) return;

        if (temperatureTenthsC >= BATTERY_HOT_ENTER_TENTHS_C) {
            batteryMode = Mode.HOT;
        } else if (batteryMode == Mode.HOT) {
            if (temperatureTenthsC <= BATTERY_HOT_EXIT_TENTHS_C) batteryMode = Mode.WARM;
        } else if (batteryMode == Mode.WARM) {
            if (temperatureTenthsC <= BATTERY_WARM_EXIT_TENTHS_C) batteryMode = Mode.NORMAL;
        } else if (temperatureTenthsC >= BATTERY_WARM_ENTER_TENTHS_C) {
            batteryMode = Mode.WARM;
        }
    }

    private void updateThermalMode(int thermalStatus) {
        if (thermalStatus < THERMAL_STATUS_NONE) return;
        if (thermalStatus >= THERMAL_STATUS_SEVERE) {
            thermalMode = Mode.HOT;
        } else if (thermalStatus >= THERMAL_STATUS_LIGHT) {
            thermalMode = Mode.WARM;
        } else {
            thermalMode = Mode.NORMAL;
        }
    }

    private void recomputeDeadline() {
        if (lastCompletedAtMs < 0) {
            nextAllowedAtMs = -1;
            lastRestMs = 0;
            return;
        }

        long cost = Math.max(0, lastCostMs);
        long rest = restFor(mode, cost);
        lastRestMs = rest;

        long byStartInterval = saturatedAdd(lastStartedAtMs, MIN_START_INTERVAL_MS);
        long byCompletedRest = saturatedAdd(lastCompletedAtMs, rest);
        nextAllowedAtMs = Math.max(byStartInterval, byCompletedRest);
    }

    private static long restFor(Mode mode, long costMs) {
        switch (mode) {
            case WARM: {
                // 3/4 of 534 ms already reaches the 400 ms cap; avoid
                // overflowing when processing time is unexpectedly large.
                long warmRest = costMs >= 534 ? 400 : (costMs * 3) / 4;
                return clampRest(Math.max(120, warmRest), 400);
            }
            case HOT:
                return clampRest(Math.max(180, costMs), 600);
            case NORMAL:
            default:
                return clampRest(Math.max(40, costMs / 4), 150);
        }
    }

    private static long clampRest(long requestedMs, long maximumMs) {
        return Math.min(Math.max(0, requestedMs), maximumMs);
    }

    private static long saturatedAdd(long value, long increment) {
        if (increment > 0 && value > Long.MAX_VALUE - increment) return Long.MAX_VALUE;
        return value + increment;
    }

    private static Mode max(Mode left, Mode right) {
        return left.ordinal() >= right.ordinal() ? left : right;
    }
}
