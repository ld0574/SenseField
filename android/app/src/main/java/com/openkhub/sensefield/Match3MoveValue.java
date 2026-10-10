package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.Collections;

/** Lower-bound direct task contribution; excludes random falls, blasts and unverified obstacle rules. */
final class Match3MoveValue {
    final Match3Board.Swap swap;
    final int completedTargets, progressMilli, directUnits, relevantHits, potentialSpecials;
    final boolean allTargetsFinish;
    final String reason;
    final boolean grounded;
    private final int[] collected, hits;

    private Match3MoveValue(Match3Board.Swap swap, Match3Goals goals, int[] collected, int[] hits, int specials) {
        this.swap = swap; this.collected = collected.clone(); this.hits = hits.clone(); potentialSpecials = specials;
        int complete = 0, progress = 0, units = 0, relevant = 0, positive = 0;
        boolean all = goals.fullyKnown() && !goals.finished();
        Match3Goals.Kind spoken = null;
        Match3Goals.Kind related = null;
        for (Match3Goals.Kind kind : Match3Goals.Kind.values()) {
            if(!goals.active(kind))continue;
            int remaining = goals.remaining(kind);
            int gain = remaining>0?Math.min(remaining, collected[kind.ordinal()]):0;
            if (gain > 0) {
                units += gain; if(remaining>0)progress += (int) (1000L * gain / remaining);
                positive++; spoken = kind;
            }
            if (remaining>0 && gain >= remaining) complete++; else all = false;
            relevant += hits[kind.ordinal()];
            if(remaining<0)relevant+=collected[kind.ordinal()];
            if(hits[kind.ordinal()]>0 || remaining<0 && collected[kind.ordinal()]>0)related=kind;
        }
        // An egg hit prepares a chick task, but never invents newly spawned or collected chicks.
        if (goals.active(Match3Goals.Kind.CHICK)) relevant += hits[Match3Goals.Kind.EGG.ordinal()];
        completedTargets = complete; progressMilli = progress; directUnits = units;
        boolean known=false;
        for(Match3Goals.Kind kind:Match3Goals.Kind.values())if(goals.active(kind))known=true;
        grounded=known;
        relevantHits = relevant; allTargetsFinish = all;
        if (positive > 1) reason = "兼顾任务";
        else if (positive == 1) reason = spoken.color != '\0' ? "收集" + spoken.name
                : spoken == Match3Goals.Kind.COIN ? "收集银币"
                : spoken == Match3Goals.Kind.ICE ? "消除冰层" : "清除障碍";
        else if (relevant > 0) reason = goals.active(Match3Goals.Kind.CHICK)
                && hits[Match3Goals.Kind.EGG.ordinal()] > 0 ? "靠近鸡蛋"
                :goals.active(Match3Goals.Kind.COOKIE) && hits[Match3Goals.Kind.COOKIE.ordinal()]>0?"靠近饼干"
                :related!=null && related.color!='\0'?"优先"+related.name
                :related==Match3Goals.Kind.ICE?"优先消冰":"清理障碍";
        else reason = "";
    }

    int collected(Match3Goals.Kind kind) { return collected[kind.ordinal()]; }
    int hit(Match3Goals.Kind kind) { return hits[kind.ordinal()]; }
    boolean sameEvidence(Match3MoveValue other) {
        return other != null && java.util.Arrays.equals(collected,other.collected)
                && java.util.Arrays.equals(hits,other.hits) && potentialSpecials == other.potentialSpecials
                && reason.equals(other.reason) && grounded == other.grounded
                && swap.matchedPositions().equals(other.swap.matchedPositions())
                && swap.adjacentIce == other.swap.adjacentIce && swap.longestRun == other.swap.longestRun;
    }
    private static char after(Match3Position board, Match3Board.Swap swap, int r, int c) {
        if (r == swap.fromRow && c == swap.fromCol) return board.cell(swap.toRow, swap.toCol).color;
        if (r == swap.toRow && c == swap.toCol) return board.cell(swap.fromRow, swap.fromCol).color;
        return board.cell(r, c).color;
    }

