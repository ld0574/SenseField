package com.openkhub.sensefield;

import java.util.Objects;

/**
 * 养老场景「可信标签」判定结果（方案一 MVP，纯逻辑旁挂，不接播放路径）。
 *
 * 五档语义（docs/design/养老场景可信标签MVP.md §三）：
 * - RED     高风险：命中养老诈骗话术特征，立即语音提示并留痕；
 * - YELLOW  第三方推广：营销/诱导内容，低频提示，不打断；
 * - REVIEW  待人工审核：涉资金/健康但无明确诈骗特征，不惊扰老人，进审核队列（白名单+人工兜底）；
 * - GREEN   官方认证：命中官方服务内容签名或可信来源包名；
 * - NEUTRAL 无证据：既不贴绿也不贴红——没有证据不等于安全，默认不打扰。
 */
public final class ElderRiskLabel {

    public enum Tier {GREEN, YELLOW, RED, REVIEW, NEUTRAL}

    /** 播报紧迫度：红＝立即，黄＝低频，REVIEW/GREEN/NEUTRAL＝不播。 */
    public enum Announce {IMMEDIATE, LOW_PRIORITY, NONE}

    public final Tier tier;
    /** 命中的规则 id（如 RED-02）；GREEN/NEUTRAL 为空串。 */
    public final String ruleId;
    /** 命中关键词所在的原文窗口（去空白归一前），供播报引用与审核留痕。 */
    public final String evidence;
    /** 面向老人的口语化解释文案（语音解释优先原则）。 */
    public final String explanation;
    /** 来源是否在可信包名白名单内。 */
    public final boolean sourceTrusted;

    public ElderRiskLabel(Tier tier, String ruleId, String evidence,
                          String explanation, boolean sourceTrusted) {
        this.tier = Objects.requireNonNull(tier);
        this.ruleId = ruleId == null ? "" : ruleId;
        this.evidence = evidence == null ? "" : evidence;
        this.explanation = explanation == null ? "" : explanation;
        this.sourceTrusted = sourceTrusted;
    }

    public static ElderRiskLabel neutral(boolean sourceTrusted) {
        return new ElderRiskLabel(Tier.NEUTRAL, "", "", "", sourceTrusted);
    }

    public Announce announceKind() {
        if (tier == Tier.RED) return Announce.IMMEDIATE;
        if (tier == Tier.YELLOW) return Announce.LOW_PRIORITY;
        return Announce.NONE;
    }

    /** 播报去重用的指纹：档位＋规则＋证据窗口。 */
    public String fingerprint() {
        return tier.name() + '|' + ruleId + '|' + evidence;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof ElderRiskLabel)) return false;
        ElderRiskLabel that = (ElderRiskLabel) o;
        return fingerprint().equals(that.fingerprint());
    }

    @Override
    public int hashCode() {
        return fingerprint().hashCode();
    }

    @Override
    public String toString() {
        return "ElderRiskLabel{" + tier + " rule=" + ruleId
                + " trusted=" + sourceTrusted + " evidence=" + evidence + "}";
    }
}
