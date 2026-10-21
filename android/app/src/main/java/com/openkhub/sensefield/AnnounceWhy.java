package com.openkhub.sensefield;

/**
 * 低置信／弃权那支播报的「念理由」生成器（纯 JVM，不 import 任何 Android 类）。
 *
 * <p>裁决口径是<b>只换不加</b>：旧句「这一屏我没看清楚，要我从上往下逐条读吗？」让用户只听到「没看清」
 * 却不知道原因。本类把那一句换成一句含理由的话，同时净长度不许增加——旧裁决「坐标播报不许再拖长」仍有效，
 * 所以硬判据是 {@link #speech} 的输出长度 ≤ 旧句长度（见 {@code AnnounceWhyTest}）。
 *
 * <p>做法是把「这一屏我没看清楚，」这一<em>无信息的头部</em>换成<em>有理由的头部</em>，句尾「要我从上往下逐条读吗？」
 * 这条既有要约原样保留：
 * <ul>
 *   <li>低置信支（{@code abstained=false}，即 {@code confidence < mid}）：理由＝我的把握没过低线
 *       →「我的把握没过低线，要我从上往下逐条读吗？」（正好 20 字＝旧句预算上限，只换不加）。</li>
 *   <li>弃权支（{@code abstained=true}，判定层自己投了信息不足）：理由＝判定层弃权
 *       →「判定层弃权，要我从上往下逐条读吗？」（17 字，短于旧句）。</li>
 * </ul>
 *
 * <p>档位线 {@code high}/{@code mid} 与置信读数 {@code confidence} 一并入参，是为让调用点保持与
 * {@link DecisionWhy#explain} 同一口径的判定面输入；本句是听觉播报、长度预算极紧（旧裁决不许拖长），
 * 放不下百分比数字，所以理由取「没过低线／判定层弃权」这类定性说法，数值读数留给只读展示行的
 * {@link DecisionWhy#explain} 去带。两支都<em>不含</em>播报坐标（行列数字），故「坐标一个字节不许删」
 * 这条纪律不与本轮冲突。措辞受 {@link WordingGuard} 口径约束：不出现「自动执行／帮你点」这类越权词。
 */
public final class AnnounceWhy {

    /** 被本类替换的旧句（任务 0 锚点原文，用于长度基线；一字不改）。 */
    static final String OLD_SPEECH = "这一屏我没看清楚，要我从上往下逐条读吗？";

    /** 句尾既有要约，两支都原样保留，只换头部。 */
    static final String OFFER_TAIL = "，要我从上往下逐条读吗？";

    /** 低置信支的理由头部（8 字；加句尾共 20 字，等于旧句预算上限，只换不加）。 */
    static final String LOW_HEAD = "我的把握没过低线";

    /** 弃权支的理由头部。 */
    static final String ABSTAIN_HEAD = "判定层弃权";

    private AnnounceWhy() {
    }

    /**
     * 输入置信读数与档位线，输出含理由的一句中文播报。
     * {@code abstained=true} 走弃权支理由，否则走低置信支理由；两支长度均 ≤ {@link #OLD_SPEECH}。
     * 永不返回 null、永不返回空串。
     */
    public static String speech(boolean abstained, double confidence, double high, double mid) {
        String head = abstained ? ABSTAIN_HEAD : LOW_HEAD;
        return head + OFFER_TAIL;
    }
}
