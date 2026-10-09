package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 本关收集目标的读数：吃顶栏挂牌逐格的颜色分类结果（char[]），输出这一关要收集哪几种动物。
 * 只认基础动物（一律走 Match3Sampler.isMovable，本类不另立名单）；挂牌上的数字读不了
 * （仓库没有本地 OCR），冰块/银币/特效类目标也读不出，都按弃权处理。
 * 任何一格认不出就整体弃权：不忽略那一格，也不把看不清的格子补成某种动物。
 */
final class Match3GoalStrip {

    /**
     * 挂牌采点位置（画面百分比）：计划书只说到「左上角挂牌」，本机无 adb 通道、没有真机像素标定过，
     * 所以这组常量是未标定的猜测。标定前 read() 靠「任一格不是动物就整体弃权」兜住，
     * 不会把背景色补成某种目标；弃权原因会进诊断串，装机后可直接照着改这几个值。
     */
    static final int CARD_Y_PCT = 5;
    static final int[] CARD_X_PCT = {7, 14, 21};

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
