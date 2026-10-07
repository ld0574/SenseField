package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 门序重排（修复二）的判据测试：「读不读得出」与「要不要出声」这两条线必须能在
 *  没有 Android framework 的情况下靠喂矩阵复现——真机那次失效模式（坏盘被首播双重
 *  确认拦下 → 全程静默）只存在于决策层，不在 Bitmap 层。 */
public class Match3UnreadableGateTest {

    /** rows x cols 盘，前 unknown 格填「认不出」，其余填基础色。 */
    private static char[][] board(int rows, int cols, int unknown) {
        char[][] m = new char[rows][cols];
        int left = unknown;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                m[r][c] = left > 0 ? Match3Sampler.UNKNOWN : 'R';
                if (left > 0) left--;
            }
        }
        return m;
    }

    /** 40% 线钉死：64 格里 26 格读不出（40.6%）算坏盘，25 格（39.06%）不算。 */
    @Test
    public void unreadableThresholdSitsJustAboveFortyPercent() {
        assertTrue(Match3LiveService.isUnreadableBoard(board(8, 8, 26)));
        assertFalse(Match3LiveService.isUnreadableBoard(board(8, 8, 25)));
        assertFalse(Match3LiveService.isUnreadableBoard(board(8, 8, 0)));
        assertTrue(Match3LiveService.isUnreadableBoard(board(8, 8, 64)));
    }

    /** 空格与未识别是两个词，但都算「这一格读不出」——自我修复与出声闸门共用口径。 */
    @Test
    public void emptyAndUnknownBothCountAsUnreadable() {
        char[][] m = board(6, 6, 0);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 6; c++) {
                m[r][c] = r % 2 == 0 ? Match3Sampler.EMPTY_CELL : Match3Sampler.UNKNOWN;
            }
        }
        assertTrue(Match3LiveService.isUnreadableBoard(m));
    }

    /** 三帧每帧都在漂时，多数票把整盘判成未定——坏盘因此一定触发闸门，不会漏到播报里。 */
    @Test
    public void driftingFramesCollapseToUnknownBoard() {
        char[][][] window = new char[3][][];
        for (int f = 0; f < 3; f++) {
            window[f] = new char[4][4];
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) {
                    window[f][r][c] = (char) ('A' + (r * 4 + c + f * 3) % 26);
                }
            }
        }
        assertTrue(Match3LiveService.isUnreadableBoard(Match3LiveService.majorityMatrix(window)));
    }

    /** 出声要连续两个稳定窗：单窗坏盘只计数、不开口（关卡开场掉落不该被念成认不出）。 */
    @Test
    public void singleBadWindowDoesNotSpeak() {
        assertFalse(Match3LiveService.shouldAnnounceUnreadable(0, false, 60_000));
        assertFalse(Match3LiveService.shouldAnnounceUnreadable(1, false, 60_000));
    }

    /** 两窗坏盘＋没播过＋过了最小间隔才播；三条里任一条不满足就不播。 */
    @Test
    public void twoBadWindowsSpeakOnceOnly() {
        assertTrue(Match3LiveService.shouldAnnounceUnreadable(2, false, 6_000));
        assertFalse(Match3LiveService.shouldAnnounceUnreadable(2, false, 5_999));
        assertFalse(Match3LiveService.shouldAnnounceUnreadable(2, true, 60_000));
        assertTrue(Match3LiveService.shouldAnnounceUnreadable(5, false, 60_000));
    }

    /** 按生产代码的判据逐窗走一遍坏盘：第 2 窗必须出声，第 3、4 窗不许重复播。 */
    @Test
    public void badBoardSpeaksInSecondWindowAndStaysQuietAfter() {
        int streak = 0;
        boolean announced = false;
        int spoken = 0;
        for (int win = 1; win <= 4; win++) {
            if (Match3LiveService.isUnreadableBoard(board(8, 8, 40))) {
                streak++;
                if (Match3LiveService.shouldAnnounceUnreadable(streak, announced, 60_000)) {
                    announced = true;
                    spoken++;
                }
            }
        }
        assertEquals("坏盘在第 2 窗就该出声（否则又回到永久静默）", 1, spoken);
        assertEquals(4, streak);
    }

    /** 开场动画两窗未知、第三窗起读得出：闸门必须放行，局面播报才接得上。 */
    @Test
    public void readableBoardOpensTheGateAgain() {
        assertTrue(Match3LiveService.isUnreadableBoard(board(8, 8, 40)));
        assertFalse(Match3LiveService.isUnreadableBoard(board(8, 8, 10)));
        assertEquals(10, Match3LiveService.countUnknown(board(8, 8, 10)));
    }
}
