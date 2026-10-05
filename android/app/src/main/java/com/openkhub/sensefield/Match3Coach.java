package com.openkhub.sensefield;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.LinkedHashMap;
import java.util.Map;

/** 消消乐教练：说明书知识库、教程弹窗检测、4 象限报点、任意子区域内容摘要。 */
final class Match3Coach {
    private Match3Coach() {
    }

    /* ---------- 说明书知识库（开心消消乐通用规则，内置） ---------- */

    static final Map<String, String> KNOWLEDGE = new LinkedHashMap<>();

    static {
        KNOWLEDGE.put("直线特效", "同色 4 个连成一线，会合成直线特效，可以清除整行或整列。");
        KNOWLEDGE.put("爆炸特效", "5 个相同的动物组成 T 形或 L 形，能合成爆炸特效，可以清除周围一圈。");
        KNOWLEDGE.put("活力鸟", "同色 5 个连成一条直线，会合成活力鸟。活力鸟和任意棋子交换，可以清除全屏该颜色。");
        KNOWLEDGE.put("组合特效", "两种特效相邻时交换，效果会叠加，威力更大。");
        KNOWLEDGE.put("任务目标", "左上角挂牌显示本关要收集的动物和数量，达成即可过关。");
        KNOWLEDGE.put("步数", "右上角数字是剩余步数，步数用完未达成目标即失败。");
        KNOWLEDGE.put("冰块障碍", "带冰壳的棋子需要在其旁边消除一次来打碎冰壳。");
        KNOWLEDGE.put("毒水障碍", "深色毒水格会污染相邻棋子，优先在毒水旁消除。");
        KNOWLEDGE.put("藤蔓障碍", "被藤蔓锁住的棋子不能移动，先消除藤蔓上的棋子解开。");
    }

    static String[] knowledgeNames() {
        return KNOWLEDGE.keySet().toArray(new String[0]);
    }

    /* ---------- 教程弹窗检测：弹窗出现时画面中带会明显变亮变白 ---------- */

    /**
     * 检测画面中央是否出现大幅亮色弹窗（教程/说明页的显著特征）。
     * 取屏幕中央带（高度 30%-70%、宽度 15%-85%）的平均亮度与近白像素占比。
     */
    static boolean isPopupShowing(Bitmap frame) {
        int w = frame.getWidth(), h = frame.getHeight();
        int l = w * 15 / 100, r = w * 85 / 100;
        int t = h * 30 / 100, b = h * 70 / 100;
        long sum = 0, white = 0, n = 0;
        int step = Math.max(4, w / 120);
        for (int y = t; y < b; y += step) {
            for (int x = l; x < r; x += step) {
                int px = frame.getPixel(x, y);
                float[] hsv = new float[3];
                Color.colorToHSV(px, hsv);
                sum += hsv[2];
                if (hsv[2] > 0.85f && hsv[1] < 0.25f) white++;
                n++;
            }
        }
        if (n == 0) return false;
        float avgV = sum / (float) n;
        float whiteRatio = white / (float) n;
        return avgV > 0.62f && whiteRatio > 0.35f;
    }

    /* ---------- 4 象限报点 ---------- */

    static String quadrantOf(int row, int col, int rows, int cols) {
        boolean topHalf = row < rows / 2;
        boolean leftHalf = col < cols / 2;
        if (topHalf && leftHalf) return "左上";
        if (topHalf) return "右上";
        if (leftHalf) return "左下";
        return "右下";
    }

    /** 带象限的可消除播报文案。 */
    static String swapSpeechWithQuadrant(Match3Board.Swap s, int rows, int cols) {
        return quadrantOf(s.fromRow, s.fromCol, rows, cols) + "区域，"
                + Match3Board.swapSpeech(s);
    }

    /* ---------- 任意子区域内容摘要（自由框选） ---------- */

    static final class RegionSummary {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final int total;
        final int unknown;

        RegionSummary(Map<String, Integer> counts, int total, int unknown) {
            this.counts.putAll(counts);
            this.total = total;
            this.unknown = unknown;
        }
    }

    /** 统计子区域内各颜色棋子数量（直接复用采样矩阵的子块）。 */
    static RegionSummary summarizeRegion(char[][] board, int r1, int c1, int r2, int c2) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0, unknown = 0;
        int rLo = Math.min(r1, r2), rHi = Math.max(r1, r2);
        int cLo = Math.min(c1, c2), cHi = Math.max(c1, c2);
        for (int r = rLo; r <= rHi && r < board.length; r++) {
            for (int c = cLo; c <= cHi && c < board[r].length; c++) {
                char piece = board[r][c];
                String name = pieceName(piece);
                counts.merge(name, 1, Integer::sum);
                total++;
                if (piece == '.') unknown++;
            }
        }
        return new RegionSummary(counts, total, unknown);
    }

    /** 棋盘格字母 → 玩家听到的唯一叫法（全项目播报只有这一个词表来源）。 */
    static String pieceName(char c) {
        if (c == Match3Sampler.EMPTY_CELL) return "空";
        switch (c) {
            case 'R': return "红狐狸";
            case 'O': return "棕熊";
            case 'Y': return "小鸡";
            case 'G': return "青蛙";
            case 'B': return "河马";
            case 'P': return "紫猫";
            default: break;
        }
        String learned = Match3Sampler.nameForCode(c);
        return learned != null ? learned : "未识别";
    }

    /** 区域摘要播报文案。 */
    static String regionSpeech(RegionSummary summary) {
        StringBuilder sb = new StringBuilder("框选区域共有 ").append(summary.total).append(" 格。");
        for (Map.Entry<String, Integer> e : summary.counts.entrySet()) {
            sb.append(e.getKey()).append(" ").append(e.getValue()).append(" 个；");
        }
        if (summary.unknown > 0) {
            sb.append("其中 ").append(summary.unknown).append(" 格未能识别。");
        }
        return sb.toString();
    }
}
