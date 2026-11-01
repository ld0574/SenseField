package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link DecisionWhy} 的纯 JVM 单测。期望值全部手写中文，不从被测函数的输出反推。
 *
 * <p>两条硬检查按任务书一要求落在这里：任何档位的输出都不得含坐标 {@code @[}，
 * 也不得含带点号的包名或模型名字面量（判据：ASCII 字母/数字 + {@code .} + ASCII 字母/数字）。
 * 另加任务 2 那条纪律的证伪点：敏感档位即使收到原文片段也只拼固定话术。
 */
public class DecisionWhyTest {

    private static final DecisionWhy.Kind[] ALL = {
            DecisionWhy.Kind.NOT_RUNNING,
            DecisionWhy.Kind.NOT_READY,
            DecisionWhy.Kind.SENSITIVE,
            DecisionWhy.Kind.HIGH,
            DecisionWhy.Kind.MID,
            DecisionWhy.Kind.LOW,
            DecisionWhy.Kind.ABSTAIN,
            DecisionWhy.Kind.ICON_DISAMBIG,
    };

    /** 一段典型的「命中原文」：敏感闸门背后的压缩状态片段。任何档位都不许把它带进展示。 */
    private static final String RAW_HIT = "EditText \"请输入您的密码\" @[412,980] clickable=true";

    private static String why(DecisionWhy.Kind kind, double c, double high, double mid) {
        return DecisionWhy.explain(kind, c, high, mid, RAW_HIT);
    }

    private static boolean hasDottedAsciiName(String s) {
        for (int i = 1; i + 1 < s.length(); i++) {
            if (s.charAt(i) != '.') {
                continue;
            }
            if (isAsciiAlnum(s.charAt(i - 1)) && isAsciiAlnum(s.charAt(i + 1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiAlnum(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /* 1. 高置信：说清「够稳所以直接播」，并带上换算成整数百分比的读数。 */
    @Test
    public void highConfidenceExplainsDirectPlayback() {
        String out = why(DecisionWhy.Kind.HIGH, 0.92d, 0.85d, 0.60d);
        assertTrue(out, out.contains("置信度 92%"));
        assertTrue(out, out.contains("高线 85%"));
        assertTrue(out, out.contains("直接播"));
        assertFalse(out, out.contains("不太确定"));
    }

    /* 2. 中置信：说清为什么句尾要加「不太确定」。 */
    @Test
    public void midConfidenceExplainsTheHedge() {
        String out = why(DecisionWhy.Kind.MID, 0.70d, 0.85d, 0.60d);
        assertTrue(out, out.contains("不太确定"));
        assertTrue(out, out.contains("置信度 70%"));
        assertTrue(out, out.contains("低线 60%"));
    }

    /* 3. 低置信：说清宁可不念。 */
    @Test
    public void lowConfidenceExplainsRefusalToGuess() {
        String out = why(DecisionWhy.Kind.LOW, 0.41d, 0.85d, 0.60d);
        assertTrue(out, out.contains("低于低线 60%"));
        assertTrue(out, out.contains("不猜"));
        assertFalse(out, out.contains("直接播"));
    }

    /* 4. ABSTAIN：判定层自己投了信息不足。 */
    @Test
    public void abstainExplainsInsufficientInformation() {
        String out = why(DecisionWhy.Kind.ABSTAIN, 0.0d, 0.85d, 0.60d);
        assertTrue(out, out.contains("信息不足"));
        assertTrue(out, out.contains("不猜"));
    }

    /* 5. 敏感闸门：只许固定话术，收到的原文片段一个字节不许外泄（反向验证 (a) 的断言）。 */
    @Test
    public void sensitiveBranchNeverEchoesMatchedText() {
        String out = why(DecisionWhy.Kind.SENSITIVE, 0.99d, 0.85d, 0.60d);
        assertTrue(out, out.contains("不上云"));
        assertTrue(out, out.contains("不念细节"));
        assertFalse(out, out.contains(RAW_HIT));
        assertFalse(out, out.contains("请输入您的密码"));
        assertFalse(out, out.contains("EditText"));
        assertFalse(out, out.contains("clickable"));
    }

    /* 6. 敏感档位与命中片段无关：换任何片段，固定话术的关键字仍在。 */
    @Test
    public void sensitiveWordingIsIndependentOfFragment() {
        String a = DecisionWhy.explain(DecisionWhy.Kind.SENSITIVE, 0.9d, 0.85d, 0.60d, "卡号 6222020000000000");
        String b = DecisionWhy.explain(DecisionWhy.Kind.SENSITIVE, 0.9d, 0.85d, 0.60d, null);
        assertFalse(a, a.contains("6222020000000000"));
        assertTrue(a, a.contains("手动处理"));
        assertTrue(b, b.contains("本地敏感规则"));
    }

    /* 7. 图标消歧：说清为什么要停下来问一句，且不外泄节点行（反向验证 (b) 的断言）。 */
    @Test
    public void iconDisambiguationExplainsTheQuestion() {
        String out = why(DecisionWhy.Kind.ICON_DISAMBIG, 0.5d, 0.85d, 0.60d);
        assertTrue(out, out.contains("无文字图标"));
        assertTrue(out, out.contains("问你一句"));
        assertFalse(out, out.contains("@["));
    }

    /* 8. 未运行／未就绪两条提前返回也要有自己的说明行。 */
    @Test
    public void earlyReturnBranchesExplainWhyNothingWasPlayed() {
        String notRunning = why(DecisionWhy.Kind.NOT_RUNNING, 0.0d, 0.85d, 0.60d);
        String notReady = why(DecisionWhy.Kind.NOT_READY, 0.0d, 0.85d, 0.60d);
        assertTrue(notRunning, notRunning.contains("判定层未启用"));
        assertTrue(notRunning, notRunning.contains("没有内容上云"));
        assertTrue(notReady, notReady.contains("读屏状态服务"));
        assertFalse(notRunning, notRunning.contains("92%"));
    }

    /* 9. 硬检查一：所有档位的输出都不含坐标方括号。 */
    @Test
    public void noOutputEverCarriesCoordinates() {
        for (DecisionWhy.Kind kind : ALL) {
            String out = why(kind, 0.92d, 0.85d, 0.60d);
            assertFalse(kind + " 漏出坐标", out.contains("@["));
        }
    }

    /* 10. 硬检查二：所有档位的输出都不含带点号的包名或模型名字面量。 */
    @Test
    public void noOutputEverCarriesDottedNames() {
        for (DecisionWhy.Kind kind : ALL) {
            String out = why(kind, 0.92d, 0.85d, 0.60d);
            assertFalse(kind + " 漏出带点号的名字", hasDottedAsciiName(out));
        }
    }

    /* 11. 阈值读数真的参与了输出（1 位小数取整成百分比），且小数点不会因此出现。 */
    @Test
    public void thresholdsAreRenderedAsRoundedPercent() {
        String out = why(DecisionWhy.Kind.HIGH, 0.855d, 0.8501d, 0.6d);
        assertTrue(out, out.contains("86%"));
        assertTrue(out, out.contains("85%"));
        assertFalse(out, hasDottedAsciiName(out));
    }

    /* 12. 档位缺失（null）不静默、不编造，按没看清说。 */
    @Test
    public void nullKindFallsBackToNoGuess() {
        String out = DecisionWhy.explain(null, 0.9d, 0.85d, 0.60d, RAW_HIT);
        assertTrue(out, out.contains("按没看清处理"));
        assertFalse(out, out.contains("@["));
    }

    /* 13. 枚举就是现成判定面的八个档位，没有新增判定。 */
    @Test
    public void kindTableMatchesTheExistingDecisionSurface() {
        assertEquals(8, DecisionWhy.Kind.values().length);
        assertEquals("HIGH", DecisionWhy.Kind.HIGH.name());
        assertEquals("ICON_DISAMBIG", DecisionWhy.Kind.ICON_DISAMBIG.name());
    }
}
