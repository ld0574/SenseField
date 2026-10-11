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
        KNOWLEDGE.put("爆炸特效", "5 个相同的动物组成 T 形或 L 形，能合成爆炸特效。参考描述为周围两圈、两次爆炸，具体命中范围仍需确认。");
        KNOWLEDGE.put("魔力鸟", "同色 5 个连成一条直线，会合成魔力鸟。魔力鸟和普通动物交换，可以清除全屏该颜色动物；双魔力鸟交换可以清除全屏动物。");
        KNOWLEDGE.put("组合特效", "已确认魔力鸟与普通动物、双魔力鸟的组合。其它特效组合需分别确认，不能只凭两种特效相邻就判断效果。");
        KNOWLEDGE.put("任务目标", "顶部挂牌显示本关要完成的目标和剩余数量。目标可能是动物或障碍；带完成勾的目标已经完成。");
        KNOWLEDGE.put("步数", "右上角数字是剩余步数，步数用完未达成目标即失败。");
        KNOWLEDGE.put("冰块障碍", "透明冰包住动物时，动物不能移动或参与三消。在旁边消除一次削去一层，双层需要两次。");
        KNOWLEDGE.put("静止冰面", "动物下面的静止单层冰面是另一种状态。已确认的这种冰面允许动物交换和配对，消除上面的动物可清除冰面。");
        KNOWLEDGE.put("毒水障碍", "毒液会随走步蔓延，在旁边消除可清除，也能为星星瓶积攒能量。要结合当前任务和瓶子能量决定时机。");
        KNOWLEDGE.put("藤蔓障碍", "被锁链或藤蔓锁住的动物不能移动，但可以配对消除；在旁边消除一次也可解开。");
        KNOWLEDGE.put("冰块关卡规则", "雪块占据格子；在旁边消除一次打掉一层。包裹动物的冻结冰和动物下面的静止冰面要分别判断。");
        KNOWLEDGE.put("双箭头提示", "画面中的双箭头是游戏自带的推荐走法提示，指出的两个棋子交换可以消除。");
        KNOWLEDGE.put("目标栏数字", "顶部图标和数字表示各项任务的剩余数量。部分小鸡任务靠鸡蛋孵化收集，不能把三消黄色动物都当成任务进度；要结合该关任务说明判断。");
        KNOWLEDGE.put("蜂蜜", "蜂蜜覆盖需要在旁边多次消除，不能只凭外观确定剩余次数。单级蜜罐是另一种状态。");
        KNOWLEDGE.put("解救目标", "企鹅和雪熊需要清除覆盖物才能解救。占多格的雪熊是一个对象，不能按覆盖格子数重复计算解救数量。");
        KNOWLEDGE.put("下落收集", "金豆荚和钥匙需要沿下落或传送路线进入收集口；接近收集口还不等于送达。钥匙集齐可开宝箱，宝箱不能直接打掉。");
        KNOWLEDGE.put("毛球", "普通三消不能清除毛球，需要特效。无敌毛球受特效影响会暂时消失，之后还会回来。");
    }

    static String[] knowledgeNames() {
        return KNOWLEDGE.keySet().toArray(new String[0]);
    }

    /* ---------- 教程弹窗检测：弹窗出现时画面中带会明显变亮变白 ---------- */

    /**
     * 检测画面中央是否出现大幅亮色弹窗（教程/说明页的显著特征）。
     * 取屏幕中央带（高度 30%-70%、宽度 15%-85%）的平均亮度与近白像素占比。
     * 阈值较严格：避免全局触点反馈的白色涟漪动画、普通 UI 高亮误触发。
     */
    static boolean isPopupShowing(Bitmap frame) {
        int w = frame.getWidth(), h = frame.getHeight();
        int l = w * 15 / 100, r = w * 85 / 100;
        int t = h * 30 / 100, b = h * 70 / 100;
        double sum = 0;
        long white = 0, n = 0;
        int step = Math.max(4, w / 120);
        float[] hsv = new float[3];
        for (int y = t; y < b; y += step) {
            for (int x = l; x < r; x += step) {
                int px = frame.getPixel(x, y);
                Color.colorToHSV(px, hsv);
                sum += hsv[2];
                if (hsv[2] > 0.85f && hsv[1] < 0.25f) white++;
                n++;
            }
        }
        if (n == 0) return false;
        float avgV = (float) (sum / n);
        float whiteRatio = white / (float) n;
        return avgV > 0.75f && whiteRatio > 0.55f;
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
        return summarizeRegion(board, r1, c1, r2, c2, Match3Coach::pieceName);
    }

    static RegionSummary summarizeRegion(char[][] board, int r1, int c1, int r2, int c2,
                                         java.util.function.Function<Character, String> names) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0, unknown = 0;
        if (Match3Board.columns(board) == 0) return new RegionSummary(counts, 0, 0);
        int rLo = Math.max(0, Math.min(r1, r2)), rHi = Math.max(r1, r2);
        int cLo = Math.max(0, Math.min(c1, c2)), cHi = Math.max(c1, c2);
        for (int r = rLo; r <= rHi && r < board.length; r++) {
            for (int c = cLo; c <= cHi && c < board[r].length; c++) {
                char piece = board[r][c];
                String name = names.apply(piece);
                counts.merge(name, 1, Integer::sum);
                total++;
                if (Match3Sampler.isUnknown(piece)) unknown++;
            }
        }
        return new RegionSummary(counts, total, unknown);
    }

    /** 棋盘格字母 → 玩家听到的唯一叫法（全项目播报只有这一个词表来源）。 */
    static String pieceName(char c) {
        if (c == Match3Sampler.EMPTY_CELL) return "空";
        if (c == Match3Sampler.NON_SWAP_CELL) return "非普通棋子区域";
        if (c == Match3Sampler.COIN_CELL) return "银币";
        if (c == Match3Sampler.EGG_CELL) return "鸡蛋";
        switch (c) {
            case 'R': return "红狐狸";
            case 'O': return "棕色动物";
            case 'Y': return "小鸡";
            case 'G': return "青蛙";
            case 'B': return "河马";
            case 'P': return "紫猫头鹰";
            case 'I': return "雪块";
            case 'H': return "空位";
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
