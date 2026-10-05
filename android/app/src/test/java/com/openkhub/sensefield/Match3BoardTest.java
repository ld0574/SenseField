package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

/** 消消乐棋盘纯逻辑测试：三连检测、交换枚举、播报文案（纯 JVM）。 */
public class Match3BoardTest {

    private static final String[][] EIGHT = {
            {"R", "Y", "B", "G", "O", "P", "R", "Y"},
            {"G", "R", "Y", "B", "G", "O", "P", "R"},
            {"B", "G", "R", "R", "R", "G", "O", "P"},
            {"Y", "B", "G", "O", "Y", "B", "G", "O"},
            {"O", "Y", "B", "G", "O", "Y", "B", "G"},
            {"P", "O", "Y", "B", "G", "O", "Y", "B"},
            {"R", "P", "O", "Y", "B", "G", "O", "Y"},
            {"Y", "R", "P", "O", "Y", "B", "G", "O"}
    };

    private static char[][] of(String[][] src) {
        char[][] b = new char[src.length][src[0].length];
        for (int r = 0; r < src.length; r++)
            for (int c = 0; c < src[0].length; c++)
                b[r][c] = src[r][c].charAt(0);
        return b;
    }

    @Test
    public void findsHorizontalRun() {
        List<Match3Board.Run> runs = Match3Board.findRuns(of(EIGHT));
        assertEquals(1, runs.size());
        Match3Board.Run run = runs.get(0);
        assertTrue(run.horizontal);
        assertEquals(2, run.row);
        assertEquals(2, run.col);
        assertEquals(3, run.length);
    }

    @Test
    public void findsVerticalRun() {
        String[][] src = {
                {"R", "B", "Y"},
                {"R", "B", "G"},
                {"R", "Y", "O"},
                {"B", "R", "P"}
        };
        List<Match3Board.Run> runs = Match3Board.findRuns(of(src));
        assertEquals(1, runs.size());
        Match3Board.Run run = runs.get(0);
        assertTrue(!run.horizontal);
        assertEquals(0, run.row);
        assertEquals(0, run.col);
        assertEquals(3, run.length);
    }

    @Test
    public void noRunsWhenBoardClean() {
        String[][] src = {
                {"R", "Y", "B", "G"},
                {"Y", "B", "G", "R"},
                {"B", "G", "R", "Y"},
                {"G", "R", "Y", "B"}
        };
        assertEquals(0, Match3Board.findRuns(of(src)).size());
        assertEquals(0, Match3Board.findSwaps(of(src)).size());
    }

    @Test
    public void findsSwapThatFormsRun() {
        // 横向交换 [0][0] 的 Y 与 [0][1] 的 R 后，第 0 列变 R R R（第 3 行本就是 R）
        String[][] src = {
                {"Y", "R", "B"},
                {"R", "B", "G"},
                {"R", "Y", "O"}
        };
        List<Match3Board.Swap> swaps = Match3Board.findSwaps(of(src));
        assertEquals(1, swaps.size());
        Match3Board.Swap s = swaps.get(0);
        assertEquals(0, s.fromRow);
        assertEquals(0, s.fromCol);
        assertEquals(0, s.toRow);
        assertEquals(1, s.toCol);
        assertTrue(s.runsFormed >= 1);
        assertEquals("第 1 行，第 1 个和第 2 个交换，可以消除", Match3Board.swapSpeech(s));
    }

    @Test
    public void existingRunsDoNotCountAsSwapMerit() {
        // 棋盘已有一个三连（第 1 行 RRR）；唯一合法的交换是 (1,2)↔(1,3)——它让第 4 列变 BBB。
        // 若"原有三连"被错误记作交换功劳，交换数会远大于 1。
        String[][] src = {
                {"R", "R", "R", "B"},
                {"Y", "G", "B", "Y"},
                {"B", "Y", "G", "B"},
                {"G", "B", "Y", "G"}
        };
        assertEquals(1, Match3Board.findRuns(of(src)).size());
        List<Match3Board.Swap> swaps = Match3Board.findSwaps(of(src));
        assertEquals(1, swaps.size());
        assertEquals(1, swaps.get(0).fromRow);
        assertEquals(2, swaps.get(0).fromCol);
        assertEquals(1, swaps.get(0).toRow);
        assertEquals(3, swaps.get(0).toCol);
    }

    @Test
    public void ignoresSwapOfSameColor() {
        String[][] src = {
                {"R", "R", "Y"},
                {"B", "G", "O"},
                {"Y", "B", "G"}
        };
        // [0][0] 与 [0][1] 同色不可交换；其余交换不形成三连
        assertEquals(0, Match3Board.findSwaps(of(src)).size());
    }

    /** 逐行扫描与点读共用一套词表（Match3Coach.pieceName）：念动物名，弃权格念「未识别」而不是「空」。 */
    @Test
    public void scanSpeechUsesTheSharedAnimalVocabulary() {
        String[][] src = {{"R", "Y"}, {"B", "."}};
        List<String> lines = Match3Board.scanSpeech(of(src));
        assertEquals(2, lines.size());
        assertEquals("第 1 行：红狐狸、小鸡", lines.get(0));
        assertEquals("第 2 行：河马、未识别", lines.get(1));
    }

    /** 玩家学过的特殊棋子（字母池 1-9/a-z）必须能参与三连：旧 isPiece 写死 6 个基础色，特效棋子在走法枚举里等于不存在。 */
    @Test
    public void specialPieceLettersParticipateInRuns() {
        String[][] src = {{"R", "Y", "1", "1", "1", "G", "O", "P"}};
        List<Match3Board.Run> runs = Match3Board.findRuns(of(src));
        assertEquals(1, runs.size());
        assertEquals(2, runs.get(0).col);
        assertEquals(3, runs.get(0).length);
    }

    /** 涉及特殊棋子的交换要能被推荐出来（换完凑成竖三）。 */
    @Test
    public void swapInvolvingSpecialPieceIsProposed() {
        String[][] src = {{"R", "G", "1"}, {"Y", "O", "1"}, {"B", "1", "G"}};
        char[][] board = of(src);
        assertEquals("原盘不该有三连", 0, Match3Board.findRuns(board).size());
        List<Match3Board.Swap> swaps = Match3Board.findSwaps(board);
        boolean found = false;
        for (Match3Board.Swap s : swaps)
            if (s.fromRow == 2 && s.fromCol == 1 && s.toRow == 2 && s.toCol == 2) found = true;
        assertTrue("应推荐第 3 行第 2、3 个交换（把特殊棋子换进竖列）", found);
    }

    /** 空格与未识别永远不算棋子，不许凑成三连或产生假走法。 */
    @Test
    public void emptyAndUnknownNeverCountAsPieces() {
        String[][] src = {{"R", "R", " ", "."}, {"G", "Y", " ", "."}, {"B", "O", " ", "."}};
        char[][] board = of(src);
        assertEquals(0, Match3Board.findRuns(board).size());
        assertEquals(0, Match3Board.findSwaps(board).size());
    }
}
