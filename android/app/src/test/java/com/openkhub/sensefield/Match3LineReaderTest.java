package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * 逐条读生成器的 JVM 单测。期望全部由人按 {@code SenseFieldReaderService} 压缩口径手写，
 * 不拿被测函数的输出反推。两条硬检查：输出永不含 {@code @[} 坐标格式；每句都以「第 N 条：」开头且序号连续。
 */
public class Match3LineReaderTest {

    private static final String FIXTURE =
            "app=com.example.demo activity=Main dark=false\n"
            + "n0 [顶栏] ImageButton 可点 状态=E- \"返回\" @[24,80][144,176]\n"
            + "n1 [顶栏] TextView 状态=E- \"聊天\" @[480,100][720,160]\n"
            + "\n"
            + "n2 [主内容] LinearLayout 状态=E- \"\" @[0,200][1080,1800]\n"
            + "n3 [主内容] EditText 可点 状态=E- \"今晚七点吃饭\" @[60,900][700,980]\n"
            + "n4 [主内容] CheckBox 可点 状态=E1 \"记住我\" @[60,1000][300,1060]\n"
            + "n5 [底栏] Button 可点 状态=E- \"发送\" @[900,1900][1060,1980]\n";

    private static String joined(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    @Test
    public void emptyOrMissingStateReadsNothing() {
        assertTrue(Match3LineReader.read("").isEmpty());
        assertTrue(Match3LineReader.read(null).isEmpty());
        assertTrue(Match3LineReader.read("app=com.example.demo dark=false\n").isEmpty());
    }

    @Test
    public void singleNodeBecomesOneSentence() {
        String state = "app=com.example.demo\n"
                + "n0 [顶栏] ImageButton 可点 状态=E- \"返回\" @[24,80][144,176]\n";
        List<String> lines = Match3LineReader.read(state);
        assertEquals(1, lines.size());
        assertEquals("第 1 条：返回，可点。", lines.get(0));
    }

    @Test
    public void manyNodesTopDownWithClickableFlag() {
        List<String> lines = Match3LineReader.read(FIXTURE);
        assertEquals(5, lines.size());
        assertEquals("第 1 条：返回，可点。", lines.get(0));
        assertEquals("第 2 条：聊天，不可点。", lines.get(1));
        assertEquals("第 3 条：今晚七点吃饭，可点。", lines.get(2));
        assertEquals("第 4 条：记住我，可点。", lines.get(3));
        assertEquals("第 5 条：发送，可点。", lines.get(4));
    }

    @Test
    public void emptyLabelShellIsSkipped() {
        String state = "app=com.example.demo\n"
                + "n0 [主内容] LinearLayout 状态=E- \"\" @[0,200][1080,1800]\n";
        assertTrue(Match3LineReader.read(state).isEmpty());
    }

    @Test
    public void coordinateOnlyLineIsSkipped() {
        String state = "app=com.example.demo\n@[32,400][900,480]\n";
        assertTrue(Match3LineReader.read(state).isEmpty());
    }

    @Test
    public void missingLabelFallsBackToClassAndStateCode() {
        String state = "app=com.example.demo\n"
                + "n0 [底栏] View 可点 状态=E- @[10,20][30,40]\n";
        List<String> lines = Match3LineReader.read(state);
        assertEquals(1, lines.size());
        assertEquals("第 1 条：View控件启用，可点。", lines.get(0));
    }

    @Test
    public void checkedStateCodeIsSpokenAsWords() {
        String state = "app=com.example.demo\n"
                + "n0 [主内容] CheckBox 状态=E1 @[10,20][30,40]\n";
        List<String> lines = Match3LineReader.read(state);
        assertEquals(1, lines.size());
        assertEquals("第 1 条：CheckBox控件启用且已选中，不可点。", lines.get(0));
    }

    @Test
    public void stateCodeNeverSpokenAsRawLetters() {
        List<String> lines = Match3LineReader.read(FIXTURE);
        String all = joined(lines);
        assertFalse(all.contains("状态="));
        assertFalse(all.contains("E1"));
        assertFalse(all.contains("E-"));
    }

    @Test
    public void overLimitKeepsHeadAndTellsRemaining() {
        StringBuilder sb = new StringBuilder("app=com.example.demo\n");
        for (int i = 0; i < 12; i++) {
            sb.append("n").append(i).append(" [主内容] TextView 状态=E- \"第")
              .append(i). append("行文字\" @[").append(i).append(",0][9,9]\n");
        }
        List<String> lines = Match3LineReader.read(sb.toString(), 10);
        assertEquals(11, lines.size());
        assertEquals("第 1 条：第0行文字，不可点。", lines.get(0));
        assertEquals("第 10 条：第9行文字，不可点。", lines.get(9));
        assertEquals("后面还有 2 条没念。", lines.get(10));
    }

    @Test
    public void truncatedDirtyLinesAreSkippedNotSpoken() {
        String state = "app=com.example.demo\n"
                + "n0 [顶栏] TextView 状态=E- \"今天天气不错\" @[1,2][3,4]\n"
                + "n1 [主内容] EditText 可点 状态=E- \"被拦腰的半截文\n"
                + "n2 [主内容] Tex\n"
                + "n3 [主内容] TextView 状态=E- \"第二句\" @[5,6][7\n"
                + "n4 [ 主内容 TextView 状态=E- \"缺右括号\" @[8,9][1,1]\n";
        List<String> lines = Match3LineReader.read(state);
        assertEquals(2, lines.size());
        assertEquals("第 1 条：今天天气不错，不可点。", lines.get(0));
        assertEquals("第 2 条：第二句，不可点。", lines.get(1));
    }

    @Test
    public void hardNeverSpeaksCoordinateFormat() {
        String all = joined(Match3LineReader.read(FIXTURE))
                + joined(Match3LineReader.read("app=x\nn0 [主内容] TextView 状态=E- \"看这里 @[12,34]\""
                        + " @[10,20][30,40]\n"));
        assertFalse("逐条读不许把 @[ 坐标格式念出来", all.contains("@["));
        assertFalse(all.contains("24,80"));
        assertFalse(all.contains("1080,1800"));
    }

    @Test
    public void hardEverySentenceLeadsWithConsecutiveIndex() {
        List<String> lines = Match3LineReader.read(FIXTURE);
        assertFalse(lines.isEmpty());
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            assertTrue("每句必须以「第 " + (i + 1) + " 条：」开头，实测：" + l,
                    l.startsWith("第 " + (i + 1) + " 条："));
            assertTrue(l.endsWith("。"));
        }
    }

    @Test
    public void nonPositiveLimitReadsNothing() {
        assertTrue(Match3LineReader.read(FIXTURE, 0).isEmpty());
        assertTrue(Match3LineReader.read(FIXTURE, -3).isEmpty());
    }

    @Test
    public void zoneAndHeaderNoiseIsNotSpoken() {
        String all = joined(Match3LineReader.read(FIXTURE));
        assertFalse(all.contains("app="));
        assertFalse(all.contains("顶栏"));
        assertFalse(all.contains("主内容"));
    }

    @Test
    public void sensitivePageReadsOnlyTheGateSentence() {
        String state = "app=com.example.bank activity=Pay dark=false\n"
                + "n0 [主内容] EditText 可点 状态=E- \"请输入密码\" @[1,2][3,4]\n"
                + "n1 [主内容] Button 可点 状态=E- \"确认支付\" @[5,6][7,8]\n";
        List<String> lines = Match3LineReader.readGuarded(state);
        assertEquals("敏感页只许一句闸门话术，实测：" + joined(lines), 1, lines.size());
        assertEquals(Match3LineReader.GATE_BLOCK, lines.get(0));
        String all = joined(lines);
        assertFalse(all.contains("密码"));
        assertFalse(all.contains("支付"));
    }

    @Test
    public void guardPassesThroughCleanPage() {
        assertEquals(Match3LineReader.read(FIXTURE), Match3LineReader.readGuarded(FIXTURE));
    }
}
