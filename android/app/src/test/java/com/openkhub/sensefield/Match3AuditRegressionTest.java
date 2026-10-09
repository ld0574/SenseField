package com.openkhub.sensefield;

import static org.junit.Assert.*;

import org.junit.Test;

/** Reproductions from the audit, specified independently of the live service's branches. */
public final class Match3AuditRegressionTest {
    private static char[][] board(String... rows) {
        char[][] out = new char[rows.length][];
        for (int r = 0; r < rows.length; r++) out[r] = rows[r].toCharArray();
        return out;
    }

    @Test public void knownHolesAndObstaclesDoNotMakeAReadableBoardUnknown() {
        char[][] sparse = board("HHHHHHH", "IIIIIHH", "HHHHHHH", "   HHHH", "HHHRYGH", "HHHRBOH", "HHHYRPH");
        assertEquals(0, Match3LiveService.countUnknown(sparse));
        assertFalse(Match3LiveService.isUnreadableBoard(sparse));
        assertFalse(Match3Board.findSwaps(sparse).isEmpty());
    }

    @Test public void invalidSymbolsCannotInventAnimalsOrThreeInARow() {
        for (char symbol : new char[]{'Z', '?', '\0', '0'}) {
            assertFalse("Invalid symbol " + (int) symbol, Match3Board.isPiece(symbol));
            assertTrue(Match3Board.findRuns(new char[][]{{symbol, symbol, symbol}}).isEmpty());
        }
        assertEquals(4, Match3LiveService.countUnknown(board("Z?\0.")));
    }

    @Test public void incompleteBoardsAreRejectedInsteadOfCrashingOrSuggestingAMove() {
        for (char[][] broken : new char[][][]{null, new char[0][], new char[][]{new char[0]},
                new char[][]{null}, board("R", "RY")}) {
            assertTrue(Match3Board.findRuns(broken).isEmpty());
            assertTrue(Match3Board.findSwaps(broken).isEmpty());
            assertTrue(Match3LiveService.isUnreadableBoard(broken));
        }
    }

    @Test public void aSingleReturningFrameCannotRestoreTheOldRecommendation() {
        Match3BoardConfirmation gate = new Match3BoardConfirmation();
        char[][] a = board("YRB", "RBG", "RYO"), b = board("RRG", "BYY", "GOP");
        assertFalse(gate.accept(a)); assertTrue(gate.accept(a));
        assertFalse("A changed board must be reconfirmed", gate.accept(b));
        assertFalse("An isolated old frame must not restore A", gate.accept(a));
        assertTrue(gate.accept(a));
        assertFalse(gate.accept(b)); assertTrue(gate.accept(b));
    }

    @Test public void emptyOrMalformedSamplesBreakTheConfirmationSequence() {
        Match3BoardConfirmation gate = new Match3BoardConfirmation();
        assertFalse(gate.accept(board("RYG")));
        assertFalse(gate.accept(null));
        assertFalse(gate.accept(board("RYG")));
        assertTrue(gate.accept(board("RYG")));
    }

    @Test public void selectingAnOutOfBoundsRegionClipsToTheBoard() {
        Match3Coach.RegionSummary s = Match3Coach.summarizeRegion(board("ROY", "GB."), -2, -3, 0, 99);
        assertEquals(3, s.total); assertEquals(0, s.unknown);
        assertEquals(0, Match3Coach.summarizeRegion(board("ROY"), -4, -4, -1, -1).total);
        assertEquals(0, Match3Coach.summarizeRegion(null, 0, 0, 1, 1).total);
    }
}
