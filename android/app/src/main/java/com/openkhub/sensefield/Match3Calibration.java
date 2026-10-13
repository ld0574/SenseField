package com.openkhub.sensefield;

import android.content.SharedPreferences;

/** An explicitly saved screenshot rectangle is only a candidate for current-frame verification. */
final class Match3Calibration {
    private static final String PREFIX = "match3_manual_";
    private static final String[] MANUAL_KEYS = {"saved", "width", "height", "left", "top", "right", "bottom", "rows", "cols"};
    private static final String[] LEGACY_KEYS = {"match3_calibrated", "match3_l", "match3_t", "match3_r", "match3_b", "match3_rows", "match3_cols"};

    static boolean plausible(BoardGeometry geometry) {
        return geometry != null && geometry.rows >= 6 && geometry.rows <= 9
                && geometry.cols >= 6 && geometry.cols <= 9
                && geometry.cellWidth() >= Match3Sampler.minPlausibleCell(geometry.frameWidth)
                && geometry.cellHeight() >= Match3Sampler.minPlausibleCell(geometry.frameWidth);
    }

    static void saveManual(SharedPreferences prefs, BoardGeometry geometry) {
        if (!plausible(geometry)) throw new IllegalArgumentException("Invalid calibration grid");
        int[] pct = geometry.percentages();
        prefs.edit().putBoolean(PREFIX + "saved", true)
                .putInt(PREFIX + "width", geometry.frameWidth).putInt(PREFIX + "height", geometry.frameHeight)
                .putInt(PREFIX + "left", geometry.left).putInt(PREFIX + "top", geometry.top)
                .putInt(PREFIX + "right", geometry.right).putInt(PREFIX + "bottom", geometry.bottom)
                .putInt(PREFIX + "rows", geometry.rows).putInt(PREFIX + "cols", geometry.cols)
                .putInt("match3_l", pct[0]).putInt("match3_t", pct[1])
                .putInt("match3_r", pct[2]).putInt("match3_b", pct[3])
                .putInt("match3_rows", geometry.rows).putInt("match3_cols", geometry.cols)
                .putBoolean("match3_calibrated", true).apply();
    }

    static BoardGeometry manual(SharedPreferences prefs) {
        try {
            if (!prefs.getBoolean(PREFIX + "saved", false)) return null;
            BoardGeometry geometry = new BoardGeometry(prefs.getInt(PREFIX + "width", 0),
                    prefs.getInt(PREFIX + "height", 0), prefs.getInt(PREFIX + "left", -1),
                    prefs.getInt(PREFIX + "top", -1), prefs.getInt(PREFIX + "right", -1),
                    prefs.getInt(PREFIX + "bottom", -1), prefs.getInt(PREFIX + "rows", 0), prefs.getInt(PREFIX + "cols", 0));
            return plausible(geometry) ? geometry : null;
        } catch (IllegalArgumentException | ClassCastException invalid) { return null; }
    }

    static BoardGeometry candidateForFrame(SharedPreferences prefs, int width, int height) {
        try {
            if (prefs.getBoolean(PREFIX + "saved", false)) {
                BoardGeometry saved = manual(prefs);
                BoardGeometry scaled = saved == null ? null : saved.scaledTo(width, height);
                return plausible(scaled) ? scaled : null;
            }
            if (!prefs.getBoolean("match3_calibrated", false)) return null;
            BoardGeometry legacy = BoardGeometry.fromPercent(width, height, prefs.getInt("match3_rows", 8),
                    prefs.getInt("match3_cols", 8), new int[]{prefs.getInt("match3_l", 4), prefs.getInt("match3_t", 18),
                            prefs.getInt("match3_r", 96), prefs.getInt("match3_b", 82)});
            return plausible(legacy) ? legacy : null;
        } catch (IllegalArgumentException | ClassCastException invalid) { return null; }
    }

    static void clear(SharedPreferences prefs) {
        SharedPreferences.Editor edit = prefs.edit();
        for (String key : MANUAL_KEYS) edit.remove(PREFIX + key);
        for (String key : LEGACY_KEYS) edit.remove(key);
        edit.apply();
    }

    private Match3Calibration() { }
}
