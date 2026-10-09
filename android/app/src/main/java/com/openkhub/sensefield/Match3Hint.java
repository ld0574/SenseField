package com.openkhub.sensefield;

/** Immutable recommendation shared by speech, drawing and diagnostics. */
final class Match3Hint {
    static final String NUMBERING = "整个棋盘左上角为起点，行从上往下数，列从左往右数，都从1开始。区域名称不代表重新编号。";
    final String sessionId;
    final long revision, frameAtMs;
    final BoardGeometry geometry;
    final Match3Board.Swap swap;
    final String speech;
    Match3Hint(String sessionId, long revision, long frameAtMs, BoardGeometry geometry,
               Match3Board.Swap swap) {
        if (swap.fromRow < 0 || swap.toRow >= geometry.rows || swap.fromCol < 0
                || swap.toCol >= geometry.cols || swap.toRow < 0 || swap.toCol < 0
                || swap.fromRow >= geometry.rows || swap.fromCol >= geometry.cols
                || Math.abs(swap.fromRow - swap.toRow) + Math.abs(swap.fromCol - swap.toCol) != 1)
            throw new IllegalArgumentException("Invalid swap");
        this.sessionId = sessionId; this.revision = revision; this.frameAtMs = frameAtMs;
        this.geometry = geometry; this.swap = swap;
        speech = Match3Coach.swapSpeechWithQuadrant(swap, geometry.rows, geometry.cols) + "。";
    }
}
