package com.openkhub.sensefield;

/** Immutable recommendation shared by speech, drawing and diagnostics. */
final class Match3Hint {
    static final String DIRECTION_GUIDANCE = "方向以整个棋盘为准。中心引导箭头指向推荐位置，亮黄框标出要操作的棋子；语音会说左上方、右侧等方位，再说左右交换、上下交换或点击，无需数格子。";
    final String sessionId;
    final long revision, frameAtMs;
    final BoardGeometry geometry;
    final Match3Board.Swap swap;
    final String speech;
    final Match3MoveValue value;
    final String goalKey;
    Match3Hint(String sessionId, long revision, long frameAtMs, BoardGeometry geometry,
               Match3Board.Swap swap) {
        this(sessionId,revision,frameAtMs,geometry,swap,null,"unread");
    }
    Match3Hint(String sessionId,long revision,long frameAtMs,BoardGeometry geometry,Match3MoveValue value,String goalKey) {
        this(sessionId,revision,frameAtMs,geometry,value.swap,value,goalKey);
    }
    private Match3Hint(String sessionId,long revision,long frameAtMs,BoardGeometry geometry,
                      Match3Board.Swap swap,Match3MoveValue value,String goalKey) {
        if (swap.fromRow < 0 || swap.toRow >= geometry.rows || swap.fromCol < 0
                || swap.toCol >= geometry.cols || swap.toRow < 0 || swap.toCol < 0
                || swap.fromRow >= geometry.rows || swap.fromCol >= geometry.cols
                || Math.abs(swap.fromRow - swap.toRow) + Math.abs(swap.fromCol - swap.toCol)
                != (swap.actionType==Match3Action.Type.ACTIVATE?0:1))
            throw new IllegalArgumentException("Invalid swap");
        this.sessionId = sessionId; this.revision = revision; this.frameAtMs = frameAtMs;
        this.geometry = geometry; this.swap = swap;
        this.value=value;this.goalKey=goalKey;
        speech = Match3Coach.swapSpeechWithDirection(swap, geometry.rows, geometry.cols,
                value == null ? null : value.result.before)
                + (value!=null && !value.reason.isEmpty()?"，"+value.reason:"")+"。";
    }
}
