package com.openkhub.sensefield;

import static org.junit.Assert.*;

import org.junit.Test;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/** Independent triple-window oracle, plus invariants under transpose and reflection. */
public final class Match3SwapOracleTest {
    private static final String ANIMALS = "ROYGBP";
    private static boolean animal(char c) { return ANIMALS.indexOf(c) >= 0; }
    private static char[][] copy(char[][] board) {
        char[][] out = new char[board.length][];
        for (int r = 0; r < board.length; r++) out[r] = board[r].clone();
        return out;
    }
    private static String pair(int r1, int c1, int r2, int c2) {
        if (r1 > r2 || r1 == r2 && c1 > c2) return pair(r2, c2, r1, c1);
        return r1 + "," + c1 + ":" + r2 + "," + c2;
    }
    private static Set<String> actual(char[][] board) {
        char[][] before = copy(board);
        Set<String> out = new HashSet<>(); int merit = Integer.MAX_VALUE;
        List<Match3Board.Swap> swaps = Match3Board.findSwaps(board);
        for (Match3Board.Swap s : swaps) {
            assertEquals(1, Math.abs(s.fromRow - s.toRow) + Math.abs(s.fromCol - s.toCol));
            assertTrue(animal(board[s.fromRow][s.fromCol])); assertTrue(animal(board[s.toRow][s.toCol]));
            assertNotEquals(board[s.fromRow][s.fromCol], board[s.toRow][s.toCol]);
            assertTrue(s.runsFormed > 0 && s.runsFormed <= merit); merit = s.runsFormed;
            assertTrue("Each swap appears once", out.add(pair(s.fromRow, s.fromCol, s.toRow, s.toCol)));
        }
        assertTrue("Enumeration never mutates the recognized board", java.util.Arrays.deepEquals(before, board));
        return out;
    }
    private static boolean tripleAt(char[][] b, int r, int c) {
        char color = b[r][c];
        if (!animal(color)) return false;
        for (int offset = -2; offset <= 0; offset++) {
            int startCol = c + offset, startRow = r + offset;
            if (startCol >= 0 && startCol + 2 < b[0].length
                    && b[r][startCol] == color && b[r][startCol + 1] == color && b[r][startCol + 2] == color) return true;
            if (startRow >= 0 && startRow + 2 < b.length
                    && b[startRow][c] == color && b[startRow + 1][c] == color && b[startRow + 2][c] == color) return true;
        }
        return false;
    }
    private static Set<String> oracle(char[][] original) {
        char[][] b = copy(original); Set<String> out = new HashSet<>();
        for (int r = 0; r < b.length; r++) for (int c = 0; c < b[0].length; c++)
            for (int[] step : new int[][]{{0, 1}, {1, 0}}) {
                int rr = r + step[0], cc = c + step[1];
                if (rr >= b.length || cc >= b[0].length || !animal(b[r][c]) || !animal(b[rr][cc]) || b[r][c] == b[rr][cc]) continue;
                char first = b[r][c], second = b[rr][cc]; b[r][c] = second; b[rr][cc] = first;
                if (tripleAt(b, r, c) || tripleAt(b, rr, cc)) out.add(pair(r, c, rr, cc));
                b[r][c] = first; b[rr][cc] = second;
            }
        return out;
    }

    @Test public void allSupportedSizesAgreeWithAnIndependentMoveOracle() {
        Random random = new Random(0x4_5_20261009L);
        String symbols = ANIMALS + "123az .HI?Z";
        for (int rows = 6; rows <= 9; rows++) for (int cols = 6; cols <= 9; cols++)
            for (int trial = 0; trial < 32; trial++) {
                char[][] b = new char[rows][cols];
                for (char[] row : b) for (int c = 0; c < cols; c++) row[c] = symbols.charAt(random.nextInt(symbols.length()));
                assertEquals(rows + "x" + cols + " example " + trial, oracle(b), actual(b));
                char[][] transpose = new char[cols][rows], mirror = new char[rows][cols];
                for (int r = 0; r < rows; r++) for (int c = 0; c < cols; c++) {
                    transpose[c][r] = b[r][c]; mirror[r][cols - 1 - c] = b[r][c];
                }
                Set<String> transposed = new HashSet<>(), reflected = new HashSet<>();
                for (Match3Board.Swap s : Match3Board.findSwaps(b)) {
                    transposed.add(pair(s.fromCol, s.fromRow, s.toCol, s.toRow));
                    reflected.add(pair(s.fromRow, cols - 1 - s.fromCol, s.toRow, cols - 1 - s.toCol));
                }
                assertEquals(transposed, actual(transpose)); assertEquals(reflected, actual(mirror));
            }
    }

    @Test public void narrowBoardsAndLongCrossingMatchesUseTheSameRules() {
        for (char[][] b : new char[][][]{ {"RYRRRR".toCharArray()},
                {{'R'}, {'Y'}, {'R'}, {'R'}, {'R'}},
                {"RYRG".toCharArray(), "RYRG".toCharArray(), "YRRY".toCharArray(), "GYGB".toCharArray()} })
            assertEquals(oracle(b), actual(b));
    }
}
