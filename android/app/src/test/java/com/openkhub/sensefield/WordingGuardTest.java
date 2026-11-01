package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 措辞守卫的纯 JVM 单测：期望值全部手写死，不从被测函数反推。
 * 覆盖面 = 任务书九任务 1 列的七项：七个黑名单词各自被抓、行号与文件名对得上、注释行不误报、
 * {@code DIRECT_ANNOUNCE} 标识符不误报、含「自动定位」的如实描述不误报、字面量里的执行词必报、
 * 空输入返回空列表。
 */
public class WordingGuardTest {

    private static final String FILE = "Match3ToolsActivity.java";

    private static List<WordingGuard.Hit> scan(String... lines) {
        return WordingGuard.check(FILE, Arrays.asList(lines));
    }

    /** 把一句中文包成一条「return "…";」语句再扫：省掉手写引号转义，期望值仍是手写的。 */
    private static List<WordingGuard.Hit> scanLiteral(String chineseContent) {
        return WordingGuard.check(FILE, Collections.singletonList(
                "        return \"" + chineseContent + "\";"));
    }

    private static List<String> termsOf(List<WordingGuard.Hit> hits) {
        List<String> out = new ArrayList<>();
        for (WordingGuard.Hit h : hits) {
            out.add(h.term);
        }
        return out;
    }

    /* 1. 黑名单就是那十一个执行类词组，一字不多一字不少（钉死 char 构造后的运行时值）。 */
    @Test
    public void blackListIsExactlyTheElevenExecutionPhrases() {
        assertEquals(11, WordingGuard.BLACKLIST.size());
        assertEquals(Arrays.asList("自动执行", "自动点击", "帮你点", "代打", "自动过关", "自动连招",
                "替你操作", "胜率", "上分", "躺赢", "代练"), new ArrayList<>(WordingGuard.BLACKLIST));
    }

    /* 2. 孤零零的「自动」不在黑名单里 —— 如实描述不该被判红。 */
    @Test
    public void blackListDoesNotContainTheBareWordAuto() {
        assertFalse(WordingGuard.BLACKLIST.contains("自动"));
        assertFalse(WordingGuard.BLACKLIST.contains("定位"));
    }

    /* 3. 十一个词各自都要被抓到，且抓到的就是那一个词。 */
    @Test
    public void eachBlackListedPhraseIsCaught() {
        assertEquals(Collections.singletonList("自动执行"), termsOf(scanLiteral("高置信·自动执行")));
        assertEquals(Collections.singletonList("自动点击"), termsOf(scanLiteral("我可以自动点击屏幕")));
        assertEquals(Collections.singletonList("帮你点"), termsOf(scanLiteral("要不要帮你点这一下")));
        assertEquals(Collections.singletonList("代打"), termsOf(scanLiteral("本工具可代打")));
        assertEquals(Collections.singletonList("自动过关"), termsOf(scanLiteral("自动过关进行中")));
        assertEquals(Collections.singletonList("自动连招"), termsOf(scanLiteral("自动连招已开启")));
        assertEquals(Collections.singletonList("替你操作"), termsOf(scanLiteral("它会替你操作")));
        assertEquals(Collections.singletonList("胜率"), termsOf(scanLiteral("提升胜率")));
        assertEquals(Collections.singletonList("上分"), termsOf(scanLiteral("帮你上分")));
        assertEquals(Collections.singletonList("躺赢"), termsOf(scanLiteral("轻松躺赢")));
        assertEquals(Collections.singletonList("代练"), termsOf(scanLiteral("提供代练服务")));
    }

    /* 4. 文件名与 1 基行号必须对得上。 */
    @Test
    public void hitCarriesFileNameAndOneBasedLineNumber() {
        List<WordingGuard.Hit> hits = scan(
                "    private static String decisionLabel(JevThresholds.Decision d) {",
                "        switch (d) {",
                "            case DIRECT_ANNOUNCE: return \"高置信·自动执行\";",
                "        }");
        assertEquals(1, hits.size());
        assertEquals(FILE, hits.get(0).file);
        assertEquals(3, hits.get(0).line);
        assertEquals("自动执行", hits.get(0).term);
        assertEquals(FILE + ":3 自动执行", hits.get(0).toString());
    }

