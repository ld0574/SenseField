package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 任务书 26 的报警器：钉住「状态码 → 句子里那段人话」的映射与接线。
 *
 * <p>锁四件事：① 六个可达码各自的输出；② 六码之外（半截、空串、null、大小写不同）一律不加、
 * 且绝不因为解析不了就丢行或抛；③ 新增语义<b>只作附加</b>——「可点／不可点」四个字必须原样还在，
 * 位置固定在语义之后、状态段之前；④ 状态码原文与坐标都不许进句子，敏感闸门仍旧一条也不念。
 *
 * <p>期望串全部由人写死，不拿被测函数的输出反推。行格式照 {@code SenseFieldReaderService}
 * 的 {@code state2} 拼装手搓。纯 JVM：本机无 adb、无设备，本类只证代码路径，不证真机听感。
 */
public final class StatePhraseContractTest {

    private static final String HEADER = "app=com.example.shop activity=MainActivity dark=false";

    /** 六个可达码，各配一行；可点与不可点两种都覆盖到。 */
    private static final String E1_CLICKY = "n0 [顶栏] Button 可点 状态=E1 \"灯一\" @[10,20][30,40]";
    private static final String E1_STILL = "n0 [主内容] TextView 状态=E1 \"灯二\" @[10,20][30,40]";
    private static final String E0_STILL = "n0 [主内容] TextView 状态=E0 \"特价\" @[10,20][30,40]";
    private static final String E0_CLICKY = "n0 [底栏] Button 可点 状态=E0 \"买它\" @[10,20][30,40]";
    private static final String E_MINUS = "n0 [顶栏] Button 可点 状态=E- \"搜索\" @[10,20][30,40]";
    private static final String M1_CLICKY = "n0 [底栏] Button 可点 状态=-1 \"提交\" @[10,20][30,40]";
    private static final String M0_STILL = "n0 [主内容] CheckBox 状态=-0 \"记住我\" @[10,20][30,40]";
    private static final String MM_STILL = "n0 [主内容] FrameLayout 状态=-- \"空位\" @[10,20][30,40]";
    /** 敏感行：闸门命中后一条也不许念。 */
    private static final String PASSWORD = "n4 [底栏] EditText 状态=-1 \"请输入支付密码\" @[10,40][90,46]";

