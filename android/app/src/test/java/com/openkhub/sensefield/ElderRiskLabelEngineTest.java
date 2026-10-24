package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 引擎档位语义：优先级、白名单、绿签名、插字符绕过、异常输入降级。 */
public class ElderRiskLabelEngineTest {

    private final ElderRiskLabelEngine engine = new ElderRiskLabelEngine();

    @Test
    public void impersonatingPoliceWithFreezeWordsIsRed() {
        ElderRiskLabel label = engine.evaluate("com.scam.browser", Arrays.asList(
                "您涉嫌洗钱案件", "公检法 专线", "需冻结 您的资金，转入安全账户核查"));
        assertEquals(ElderRiskLabel.Tier.RED, label.tier);
        assertEquals("RED-01", label.ruleId);
        assertFalse(label.explanation.isEmpty());
        assertTrue(label.evidence.contains("公检法"));
    }

    @Test
    public void whitespaceInsertionCannotEvadeMatching() {
        // 「医 保 异 常」中间插空格/换页符也要命中——归一化去全部空白。
        ElderRiskLabel label = engine.evaluate("com.scam.sms", Arrays.asList(
                "您的\n医 保\n待 遇 资 格 异 常", "限 期 处 理"));
        assertEquals(ElderRiskLabel.Tier.RED, label.tier);
        assertEquals("RED-02", label.ruleId);
    }

    @Test
    public void redOverridesWhitelistedPackage() {
        // 可信包名＋屏幕上出现诈骗话术＝红：内容证据优先于来源信任（防页面被劫持/仿冒）。
        ElderRiskLabel label = engine.evaluate("com.android.settings", Collections.singletonList(
                "恭喜您中奖了，先交手续费领取"));
        assertEquals(ElderRiskLabel.Tier.RED, label.tier);
        assertTrue(label.sourceTrusted);
    }

    @Test
    public void marketingOnlyIsYellowNotRed() {
        ElderRiskLabel label = engine.evaluate("com.shop.app", Arrays.asList(
                "限时抢购", "直播间专享 立减 50"));
        assertEquals(ElderRiskLabel.Tier.YELLOW, label.tier);
        assertEquals("YEL-02", label.ruleId);
    }

    @Test
    public void moneyRequestWithoutScamSignatureIsReviewNotRed() {
        ElderRiskLabel label = engine.evaluate("com.unknown.chat", Collections.singletonList(
                "请转账 到这个账户"));
        assertEquals(ElderRiskLabel.Tier.REVIEW, label.tier);
        assertEquals("REV-01", label.ruleId);
    }

    @Test
    public void officialServiceSignatureIsGreen() {
        ElderRiskLabel label = engine.evaluate("com.hospital.app", Collections.singletonList(
                "您的挂号成功，请于明早 9 点取药提醒已设置"));
        assertEquals(ElderRiskLabel.Tier.GREEN, label.tier);
        assertEquals("GREEN-SIG", label.ruleId);
    }

    @Test
    public void trustedPackageWithNoRuleHitIsGreenBySource() {
        ElderRiskLabel label = engine.evaluate("com.android.dialer",
                Collections.singletonList("通话记录"));
        assertEquals(ElderRiskLabel.Tier.GREEN, label.tier);
        assertEquals("GREEN-SRC", label.ruleId);
        assertTrue(label.sourceTrusted);
    }

    @Test
    public void ordinaryUnknownContentStaysNeutralAndSilent() {
        ElderRiskLabel label = engine.evaluate("com.news.app",
                Collections.singletonList("今日天气多云，气温 22 度"));
        assertEquals(ElderRiskLabel.Tier.NEUTRAL, label.tier);
        assertEquals(ElderRiskLabel.Announce.NONE, label.announceKind());
        assertFalse(label.sourceTrusted);
    }

    @Test
    public void malformedInputDegradesToNeutralInsteadOfThrowing() {
        assertEquals(ElderRiskLabel.Tier.NEUTRAL, engine.evaluate(null, null).tier);
        assertEquals(ElderRiskLabel.Tier.NEUTRAL,
                engine.evaluate("com.x", Arrays.asList(null, "", "  ")).tier);
    }

    @Test
    public void evidenceWindowIsBoundedNotWholeScreen() {
        StringBuilder page = new StringBuilder();
        for (int i = 0; i < 500; i++) page.append("前文填充内容");
        page.append("稳赚不赔 高息项目");
        for (int i = 0; i < 500; i++) page.append("后文填充内容");
        ElderRiskLabel label = engine.evaluate("com.x", Collections.singletonList(page.toString()));
        assertEquals(ElderRiskLabel.Tier.RED, label.tier);
        assertTrue("证据窗口必须带省略号且远小于整屏",
                label.evidence.length() < 100 && label.evidence.startsWith("…") && label.evidence.endsWith("…"));
    }

    @Test
    public void priorityRedBeatsYellowWhenBothPresent() {
        ElderRiskLabel label = engine.evaluate("com.x", Arrays.asList(
                "限时抢购 优惠券", "把验证码告诉我 就送"));
        assertEquals("RED-06", label.ruleId);
    }

    @Test
    public void oversizeInputIsTruncatedNotRejected() {
        List<String> huge = Collections.singletonList(
                repeat("普通内容", 20000) + "包治百病 神药");
        ElderRiskLabel label = engine.evaluate("com.x", huge);
        // 截断发生在 20000 字符处，红特征在截断点之后仍应命中不了——这是有界成本的体现。
        assertEquals(ElderRiskLabel.Tier.NEUTRAL, label.tier);
    }

    @Test
    public void oversizeButEarlyHitStillDetected() {
        List<String> huge = Collections.singletonList(
                "包治百病 神药" + repeat("普通内容", 20000));
        assertEquals(ElderRiskLabel.Tier.RED, engine.evaluate("com.x", huge).tier);
    }

    private static String repeat(String s, int times) {
        StringBuilder sb = new StringBuilder(s.length() * times);
        for (int i = 0; i < times; i++) sb.append(s);
        return sb.toString();
    }
}
