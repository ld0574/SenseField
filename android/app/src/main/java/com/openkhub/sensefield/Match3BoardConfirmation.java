package com.openkhub.sensefield;

import java.util.Arrays;

/** Every changed board needs consecutive matching samples, including a returning old board. */
final class Match3BoardConfirmation {
    private char[][] candidate;

    boolean accept(char[][] board) {
        if (Match3Board.columns(board) == 0) { reset(); return false; }
        if (candidate != null && Arrays.deepEquals(candidate, board)) return true;
        candidate = new char[board.length][];
        for (int row = 0; row < board.length; row++) candidate[row] = board[row].clone();
        return false;
    }

    void reset() {
        candidate = null;
    }
}
