package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 历史颜色采点原型，仅供兼容排序比较与纯逻辑回归，不用于当前实时／截图目标确认。
 * 当前链路使用 Match3HudReader 的图标、数字、完成态与步数及独立观测确认。
 * 本原型吃顶栏颜色分类结果（char[]），只表达收集哪几种动物，不能代表当前剩余任务。
 * 只认基础动物（一律走 Match3Sampler.isMovable，本类不另立名单）；挂牌上的数字读不了
 * （本原型没有数字读取），冰块/银币/特效类目标也读不出，都按弃权处理。
 * 任何一格认不出就整体弃权：不忽略那一格，也不把看不清的格子补成某种动物。
 */
final class Match3GoalStrip {

    /**
     * 挂牌采点位置（画面千分比，避免浮点）。双格位与纵坐标在 27 个诊断包里 20 帧带图语料上量得：
     * 475 宽帧两格中心 43.6%／56.5%、纵 9.0%；432 宽帧 43.1%／57.2%、纵 9.8%；卡框约 59x50 像素，
     * 采点半宽只有 6 像素，所以取两组读数的中段就落在框内。三格位没有语料样本，
     * 是按「两格以画面中线为中心、格距约 13.5%」推出来的，未经实测，只能当兜底布局。
     */
    static final int CARD_Y_PER_MILLE = 94;
    static final int[] PAIR_X_PER_MILLE = {434, 570};
    static final int[] TRIPLE_X_PER_MILLE = {366, 501, 636};

    /** 一次挂牌读数：可信时给出要收集的种类，不可信时给出弃权原因。 */
    static final class Snapshot {
        private final char[] slots;
        private final List<Character> kinds;
        private final String reason;

        private Snapshot(char[] slots, List<Character> kinds, String reason) {
            this.slots = slots;
            this.kinds = Collections.unmodifiableList(kinds);
            this.reason = reason;
        }

        boolean trusted() { return !kinds.isEmpty(); }

        /** 去重后的目标种类，按扫描顺序首次出现的次序排列。 */
        List<Character> kinds() { return kinds; }

        int slotCount() { return slots.length; }

        String reason() { return reason; }

        /** 该种动物在挂牌上占几格；读不出时恒为 0。 */
        int countOf(char kind) {
            int n = 0;
            for (char slot : slots) if (slot == kind) n++;
            return trusted() ? n : 0;
        }

        /** 这枚棋子是否是本关要收集的目标。读不出时恒为 false，调用方据此退回局部排序。 */
        boolean collects(char kind) { return trusted() && kinds.contains(kind); }

        String describe() {
            if (!trusted()) return "goal_strip=abstain reason=" + reason + " slots=" + slots.length;
            StringBuilder sb = new StringBuilder("goal_strip=trusted kinds=");
            for (char kind : kinds) sb.append(kind);
            return sb.append(" slots=").append(slots.length).toString();
        }
    }

    private Match3GoalStrip() { }

    /** 没有挂牌可采（还没截图、或帧里没有顶栏）时的读数。 */
    static Snapshot untrusted(String reason) {
        return new Snapshot(new char[0], new ArrayList<Character>(), reason);
    }

    /**
     * 读数：逐格要求是基础动物，任一格不是动物就整体弃权。
     * @param strip 顶栏挂牌各格的颜色分类结果，按从左到右顺序
     */
    static Snapshot read(char[] strip) {
        if (strip == null || strip.length == 0) return untrusted("empty_strip");
        List<Character> kinds = new ArrayList<>();
        for (int i = 0; i < strip.length; i++) {
            char c = strip[i];
            if (!Match3Sampler.isMovable(c)) {
                return new Snapshot(strip.clone(), new ArrayList<Character>(),
                        "slot" + (i + 1) + "=" + whyNotAnimal(c));
            }
            if (!kinds.contains(c)) kinds.add(c);
        }
        return new Snapshot(strip.clone(), kinds, "all_slots_are_animals");
    }

    /**
     * 两套候选采点布局按顺序试：先双格（语料里全是双格），不成再试三格。
     * 任何一套整体读成动物就采用它，绝不把两套拼在一起；两套都不成才弃权，
     * 弃权原因带上两套各自失败的那一格，装机后照字面就能看出该挪哪个点。
     */
    static Snapshot readLayouts(char[] pair, char[] triple) {
        Snapshot p = read(pair);
        if (p.trusted()) return p;
        Snapshot t = read(triple);
        if (t.trusted()) return t;
        return new Snapshot(p.slots.clone(), new ArrayList<Character>(),
                "no_layout_matched pair=" + p.reason() + " triple=" + t.reason());
    }

    private static String whyNotAnimal(char c) {
        if (c == Match3Sampler.EMPTY_CELL) return "empty";
        if (c == '.') return "unknown";
        if (c == Match3Sampler.GAP_CELL) return "gap";
        if (c == Match3Sampler.NON_SWAP_CELL) return "non_swap";
        if (c == 'I') return "ice";
        if (Match3Sampler.isTemplateCode(c)) return "learned_label";
        return "code_" + c;
    }
}
