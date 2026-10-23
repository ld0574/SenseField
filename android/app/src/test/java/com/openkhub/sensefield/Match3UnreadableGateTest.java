package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Readability is independent of whether a cell contains a movable animal. */
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

    /** Known empty cells are readable; only the truly unknown cells consume the 40% budget. */
    @Test
    public void knownEmptyCellsDoNotConsumeTheUnknownBudget() {
        char[][] m = board(6, 6, 0);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 6; c++) {
                m[r][c] = r % 2 == 0 ? Match3Sampler.EMPTY_CELL : Match3Sampler.UNKNOWN;
            }
        }
        assertEquals(6, Match3LiveService.countUnknown(m));
        assertFalse(Match3LiveService.isUnreadableBoard(m));
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

    /** 开场动画两窗未知、第三窗起读得出：闸门必须放行，局面播报才接得上。 */
    @Test
    public void readableBoardOpensTheGateAgain() {
        assertTrue(Match3LiveService.isUnreadableBoard(board(8, 8, 40)));
        assertFalse(Match3LiveService.isUnreadableBoard(board(8, 8, 10)));
        assertEquals(10, Match3LiveService.countUnknown(board(8, 8, 10)));
    }

    @Test public void excludedSurfaceCellsCannotBecomeMovableRunsOrExchanges() {
        char[][] m = board(9, 9, 0);
        for (int row = 0; row < 9; row++) java.util.Arrays.fill(m[row], Match3Sampler.NON_SWAP_CELL);
        assertFalse(Match3LiveService.isUnreadableBoard(m));
        assertEquals(0, Match3LiveService.countUnknown(m));
        assertTrue(Match3Board.findRuns(m).isEmpty());
        assertTrue(Match3Board.findSwaps(m).isEmpty());
        assertEquals("非普通棋子区域", Match3Coach.pieceName(Match3Sampler.NON_SWAP_CELL));
    }
}
