package com.openkhub.sensefield;

import java.util.HashSet;
import java.util.Set;

/** 每问三档阈值（默认 0.9 / 0.5；配置含 low 供后续细分，当前决策只用 high/mid）。 */
final class JevThresholds {
    final double high;
    final double mid;
    final double low;

    static final JevThresholds DEFAULT = new JevThresholds(0.9d, 0.5d, 0.3d);

    JevThresholds(double high, double mid, double low) {
        this.high = high;
        this.mid = mid;
        this.low = low;
    }

    enum Decision {
        /** 高置信：自动执行（仍必须过白名单校验）。 */
        AUTO_EXECUTE,
        /** 中置信：先播报请求确认，不直接执行。 */
        CONFIRM,
        /** 低置信：不执行、不猜，出声播报「没看清」。 */
        UNCERTAIN
    }

    /**
     * 三档分流。铁律：Jev 判断，Harness 授权与执行；低置信绝不静默。
     * choice/score 用 confidence；noul 用 |2p−1|（JevAnswer.surety() 已统一）。
     */
    Decision decide(JevAnswer answer) {
        double c = answer.surety();
        if (c >= high) return Decision.AUTO_EXECUTE;
        if (c >= mid) return Decision.CONFIRM;
        return Decision.UNCERTAIN;
    }

    /** 白名单校验：Jev 说「该做什么」不算数，只有白名单内的 next_action 才许触发执行层。 */
    static boolean inWhitelist(JevAnswer nextAction, Set<String> whitelist) {
        if (nextAction == null || !"choice".equals(nextAction.type) || nextAction.choice == null) {
            return false;
        }
        return whitelist.contains(nextAction.choice);
    }

    /** 播报文案（无障碍底线：低置信必须出声，静默失败视为验收不通过）。 */
    static String speechFor(Decision decision, JevAnswer answer) {
        switch (decision) {
            case AUTO_EXECUTE:
            case CONFIRM:
                return describe(answer);
            default:
                return UNCERTAIN_SPEECH;
        }
    }

    static final String UNCERTAIN_SPEECH = "我没看清当前局面，请让我重新扫描";

    private static String describe(JevAnswer a) {
        if (JevQuestion.TYPE_CHOICE.equals(a.type)) return a.choice;
        if (JevQuestion.TYPE_SCORE.equals(a.type)) return "等级 " + a.score;
        return a.noul >= 0.5 ? "是" : "否";
    }

    /** 消除类 MVP 的默认白名单（与创作工具默认五问的 next_action 选项集一致）。 */
    static Set<String> defaultWhitelist() {
        Set<String> s = new HashSet<>();
        s.add("announce_match");
        s.add("scan_board");
        s.add("read_menu");
        s.add("wait");
        return s;
    }
}
