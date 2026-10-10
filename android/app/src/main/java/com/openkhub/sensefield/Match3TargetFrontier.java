package com.openkhub.sensefield;

import java.util.BitSet;
import java.util.Set;

/** Spatial preparation only: it predicts neither a fall nor a future task reward. */
final class Match3TargetFrontier {
    static final int UNAVAILABLE = Integer.MAX_VALUE;
    final int distance;
    final Match3Goals.Kind kind;

    private Match3TargetFrontier(int distance, Match3Goals.Kind kind) {
        this.distance = distance;
        this.kind = kind;
    }

    static final class Field {
        private final int[] distance;
        private final Match3Goals.Kind[] kind;
        Field(int cells) {
            distance = new int[cells]; java.util.Arrays.fill(distance, UNAVAILABLE);
            kind = new Match3Goals.Kind[cells]; java.util.Arrays.fill(kind, Match3Goals.Kind.UNKNOWN);
        }
        Match3TargetFrontier at(BitSet matched) {
            int best = UNAVAILABLE; Match3Goals.Kind nearest = Match3Goals.Kind.UNKNOWN;
            for (int p = matched.nextSetBit(0); p >= 0; p = matched.nextSetBit(p + 1))
                if (distance[p] < best) { best = distance[p]; nearest = kind[p]; }
            return new Match3TargetFrontier(best, nearest);
        }
    }

    static Field prepare(Match3Position board, Match3Goals goals, Set<Match3Goals.Kind> abstained) {
        Field field = new Field(board.rows * board.cols);
        // A preparation move cannot offer another turn when there is only one left.
        if (!goals.hudVerified || goals.steps == 1) return field;
        java.util.List<Match3Goals.Kind> active = new java.util.ArrayList<>();
        for (Match3Goals.Kind target : Match3Goals.Kind.values())
            // Keep the existing observed contacts for unread counts, but do not
            // add a speculative preparation preference until a remaining count
            // is reliable. It otherwise favours fragile idle artwork over an
            // already stable basic exchange in the unread-task regressions.
            if (goals.active(target) && goals.remaining(target) > 0 && !abstained.contains(target)) active.add(target);
        int[] queue = new int[field.distance.length];
        int tail = 0;
        for (int r = 0; r < board.rows; r++) for (int c = 0; c < board.cols; c++) {
            Match3Position.Cell cell = board.cell(r, c);
            for (Match3Goals.Kind target : active) {
                if (!isTarget(cell, target)) continue;
                int p = r * board.cols + c;
                field.distance[p] = 0; field.kind[p] = target; queue[tail++] = p;
                break;
            }
        }
        // Multi-source propagation computes the same Manhattan distance across
        // the entire rectangle. Holes are not walls: this remains proximity,
        // never a playable path or a gravity prediction. Each cell enters once.
        for (int head = 0; head < tail; head++) {
            int p = queue[head], r = p / board.cols, c = p % board.cols;
            if (r > 0) tail = visit(field, queue, tail, p, p - board.cols);
            if (r + 1 < board.rows) tail = visit(field, queue, tail, p, p + board.cols);
            if (c > 0) tail = visit(field, queue, tail, p, p - 1);
            if (c + 1 < board.cols) tail = visit(field, queue, tail, p, p + 1);
        }
        return field;
    }

    private static int visit(Field field, int[] queue, int tail, int from, int to) {
        if (field.distance[to] != UNAVAILABLE) return tail;
        field.distance[to] = field.distance[from] + 1;
        field.kind[to] = field.kind[from]; queue[tail++] = to;
        return tail;
    }

    private static boolean isTarget(Match3Position.Cell cell, Match3Goals.Kind target) {
        if (target.color != '\0') return cell.kind == Match3Position.Kind.ANIMAL
                && cell.swappable && cell.color == target.color;
        switch (target) {
            case COIN: return cell.kind == Match3Position.Kind.COIN;
            case SNOW: return cell.kind == Match3Position.Kind.SNOW;
            case ICEFLOWER: return cell.kind == Match3Position.Kind.ICEFLOWER;
            case HONEY: return cell.kind == Match3Position.Kind.HONEY;
            case COOKIE: return cell.kind == Match3Position.Kind.COOKIE;
            case EGG: return cell.kind == Match3Position.Kind.EGG;
            case ICE:
                // Known ice and known foreground identity establish a location,
                // even when the animal's exchange permission is unknown. This
                // field never grants a swap or a direct removal. Cold sky and
                // unidentified foregrounds still cannot establish a destination.
                return cell.iceLayers > 0 && (cell.kind == Match3Position.Kind.ANIMAL
                        || cell.kind == Match3Position.Kind.SNOW || cell.kind == Match3Position.Kind.COIN
                        || cell.kind == Match3Position.Kind.EGG || cell.kind == Match3Position.Kind.COOKIE);
            default: return false;
        }
    }

    String reason() {
        if (kind == Match3Goals.Kind.UNKNOWN) return "";
        if (kind == Match3Goals.Kind.ICE) return "准备消冰";
        if (kind == Match3Goals.Kind.ICEFLOWER) return "靠近冰花";
        if (kind == Match3Goals.Kind.HONEY) return "靠近蜜罐";
        if (kind == Match3Goals.Kind.COIN) return distance <= 2 ? "靠近银币" : "准备收集银币";
        if (kind == Match3Goals.Kind.COOKIE) return "靠近饼干";
        if (kind == Match3Goals.Kind.EGG) return "靠近鸡蛋";
        return "靠近目标";
    }
}
