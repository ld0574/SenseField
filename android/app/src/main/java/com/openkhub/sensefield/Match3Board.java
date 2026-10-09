package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.List;

/**
 * 消消乐棋盘纯逻辑：颜色矩阵 → 三连检测 + 相邻交换枚举。
 * 只依赖 char[][]，可在 JVM 单元测试中覆盖（不碰 Bitmap/Android API）。
 * 字母表：R O Y G B P 基础动物；1-9/a-z 玩家学过的外观标签（供点读，不推断交换规则）；
 * ' ' 空格；'.' 未识别。播报叫法一律走 Match3Coach.pieceName，本类不再自带词表；
 * 哪些字符算「一颗棋子」一律走 Match3Sampler.isMovable，本类不另立名单。
 */
final class Match3Board {

    static final class Run {
        final int row;
        final int col;
        final boolean horizontal;
        final int length;

        Run(int row, int col, boolean horizontal, int length) {
            this.row = row;
            this.col = col;
            this.horizontal = horizontal;
            this.length = length;
        }
    }

    static final class Swap {
        final int fromRow;
        final int fromCol;
        final int toRow;
        final int toCol;
        final int runsFormed;

        Swap(int fromRow, int fromCol, int toRow, int toCol, int runsFormed) {
            this.fromRow = fromRow;
            this.fromCol = fromCol;
            this.toRow = toRow;
            this.toCol = toCol;
            this.runsFormed = runsFormed;
        }
    }

    private Match3Board() {
    }

    /** Only confirmed basic animals carry ordinary color-matching/swap semantics. */
    static boolean isPiece(char c) {
        return Match3Sampler.isMovable(c);
    }

    /** Invalid/incomplete capture results are not playable boards. */
    static int columns(char[][] board) {
        if (board == null || board.length == 0 || board[0] == null || board[0].length == 0) return 0;
        int cols = board[0].length;
        for (char[] row : board) if (row == null || row.length != cols) return 0;
        return cols;
    }

    /** 横纵两个方向的三连及以上（同一长连只报一次，起点为其最左/最上格）。 */
    static List<Run> findRuns(char[][] board) {
        List<Run> runs = new ArrayList<>();
        int cols = columns(board);
        if (cols == 0) return runs;
        int rows = board.length;
        for (int r = 0; r < rows; r++) {
            int run = 1;
            for (int c = 1; c <= cols; c++) {
                boolean same = c < cols && isPiece(board[r][c]) && board[r][c] == board[r][c - 1];
                if (same) {
                    run++;
                } else {
                    if (run >= 3) runs.add(new Run(r, c - run, true, run));
                    run = 1;
                }
            }
        }
        for (int c = 0; c < cols; c++) {
            int run = 1;
            for (int r = 1; r <= rows; r++) {
                boolean same = r < rows && isPiece(board[r][c]) && board[r][c] == board[r - 1][c];
                if (same) {
                    run++;
                } else {
                    if (run >= 3) runs.add(new Run(r - run, c, false, run));
                    run = 1;
                }
            }
        }
        return runs;
    }

    /** 枚举所有相邻交换，返回交换后能形成三连的走法（按 formedRuns 降序、行优先排序）。 */
    static List<Swap> findSwaps(char[][] board) {
        List<Swap> swaps = new ArrayList<>();
        int cols = columns(board);
        if (cols == 0) return swaps;
        int rows = board.length;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (!isPiece(board[r][c])) continue;
                // 右邻
                if (c + 1 < cols && isPiece(board[r][c + 1]) && board[r][c] != board[r][c + 1]) {
                    addIfForms(swaps, board, r, c, r, c + 1);
                }
                // 下邻
                if (r + 1 < rows && isPiece(board[r + 1][c]) && board[r][c] != board[r + 1][c]) {
                    addIfForms(swaps, board, r, c, r + 1, c);
                }
            }
        }
        swaps.sort((a, b) -> b.runsFormed - a.runsFormed);
        return swaps;
    }

    private static void addIfForms(List<Swap> swaps, char[][] board, int r1, int c1, int r2, int c2) {
        char[][] copy = new char[board.length][];
        for (int i = 0; i < board.length; i++) copy[i] = board[i].clone();
        char tmp = copy[r1][c1];
        copy[r1][c1] = copy[r2][c2];
        copy[r2][c2] = tmp;
        /* 只认涉及被交换两格的新增三连：棋盘上原有的三连不算这次交换的功劳 */
        int formed = 0;
        for (Run run : findRuns(copy)) {
            boolean touches = run.horizontal
                    ? run.row == r1 && c1 >= run.col && c1 < run.col + run.length
                    || run.row == r2 && c2 >= run.col && c2 < run.col + run.length
                    : run.col == c1 && r1 >= run.row && r1 < run.row + run.length
                    || run.col == c2 && r2 >= run.row && r2 < run.row + run.length;
            if (touches) formed++;
        }
        if (formed > 0) {
            swaps.add(new Swap(r1, c1, r2, c2, formed));
        }
    }

    /** 一条可消除走法的播报文案（行/列从 1 数起，与语音习惯一致）。 */
    static String swapSpeech(Swap s) {
        if (s.fromRow == s.toRow) {
            return "第 " + (s.fromRow + 1) + " 行，第 " + (s.fromCol + 1) + " 列和第 "
                    + (s.toCol + 1) + " 列交换";
        }
        return "第 " + (s.fromRow + 1) + " 行和第 " + (s.toRow + 1) + " 行，第 "
                + (s.fromCol + 1) + " 列交换";
    }

    /** 局面逐行扫描播报文案。 */
    static List<String> scanSpeech(char[][] board) {
        return scanSpeech(board, Match3Coach::pieceName);
    }

    static List<String> scanSpeech(char[][] board, java.util.function.Function<Character, String> names) {
        List<String> lines = new ArrayList<>();
        if (columns(board) == 0) return lines;
        for (int r = 0; r < board.length; r++) {
            StringBuilder sb = new StringBuilder("第 " + (r + 1) + " 行：");
            for (int c = 0; c < board[r].length; c++) {
                sb.append(names.apply(board[r][c]));
                if (c < board[r].length - 1) sb.append("、");
            }
            lines.add(sb.toString());
        }
        return lines;
    }
}
