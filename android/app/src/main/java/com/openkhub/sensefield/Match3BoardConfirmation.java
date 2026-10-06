package com.openkhub.sensefield;

import java.util.Arrays;

/** Require two matching stable windows after start or a board transition. */
final class Match3BoardConfirmation {
    private char[][] candidate;
    private boolean waiting = true;

    boolean accept(char[][] board) {
        if (!waiting) return true;
        if (candidate != null && Arrays.deepEquals(candidate, board)) {
            waiting = false;
            candidate = null;
            return true;
        }
        candidate = new char[board.length][];
        for (int row = 0; row < board.length; row++) candidate[row] = board[row].clone();
        return false;
    }

    void reset() {
        candidate = null;
        waiting = true;
    }
}