    private static String stateOf(String... nodes) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (String n : nodes) {
            sb.append(n).append('\n');
        }
        return sb.toString();
    }

    /** 只跑一行，取出那一条句子；没有句子就直接失败，免得断言对着空列表假绿。 */
    private static String one(String line) {
        List<String> out = LocalReadAloud.sentences(stateOf(line), 12);
        assertEquals("这一行本该产出一条句子，实际=" + out, 1, out.size());
        return out.get(0);
    }

    /* ---------- ① 六个码各自的输出 ---------- */

    /* 1. E1：可用是常态所以不说，只把选中位念成「已选中」。 */
    @Test
    public void enabledAndCheckedAddsOnlySelected() {
        assertEquals("第 1 条：灯一，可点，已选中", one(E1_CLICKY));
    }

    /* 2. E1 不可点的行：可点词换成不可点，状态段照旧只加已选中。 */
    @Test
    public void enabledAndCheckedOnNonClickableRowKeepsUnclickableWord() {
        assertEquals("第 1 条：灯二，不可点，已选中", one(E1_STILL));
    }

    /* 3. E0：只加「未选中」。 */
    @Test
    public void enabledAndNotCheckedAddsOnlyUnselected() {
        assertEquals("第 1 条：特价，不可点，未选中", one(E0_STILL));
    }

    /* 4. E-：可用＋不可选，两个都不念，句子与新增之前一字相同（不许留尾逗号）。 */
    @Test
    public void enabledAndNotCheckableAddsNothingAtAll() {
        assertEquals("第 1 条：搜索，可点", one(E_MINUS));
        assertFalse("E- 是本来的常态句，不该多出任何状态段：" + one(E_MINUS), one(E_MINUS).contains("，，"));
    }

    /* 5. -1：先说不可用，再说已选中。 */
    @Test
    public void disabledAndCheckedSaysUnavailableThenSelected() {
        assertEquals("第 1 条：提交，可点，当前不可用、已选中", one(M1_CLICKY));
    }

    /* 6. -0：先说不可用，再说未选中。 */
    @Test
    public void disabledAndNotCheckedSaysUnavailableThenUnselected() {
        assertEquals("第 1 条：记住我，不可点，当前不可用、未选中", one(M0_STILL));
    }

    /* 7. --：只说不可用，不许把「不可选」念成「未选中」。 */
    @Test
    public void fullyDisabledSaysOnlyUnavailable() {
        String s = one(MM_STILL);
        assertEquals("第 1 条：空位，不可点，当前不可用", s);
        assertFalse("不可选不等于未选中，不该念出未选中：" + s, s.contains("未选中"));
    }

    /* 8. 六码的输出逐个直接过映射函数，确认与句子装配层的读数是同一套。 */
    @Test
    public void mappingTableCoversExactlyTheSixReachableCodes() {
        assertEquals(Arrays.asList("已选中", "未选中", "", "当前不可用、已选中",
                        "当前不可用、未选中", "当前不可用"),
                Arrays.asList(LocalReadAloud.statePhrase("E1"), LocalReadAloud.statePhrase("E0"),
                        LocalReadAloud.statePhrase("E-"), LocalReadAloud.statePhrase("-1"),
                        LocalReadAloud.statePhrase("-0"), LocalReadAloud.statePhrase("--")));
    }

    /* ---------- ② 六码之外：不加、不抛、不丢行 ---------- */

    /* 9. 半截码（只有一位）、三位码、空串：都不加，且这一行照常念出来。 */
    @Test
    public void malformedCodesAddNothingButNeverDropTheLine() {
        assertEquals("第 1 条：搜索，可点", one("n0 [顶栏] Button 可点 状态=E \"搜索\" @[1,2][3,4]"));
        assertEquals("第 1 条：搜索，可点", one("n0 [顶栏] Button 可点 状态=E12 \"搜索\" @[1,2][3,4]"));
        assertEquals("第 1 条：搜索，可点", one("n0 [顶栏] Button 可点 状态= \"搜索\" @[1,2][3,4]"));
        assertEquals("", LocalReadAloud.statePhrase(""));
        assertEquals("", LocalReadAloud.statePhrase("E"));
        assertEquals("", LocalReadAloud.statePhrase("E12"));
    }

    /* 10. 大小写不同不是采集侧能产出的码：一律不加，也不抛。 */
    @Test
    public void lowerCaseCodesAreNotRecognisedAndAddNothing() {
        assertEquals("", LocalReadAloud.statePhrase("e1"));
        assertEquals("", LocalReadAloud.statePhrase("E1".toLowerCase()));
        assertEquals("", LocalReadAloud.statePhrase("-e"));
        assertEquals("第 1 条：搜索，可点", one("n0 [顶栏] Button 可点 状态=e1 \"搜索\" @[1,2][3,4]"));
    }

    /* 11. null 喂进映射不许炸。 */
    @Test
    public void nullCodeYieldsEmptyPhraseWithoutThrowing() {
        try {
            assertEquals("", LocalReadAloud.statePhrase(null));
        } catch (RuntimeException e) {
            throw new AssertionError("解析不了就丢整行是禁止的，statePhrase(null) 抛了：" + e, e);
        }
    }

    /* 12. 行里压根没有状态标记：不加、不抛，句子照旧。 */
    @Test
    public void lineWithoutStateMarkerIsReadUnchanged() {
        assertEquals("第 1 条：搜索，可点", one("n0 [顶栏] Button 可点 \"搜索\" @[1,2][3,4]"));
    }

    /* 13. 脏行（半截字节被拦腰）仍只跳过、不抛，后面的正常行照旧产句。 */
    @Test
    public void dirtyTruncatedLineStillSkippedNotThrown() {
        List<String> out = LocalReadAloud.sentences(stateOf(
                "n0 [顶栏] But",
                "n1 [主内",
                "n2 状态=-1",
                E1_CLICKY), 12);
        assertEquals(Collections.singletonList("第 1 条：灯一，可点，已选中"), out);
    }

    /* ---------- ③ 新增只作附加：可点／不可点不许被替换 ---------- */

    /* 14. 六个码全跑一遍：每一句都必须仍含「可点」或「不可点」，一字未换。 */
    @Test
    public void clickableWordsSurviveOnEveryStateCode() {
        for (String line : Arrays.asList(E1_CLICKY, E1_STILL, E0_STILL, E0_CLICKY, E_MINUS,
                M1_CLICKY, M0_STILL, MM_STILL)) {
            String s = one(line);
            boolean hasClickable = s.contains("，可点") || s.contains("，不可点")
                    || s.contains("，可点，") || s.contains("，不可点，");
            assertTrue("「可点／不可点」被新语义替换掉了：" + s, hasClickable);
        }
    }

    /* 15. 「可用」不许单独出现（可用是常态）：把「当前不可用」整段剥掉后，句子里不该再剩「可用」。 */
    @Test
    public void availableIsNeverReadAsACompliment() {
        for (String line : Arrays.asList(E1_CLICKY, E0_STILL, E_MINUS, M1_CLICKY, M0_STILL, MM_STILL)) {
            String s = one(line).replace("当前不可用", "");
            assertFalse("可用是常态，不该被念成一句废话：" + one(line), s.contains("可用"));
        }
    }

    /* 16. 位置固定：状态段永远排在「可点／不可点」之后，且整句只有语义、可不可点、状态这三段。 */
    @Test
    public void stateSegmentSitsLastAndNeverBeforeClickableWord() {
        for (String line : Arrays.asList(E1_CLICKY, E0_CLICKY, M1_CLICKY)) {
            String s = one(line);
            int click = Math.max(s.lastIndexOf("可点"), s.lastIndexOf("不可点"));
            int phrase = Math.max(s.lastIndexOf("已选中"), s.lastIndexOf("未选中"));
            assertTrue("状态段跑到可点前面去了：" + s, phrase > click);
            assertTrue("句子结构不是语义→可不可点→状态：" + s,
                    s.startsWith("第 1 条：") && s.indexOf('，') < click);
        }
        assertEquals("第 1 条：空位，不可点，当前不可用", one(MM_STILL));
    }

    /* ---------- ④ 原文、坐标、闸门 ---------- */

    /* 17. 输出里不许出现状态码原文，也不许出现坐标片段。 */
    @Test
    public void noRawStatusCodeAndNoCoordinateEverReachesOutput() {
        List<String> out = LocalReadAloud.sentences(
                stateOf(E1_CLICKY, E0_STILL, E_MINUS, M1_CLICKY, M0_STILL, MM_STILL), 12);
        assertEquals(6, out.size());
        for (String s : out) {
            for (String code : Arrays.asList("E1", "E0", "E-", "-1", "-0", "--", "状态=")) {
                assertFalse("漏出状态码原文 " + code + "：" + s, s.contains(code));
            }
            assertFalse("漏出坐标：" + s, s.contains("@["));
            assertFalse("漏出坐标片段：" + s, s.contains("]["));
            assertFalse("漏出包名：" + s, s.contains("com.example"));
        }
    }

    /* 18. 敏感闸门优先于新功能：命中时仍一条也不念，新路径不许绕开它。 */
    @Test
    public void sensitivePageStillReturnsEmptyListThroughTheNewPath() {
        String sensitive = stateOf(E1_CLICKY, M1_CLICKY, PASSWORD);
        assertEquals("密码或凭证", Match3Gate.sensitiveHit(sensitive));
        assertTrue("敏感页必须一条也不念，实际=" + LocalReadAloud.sentences(sensitive, 12),
                LocalReadAloud.sentences(sensitive, 12).isEmpty());
        assertEquals("", LocalReadAloud.speech(sensitive, 12));
    }

    /* 19. 多行混排：只有该加长的码才加长，句长与状态码取值严格对应。 */
    @Test
    public void mixedScreenLengthensOnlyTheCodesThatNeedIt() {
        List<String> out = LocalReadAloud.sentences(stateOf(E_MINUS, E1_CLICKY, MM_STILL), 12);
        assertEquals(Arrays.asList("第 1 条：搜索，可点", "第 2 条：灯一，可点，已选中",
                "第 3 条：空位，不可点，当前不可用"), out);
    }

    /* 20. speech 串句时也一样：新段落跟着进整句，可点词不丢。 */
    @Test
    public void speechJoinsSentencesWithoutLosingTheClickableWord() {
        String spoken = LocalReadAloud.speech(stateOf(E1_STILL, E_MINUS), 12);
        assertEquals("第 1 条：灯二，不可点，已选中 第 2 条：搜索，可点", spoken);
    }
}
