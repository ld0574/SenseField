package com.openkhub.sensefield;

import java.util.List;

/** Validate the spoken exchange and its claimed effects, rather than unrelated cells. */
final class Match3HintValidity {
    private Match3HintValidity() { }

    static boolean valid(Match3Position source, Match3Position current,
                         Match3MoveValue expected, List<Match3MoveValue> currentMoves) {
        if (source == null || current == null || expected == null || currentMoves == null
                || source.rows != current.rows || source.cols != current.cols) return false;
        Match3Board.Swap swap = expected.swap;
        if (!source.cell(swap.fromRow,swap.fromCol).equals(current.cell(swap.fromRow,swap.fromCol))
                || !source.cell(swap.toRow,swap.toCol).equals(current.cell(swap.toRow,swap.toCol))) return false;
        for (Match3MoveValue value : currentMoves) if (sameExchange(swap,value.swap))
            return expected.sameEvidence(value);
        return false;
    }

    static boolean sameExchange(Match3Board.Swap a, Match3Board.Swap b) {
        return a.fromRow == b.fromRow && a.fromCol == b.fromCol && a.toRow == b.toRow && a.toCol == b.toCol;
    }
}
