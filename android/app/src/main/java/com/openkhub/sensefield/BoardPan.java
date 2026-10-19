package com.openkhub.sensefield;

import java.util.Locale;

/**
 * 盘上格子 → 连续立体声位置 pan 的纯 JVM 映射（无 Android 依赖，可单测）。
 *
 * <p>pan 语义沿用 {@link CueRequest#pan}（连续立体声位置 [-1,1]；NaN 表示「沿用旧的方向 pan」，
 * 本项目里等同于「没有读数」）：最左列为负、最右列为正、奇数列的正中列为 0，端点绝对值恒不超过 1，
 * 行数不参与左右映射。
 *
 * <p>非法输入（{@code cols <= 1}、{@code rows <= 0}、列越界）返回 {@link Float#NaN} 而不是 0：
 * 单列盘或越界读数本身无从推断左右，把它当 0 等于告诉用户「在正中间」，是比不报更坏的假信号。
 *
 * <p>方位词只有一个来源 {@link Match3Coach#quadrantOf}，本类不另造一套左上／右下的说法。
 */
final class BoardPan {
    private BoardPan() {
    }

    /** 诊断读数里「拿不到 pan」的稳定文本；对齐 {@code DiagnosticRecorder} 只在有限数值时才落键的口径。 */
    static final String UNKNOWN_READING = "unknown";

    /**
     * 格子 → 立体声 pan。
     *
     * @param row  行下标（从 0 起；行数只用于合法性校验，不参与左右映射）
     * @param col  列下标（从 0 起）
     * @param rows 盘面行数
     * @param cols 盘面列数
     * @return [-1,1] 的 pan；非法输入返回 {@link Float#NaN}
     */
    static float panOf(int row, int col, int rows, int cols) {
        if (rows <= 0 || cols <= 1) return Float.NaN;
        if (col < 0 || col >= cols) return Float.NaN;
        float span = cols - 1;
        float pan = (2f * col - span) / span;
        if (pan < -1f) return -1f;
        if (pan > 1f) return 1f;
        return pan;
    }

    /**
     * 一句方位说明，复用播报层唯一的象限词表。
     *
     * @return 「X区域」；输入非法（单列盘、无行、列越界）时返回 null，表示不猜方位
     */
    static String quadrantSpeech(int row, int col, int rows, int cols) {
        if (Float.isNaN(panOf(row, col, rows, cols))) return null;
        return Match3Coach.quadrantOf(row, col, rows, cols) + "区域";
    }

    /**
     * 诊断字段用的稳定文本：有限 pan 取三位小数，非法输入给 {@link #UNKNOWN_READING}。
     * 直接丢 float 进 {@code DiagnosticRecorder.object} 会被「非有限数值」规则整键吞掉。
     */
    static String readingOf(int row, int col, int rows, int cols) {
        float pan = panOf(row, col, rows, cols);
        return Float.isFinite(pan) ? String.format(Locale.US, "%.3f", pan) : UNKNOWN_READING;
    }
}
