package com.openkhub.sensefield;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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
        return rankedSwaps(board, Match3GoalStrip.untrusted("no_strip"));
    }

    /**
     * Historical kinds-only comparison API. Runtime uses rankedMoves with confirmed counts.
     * 目标可信时先比「这一步消掉的动物是不是本关要收集的」，再走原来的局部键序；
     * 目标不可信（挂牌读不出、没有挂牌）时排序与 rankedSwaps(board) 逐位相同。
     */
    static List<Match3Board.Swap> rankedSwaps(char[][] board, Match3GoalStrip.Snapshot goals) {
        List<Match3Board.Swap> swaps = Match3Board.findSwaps(board);
        if (goals == null || !goals.trusted()) return rankLocally(swaps);
        Map<Match3Board.Swap, Boolean> onGoal = new HashMap<>();
        for (Match3Board.Swap swap : swaps) onGoal.put(swap, clearsGoalAnimal(board, swap, goals));
        swaps.sort((a, b) -> {
            int order = Boolean.compare(onGoal.get(b), onGoal.get(a));
            if (order == 0) order = compareLocal(a, b);
            return order;
        });
        return swaps;
    }

    /** 原来的局部偏好排序，目标读不出时一字不变地走这条。 */
    private static List<Match3Board.Swap> rankLocally(List<Match3Board.Swap> swaps) {
        // Stable sort keeps the existing deterministic scan order for equal evidence.
        // Ice is counted by unique neighbouring cells; crossing runs also count each animal once.
        swaps.sort((a, b) -> compareLocal(a, b));
        return swaps;
    }

    private static int compareLocal(Match3Board.Swap a, Match3Board.Swap b) {
        int order = Integer.compare(b.adjacentIce, a.adjacentIce);
        if (order == 0) order = Integer.compare(b.longestRun, a.longestRun);
        if (order == 0) order = Integer.compare(b.matchedCells, a.matchedCells);
        if (order == 0) order = Integer.compare(b.runsFormed, a.runsFormed);
        return order;
    }

    static String evidence(Match3Board.Swap swap) {
        return evidence(swap, null, Match3GoalStrip.untrusted("no_strip"));
    }

    /** 诊断口径：目标读不出时继续标 unread，读得出时改成目标种类读数。 */
    static String evidence(Match3Board.Swap swap, char[][] board, Match3GoalStrip.Snapshot goals) {
        String base = "ice_neighbors=" + swap.adjacentIce + " longest_run=" + swap.longestRun
                + " matched_cells=" + swap.matchedCells;
        boolean trusted = goals != null && goals.trusted();
        if (!trusted) {
            String tail = goals != null && goals.slotCount() > 0 ? " " + goals.describe() : "";
            return base + " scope=local_one_move_level_goal_unread" + tail;
        }
        return base + " goal_cleared=" + (clearsGoalAnimal(board, swap, goals) ? "yes" : "no")
                + " " + goals.describe() + " scope=local_one_move_plus_level_goal_kinds";
    }

    /** 只认涉及被交换两格的新增三连，与 Match3Board 同一条口径。 */
    private static boolean clearsGoalAnimal(char[][] board, Match3Board.Swap swap,
                                            Match3GoalStrip.Snapshot goals) {
        char[][] copy = swappedCopy(board, swap);
        for (Match3Board.Run run : Match3Board.findRuns(copy)) {
            if (!touches(run, swap.fromRow, swap.fromCol, swap.toRow, swap.toCol)) continue;
            if (goals.collects(copy[run.row][run.col])) return true;
        }
        return false;
    }

    private static char[][] swappedCopy(char[][] board, Match3Board.Swap swap) {
        char[][] copy = new char[board.length][];
        for (int i = 0; i < board.length; i++) copy[i] = board[i].clone();
        char tmp = copy[swap.fromRow][swap.fromCol];
        copy[swap.fromRow][swap.fromCol] = copy[swap.toRow][swap.toCol];
        copy[swap.toRow][swap.toCol] = tmp;
        return copy;
    }

    private static boolean touches(Match3Board.Run run, int r1, int c1, int r2, int c2) {
        return run.horizontal
                ? run.row == r1 && c1 >= run.col && c1 < run.col + run.length
                || run.row == r2 && c2 >= run.col && c2 < run.col + run.length
                : run.col == c1 && r1 >= run.row && r1 < run.row + run.length
                || run.col == c2 && r2 >= run.row && r2 < run.row + run.length;
    }
}
