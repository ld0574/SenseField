package com.openkhub.sensefield;

import static org.junit.Assert.*;
import org.junit.Test;

public final class Match3BoardConfirmationTest {
    @Test public void openingAnimationNeedsTwoMatchingStableWindows() {
        Match3BoardConfirmation gate = new Match3BoardConfirmation();
        assertFalse(gate.accept(board("ROY", "GRB")));
        assertFalse(gate.accept(board("RYO", "GRB")));
        assertTrue(gate.accept(board("RYO", "GRB")));
    }

    @Test public void normalUpdatesDoNotRepeatTheOpeningConfirmation() {
        Match3BoardConfirmation gate = new Match3BoardConfirmation();
        assertFalse(gate.accept(board("ROY")));
        assertTrue(gate.accept(board("ROY")));
        assertTrue(gate.accept(board("RYO")));
    }

    @Test public void transitionCannotReuseThePreviousCandidate() {
        Match3BoardConfirmation gate = new Match3BoardConfirmation();
        assertFalse(gate.accept(board("ROY")));
        gate.reset();
        assertFalse(gate.accept(board("ROY")));
        assertTrue(gate.accept(board("ROY")));
        gate.reset();
        assertFalse(gate.accept(board("GGY")));
        assertTrue(gate.accept(board("GGY")));
    }

    @Test public void changingTheInputCannotMutateThePendingBoard() {
        Match3BoardConfirmation gate = new Match3BoardConfirmation();
        char[][] first = board("ROY");
        assertFalse(gate.accept(first));
        first[0][0] = 'G';
        assertFalse(gate.accept(first));
        assertTrue(gate.accept(board("GOY")));
    }

    private static char[][] board(String... rows) {
        char[][] result = new char[rows.length][];
        for (int row = 0; row < rows.length; row++) result[row] = rows[row].toCharArray();
        return result;
    }
}
