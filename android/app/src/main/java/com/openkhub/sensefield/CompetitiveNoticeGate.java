package com.openkhub.sensefield;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 竞技那几路的<b>一次性风险告知闸门</b>（B 档实体：竞技与非竞技在产品里显式分开）。
 *
 * <p>裁决口径只有三格，写死在 {@code CompetitiveNoticeGateTest}：
 * <ul>
 *   <li>本次不是主动开启（关某一路）⇒ {@link Outcome#NONE}，照普通写值路径走，不打扰；</li>
 *   <li>本次是主动开启、但该路<b>已经是开</b>（老用户存过 {@code true}）⇒ {@link Outcome#NONE}，
 *       没有新开启发生，既不拦也不重复告知；</li>
 *   <li>本次是主动开启、且从未看过告知 ⇒ {@link Outcome#SHOW_NOTICE}，
 *       <b>并且这一趟不许落 {@code true}</b>——「看一眼再回来开」，不许一边弹一边就开好了；</li>
 *   <li>本次是主动开启、告知已看过 ⇒ {@link Outcome#ENABLED}，玩家的决定生效。</li>
 * </ul>
 *
 * <p>「已看过」由设置页把告知真正显示出来那一刻记一次（{@link #NOTICE_SEEN_KEY}），
 * 它不是提示偏好，写它不许把档位改成自定义。
 *
 * <p>纯 JVM：不 import 任何 {@code android.*} 类，所以这三条入参一个出参能在 JVM 单测里逐格跑，
 * 不需要 Robolectric、不需要真机。文案由同一份常量承载，黑名单手查也查的是这份字面量。
 */
final class CompetitiveNoticeGate {
    /** 告知是否已经显示过。一次性，之后不再打断玩家。 */
    static final String NOTICE_SEEN_KEY = "cue_competitive_notice_seen";

    /** 这一趟勾选的处理结果。 */
    enum Outcome {
        /** 与本闸门无关（不是主动开启，或本来就已开着）：照常走原写值路径。 */
        NONE,
        /** 只展示告知：本次不许写值，勾选框保持原状。 */
        SHOW_NOTICE,
        /** 告知已看过：本次主动开启放行。 */
        ENABLED
    }

    /** 告知的标题与正文。逐行渲染，不做任何富文本。 */
    static final String NOTICE_TITLE = "开启这几路之前，请先读这一段";

    static final List<String> NOTICE_LINES = Collections.unmodifiableList(Arrays.asList(
            "这几路会在对局里额外播报敌人的方位、距离或新出现，属于画面辅助。",
            "它不改动游戏画面，也不产生任何操作；开与不开都由你在这页决定。",
            "这不是医疗建议，本应用不诊断任何疾病。",
            "在竞技对局中使用画面辅助可能违反该游戏的服务条款，后果由账号持有人承担。",
            "现在这一路仍是关闭的。读完后再点一次勾选框，才会真正打开。",
            "消消乐提示、助手问答、读屏与逐条读不在这几路里，它们不受本条提示影响。"));

    private CompetitiveNoticeGate() {
    }

    /**
     * 该键当前值、是否已看过告知、本次是否为主动开启 ⇒ 唯一处置。
     *
     * @param currentValue 本次勾选<b>之前</b>该键读到的值（含兜底缺省）
     * @param noticeSeen   玩家是否已经看过一次风险告知
     * @param activeEnable 本次是不是要把它打开
     */
    static Outcome decide(boolean currentValue, boolean noticeSeen, boolean activeEnable) {
        if (!activeEnable) return Outcome.NONE;
        if (currentValue) return Outcome.NONE;
        return noticeSeen ? Outcome.ENABLED : Outcome.SHOW_NOTICE;
    }

    /**
     * 本次勾选的新值能不能落到偏好里。闸门只有一处放行判断，设置页不许自己判断 {@code checked}
     * 就直接写，否则「被拦时值仍为关」只剩注释在承诺。
     */
    static boolean writesThrough(Outcome outcome) {
        return outcome != Outcome.SHOW_NOTICE;
    }
}
