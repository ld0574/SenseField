package com.openkhub.sensefield;

import static org.junit.Assert.*;
import org.junit.Test;

public final class Match3HintGuideTest {
    @Test public void speechDistinguishesEightDirectionsAndTheCentreWithoutCountingCells() {
        String[][] directions = {{"左上方", "上方", "右上方"}, {"左侧", "中间", "右侧"},
                {"左下方", "下方", "右下方"}};
        int[] rows = {0, 4, 8}, cols = {0, 4, 7};
        for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
            Match3Board.Swap swap = new Match3Board.Swap(rows[r], cols[c], rows[r], cols[c] + 1, 3);
            assertEquals(directions[r][c], Match3HintGuide.direction(swap, 9, 9));
            String speech = Match3Coach.swapSpeechWithDirection(swap, 9, 9, null);
            assertTrue(speech.startsWith(directions[r][c] + "可以消除"));
            assertTrue(speech.endsWith("左右交换"));
            assertFalse(speech.contains("行")); assertFalse(speech.contains("列"));
        }
        assertTrue(Match3Coach.swapSpeechWithDirection(new Match3Board.Swap(7, 8, 8, 8, 3), 9, 9, null)
                .endsWith("上下交换"));
    }

    @Test public void spokenAnimalNamesRemainUsefulWithoutAnOverlay() {
        Match3Position position = new Match3Position(new Match3Position.Cell[][]{
                {Match3Position.Cell.animal('G'), Match3Position.Cell.animal('R')},
                {Match3Position.Cell.bird(), Match3Position.Cell.animal('B')}});
        assertEquals("上方可以消除，青蛙和红狐狸左右交换", Match3Coach.swapSpeechWithDirection(
                new Match3Board.Swap(0, 0, 0, 1, 3), 2, 2, position));
        assertEquals("下方可以消除，魔力鸟和河马左右交换", Match3Coach.swapSpeechWithDirection(
                new Match3Board.Swap(1, 0, 1, 1, 3), 2, 2, position));
    }

    @Test public void clickingASpecialNeverAsksThePlayerToExchangeItWithItself() {
        Match3Board.Swap activate = new Match3Board.Swap(0, 0, 0, 0, 0, 0, 0, 0,
                new java.util.BitSet(), java.util.Collections.emptyList(), Match3Action.Type.ACTIVATE, "line");
        String speech = Match3Coach.swapSpeechWithDirection(activate, 9, 9, null);
        assertEquals("左上方可以消除，点击亮框内的棋子", speech);
        assertFalse(speech.contains("交换"));
    }

    @Test public void guidesStartAtTheBoardCentreAndStopBeforeTheTargetAcrossRectangularBoards() {
        for (int rows = 6; rows <= 9; rows++) for (int cols = 6; cols <= 9; cols++) {
            BoardGeometry g = new BoardGeometry(1220, 2712, 35, 900, 1185, 2050, rows, cols);
            for (int row = 0; row < rows; row++) for (int col = 0; col < cols; col++) {
                if (col + 1 < cols) checkGuide(g, new Match3Board.Swap(row, col, row, col + 1, 3));
                if (row + 1 < rows) checkGuide(g, new Match3Board.Swap(row, col, row + 1, col, 3));
            }
        }
    }

    private static void checkGuide(BoardGeometry g, Match3Board.Swap swap) {
        Match3HintGuide guide = Match3HintGuide.forHint(new Match3Hint("test", 1, 1, g, swap));
        Match3Board.Swap reversed = new Match3Board.Swap(swap.toRow, swap.toCol, swap.fromRow, swap.fromCol, 3);
        assertEquals(Match3HintGuide.direction(swap, g.rows, g.cols), Match3HintGuide.direction(reversed, g.rows, g.cols));
        Match3HintGuide other = Match3HintGuide.forHint(new Match3Hint("test", 2, 2, g, reversed));
        if (guide == null) { assertNull(other); return; }
        assertNotNull(other);
        assertEquals((g.left + g.right) / 2f, guide.startX, 0);
        assertEquals((g.top + g.bottom) / 2f, guide.startY, 0);
        assertEquals(guide.endX, other.endX, .001f); assertEquals(guide.endY, other.endY, .001f);
        assertTrue(Float.isFinite(guide.endX) && Float.isFinite(guide.endY));
        assertTrue(guide.endX > g.left && guide.endX < g.right && guide.endY > g.top && guide.endY < g.bottom);
        float left = g.cellLeft(Math.min(swap.fromCol, swap.toCol)), right = g.cellLeft(Math.max(swap.fromCol, swap.toCol) + 1);
        float top = g.cellTop(Math.min(swap.fromRow, swap.toRow)), bottom = g.cellTop(Math.max(swap.fromRow, swap.toRow) + 1);
        assertTrue("The guide must not cover the target animals", guide.endX < left || guide.endX > right
                || guide.endY < top || guide.endY > bottom);
        float dx = (left + right) / 2 - guide.startX, dy = (top + bottom) / 2 - guide.startY;
        assertTrue("The arrow must point towards the target", dx * (guide.endX - guide.startX) + dy * (guide.endY - guide.startY) > 0);
    }
}
