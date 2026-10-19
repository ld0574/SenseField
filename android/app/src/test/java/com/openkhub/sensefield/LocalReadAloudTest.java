package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link LocalReadAloud} 的纯 JVM 单测：所有期望串由人写死，不拿被测函数的输出反推。
 * 行样例照 {@code SenseFieldReaderService:151-155} 的实际拼装格式手搓。
 *
 * <p>两条硬检查按任务书二完成条件 3 落在这里：任何输出都不许出现 {@code @[} 坐标格式；
 * 每一条句子都以「第 N 条：」开头且 N 逐条递增。
 */
public class LocalReadAloudTest {

    private static final String HEADER = "app=com.example.shop activity=MainActivity dark=false";

    private static final String N_SEARCH =
            "n0 [顶栏] Button 可点 状态=E- \"搜索\" @[24,96][120,180]";
    private static final String N_TITLE =
            "n1 [主内容] TextView 状态=E1 \"本周必买\" @[24,300][400,360]";
    private static final String N_ICON_NO_LABEL =
            "n2 [主内容] ImageView 可点 状态=E- \"\" @[500,300][600,400]";
    private static final String N_SHELL =
            "n3 [主内容] LinearLayout 状态=-- \"\" @[0,200][1080,900]";
    private static final String N_PASSWORD =
            "n4 [底栏] EditText 状态=E- \"请输入您的密码\" @[10,1400][900,1460]";
    private static final String N_SUBMIT =
            "n5 [底栏] Button 可点 状态=E- \"提交订单\" @[10,1400][900,1460]";

    private static String stateOf(String... nodes) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (String n : nodes) {
            sb.append(n).append('\n');
        }
        return sb.toString();
    }

    /* 1. 空文本 / null / 只有空白：一条也不生成，也不炸。 */
    @Test
    public void emptyStateYieldsNoSentences() {
        assertTrue(LocalReadAloud.sentences(null).isEmpty());
        assertTrue(LocalReadAloud.sentences("").isEmpty());
        assertTrue(LocalReadAloud.sentences("   \n\t\n").isEmpty());
        assertEquals("", LocalReadAloud.speech("", LocalReadAloud.DEFAULT_LIMIT));
    }

    /* 2. 单条：只有节点行才进句子。 */
    @Test
    public void singleNodeBecomesOneSentence() {
        assertEquals(Collections.singletonList("第 1 条：搜索，可点"),
                LocalReadAloud.sentences(stateOf(N_SEARCH), 12));
    }

    /* 3. 多条：可点与不可点都要说清，顺序就是压缩状态从上到下的顺序。 */
    @Test
    public void multipleNodesKeepOrderAndClickableFlag() {
        List<String> out = LocalReadAloud.sentences(stateOf(N_SEARCH, N_TITLE), 12);
        assertEquals(Arrays.asList("第 1 条：搜索，可点", "第 2 条：本周必买，不可点，已选中"), out);
    }

    /* 4. 无 label、只有 className 的可点节点：说成「没有文字的控件」，不念类名。 */
    @Test
    public void clickableNodeWithoutLabelIsDescribedWithoutClassName() {
        List<String> out = LocalReadAloud.sentences(stateOf(N_ICON_NO_LABEL), 12);
        assertEquals(Collections.singletonList("第 1 条：没有文字的控件，可点"), out);
        assertFalse(out.get(0), out.get(0).contains("ImageView"));
    }

    /* 5. 只有坐标、既无文字又不可点的行＝容器空壳：整行跳过。 */
    @Test
    public void shellNodeAndBareCoordinatesAreSkipped() {
        assertTrue(LocalReadAloud.sentences(stateOf(N_SHELL), 12).isEmpty());
        assertTrue(LocalReadAloud.sentences(stateOf("@[12,940][120,180]"), 12).isEmpty());
        assertEquals(Collections.singletonList("第 1 条：搜索，可点"),
                LocalReadAloud.sentences(stateOf(N_SEARCH, N_SHELL), 12));
    }

    /* 6. 带状态码的行：状态码不进朗读（念出来是噪声），语义仍由可点/不可点承担。 */
    @Test
    public void stateCodesAreNotReadAloud() {
        List<String> out = LocalReadAloud.sentences(stateOf(N_TITLE), 12);   // 状态=E1
        assertEquals(Collections.singletonList("第 1 条：本周必买，不可点，已选中"), out);
        String s = out.get(0);
        assertFalse(s, s.contains("状态="));
        assertFalse(s, s.contains("E1"));
    }

    /* 7. 超上限：截断并在末句用同一种编号说清还剩几条。 */
    @Test
    public void overLimitTruncatesAndReportsRemaining() {
        List<String> out = LocalReadAloud.sentences(
                stateOf(N_SEARCH, N_TITLE, N_ICON_NO_LABEL, N_SEARCH, N_TITLE), 3);
        assertEquals(4, out.size());
        assertEquals("第 1 条：搜索，可点", out.get(0));
        assertEquals("第 3 条：没有文字的控件，可点", out.get(2));
        assertEquals("第 4 条往后：还有 2 条没念，想听完请再说一次。", out.get(3));
    }

    /* 8. 脏行（半截字节被拦腰）只跳过，不影响后面的正常行，也不抛异常。 */
    @Test
    public void dirtyTruncatedLinesAreSkippedNotThrown() {
        List<String> out = LocalReadAloud.sentences(stateOf(
                "n0 [顶栏] But",                       // 拦腰截断
                "n1 [主内",                            // 连 ID 都不成形
                N_SEARCH), 12);
        assertEquals(Collections.singletonList("第 1 条：搜索，可点"), out);
    }

    /* 9. 硬检查一：任何输出的任何一句都不许带 @[ 坐标格式，也不带 ][ 片段。 */
    @Test
    public void noSentenceEverCarriesCoordinates() {
        List<String> out = LocalReadAloud.sentences(
                stateOf(N_SEARCH, N_TITLE, N_ICON_NO_LABEL, N_SHELL, N_SUBMIT), 12);
        assertFalse(out.isEmpty());
        for (String s : out) {
            assertFalse("漏出坐标：" + s, s.contains("@["));
            assertFalse("漏出坐标片段：" + s, s.contains("]["));
            assertFalse("漏出裸方括号坐标：" + s, s.contains("[24,96]"));
        }
    }

    /* 9b. 标签里混进坐标片段也要剥干净：保守做法是遇到第一个 @ 就截断，宁可少念也不念坐标。 */
    @Test
    public void coordinatesInsideLabelAreStripped() {
        List<String> out = LocalReadAloud.sentences(
                stateOf("n0 [顶栏] Button 可点 状态=E- \"楼层 @[7,8][9,10] 导航\" @[1,2][3,4]"), 12);
        assertEquals(Collections.singletonList("第 1 条：楼层，可点"), out);
    }

    /* 10. 硬检查二：每句都以「第 N 条：」开头，N 从 1 逐条递增。 */
    @Test
    public void everySentenceStartsWithSequentialNumbering() {
        List<String> out = LocalReadAloud.sentences(stateOf(N_SEARCH, N_TITLE, N_ICON_NO_LABEL), 12);
        assertEquals(3, out.size());
        for (int i = 0; i < out.size(); i++) {
            assertTrue(out.get(i), out.get(i).startsWith("第 " + (i + 1) + " 条："));
        }
    }

    /* 11. 安全闸门优先于功能：敏感页命中时一条也不念（明细不外泄）。 */
    @Test
    public void sensitivePageIsNotReadItemAtAll() {
        String sensitive = stateOf(N_SEARCH, N_TITLE, N_PASSWORD);
        assertTrue("敏感页必须一条也不念，实际=" + LocalReadAloud.sentences(sensitive, 12),
                LocalReadAloud.sentences(sensitive, 12).isEmpty());
        assertEquals("", LocalReadAloud.speech(sensitive, 12));
    }

    /* 12. 非敏感页照常念，且状态头永不进句子。 */
    @Test
    public void nonSensitivePageIsReadAndHeaderNeverAppears() {
        String plain = stateOf(N_SEARCH, N_TITLE);
        List<String> out = LocalReadAloud.sentences(plain, 12);
        assertEquals(2, out.size());
        for (String s : out) {
            assertFalse(s, s.contains("app="));
            assertFalse(s, s.contains("MainActivity"));
        }
    }

    /* 13. limit<=0 走默认上限 12：不给「一条也不读」留口子，条数没超就不出现末句。 */
    @Test
    public void nonPositiveLimitFallsBackToDefault() {
        List<String> out = LocalReadAloud.sentences(
                stateOf(N_SEARCH, N_TITLE, N_ICON_NO_LABEL, N_SEARCH), 0);
        assertEquals(4, out.size());
        assertEquals("第 4 条：搜索，可点", out.get(3));
        assertFalse(out.get(3), out.get(3).contains("没念"));
    }
}