    static Match3MoveValue evaluate(Match3Position board, Match3Goals goals, Match3Board.Swap swap) {
        return evaluate(board,goals,swap,Collections.emptySet());
    }
    static Match3MoveValue evaluate(Match3Position board, Match3Goals goals, Match3Board.Swap swap,Set<Match3Goals.Kind> abstained) {
        int[] counts = new int[Match3Goals.Kind.values().length], hits = new int[counts.length];
        BitSet matched = swap.matchedPositions();
        List<BitSet> components = specialComponents(swap, board.cols);
        for (int p = matched.nextSetBit(0); p >= 0; p = matched.nextSetBit(p + 1)) {
            int r = p / board.cols, c = p % board.cols;
            Match3Goals.Kind animal = Match3Goals.Kind.animal(after(board, swap, r, c));
            if (animal != Match3Goals.Kind.UNKNOWN) counts[animal.ordinal()]++;
            int ice = board.cell(r, c).iceLayers;
            if (ice > 0) { hits[Match3Goals.Kind.ICE.ordinal()]++; if (ice == 1) counts[Match3Goals.Kind.ICE.ordinal()]++; }
        }
        for (BitSet component : components) {
            int p = component.nextSetBit(0);
            Match3Goals.Kind kind = Match3Goals.Kind.animal(after(board, swap, p / board.cols, p % board.cols));
            if (kind != Match3Goals.Kind.UNKNOWN) counts[kind.ordinal()] = Math.max(0, counts[kind.ordinal()] - 1);
            // The retained special's exact cell is not inferred. At most one covered
            // cell per component may remain, so do not call that ice a certain clear.
            for (int at = component.nextSetBit(0); at >= 0; at = component.nextSetBit(at + 1))
                if (board.cell(at / board.cols, at % board.cols).iceLayers > 0) {
                    counts[Match3Goals.Kind.ICE.ordinal()] = Math.max(0, counts[Match3Goals.Kind.ICE.ordinal()] - 1); break;
                }
        }
        BitSet affected = new BitSet(board.rows * board.cols);
        for (int p = matched.nextSetBit(0); p >= 0; p = matched.nextSetBit(p + 1)) {
            int r = p / board.cols, c = p % board.cols;
            if (r > 0) affected.set(p - board.cols);
            if (r + 1 < board.rows) affected.set(p + board.cols);
            if (c > 0) affected.set(p - 1);
            if (c + 1 < board.cols) affected.set(p + 1);
        }
        java.util.Set<Integer> cookieObjects=new java.util.HashSet<>();
        for (int p = affected.nextSetBit(0); p >= 0; p = affected.nextSetBit(p + 1)) {
            int r = p / board.cols, c = p % board.cols;
            Match3Position.Cell cell = board.cell(r, c);
            Match3Goals.Kind kind = cell.kind == Match3Position.Kind.SNOW ? Match3Goals.Kind.SNOW
                    : cell.kind == Match3Position.Kind.COIN ? Match3Goals.Kind.COIN
                    : cell.kind == Match3Position.Kind.EGG ? Match3Goals.Kind.EGG
                    : cell.kind == Match3Position.Kind.COOKIE ? Match3Goals.Kind.COOKIE : Match3Goals.Kind.UNKNOWN;
            if (kind == Match3Goals.Kind.UNKNOWN)continue;
            boolean touches=kind==Match3Goals.Kind.COOKIE?guaranteedObjectNeighbour(board,matched,components,cell.objectId,r,c)
                    :guaranteedNeighbour(board,matched,components,r,c);
            if(!touches)continue;
            // Four covered cells describe one large object. Count one opportunity,
            // never four predicted removals or an unverified number of layers.
            if(kind==Match3Goals.Kind.COOKIE && !cookieObjects.add(cell.objectId>=0?cell.objectId:p))continue;
            hits[kind.ordinal()]++;
            if ((kind == Match3Goals.Kind.SNOW || kind == Match3Goals.Kind.COIN) && cell.layers == 1)
                counts[kind.ordinal()]++;
        }
        for(Match3Goals.Kind kind:abstained) { counts[kind.ordinal()]=0;hits[kind.ordinal()]=0; }
        return new Match3MoveValue(swap, goals, counts, hits, components.size());
    }

    private static boolean guaranteedNeighbour(Match3Position board, BitSet matched,
                                               List<BitSet> special, int r, int c) {
        return guaranteedNeighbours(neighbours(board,matched,r,c),special);
    }
    private static BitSet neighbours(Match3Position board,BitSet matched,int r,int c) {
        BitSet neighbours=new BitSet();
        if (r > 0 && matched.get((r - 1) * board.cols + c)) neighbours.set((r - 1) * board.cols + c);
        if (r + 1 < board.rows && matched.get((r + 1) * board.cols + c)) neighbours.set((r + 1) * board.cols + c);
        if (c > 0 && matched.get(r * board.cols + c - 1)) neighbours.set(r * board.cols + c - 1);
        if (c + 1 < board.cols && matched.get(r * board.cols + c + 1)) neighbours.set(r * board.cols + c + 1);
        return neighbours;
    }
    private static boolean guaranteedObjectNeighbour(Match3Position board,BitSet matched,List<BitSet> special,int id,int r,int c) {
        if(id<0)return guaranteedNeighbour(board,matched,special,r,c);
        BitSet around=new BitSet();
        for(int rr=0;rr<board.rows;rr++)for(int cc=0;cc<board.cols;cc++)
            if(board.cell(rr,cc).kind==Match3Position.Kind.COOKIE && board.cell(rr,cc).objectId==id)
                around.or(neighbours(board,matched,rr,cc));
        return guaranteedNeighbours(around,special);
    }
    private static boolean guaranteedNeighbours(BitSet neighbours,List<BitSet> special) {
        BitSet possiblyHeld = new BitSet();
        for (BitSet component : special) {
            BitSet overlap = (BitSet) component.clone(); overlap.and(neighbours);
            if (overlap.cardinality() >= 2) return true;
            possiblyHeld.or(component);
        }
        neighbours.andNot(possiblyHeld);
        return !neighbours.isEmpty();
    }

    private static List<BitSet> specialComponents(Match3Board.Swap swap, int cols) {
        List<BitSet> sets = new ArrayList<>();
        for (Match3Board.Run run : swap.formedRuns()) {
            BitSet cells = new BitSet();
            for (int i = 0; i < run.length; i++) cells.set((run.row + (run.horizontal ? 0 : i)) * cols
                    + run.col + (run.horizontal ? i : 0));
            for (int i = sets.size() - 1; i >= 0; i--) if (sets.get(i).intersects(cells)) cells.or(sets.remove(i));
            sets.add(cells);
        }
        List<BitSet> result = new ArrayList<>();
        for (BitSet set : sets) if (set.cardinality() >= 4) result.add(set);
        return result;
    }
    String evidence() {
        return "scope="+(grounded?"observed_goal_one_move_lower_bound":"basic_one_move_goal_unread")+" complete=" + completedTargets + " progress_milli="
                + progressMilli + " direct_units=" + directUnits + " relevant_hits=" + relevantHits
                + " potential_specials=" + potentialSpecials + " random_falls=excluded";
    }
}
