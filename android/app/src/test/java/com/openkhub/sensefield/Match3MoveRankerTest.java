package com.openkhub.sensefield;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public final class Match3MoveRankerTest {
    private static char[][] board(String... rows) {
        char[][] board = new char[rows.length][];
        for (int r = 0; r < rows.length; r++) board[r] = rows[r].toCharArray();
        return board;
    }
    private static Match3Board.Swap move(List<Match3Board.Swap> moves, int r, int c, int rr, int cc) {
        for (Match3Board.Swap move : moves) if (move.fromRow == r && move.fromCol == c
                && move.toRow == rr && move.toCol == cc) return move;
        throw new AssertionError("Expected legal exchange not found");
    }
    private static Set<String> pairs(List<Match3Board.Swap> moves) {
        Set<String> pairs = new HashSet<>();
        for (Match3Board.Swap move : moves) pairs.add(move.fromRow + "," + move.fromCol
                + ":" + move.toRow + "," + move.toCol);
        return pairs;
    }

    @Test public void lowerMoveNextToTwoIceCellsWinsOverAnUnrelatedTopTriple() {
        char[][] board = board("RYRHHH", "HRHHHH", "HHHHHH", "BYBHHH", "IBIHHH", "HHHHHH");
        List<Match3Board.Swap> moves = Match3MoveRanker.rankedSwaps(board);
        Match3Board.Swap best = moves.get(0);
        assertEquals(3, best.fromRow); assertEquals(1, best.fromCol);
        assertEquals(4, best.toRow); assertEquals(1, best.toCol);
        assertEquals(2, best.adjacentIce); assertEquals(3, best.matchedCells);
        assertEquals(0, move(moves, 0, 1, 1, 1).adjacentIce);
    }

    @Test public void aFourAnimalLineWinsOverThreeWhenNoIceWasRecognized() {
        char[][] board = board("RYRHHHH", "HRHHHHH", "HHHHHHH", "BYBBHHH", "HBHHHHH");
        Match3Board.Swap best = Match3MoveRanker.rankedSwaps(board).get(0);
        assertEquals(3, best.fromRow); assertEquals(1, best.fromCol);
        assertEquals(4, best.longestRun); assertEquals(4, best.matchedCells);
        assertEquals(0, best.adjacentIce);
    }

    @Test public void crossingLinesCountSharedAnimalsAndNeighbouringIceOnlyOnce() {
        char[][] board = board("HHIHH", "HHRHH", "RRYRR", "HHRHH", "HHRHH");
        Match3Board.Swap cross = move(Match3MoveRanker.rankedSwaps(board), 2, 2, 2, 3);
        // Vertical four plus horizontal three share the exchanged center: 4 + 3 - 1.
        assertEquals(6, cross.matchedCells); assertEquals(4, cross.longestRun);
        assertEquals(1, cross.adjacentIce);
    }

    @Test public void rankingPreservesAllLegalMovesAndNeverMutatesTheInput() {
        char[][] board = board("RYRHHH", "HRHHHH", "HHHHHH", "BYBHHH", "IBIHHH", "HHHHHH");
        char[][] before = board("RYRHHH", "HRHHHH", "HHHHHH", "BYBHHH", "IBIHHH", "HHHHHH");
        assertEquals(pairs(Match3Board.findSwaps(board)), pairs(Match3MoveRanker.rankedSwaps(board)));
        assertTrue(java.util.Arrays.deepEquals(before, board));
        assertTrue(Match3MoveRanker.rankedSwaps(null).isEmpty());
        assertTrue(Match3MoveRanker.rankedSwaps(new char[][]{{'R'}, null}).isEmpty());
    }
}
