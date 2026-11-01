package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * Hand-written expectations for {@link BoardSemantics}. Every expected label and every expected
 * edge coordinate below is a literal I typed after reading {@code HintLabel.ofBoard},
 * {@code Match3Coach.quadrantOf} and {@code BoardGeometry.cellLeft/cellTop} — none of them is
 * produced by the code under test at run time. The board is deliberately 8x8 with a left/right
 * span of 980 px, which 8 does not divide evenly, so the pixel-box cases also pin down that the
 * boxes come from the continuous float mapping instead of {@code cellWidth()} integer division.
 */
public final class BoardSemanticsTest {

    private static final HintLabel.Tier CONFIRMED = HintLabel.Tier.CONFIRMED;
    private static final HintLabel.Tier PROBABLE = HintLabel.Tier.PROBABLE;
    private static final char[] PIECES = {'R', 'O', 'Y', 'G', 'B', 'P'};

    /** 1260x2800 frame, board bounds 140,900..1120,1880: spans of 980 in both directions. */
    private static BoardGeometry geometry(int rows, int cols) {
        return new BoardGeometry(1260, 2800, 140, 900, 1120, 1880, rows, cols);
    }

    private static char[][] grid(int rows, int cols) {
        char[][] board = new char[rows][cols];
        for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) board[r][c] = PIECES[(r + c) % 6];
        return board;
    }

    private static BoardSemantics.Cell at(BoardSemantics.Report report, int row, int col) {
        return report.cells.get(row * report.cols + col);
    }

    private static Match3Board.Swap swap(int fromRow, int fromCol, int toRow, int toCol) {
        return new Match3Board.Swap(fromRow, fromCol, toRow, toCol, 1);
    }

    private static Match3Board.Swap tap(int row, int col) {
        return new Match3Board.Swap(row, col, row, col, 0, 0, 0, 0, new BitSet(),
                Collections.emptyList(), Match3Action.Type.ACTIVATE, "line_horizontal");
    }

    /** 完成条件 1 的第一半：8×8 盘面 ⇒ 描述条数恰为 64。 */
    @Test public void eightByEightBoardProducesExactlySixtyFourDescriptions() {
        BoardSemantics.Report report =
                BoardSemantics.describe(geometry(8, 8), grid(8, 8), Collections.emptyList(), CONFIRMED);
        assertEquals(64, report.size());
        assertEquals(64, report.cells.size());
        assertEquals(8, report.rows);
        assertEquals(8, report.cols);
        assertEquals(0, report.skippedOutsideGrid);
        for (int index = 0; index < 64; index++) {
            BoardSemantics.Cell cell = report.cells.get(index);
            assertEquals(index / 8, cell.row);
            assertEquals(index % 8, cell.col);
        }
    }

    /** 六格盘面（2×3）⇒ 六条输出，行优先点名逐条对号。 */
    @Test public void sixCellBoardProducesExactlySixDescriptions() {
        BoardSemantics.Report report =
                BoardSemantics.describe(geometry(2, 3), grid(2, 3), Collections.emptyList(), CONFIRMED);
        assertEquals(6, report.size());
        int[][] expected = {{0, 0}, {0, 1}, {0, 2}, {1, 0}, {1, 1}, {1, 2}};
        for (int index = 0; index < 6; index++) {
            assertEquals("row of record " + index, expected[index][0], report.cells.get(index).row);
            assertEquals("col of record " + index, expected[index][1], report.cells.get(index).col);
        }
    }

    /**
     * 合同 2：一个被点名的单点目标，标签逐字等于 {@code HintLabel.ofBoard(row, col, true, 8, 8, CONFIRMED)}
     * 的既有四象限措辞。这里故意挑不对称的格——行在上半、列在右半，行列一旦被喂反，象限立刻翻面。
     */
    @Test public void singleTapTargetIsNamedByTheExistingTapLabel() {
        List<Match3Board.Swap> hints = Collections.singletonList(tap(1, 6));
        BoardSemantics.Report report =
                BoardSemantics.describe(geometry(8, 8), grid(8, 8), hints, CONFIRMED);
        assertEquals(64, report.size());
        BoardSemantics.Cell cell = at(report, 1, 6);
        assertEquals(1, cell.row);
        assertEquals(6, cell.col);
        assertTrue(cell.hintTarget);
        assertTrue(cell.singleTap);
        assertEquals("右上区域，可点", cell.label);
        assertEquals("棕色动物", cell.pieceName);
        assertFalse(at(report, 0, 0).singleTap);
        assertEquals("", at(report, 6, 1).label);
    }

    /** 合同 2 的交换半边：被点名的两端各得一句既有交换措辞，档位词照既有口径挂在后面。 */
    @Test public void swapTargetIsNamedByTheExistingSwapLabelOnBothEnds() {
        List<Match3Board.Swap> hints = Collections.singletonList(swap(5, 1, 5, 2));
        BoardSemantics.Report report =
                BoardSemantics.describe(geometry(8, 8), grid(8, 8), hints, PROBABLE);
        assertEquals(64, report.size());
        assertEquals("左下区域，交换，可能", at(report, 5, 1).label);
        assertEquals("左下区域，交换，可能", at(report, 5, 2).label);
        assertTrue(at(report, 5, 2).hintTarget);
        assertFalse(at(report, 5, 2).singleTap);
    }

    /** 完成条件 1 的第二半：含未知格与空格时仍 64 条，措辞取既有词表，本类不造第三种说法。 */
    @Test public void unknownAndBlankCellsStillGetTheirOwnRecordWithTheExistingUnknownWord() {
        char[][] board = grid(8, 8);
        board[2][5] = Match3Sampler.UNKNOWN;
        board[7][0] = Match3Sampler.EMPTY_CELL;
        BoardSemantics.Report report =
                BoardSemantics.describe(geometry(8, 8), board, Collections.emptyList(), CONFIRMED);
        assertEquals(64, report.size());
        BoardSemantics.Cell unknownCell = at(report, 2, 5);
        assertEquals('.', unknownCell.piece);
        assertTrue(unknownCell.unknown);
        assertEquals("未识别", unknownCell.pieceName);
        assertEquals("", unknownCell.label);
        BoardSemantics.Cell blankCell = at(report, 7, 0);
        assertEquals(' ', blankCell.piece);
        assertTrue(blankCell.blank);
        assertEquals("空", blankCell.pieceName);
        assertEquals("", blankCell.label);
        assertNotEquals(unknownCell.pieceName, blankCell.pieceName);
        assertFalse(unknownCell.hintTarget);
    }

    /** 合同 2 的反面半边：标签为空的格一条都不许被丢掉。 */
    @Test public void silenceNeverCostsARecord() {
        BoardSemantics.Report emptyTier =
                BoardSemantics.describe(geometry(8, 8), grid(8, 8), Collections.emptyList(),
                        HintLabel.Tier.UNKNOWN);
        assertEquals(64, emptyTier.size());
        for (BoardSemantics.Cell cell : emptyTier.cells) assertEquals("", cell.label);
        BoardSemantics.Report shortBoard =
                BoardSemantics.describe(geometry(8, 8), new char[][]{{'R', 'O'}},
                        Collections.emptyList(), CONFIRMED);
        assertEquals(64, shortBoard.size());
        assertEquals("矩阵覆盖不到的格按本层没有读数补，不猜一颗棋子", "未识别",
                at(shortBoard, 8 - 1, 8 - 1).pieceName);
    }

    /** 完成条件 1 的第三半：行列越界的输入不抛异常，整条跳过并计数，盘面条数不受影响。 */
    @Test public void rowOrColumnOutsideTheGridIsSkippedAndCountedInsteadOfThrowing() {
        List<Match3Board.Swap> hints = new ArrayList<>();
        hints.add(swap(9, 1, 9, 2));
        hints.add(swap(-1, 0, -1, 1));
        hints.add(swap(2, 8, 2, 9));
        hints.add(swap(0, 0, 0, 1));
        hints.add(null);
        BoardSemantics.Report report =
                BoardSemantics.describe(geometry(8, 8), grid(8, 8), hints, CONFIRMED);
        assertEquals(64, report.size());
        assertEquals(4, report.skippedOutsideGrid);
        assertEquals("左上区域，交换", at(report, 0, 0).label);
        assertEquals("左上区域，交换", at(report, 0, 1).label);
        assertEquals("被跳过的那条走法什么也不留下，越界那一行没有留下任何点名",
                "", at(report, 2, 7).label);
        assertFalse(at(report, 2, 7).hintTarget);
    }

    /** 合同 3：像素框走连续映射，首格左边界＝left、末格右边界＝right，末行下边界＝bottom。 */
    @Test public void boxesUseTheContinuousMappingAndEndExactlyOnTheBoardEdges() {
        BoardSemantics.Report report =
                BoardSemantics.describe(geometry(8, 8), grid(8, 8), Collections.emptyList(), CONFIRMED);
        BoardSemantics.Cell first = at(report, 0, 0);
        assertEquals(140f, first.left, 0f);
        assertEquals(900f, first.top, 0f);
        assertEquals(262.5f, first.right, 0f);
        assertEquals(1022.5f, first.bottom, 0f);
        assertEquals(262.5f, at(report, 0, 1).left, 0f);
        BoardSemantics.Cell lastColumn = at(report, 0, 7);
        assertEquals(997.5f, lastColumn.left, 0f);
        assertEquals("整型除法递推在这里会得 1116，越靠右偏越多", 1120f, lastColumn.right, 0f);
        BoardSemantics.Cell lastRow = at(report, 7, 0);
        assertEquals(1757.5f, lastRow.top, 0f);
        assertEquals(1880f, lastRow.bottom, 0f);
        float covered = 0f;
        for (int col = 0; col < 7; col++) {
            BoardSemantics.Cell cell = at(report, 0, col);
            covered += cell.right - cell.left;
            assertEquals("相邻两格不许留缝也不许重叠", cell.right, at(report, 0, col + 1).left, 0f);
        }
        BoardSemantics.Cell tail = at(report, 0, 7);
        covered += tail.right - tail.left;
        assertEquals(980f, covered, 0f);
        assertEquals("整型除法递推的八格总宽比真实跨度短 4 像素，这就是它不能用来铺像素框的原因",
                976, geometry(8, 8).cellWidth() * 8);
    }

    /** 合同 2 的可执行形式：非空标签只能是既有四象限措辞，且一律不含数字与坐标形状。 */
    @Test public void noDescriptionEverInventsWordingOrCarriesACoordinateShape() {
        List<Match3Board.Swap> hints = Arrays.asList(
                tap(0, 0), swap(0, 7, 0, 6), swap(7, 0, 6, 0), tap(6, 6), swap(3, 4, 4, 4));
        Set<String> allowed = new HashSet<>(Arrays.asList(
                "左上区域，可点", "左上区域，交换", "右上区域，可点", "右上区域，交换",
                "左下区域，可点", "左下区域，交换", "右下区域，可点", "右下区域，交换",
                "左上区域，可点，可能", "左上区域，交换，可能", "右上区域，可点，可能", "右上区域，交换，可能",
                "左下区域，可点，可能", "左下区域，交换，可能", "右下区域，可点，可能", "右下区域，交换，可能",
                "左上区域，可点，拿不准", "左上区域，交换，拿不准", "右上区域，可点，拿不准", "右上区域，交换，拿不准",
                "左下区域，可点，拿不准", "左下区域，交换，拿不准", "右下区域，可点，拿不准", "右下区域，交换，拿不准"));
        for (HintLabel.Tier tier : new HintLabel.Tier[]{CONFIRMED, PROBABLE, HintLabel.Tier.UNSURE}) {
            BoardSemantics.Report report =
                    BoardSemantics.describe(geometry(8, 8), grid(8, 8), hints, tier);
            assertEquals(64, report.size());
            int named = 0;
            for (BoardSemantics.Cell cell : report.cells) {
                if (cell.label.length() == 0) continue;
                named++;
                assertTrue("unexpected wording: " + cell.label, allowed.contains(cell.label));
                assertEquals(-1, cell.label.indexOf('['));
                assertEquals(-1, cell.label.indexOf(']'));
                assertEquals(-1, cell.label.indexOf('@'));
                for (char digit = '0'; digit <= '9'; digit++) assertEquals(-1, cell.label.indexOf(digit));
            }
            assertEquals(8, named);
        }
    }

    /** 没有盘面读数时出 0 条，手里的走法全部计入跳过数，而不是抛异常。 */
    @Test public void missingGeometryYieldsNoRecordsAndCountsEveryHintAsOutside() {
        List<Match3Board.Swap> hints = Arrays.asList(swap(0, 0, 0, 1), tap(3, 3));
        BoardSemantics.Report report = BoardSemantics.describe(null, grid(8, 8), hints, CONFIRMED);
        assertEquals(0, report.size());
        assertEquals(2, report.skippedOutsideGrid);
        assertEquals(0, report.rows);
        BoardSemantics.Report noHints = BoardSemantics.describe(geometry(8, 8), null, null, CONFIRMED);
        assertEquals(64, noHints.size());
        assertEquals(0, noHints.skippedOutsideGrid);
        assertEquals("未识别", at(noHints, 0, 0).pieceName);
    }
}
