package com.openkhub.sensefield;

import java.util.List;

/** One-move local preference. Does not read level goals, predict cascades or infer special-tile rules. */
final class Match3MoveRanker {
    private Match3MoveRanker() { }

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