    /* 5. 注释行不误报：行首 //、行首 /*、续行 * 三类都不算。 */
    @Test
    public void commentLinesAreNotReported() {
        assertTrue(scan("// 这里说\"自动执行\"只是注释").isEmpty());
        assertTrue(scan("    // 曾经写过 自动点击").isEmpty());
        assertTrue(scan("/** 高置信：自动执行（仍必须过白名单校验）。 */").isEmpty());
        assertTrue(scan(" * 文案里的 自动执行 属于注释。").isEmpty());
    }

    /* 6. 行尾注释不误报：代码里没有执行词，注释里有。 */
    @Test
    public void trailingCommentAfterCleanCodeIsNotReported() {
        assertTrue(scan("        return \"高置信\"; // 旧文案是 自动执行").isEmpty());
    }

    /* 7. 标识符不误报：DIRECT_ANNOUNCE 与中文标识符都落在引号之外。 */
    @Test
    public void identifiersAreNotReported() {
        assertTrue(scan("        assertEquals(JevThresholds.Decision.DIRECT_ANNOUNCE, t.decide(a));").isEmpty());
        assertTrue(scan("            case DIRECT_ANNOUNCE: return \"高置信\";").isEmpty());
        assertTrue(scan("    static final int 自动执行 = 1;").isEmpty());
    }

    /* 8. 如实描述不误报：含「自动」但不是执行类词组的字面量一律放行。 */
    @Test
    public void faithfulDescriptionsAreNotReported() {
        assertTrue(scan("        announce(\"已自动定位棋盘\");").isEmpty());
        assertTrue(scan("        log(\"自动记录诊断，不自动上传\");").isEmpty());
    }

    /* 9. 字面量里出现执行词必报，哪怕它被包在更长的句子里。 */
    @Test
    public void phraseInsideLongerLiteralIsReported() {
        List<WordingGuard.Hit> hits = scan("        return \"高置信·自动执行（仍需过白名单）\";");
        assertEquals(1, hits.size());
        assertEquals("自动执行", hits.get(0).term);
        assertEquals(1, hits.get(0).line);
    }

    /* 10. 一行两个字面量、两个词：两条命中，行号相同。 */
    @Test
    public void twoPhrasesOnOneLineYieldTwoHits() {
        List<WordingGuard.Hit> hits = scan("        return \"自动点击\" + \"帮你点\";");
        assertEquals(2, hits.size());
        assertEquals(Arrays.asList("自动点击", "帮你点"), termsOf(hits));
        assertEquals(1, hits.get(0).line);
        assertEquals(1, hits.get(1).line);
    }

    /* 11. 多行输入：每条命中各带自己的行号。 */
    @Test
    public void multiLineInputReportsEachLineSeparately() {
        List<WordingGuard.Hit> hits = scan(
                "        announce(\"自动过关开始\");",
                "        announce(\"普通播报\");",
                "        announce(\"自动连招开启\");");
        assertEquals(2, hits.size());
        assertEquals(1, hits.get(0).line);
        assertEquals("自动过关", hits.get(0).term);
        assertEquals(3, hits.get(1).line);
        assertEquals("自动连招", hits.get(1).term);
    }

    /* 12. 空输入返回空列表：空文件、空串、null 都不炸、不返回 null。 */
    @Test
    public void emptyInputYieldsEmptyList() {
        assertTrue(WordingGuard.check(FILE, Collections.<String>emptyList()).isEmpty());
        assertTrue(WordingGuard.checkText(FILE, "").isEmpty());
        assertTrue(WordingGuard.checkText(FILE, null).isEmpty());
        assertTrue(WordingGuard.check(null, Arrays.asList("自动执行")).isEmpty());
        assertEquals(0, WordingGuard.check(FILE, Arrays.asList("", "   ", "\"\"")).size());
    }

