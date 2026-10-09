package com.openkhub.sensefield;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Collections;

/** One-move task preference from confirmed observations; no predicted cascades or special activation. */
final class Match3MoveRanker {
    private Match3MoveRanker() { }

    static List<Match3MoveValue> rankedMoves(Match3Position board, Match3Goals goals) {
        return rankedMoves(board,goals,Collections.emptySet());
    }
    static List<Match3MoveValue> rankedMoves(Match3Position board, Match3Goals goals,Set<Match3Goals.Kind> abstained) {
        List<Match3MoveValue> moves = new ArrayList<>();
        if (board == null || goals == null || goals.hudVerified && (goals.steps == 0 || goals.finished())) return moves;
        for (Match3Board.Swap swap : Match3Board.findSwaps(board)) moves.add(Match3MoveValue.evaluate(board, goals, swap,abstained));
        boolean knownTarget=false;
        for(Match3Goals.Kind kind:Match3Goals.Kind.values())if(goals.remaining(kind)>0)knownTarget=true;
        final boolean hasTarget=knownTarget;
        moves.sort((a, b) -> {
            int order = Boolean.compare(b.allTargetsFinish, a.allTargetsFinish);
            if (order == 0) order = Integer.compare(b.completedTargets, a.completedTargets);
            if (order == 0) order = Integer.compare(b.progressMilli, a.progressMilli);
            if (order == 0) order = Integer.compare(b.directUnits, a.directUnits);
            if (order == 0) order = Integer.compare(b.relevantHits, a.relevantHits);
            // In the final step, creating a future special offers no guaranteed extra turn.
            if (order == 0 && hasTarget && goals.steps != 1) order = Integer.compare(b.potentialSpecials, a.potentialSpecials);
            if (order == 0) order = Integer.compare(b.swap.adjacentIce, a.swap.adjacentIce);
            if (order == 0) order = Integer.compare(b.swap.longestRun, a.swap.longestRun);
            if (order == 0) order = Integer.compare(b.swap.matchedCells, a.swap.matchedCells);
            if (order == 0) order = Integer.compare(b.swap.runsFormed, a.swap.runsFormed);
            return order;
        });
        return moves;
    }

    static List<Match3Board.Swap> rankedSwaps(char[][] board) {
        List<Match3Board.Swap> swaps = Match3Board.findSwaps(board);
        // Stable sort keeps the existing deterministic scan order for equal evidence.
        // Ice is counted by unique neighbouring cells; crossing runs also count each animal once.
        swaps.sort((a, b) -> {
            int order = Integer.compare(b.adjacentIce, a.adjacentIce);
            if (order == 0) order = Integer.compare(b.longestRun, a.longestRun);
            if (order == 0) order = Integer.compare(b.matchedCells, a.matchedCells);
            if (order == 0) order = Integer.compare(b.runsFormed, a.runsFormed);
            return order;
        });
        return swaps;
    }

    static String evidence(Match3Board.Swap swap) {
        return "ice_neighbors=" + swap.adjacentIce + " longest_run=" + swap.longestRun
                + " matched_cells=" + swap.matchedCells + " scope=local_one_move_level_goal_unread";
    }
}
