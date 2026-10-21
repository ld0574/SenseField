package com.openkhub.sensefield;

/**
 * 白盒判定层的理由解释器（纯 JVM，不 import 任何 Android 类）：把「这句播报是怎么来的」说成一句人话，
 * 供判定自测页做<b>只读展示</b>用 —— 播报内容本身一个字节不由本类产生，也不经本类改写。
 *
 * <p>规格直接照现成判定面来，不另造一套：{@link Match3Gate#sensitiveHit}（敏感闸门，命中即返回类型标签）、
 * {@link Match3Gate#gatedSpeech}（高/中/低三档）、{@link Match3Gate#firstIconOnlyNode}（图标消歧对象）、
 * 以及 {@code Match3ToolsActivity.judgeScreen()} 里「未运行」「未就绪」两条提前返回。
 *
 * <p>两条展示纪律由类签名保证、并由 {@code DecisionWhyTest} 钉死：
 * <ul>
 *   <li>第五个参数 {@code matchedFragment} 承载「命中的原文片段」（敏感分支命中的片段、图标分支那一行节点）。
 *       它在<b>任何</b>分支都不进输出 —— 敏感分支只许拼固定话术；因此展示行不可能带出密码框 label 之类原文。
 *       保留这个参数是为了让「不带原文」成为可被单测证伪的断言，而不是一句自我声明。</li>
 *   <li>置信度与阈值一律换算成整数百分比：输出串里不出现小数点，这样「不含带点号的包名或模型名字面量」
 *       这条硬检查才有判据（真出现 {@code a.b} 形态就是漏了模型名/包名），同时输出里也不可能有坐标 {@code @[}。</li>
 * </ul>
 */
public final class DecisionWhy {

    /** 命中了哪种判定。与现成判定面一一对应，不新增判定。 */
    public enum Kind {
        /** 判定层未启用／配置不完整（judgeScreen 第一条提前返回）。 */
        NOT_RUNNING,
        /** 读屏状态服务没有可用状态（judgeScreen 第二条提前返回）。 */
        NOT_READY,
        /** 本地敏感闸门命中（{@link Match3Gate#sensitiveHit} 非 null）。 */
        SENSITIVE,
        /** 高置信：照播内容（{@code confidence >= high}）。 */
        HIGH,
        /** 中置信：加一句「不太确定」（{@code mid <= confidence < high}）。 */
        MID,
        /** 低置信：这一屏没看清（{@code confidence < mid}）。 */
        LOW,
        /** 判定层自己投 ABSTAIN：信息不足。 */
        ABSTAIN,
        /** 图标消歧：第一个可点对象是无文字图标（{@link Match3Gate#firstIconOnlyNode} 非空）。 */
        ICON_DISAMBIG
    }

    private DecisionWhy() {
    }

    /**
     * 唯一入口：输入「命中了哪种判定 + 置信度 + high/mid 阈值 + 命中片段」，输出一句人话解释。
     * {@code matchedFragment} 与 {@code null}／任意内容都不影响输出（固定话术），详见类注释。
     * 未知档位回落到 {@link Kind#LOW} 的说法，绝不静默、绝不编造理由。
     */
    public static String explain(Kind kind, double confidence, double high, double mid, String matchedFragment) {
        if (kind == null) {
            return "为什么这么播：这一档没有对应的本地规则，按没看清处理，不猜。";
        }
        switch (kind) {
            case NOT_RUNNING:
                return "为什么没播：判定层未启用或配置不完整，本轮没有发起任何判定，也没有内容上云。";
            case NOT_READY:
                return "为什么没播：读屏状态服务还没有可用状态，请先开启无障碍里的读屏状态服务，再切一次屏幕。";
            case SENSITIVE:
                return "为什么这么播：本地敏感规则命中敏感页面类型，所以内容不上云、不念细节，这一屏请你手动处理。";
            case HIGH:
                return "为什么这么播：置信度 " + pct(confidence) + "% 已到高线 " + pct(high)
                        + "%，判定够稳，直接播、不用你确认。";
            case MID:
                return "为什么这么播：置信度 " + pct(confidence) + "% 在高线 " + pct(high) + "% 与低线 " + pct(mid)
                        + "% 之间，所以句尾加了「不太确定」，说错了你直接纠正我。";
            case LOW:
                return "为什么这么播：置信度 " + pct(confidence) + "% 低于低线 " + pct(mid)
                        + "%，宁可说没看清也不猜，免得把错的内容念给你。";
            case ABSTAIN:
                return "为什么这么播：判定层对这一屏投了信息不足，宁可不播也不猜。";
            case ICON_DISAMBIG:
                return "为什么这么播：这一屏第一个可点对象是无文字图标，本地没有把握说出它是什么，所以停下来问你一句。";
            default:
                return "为什么这么播：这一档没有对应的本地规则，按没看清处理，不猜。";
        }
    }

    /** 百分比取整：输出里不出现小数点（判据见类注释第二条）。 */
    private static int pct(double ratio) {
        if (Double.isNaN(ratio)) {
            return 0;
        }
        return (int) Math.round(ratio * 100.0d);
    }
}