    /* 13. 正向命中：胜率。 */
    @Test
    public void winRatePhraseIsReportedWithFileAndLine() {
        List<WordingGuard.Hit> hits = scan(
                "    void label() {",
                "        announce(\"提升胜率\");",
                "    }");
        assertEquals(1, hits.size());
        assertEquals(FILE, hits.get(0).file);
        assertEquals(2, hits.get(0).line);
        assertEquals("胜率", hits.get(0).term);
        assertEquals(FILE + ":2 胜率", hits.get(0).toString());
    }

    /* 14. 正向命中：上分。 */
    @Test
    public void boostRankPhraseIsReportedWithFileAndLine() {
        List<WordingGuard.Hit> hits = scan(
                "    void label() {",
                "        announce(\"帮你上分\");",
                "    }");
        assertEquals(1, hits.size());
        assertEquals(FILE, hits.get(0).file);
        assertEquals(2, hits.get(0).line);
        assertEquals("上分", hits.get(0).term);
        assertEquals(FILE + ":2 上分", hits.get(0).toString());
    }

    /* 15. 正向命中：躺赢。 */
    @Test
    public void effortlessWinPhraseIsReportedWithFileAndLine() {
        List<WordingGuard.Hit> hits = scan(
                "    void label() {",
                "        announce(\"轻松躺赢\");",
                "    }");
        assertEquals(1, hits.size());
        assertEquals(FILE, hits.get(0).file);
        assertEquals(2, hits.get(0).line);
        assertEquals("躺赢", hits.get(0).term);
        assertEquals(FILE + ":2 躺赢", hits.get(0).toString());
    }

    /* 16. 正向命中：代练。 */
    @Test
    public void paidBoostingPhraseIsReportedWithFileAndLine() {
        List<WordingGuard.Hit> hits = scan(
                "    void label() {",
                "        announce(\"提供代练服务\");",
                "    }");
        assertEquals(1, hits.size());
        assertEquals(FILE, hits.get(0).file);
        assertEquals(2, hits.get(0).line);
        assertEquals("代练", hits.get(0).term);
        assertEquals(FILE + ":2 代练", hits.get(0).toString());
    }

    /* 17. 合同锁定：黑名单逐字等于 11 项集合，顺序按代码里的实际顺序。 */
    @Test
    public void blackListContractIsExactlyElevenPhrasesInOrder() {
        assertEquals(Arrays.asList(
                "自动执行", "自动点击", "帮你点", "代打", "自动过关", "自动连招",
                "替你操作", "胜率", "上分", "躺赢", "代练"),
                new ArrayList<>(WordingGuard.BLACKLIST));
    }

    /* 18. 改名续账（任务书 45）②：标识符不误报这条属性换成新枚举名继续成立。
     *     三行都是生产代码现在的真实形状，全都落在引号之外或落在注释上 ⇒ 0 命中。 */
    @Test
    public void renamedIdentifierIsStillNotReported() {
        assertTrue(scan("        if (c >= high) return Decision.DIRECT_ANNOUNCE;").isEmpty());
        assertTrue(scan("            case DIRECT_ANNOUNCE: return \"高置信·直接播，不用确认\";").isEmpty());
        assertTrue(scan("        /** 高置信：直接播报，不用确认（仍必须过白名单校验）。 */").isEmpty());
    }

    /* 19. 改名续账（任务书 45）③：守卫仍报字面量里的执行词——哪怕同一行的标识符已经换成 honest 的
     *     新名字。这条是报警器本身：删掉样本、或把执行词换成中性词，本条立刻真红。 */
    @Test
    public void literalExecutionPhraseIsStillCaughtBesideRenamedIdentifier() {
        List<WordingGuard.Hit> hits = scan(
                "    private static String decisionLabel(JevThresholds.Decision d) {",
                "        switch (d) {",
                "            case DIRECT_ANNOUNCE: return \"高置信·自动执行\";",
                "        }");
        assertEquals(1, hits.size());
        assertEquals(FILE, hits.get(0).file);
        assertEquals(3, hits.get(0).line);
        assertEquals("自动执行", hits.get(0).term);
        assertEquals(FILE + ":3 自动执行", hits.get(0).toString());
    }
}
