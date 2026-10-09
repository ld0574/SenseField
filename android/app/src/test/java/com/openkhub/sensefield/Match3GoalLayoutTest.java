package com.openkhub.sensefield;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * 两套挂牌采点布局（双格先试、三格兜底）的选取规则，以及常量落点的回归钉。
 * 棋盘与期望顺序手写；落点区间取自 27 个诊断包里 20 帧带图语料的实测框（见 PROGRESS.md 清理轮）。
 */
public final class Match3GoalLayoutTest {

    private static char[][] board(String... rows) {
        char[][] board = new char[rows.length][];
        for (int r = 0; r < rows.length; r++) board[r] = rows[r].toCharArray();
        return board;
    }

    private static List<String> order(List<Match3Board.Swap> swaps) {
        List<String> out = new ArrayList<>();
        for (Match3Board.Swap s : swaps) {
            out.add(s.fromRow + "," + s.fromCol + ":" + s.toRow + "," + s.toCol);
        }
        return out;
    }

    /** 横向四连（非目标）+ 横向三连 R + 横向三连 B，与排序用例同盘。 */
    private static final String[] PLAIN = {
            "HHHHHH", "RRYRHH", "HHRHHH", "HHHHHH", "BBYGHH", "HHBHHH"};
    private static final List<String> PLAIN_LOCAL_FIRST =
            Arrays.asList("1,2:2,2", "1,2:1,3", "4,2:5,2");
    private static final List<String> PLAIN_GOAL_FIRST =
            Arrays.asList("4,2:5,2", "1,2:2,2", "1,2:1,3");

    @Test
    public void theTrustedPairIsUsedAndTheTripleIsIgnored() {
        Match3GoalStrip.Snapshot goals =
                Match3GoalStrip.readLayouts(new char[]{'Y', 'B'}, new char[]{'R', 'G', 'B'});
        assertTrue(goals.trusted());
        assertEquals("goal_strip=trusted kinds=YB slots=2", goals.describe());
        assertEquals(Arrays.asList('Y', 'B'), goals.kinds());
    }

    @Test
    public void layoutsAreNeverSplicedTogether() {
        Match3GoalStrip.Snapshot goals =
                Match3GoalStrip.readLayouts(new char[]{'Y', 'B'}, new char[]{'R', 'G', 'B'});
        assertFalse("the triple must not leak kinds into a readable pair", goals.collects('R'));
        assertEquals(2, goals.slotCount());
    }

    @Test
    public void theTripleIsUsedOnlyWhenThePairHasANonAnimalSlot() {
        Match3GoalStrip.Snapshot goals =
                Match3GoalStrip.readLayouts(new char[]{'Y', '.'}, new char[]{'R', 'G', 'B'});
        assertTrue(goals.trusted());
        assertEquals("goal_strip=trusted kinds=RGB slots=3", goals.describe());
    }

    @Test
    public void neitherLayoutMatchedNamesBothFailingSlots() {
        Match3GoalStrip.Snapshot goals =
                Match3GoalStrip.readLayouts(new char[]{'Y', 'I'}, new char[]{'#', 'G', 'B'});
        assertFalse(goals.trusted());
        assertEquals("no_layout_matched pair=slot2=ice triple=slot1=non_swap", goals.reason());
        assertEquals("goal_strip=abstain reason=no_layout_matched pair=slot2=ice"
                + " triple=slot1=non_swap slots=2", goals.describe());
    }

    @Test
    public void bothLayoutsEmptyAbstainsWithoutInventingKinds() {
        Match3GoalStrip.Snapshot goals =
                Match3GoalStrip.readLayouts(new char[0], new char[0]);
        assertFalse(goals.trusted());
        assertEquals("no_layout_matched pair=empty_strip triple=empty_strip", goals.reason());
        assertEquals(0, goals.kinds().size());
    }

    @Test
    public void theTripleLayoutDrivesTheGoalKeyWhenThePairIsUnreadable() {
        Match3GoalStrip.Snapshot goals =
                Match3GoalStrip.readLayouts(new char[]{'Y', '.'}, new char[]{'B', 'Y', 'G'});
        assertEquals(PLAIN_GOAL_FIRST, order(Match3MoveRanker.rankedSwaps(board(PLAIN), goals)));
        assertEquals("4,2:5,2", order(Match3MoveRanker.rankedSwaps(board(PLAIN), goals)).get(0));
    }

    @Test
    public void anUnmatchedLayoutKeepsTheLocalOrderBitForBit() {
        Match3GoalStrip.Snapshot goals =
                Match3GoalStrip.readLayouts(new char[]{'Y', '.'}, new char[]{'.', '.', '.'});
        assertEquals(PLAIN_LOCAL_FIRST, order(Match3MoveRanker.rankedSwaps(board(PLAIN), goals)));
        assertEquals(order(Match3MoveRanker.rankedSwaps(board(PLAIN))),
                order(Match3MoveRanker.rankedSwaps(board(PLAIN), goals)));
    }

    @Test
    public void sampledPointsStayInsideTheMeasuredCardBoxes() {
        assertTrue("pair slot1 must stay in the measured 43.1%..43.6% window",
                Match3GoalStrip.PAIR_X_PER_MILLE[0] >= 431 && Match3GoalStrip.PAIR_X_PER_MILLE[0] <= 436);
        assertTrue("pair slot2 must stay in the measured 56.5%..57.2% window",
                Match3GoalStrip.PAIR_X_PER_MILLE[1] >= 565 && Match3GoalStrip.PAIR_X_PER_MILLE[1] <= 572);
        assertTrue("card band measured at 9.0%..9.8% of frame height",
                Match3GoalStrip.CARD_Y_PER_MILLE >= 90 && Match3GoalStrip.CARD_Y_PER_MILLE <= 98);
    }

    @Test
    public void theTripleIsCentredOnTheSameAxisWithTheMeanCardSpacing() {
        int pairSpacing = Match3GoalStrip.PAIR_X_PER_MILLE[1] - Match3GoalStrip.PAIR_X_PER_MILLE[0];
        assertTrue("measured pair spacing was 12.9%..14.1%", pairSpacing >= 129 && pairSpacing <= 141);
        assertEquals(501, (Match3GoalStrip.TRIPLE_X_PER_MILLE[0]
                + Match3GoalStrip.TRIPLE_X_PER_MILLE[2]) / 2);
        assertEquals(Match3GoalStrip.TRIPLE_X_PER_MILLE[1], 501);
        assertEquals(Match3GoalStrip.TRIPLE_X_PER_MILLE[2] - Match3GoalStrip.TRIPLE_X_PER_MILLE[1],
                Match3GoalStrip.TRIPLE_X_PER_MILLE[1] - Match3GoalStrip.TRIPLE_X_PER_MILLE[0]);
    }
}
