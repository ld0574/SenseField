package com.openkhub.sensefield;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * 目标相关性进排序的独立用例。棋盘与期望顺序均为手写，不由 Match3MoveRanker 的排序结果反推。
 * 'H' 是棋盘空位（不可交换、不成三连），用来把棋盘隔离成只有指定的候选走法。
 */
public final class Match3GoalRankingTest {

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

    private static Match3GoalStrip.Snapshot cards(char... kinds) { return Match3GoalStrip.read(kinds); }

    /** 横向四连（不是本关目标）+ 横向三连 R + 横向三连 B。 */
    private static final String[] PLAIN = {
            "HHHHHH", "RRYRHH", "HHRHHH", "HHHHHH", "BBYGHH", "HHBHHH"};
    /** 同一盘，只在四连与三连 R 的下方各垫两格冰，局部首选改成靠冰邻接取胜。 */
    private static final String[] ICY = {
            "HHHHHH", "RRYRHH", "IIRRHH", "HHHHHH", "BBYGHH", "HHBHHH"};
    /** 竖向四连 R + 横向三连 B。 */
    private static final String[] VERTICAL = {
            "HHHHHHH", "HRHHHHH", "HRHHHHH", "HYHHHHH", "HRHHHHH", "HHHHHHH", "BBYGHHH", "HHBHHHH"};

    private static final List<String> PLAIN_LOCAL_FIRST =
            Arrays.asList("1,2:2,2", "1,2:1,3", "4,2:5,2");
    private static final List<String> PLAIN_GOAL_FIRST =
            Arrays.asList("4,2:5,2", "1,2:2,2", "1,2:1,3");

    @Test
    public void plainBoardHasExactlyTheThreeHandCheckedMoves() {
        assertEquals(PLAIN_LOCAL_FIRST, order(Match3MoveRanker.rankedSwaps(board(PLAIN))));
        assertEquals(3, Match3Board.findSwaps(board(PLAIN)).size());
    }

    @Test
    public void collectBearTurnsTheThreeRunAheadOfTheUncollectedFourRun() {
        List<Match3Board.Swap> ranked = Match3MoveRanker.rankedSwaps(board(PLAIN), cards('B'));
        assertEquals(PLAIN_GOAL_FIRST, order(ranked));
        assertEquals("4,2:5,2", order(ranked).get(0));
        assertEquals("1,2:2,2", order(Match3MoveRanker.rankedSwaps(board(PLAIN))).get(0));
    }

    @Test
    public void collectFoxKeepsTheLocalOrderBecauseTheStrongestMoveIsOnGoal() {
        assertEquals(PLAIN_LOCAL_FIRST, order(Match3MoveRanker.rankedSwaps(board(PLAIN), cards('R'))));
    }

    @Test
    public void goalKeyOutranksIceNeighboursOnTheSameBoard() {
        assertEquals(PLAIN_LOCAL_FIRST, order(Match3MoveRanker.rankedSwaps(board(ICY))));
        assertEquals(PLAIN_GOAL_FIRST, order(Match3MoveRanker.rankedSwaps(board(ICY), cards('B'))));
    }

    @Test
    public void verticalStrongMoveYieldsToTheHorizontalGoalRun() {
        assertEquals(Arrays.asList("3,1:4,1", "6,2:7,2"),
                order(Match3MoveRanker.rankedSwaps(board(VERTICAL))));
        assertEquals(Arrays.asList("6,2:7,2", "3,1:4,1"),
                order(Match3MoveRanker.rankedSwaps(board(VERTICAL), cards('B'))));
    }

    @Test
    public void twoGoalCardsWhereOnlyTheSecondOneIsClearableStillReorder() {
        assertEquals(PLAIN_GOAL_FIRST, order(Match3MoveRanker.rankedSwaps(board(PLAIN), cards('G', 'B'))));
    }

    @Test
    public void goalRelevanceDoesNotDropOrInventAnyMove() {
        assertEquals(sorted(order(Match3MoveRanker.rankedSwaps(board(PLAIN)))),
                sorted(order(Match3MoveRanker.rankedSwaps(board(PLAIN), cards('B')))));
    }

    @Test
    public void unrecognizableGoalCardKeepsEveryPositionOfTheLocalOrder() {
        for (String[] rows : new String[][]{PLAIN, ICY, VERTICAL}) {
            char[][] board = board(rows);
            List<String> local = order(Match3MoveRanker.rankedSwaps(board));
            assertEquals(local, order(Match3MoveRanker.rankedSwaps(board, cards('B', '.'))));
            assertEquals(local, order(Match3MoveRanker.rankedSwaps(board, cards('B', 'I'))));
            assertEquals(local, order(Match3MoveRanker.rankedSwaps(board, cards(new char[0]))));
        }
    }

    @Test
    public void evidenceNamesGoalKindsWhenTheStripWasReadable() {
        char[][] board = board(PLAIN);
        Match3GoalStrip.Snapshot goals = cards('B');
        Match3Board.Swap bear = Match3MoveRanker.rankedSwaps(board, goals).get(0);
        Match3Board.Swap fox = Match3MoveRanker.rankedSwaps(board, goals).get(1);
        assertEquals("ice_neighbors=0 longest_run=3 matched_cells=3 goal_cleared=yes"
                + " goal_strip=trusted kinds=B slots=1"
                + " scope=local_one_move_plus_level_goal_kinds", Match3MoveRanker.evidence(bear, board, goals));
        assertTrue(Match3MoveRanker.evidence(fox, board, goals).contains("goal_cleared=no"));
        assertFalse(Match3MoveRanker.evidence(fox, board, goals).contains("unread"));
    }

    @Test
    public void evidenceStillSaysUnreadWhenTheStripCouldNotBeReadAndWhy() {
        char[][] board = board(PLAIN);
        Match3GoalStrip.Snapshot goals = cards('B', '.');
        Match3Board.Swap best = Match3MoveRanker.rankedSwaps(board, goals).get(0);
        assertEquals("ice_neighbors=0 longest_run=4 matched_cells=4"
                + " scope=local_one_move_level_goal_unread"
                + " goal_strip=abstain reason=slot2=unknown slots=2",
                Match3MoveRanker.evidence(best, board, goals));
    }

    /** 旧签名（无目标上下文）的口径串一字未动。 */
    @Test
    public void legacyEvidenceStringIsUnchanged() {
        Match3Board.Swap best = Match3MoveRanker.rankedSwaps(board(PLAIN)).get(0);
        assertEquals("ice_neighbors=0 longest_run=4 matched_cells=4"
                + " scope=local_one_move_level_goal_unread", Match3MoveRanker.evidence(best));
    }

    /**
     * 反向验证的本体：挂牌采点糊掉时，排序必须整体退回局部顺序、诊断必须回到 unread。
     * 一旦读数侧把「认不出的那一格」忽略而不是弃权，这条会红。
     */
    @Test
    public void blurredGoalCardsFallBackToLocalOrderAndSayUnread() {
        char[][] board = board(PLAIN);
        Match3GoalStrip.Snapshot blurred = cards('B', '.');
        assertFalse(blurred.trusted());
        assertEquals(PLAIN_LOCAL_FIRST, order(Match3MoveRanker.rankedSwaps(board, blurred)));
        assertTrue(Match3MoveRanker.evidence(
                Match3MoveRanker.rankedSwaps(board, blurred).get(0), board, blurred)
                .contains("scope=local_one_move_level_goal_unread"));
    }

    private static List<String> sorted(List<String> in) {
        List<String> out = new ArrayList<>(in);
        java.util.Collections.sort(out);
        return out;
    }
}
