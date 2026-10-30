package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 盘面对象树的纯函数侧：把一盘的读数摊成「一格一条」的描述序列。本类只管数据结构、行列点名与
 * 标签文本，不碰窗口标志、不碰焦点序、不接系统节点树——接线之后读屏的焦点顺序会不会被打乱，
 * 本机判不了，那一半属真机轮。
 *
 * <p>返回合同三条，每条都由 {@code BoardSemanticsTest} 用写死的期望值钉住：
 * <ol>
 *   <li><b>条数恒等于 {@code rows * cols}</b>：8×8 一盘出 64 条，含空格与未知格也各占一条，
 *       不多不少，行优先排列（下标＝{@code row * cols + col}）。条数由 {@link BoardGeometry} 定，
 *       不由盘面字符矩阵定：矩阵多出来的行不进输出，矩阵不足的部分按「本层没有读数」补。
 *       <b>条数上限属接线轮口径，本卷不裁。</b></li>
 *   <li><b>标签文本一字不改地取自 {@link HintLabel#ofBoard}</b>：本类不拼字符串、不另造句式，
 *       四象限与「可点／交换」措辞全部沿用既有词表。本层没有读数的格（空格、未知格、没被任何
 *       走法点名的格）按 {@link HintLabel.Tier#UNKNOWN} 去问它，拿到的就是既有降级得到的空标签；
 *       <b>空标签照样占一条</b>，本条不因标签为空而减少，本类也不自造占位词去填——
 *       占位词属播报文案，改它要走措辞守卫，不是本卷的地界。</li>
 *   <li><b>像素框取自 {@link BoardGeometry#cellLeft(int)}／{@link BoardGeometry#cellTop(int)}
 *       的浮点比例连续映射</b>：第 0 格左边界＝{@code left}、最后一格右边界＝{@code right}，
 *       末行下边界＝{@code bottom}。不走 {@link BoardGeometry#cellWidth()} 整型除法递推，
 *       那种算法越靠右偏得越多。</li>
 * </ol>
 *
 * <p>行列点名以 {@link Cell#row}／{@link Cell#col} 字段给出（0 基，与
 * {@link Match3Board.Swap#fromRow} 与 {@link BoardGeometry#cellAt(int, int)} 同口径），
 * 而不是拼进句子里：{@link HintLabel} 的「只出语义，不出坐标」口径把任何阿拉伯数字判成坐标形状，
 * 硬把行数列号塞进一句标签，结果是整条标签作废，点名反而没了。
 *
 * <p>未知格的措辞同样不自造：叫法取全项目唯一词表 {@link Match3Coach#pieceName(char)}
 * （未知＝「未识别」，空格＝「空」），判定是否未知走既有谓词 {@link Match3Sampler#isUnknown(char)}，
 * 本类不新增第三种说法。
 *
 * <p>纯 JVM：本类不 import 任何 Android 类型，也不触碰窗口标志与输入面。
 * 行列越界的输入与空引用一律跳过并计数，不抛异常。
 */
final class BoardSemantics {

    /** 一格一条描述：行列点名＋标签文本＋像素框，外加本层对这一格知道到哪一步。 */
    static final class Cell {
        /** 0 基行号：这就是「第几行」，以字段形式给出，不拼进标签。 */
        final int row;
        /** 0 基列号：这就是「第几列」。 */
        final int col;
        /** {@link HintLabel#ofBoard} 的原样返回，可能是既有的空标签。 */
        final String label;
        /** 盘面字符原样。 */
        final char piece;
        /** 既有词表里这一格的叫法（{@link Match3Coach#pieceName(char)}）。 */
        final String pieceName;
        /** 本层对这一格没有读数：字符判为未知，或矩阵压根没覆盖到这格。 */
        final boolean unknown;
        /** 这一格是空格（既有空格哨兵）。 */
        final boolean blank;
        /** 这一格被某条走法点名。 */
        final boolean hintTarget;
        /** 这一格被点名为单点目标；判据与 {@link HintLabel#from} 用的是同一个动作类型比较。 */
        final boolean singleTap;
        /** 像素框，浮点连续映射所得。 */
        final float left, top, right, bottom;

        Cell(int row, int col, String label, char piece, String pieceName, boolean unknown,
                boolean blank, boolean hintTarget, boolean singleTap,
                float left, float top, float right, float bottom) {
            this.row = row;
            this.col = col;
            this.label = label;
            this.piece = piece;
            this.pieceName = pieceName;
            this.unknown = unknown;
            this.blank = blank;
            this.hintTarget = hintTarget;
            this.singleTap = singleTap;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        @Override public String toString() {
            return "row=" + row + " col=" + col + " label=" + label;
        }
    }

    /** 一盘的描述序列＋被跳过条数；{@code cells} 的条数就是合同里的 {@code rows * cols}。 */
    static final class Report {
        final List<Cell> cells;
        /** 行列落在盘面之外（或压根没有格序）而被跳过的输入条数。 */
        final int skippedOutsideGrid;
        final int rows, cols;

        Report(List<Cell> cells, int skippedOutsideGrid, int rows, int cols) {
            this.cells = Collections.unmodifiableList(new ArrayList<>(cells));
            this.skippedOutsideGrid = skippedOutsideGrid;
            this.rows = rows;
            this.cols = cols;
        }

        int size() {
            return cells.size();
        }
    }

    private BoardSemantics() {
    }

    /**
     * 把一盘摊成逐格描述。
     *
     * @param geometry 盘面几何；为 {@code null} 时本层没有盘面读数，出 0 条，所有提示条目计入跳过数
     * @param board    盘面字符矩阵；为 {@code null} 或比盘面小时，不足的那些格按「本层没有读数」处理
     * @param hints    走法读数（单点与交换都在这张表里）；为 {@code null} 视为本层没有任何点名
     * @param tier     本层读数档位；只发给「有读数且被点名」的格，其余按 {@link HintLabel.Tier#UNKNOWN} 问
     * @return 条数恒为 {@code rows * cols} 的报告，行优先
     */
    static Report describe(BoardGeometry geometry, char[][] board, List<Match3Board.Swap> hints,
            HintLabel.Tier tier) {
        int rows = geometry == null ? 0 : geometry.rows;
        int cols = geometry == null ? 0 : geometry.cols;

        boolean[] tapTargets = new boolean[rows * cols];
        boolean[] swapTargets = new boolean[rows * cols];
        int skipped = 0;
        if (hints != null) {
            for (Match3Board.Swap swap : hints) {
                if (swap == null || !inside(swap, rows, cols)) {
                    skipped++;
                    continue;
                }
                int from = swap.fromRow * cols + swap.fromCol;
                if (swap.actionType == Match3Action.Type.ACTIVATE) {
                    tapTargets[from] = true;
                } else {
                    swapTargets[from] = true;
                    swapTargets[swap.toRow * cols + swap.toCol] = true;
                }
            }
        }

        List<Cell> cells = new ArrayList<>(rows * cols);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                char piece = pieceAt(board, row, col);
                boolean unknown = Match3Sampler.isUnknown(piece);
                boolean blank = piece == Match3Sampler.EMPTY_CELL;
                int index = row * cols + col;
                boolean singleTap = tapTargets[index];
                boolean target = singleTap || swapTargets[index];
                HintLabel.Tier cellTier = target && !unknown && !blank ? tier : HintLabel.Tier.UNKNOWN;
                cells.add(new Cell(row, col,
                        HintLabel.ofBoard(row, col, singleTap, rows, cols, cellTier),
                        piece, Match3Coach.pieceName(piece), unknown, blank, target, singleTap,
                        boxLeft(geometry, col, cols), boxTop(geometry, row, rows),
                        boxRight(geometry, col, cols), boxBottom(geometry, row, rows)));
            }
        }
        return new Report(cells, skipped, rows, cols);
    }

    /** 四个格序都得落在盘面里，否则整条输入一起跳过——半条读数不进对象树。 */
    private static boolean inside(Match3Board.Swap swap, int rows, int cols) {
        return swap.fromRow >= 0 && swap.fromRow < rows && swap.fromCol >= 0 && swap.fromCol < cols
                && swap.toRow >= 0 && swap.toRow < rows && swap.toCol >= 0 && swap.toCol < cols;
    }

    /** 矩阵覆盖不到的格＝本层没有这个读数，用既有未知哨兵补，不猜一颗棋子出来。 */
    private static char pieceAt(char[][] board, int row, int col) {
        if (board == null || row >= board.length) return Match3Sampler.UNKNOWN;
        char[] line = board[row];
        if (line == null || col >= line.length) return Match3Sampler.UNKNOWN;
        return line[col];
    }

    private static float boxLeft(BoardGeometry geometry, int col, int cols) {
        if (geometry == null) return 0f;
        return col < cols ? geometry.cellLeft(col) : geometry.right;
    }

    private static float boxTop(BoardGeometry geometry, int row, int rows) {
        if (geometry == null) return 0f;
        return row < rows ? geometry.cellTop(row) : geometry.bottom;
    }

    /** 右边界＝下一格的左边界，末列直接钳到 {@code right}，所以最后一格不会因为整型除法差那一截跑偏。 */
    private static float boxRight(BoardGeometry geometry, int col, int cols) {
        if (geometry == null) return 0f;
        return col + 1 < cols ? geometry.cellLeft(col + 1) : geometry.right;
    }

    private static float boxBottom(BoardGeometry geometry, int row, int rows) {
        if (geometry == null) return 0f;
        return row + 1 < rows ? geometry.cellTop(row + 1) : geometry.bottom;
    }
}
