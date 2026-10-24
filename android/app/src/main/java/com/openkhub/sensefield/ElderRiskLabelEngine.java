package com.openkhub.sensefield;

import java.util.List;

/**
 * 养老场景「可信标签」判定引擎（方案一 MVP，纯 JVM 旁挂）。
 *
 * 输入：前台包名＋无障碍节点文本列表（SenseFieldReaderService 已采集的形态）；
 * 输出：{@link ElderRiskLabel}。判定顺序红＞黄＞待审＞绿签名＞可信包名＞中性。
 *
 * 工程红线（与 HapticSemantics/AudioContextLevel 旁挂先例一致）：
 * 不接 CuePlayer 播放路径、不读系统服务、不抛异常出去——任何输入异常都判 NEUTRAL
 * 并置 trusted=false，宁可少报不可惊扰（优雅降级）。
 */
public final class ElderRiskLabelEngine {

    /** 证据窗口：命中词前后各取的字符数，防止整屏长文本被原样播报。 */
    static final int EVIDENCE_RADIUS = 18;
    /** 参与判定的文本总量上限（字符），防超长页面拖垮主线程。 */
    static final int MAX_TEXT_CHARS = 20_000;

    public ElderRiskLabel evaluate(String packageName, List<String> screenTexts) {
        boolean trusted = ElderRiskRules.TRUSTED_PACKAGES.contains(packageName == null ? "" : packageName);
        String joined = joinForMatch(screenTexts);
        if (joined.isEmpty()) return ElderRiskLabel.neutral(trusted);
        if (joined.length() > MAX_TEXT_CHARS) joined = joined.substring(0, MAX_TEXT_CHARS);

        for (ElderRiskRules.Rule rule : ElderRiskRules.inPriorityOrder()) {
            String hit = firstHit(rule, joined);
            if (hit != null) {
                String evidence = evidenceWindow(joined, hit);
                return new ElderRiskLabel(rule.tier, rule.id, evidence, rule.explanation, trusted);
            }
        }
        for (String signature : ElderRiskRules.GREEN_SERVICE_SIGNATURES) {
            String normalized = normalize(signature);
            int at = joined.indexOf(normalized);
            if (at >= 0) {
                return new ElderRiskLabel(ElderRiskLabel.Tier.GREEN, "GREEN-SIG", signature,
                        "这是官方服务信息。", trusted);
            }
        }
        if (trusted) {
            return new ElderRiskLabel(ElderRiskLabel.Tier.GREEN, "GREEN-SRC", "", "这是手机系统自带应用。", true);
        }
        return ElderRiskLabel.neutral(trusted);
    }

    /** 归一：合并全部文本、去所有空白、拉丁转小写——挡「公 检 法」插字符绕过。 */
    static String joinForMatch(List<String> screenTexts) {
        if (screenTexts == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(4096, 256));
        for (String s : screenTexts) {
            if (s == null) continue;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isWhitespace(c)) continue;
                sb.append(Character.toLowerCase(c));
                if (sb.length() >= MAX_TEXT_CHARS) return sb.toString();
            }
        }
        return sb.toString();
    }

    static String normalize(String keyword) {
        StringBuilder sb = new StringBuilder(keyword.length());
        for (int i = 0; i < keyword.length(); i++) {
            char c = keyword.charAt(i);
            if (Character.isWhitespace(c)) continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 全组命中才返回命中的首个关键词（用原始关键词回找归一文本位置）。 */
    private static String firstHit(ElderRiskRules.Rule rule, String normalizedText) {
        String firstGroupHit = null;
        for (String[] group : rule.groups) {
            String groupHit = null;
            for (String keyword : group) {
                if (normalizedText.contains(normalize(keyword))) {
                    groupHit = keyword;
                    break;
                }
            }
            if (groupHit == null) return null;
            if (firstGroupHit == null) firstGroupHit = groupHit;
        }
        return firstGroupHit;
    }

    /** 命中词前后各 {@link #EVIDENCE_RADIUS} 字符的窗口，去空白后补省略号。 */
    static String evidenceWindow(String normalizedText, String keyword) {
        int at = normalizedText.indexOf(normalize(keyword));
        if (at < 0) return keyword;
        int from = Math.max(0, at - EVIDENCE_RADIUS);
        int to = Math.min(normalizedText.length(), at + normalize(keyword).length() + EVIDENCE_RADIUS);
        String window = normalizedText.substring(from, to);
        return (from > 0 ? "…" : "") + window + (to < normalizedText.length() ? "…" : "");
    }
}
