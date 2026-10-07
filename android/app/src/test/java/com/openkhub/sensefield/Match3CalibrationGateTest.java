package com.openkhub.sensefield;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 标定合理性闸门（修复三）：单格太小的框在几何上不可能圈住整盘，
 *  以前只被「r-l 与 b-t 大于 40 像素」那种形状检查放过，结果 44 像素的格子
 *  把采样点全落在缝隙上，整盘未知、全程静默。闸门挂两处（沿用支＋保存支），
 *  这里钉判据本身。 */
public class Match3CalibrationGateTest {

    private static final int W = 1080;
    private static final int H = 2400;

    /** 下限按屏宽走：1080 宽是 60 像素，与 detectGridCount 自己的弃权线同源；
     *  480 宽的老年机不能拿 60 当硬门槛（那会把有效标定全拒掉）。 */
    @Test
    public void cellFloorScalesWithScreenWidth() {
        assertEquals(60, Match3Sampler.minPlausibleCell(1080));
        assertEquals(80, Match3Sampler.minPlausibleCell(1440));
        assertEquals(40, Match3Sampler.minPlausibleCell(720));
        assertEquals(40, Match3Sampler.minPlausibleCell(480));
        assertEquals(40, Match3Sampler.minPlausibleCell(100));
    }

    /** 真机实测那次坏标定：裁出 356x336，按 8x8 切是 44x42，两边都不到 60。 */
    @Test
    public void rejectsTheRealDeviceBrokenBox() {
        int[] cell = new int[2];
        assertFalse(Match3Sampler.plausibleCalibration(W, H, 0, 0, 33, 14, 8, 8, cell));
        assertArrayEquals(new int[]{44, 42}, cell);
    }

    /** 仓内真机实测的正确框 14/38/87/71（≈788x792）：7x7 与 8x8 都必须放行，
     *  闸门不能紧到把有效标定拒掉。 */
    @Test
    public void acceptsTheMeasuredGoodBox() {
        int[] cell = new int[2];
        assertTrue(Match3Sampler.plausibleCalibration(W, H, 14, 38, 87, 71, 7, 7, cell));
        assertEquals(112, cell[0]);
        assertEquals(113, cell[1]);
        assertTrue(Match3Sampler.plausibleCalibration(W, H, 14, 38, 87, 71, 8, 8, cell));
        assertEquals(98, cell[0]);
        assertEquals(99, cell[1]);
        assertTrue(Match3Sampler.plausibleCalibration(W, H, 14, 38, 87, 71, 9, 9, cell));
    }

    /** 单边不足也必须拦：横向够宽、纵向压扁的框照样读不出整盘。 */
    @Test
    public void rejectsWhenEitherSideIsTooSmall() {
        int[] cell = new int[2];
        assertFalse(Match3Sampler.plausibleCalibration(W, H, 0, 0, 80, 3, 8, 8, cell));
        assertTrue(cell[0] >= 60);
        assertTrue(cell[1] < 60);
        assertFalse(Match3Sampler.plausibleCalibration(W, H, 0, 0, 8, 80, 8, 8, cell));
        assertTrue(cell[0] < 60);
    }

    /** 小屏上的有效标定不能被误伤：720x1280、默认框 4/18/96/82 按 8x8 是放行还是拦截，
     *  按下限 40 判定（该框裁出 662x819，单格 82x102，远高于 40）。 */
    @Test
    public void smallScreenDefaultBoxStillPasses() {
        int[] cell = new int[2];
        assertTrue(Match3Sampler.plausibleCalibration(720, 1280, 4, 18, 96, 82, 8, 8, cell));
        assertTrue(cell[0] >= Match3Sampler.minPlausibleCell(720));
        assertTrue(cell[1] >= Match3Sampler.minPlausibleCell(720));
    }

    /** 最宽的格数也救不回来：同一块坏框即使按 6x6（下限档最疏的一格数）切，
     *  单格 59x56 仍双双不足 60——闸门不会因为「换个格数试试」而漏放。 */
    @Test
    public void coarsestGridCountCannotRescueBrokenBox() {
        int[] cell = new int[2];
        assertFalse(Match3Sampler.plausibleCalibration(W, H, 0, 0, 33, 14, 6, 6, cell));
        assertArrayEquals(new int[]{59, 56}, cell);
    }

    /** cellOut 传 null 不许崩（日志支路只想要布尔判据时不必分配数组）。 */
    @Test
    public void nullCellOutIsAllowed() {
        assertTrue(Match3Sampler.plausibleCalibration(W, H, 14, 38, 87, 71, 8, 8, null));
    }

    @Test
    public void rejectsBoundsOutsideTheCapturedScreen() {
        assertFalse(Match3Sampler.plausibleCalibration(W, H, -10, 10, 90, 80, 8, 8, null));
        assertFalse(Match3Sampler.plausibleCalibration(W, H, 10, -10, 90, 80, 8, 8, null));
        assertFalse(Match3Sampler.plausibleCalibration(W, H, 10, 10, 110, 80, 8, 8, null));
        assertFalse(Match3Sampler.plausibleCalibration(W, H, 10, 10, 90, 110, 8, 8, null));
    }
}
