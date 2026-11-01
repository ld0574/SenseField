package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「盘上位置 → 立体声 pan」纯逻辑测试。
 *
 * <p>期望值全部写死，不拿被测函数自己的输出反推；象限词只跟 {@link Match3Coach#quadrantOf} 对齐。
 */
public class BoardPanTest {

    /** 奇数列的正中列必须恰为 0（不是近似 0）。 */
    @Test
    public void oddColumnCountCenterIsExactlyZero() {
        assertEquals(0f, BoardPan.panOf(0, 3, 8, 7), 0f);
        assertEquals(0f, BoardPan.panOf(4, 4, 9, 9), 0f);
    }

    /** 最左列取满负、最右列取满正，端点绝对值恰为 1。 */
    @Test
    public void outerColumnsAreFullSwingInBothDirections() {
        assertEquals(-1f, BoardPan.panOf(0, 0, 8, 7), 0f);
        assertEquals(1f, BoardPan.panOf(0, 6, 8, 7), 0f);
        assertEquals(-1f, BoardPan.panOf(2, 0, 8, 8), 0f);
        assertEquals(1f, BoardPan.panOf(2, 7, 8, 8), 0f);
    }

    /** 偶数列没有正中列：最左最右必须对称，中间两列也必须互为相反数。 */
    @Test
    public void evenColumnCountIsSymmetricEndToEnd() {
        assertEquals(-0.142857f, BoardPan.panOf(0, 3, 8, 8), 1e-6f);
        assertEquals(0.142857f, BoardPan.panOf(0, 4, 8, 8), 1e-6f);
        assertEquals(-0.6f, BoardPan.panOf(0, 1, 6, 6), 1e-6f);
        assertEquals(0.6f, BoardPan.panOf(0, 4, 6, 6), 1e-6f);
        assertEquals(-0.2f, BoardPan.panOf(0, 2, 6, 6), 1e-6f);
        assertEquals(0.2f, BoardPan.panOf(0, 3, 6, 6), 1e-6f);
    }

    /** 列越界是未知，不是正中间：必须给 NaN。 */
    @Test
    public void outOfRangeColumnReturnsNaN() {
        assertTrue(Float.isNaN(BoardPan.panOf(0, -1, 8, 8)));
        assertTrue(Float.isNaN(BoardPan.panOf(0, 8, 8, 8)));
        assertTrue(Float.isNaN(BoardPan.panOf(3, 99, 8, 8)));
    }

    /** 单列盘／无列盘无从推断左右，必须给 NaN。 */
    @Test
    public void singleColumnOrNarrowerBoardReturnsNaN() {
        assertTrue(Float.isNaN(BoardPan.panOf(0, 0, 8, 1)));
        assertTrue(Float.isNaN(BoardPan.panOf(0, 0, 8, 0)));
        assertTrue(Float.isNaN(BoardPan.panOf(0, 0, 8, -3)));
    }

    /** 行数不合法（<=0）时整块盘不可信，必须给 NaN。 */
    @Test
    public void nonPositiveRowCountReturnsNaN() {
        assertTrue(Float.isNaN(BoardPan.panOf(0, 2, 0, 9)));
        assertTrue(Float.isNaN(BoardPan.panOf(0, 2, -1, 9)));
    }

    /** 左右映射只看列：行数取多取少、同一列取哪一行，pan 都一样。 */
    @Test
    public void rowCountDoesNotAffectHorizontalMapping() {
        assertEquals(-0.5f, BoardPan.panOf(0, 2, 3, 9), 1e-6f);
        assertEquals(-0.5f, BoardPan.panOf(0, 2, 12, 9), 1e-6f);
        assertEquals(-0.5f, BoardPan.panOf(0, 2, 8, 9), 1e-6f);
        assertEquals(-0.5f, BoardPan.panOf(7, 2, 8, 9), 1e-6f);
        assertEquals(-0.5f, BoardPan.panOf(5, 2, 8, 9), 1e-6f);
    }

    /** 从左到右单调变正，且绝对值恒不超过 1。 */
    @Test
    public void panIsMonotonicAndBounded() {
        for (int cols = 2; cols <= 9; cols++) {
            float previous = -2f;
            for (int col = 0; col < cols; col++) {
                float pan = BoardPan.panOf(0, col, 8, cols);
                assertTrue(Float.isFinite(pan));
                assertTrue(Math.abs(pan) <= 1f);
                assertTrue(pan > previous);
                previous = pan;
            }
            assertEquals(1f, previous, 0f);
        }
    }

    /** 方位词必须与 Match3Coach.quadrantOf 对同一格一致，本类不另造词表。 */
    @Test
    public void quadrantWordsComeFromCoachVocabulary() {
        assertEquals("左上区域", BoardPan.quadrantSpeech(0, 0, 8, 8));
        assertEquals("右上区域", BoardPan.quadrantSpeech(0, 7, 8, 8));
        assertEquals("左下区域", BoardPan.quadrantSpeech(7, 0, 8, 8));
        assertEquals("右下区域", BoardPan.quadrantSpeech(7, 7, 8, 8));
        for (int row = 0; row < 8; row++) {
            for (int col = 0; col < 8; col++) {
                assertEquals(Match3Coach.quadrantOf(row, col, 8, 8) + "区域",
                        BoardPan.quadrantSpeech(row, col, 8, 8));
            }
        }
    }

    /** 非法输入时不猜方位：方位说明给 null，交由调用方决定怎么说。 */
    @Test
    public void quadrantSpeechStaysSilentForIllegalInput() {
        assertNull(BoardPan.quadrantSpeech(0, 0, 8, 1));
        assertNull(BoardPan.quadrantSpeech(0, 8, 8, 8));
        assertNull(BoardPan.quadrantSpeech(0, 2, 0, 9));
    }

    /** 中心列按「靠右」归象限（沿用 quadrantOf 的整数除法），pan 仍是 0：本轮不做偏半格插值。 */
    @Test
    public void centerColumnReadsZeroButQuadrantTreatsItAsRightSide() {
        assertEquals(0f, BoardPan.panOf(0, 3, 8, 7), 0f);
        assertEquals("右上区域", BoardPan.quadrantSpeech(0, 3, 8, 7));
    }

    /** 诊断读数文本：有限值给三位小数，非法输入给 unknown，绝不出现 NaN 文本。 */
    @Test
    public void diagnosticReadingIsStableText() {
        assertEquals("-1.000", BoardPan.readingOf(0, 0, 8, 8));
        assertEquals("1.000", BoardPan.readingOf(0, 7, 8, 8));
        assertEquals("0.000", BoardPan.readingOf(4, 3, 8, 7));
        assertEquals("-0.143", BoardPan.readingOf(0, 3, 8, 8));
        assertEquals("unknown", BoardPan.readingOf(0, 0, 8, 1));
        assertEquals("unknown", BoardPan.readingOf(0, 8, 8, 8));
    }
}
