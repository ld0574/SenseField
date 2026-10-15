package com.openkhub.sensefield;

/** One board-relative location shared by the spoken direction and the visual guide. */
final class Match3HintGuide {
    final float startX, startY, endX, endY;

    private Match3HintGuide(float startX, float startY, float endX, float endY) {
        this.startX = startX; this.startY = startY; this.endX = endX; this.endY = endY;
    }

    static String direction(Match3Board.Swap swap, int rows, int cols) {
        int horizontal = third(swap.fromCol + swap.toCol + 1, cols);
        int vertical = third(swap.fromRow + swap.toRow + 1, rows);
        if (vertical < 0) return horizontal < 0 ? "左上方" : horizontal > 0 ? "右上方" : "上方";
        if (vertical > 0) return horizontal < 0 ? "左下方" : horizontal > 0 ? "右下方" : "下方";
        return horizontal < 0 ? "左侧" : horizontal > 0 ? "右侧" : "中间";
    }

    private static int third(int doubledCenter, int size) {
        return 3L * doubledCenter < 2L * size ? -1 : 3L * doubledCenter >= 4L * size ? 1 : 0;
    }

    static Match3HintGuide forHint(Match3Hint hint) {
        BoardGeometry g = hint.geometry; Match3Board.Swap swap = hint.swap;
        float startX = (g.left + g.right) / 2f, startY = (g.top + g.bottom) / 2f;
        float targetX = (g.cellLeft(swap.fromCol) + g.cellLeft(swap.fromCol + 1)
                + g.cellLeft(swap.toCol) + g.cellLeft(swap.toCol + 1)) / 4;
        float targetY = (g.cellTop(swap.fromRow) + g.cellTop(swap.fromRow + 1)
                + g.cellTop(swap.toRow) + g.cellTop(swap.toRow + 1)) / 4;
        float dx = targetX - startX, dy = targetY - startY;
        float distance = (float) Math.hypot(dx, dy);
        if (distance == 0) return null;
        float cell = Math.min(g.cellWidth(), g.cellHeight());
        float ux = dx / distance, uy = dy / distance;
        float halfWidth = (g.cellLeft(Math.max(swap.fromCol, swap.toCol) + 1)
                - g.cellLeft(Math.min(swap.fromCol, swap.toCol))) / 2 + cell * .12f;
        float halfHeight = (g.cellTop(Math.max(swap.fromRow, swap.toRow) + 1)
                - g.cellTop(Math.min(swap.fromRow, swap.toRow))) / 2 + cell * .12f;
        float approach = Math.min(ux == 0 ? Float.POSITIVE_INFINITY : halfWidth / Math.abs(ux),
                uy == 0 ? Float.POSITIVE_INFINITY : halfHeight / Math.abs(uy));
        float length = distance - approach;
        // A target already at the fixation point needs its bright frame, not an overlapping arrow.
        return length <= cell * .5f ? null : new Match3HintGuide(startX, startY,
                startX + ux * length, startY + uy * length);
    }
}
