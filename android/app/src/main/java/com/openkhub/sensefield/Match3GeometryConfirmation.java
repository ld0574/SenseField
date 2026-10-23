package com.openkhub.sensefield;

/** Geometry must agree on three sampled frames; failed estimates break the run. */
final class Match3GeometryConfirmation {
    private BoardGeometry candidate;
    private int count;
    BoardGeometry accept(BoardGeometry next) {
        if (next == null) { reset(); return null; }
        count = next.sameGrid(candidate) ? count + 1 : 1;
        candidate = next;
        return count >= 3 ? next : null;
    }
    void reset() { candidate = null; count = 0; }
}
