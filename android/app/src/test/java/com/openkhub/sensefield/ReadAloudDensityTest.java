package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * {@link ReadAloudDensity} 与「档位 → 逐条读条数」接线的纯 JVM 单测（任务书 25 任务 2）。
 * 所有期望值由人写死：现值 12 直接引用 {@link LocalReadAloud#DEFAULT_LIMIT} 常量，
 * 精简 6／详细 20 用字面量钉住，防止被测类与报警器互相反推。
 *
 * <p>报警器要盯死的两件事：
 * ① 档位不同 → 一次念的条数真的不同（第 ⑥ 条，防「映射算了没人用」）；
 * ② 精简 ≤ 标准 ≤ 详细 且标准／自定义／非法输入零变化（第 ①③④⑤ 条）。
 */
public class ReadAloudDensityTest {

    private static final String HEADER = "app=com.example.shop activity=MainActivity dark=false";

    /** 一条普通可点节点行：标签「搜索」不在敏感词表内，条数测试都用它构造。 */
    private static String node(int i) {
        return "n" + i + " [主内容] Button 可点 状态=E- \"搜索\" @[" + (10 + i) + ",300]["
                + (200 + i) + ",360]";
    }

    /** 25 条可读节点的整屏状态：远超任何档位上限，用来把三档条数差逼出来。 */
    private static String state25() {
        StringBuilder sb = new StringBuilder(HEADER);
        for (int i = 0; i < 25; i++) {
            sb.append('\n').append(node(i));
        }
        return sb.toString();
    }

    /** 恰好 6 条可读节点：正好等于精简上限，末句应缺席，用来验「不越界不截断」。 */
    private static String state6() {
        StringBuilder sb = new StringBuilder(HEADER);
        for (int i = 0; i < 6; i++) {
            sb.append('\n').append(node(i));
        }
        return sb.toString();
    }

    /** 恰好 7 条可读节点：在精简上限 6 处越界、在标准上限 12 之下，用来验末句编号自洽。 */
    private static String state7() {
        StringBuilder sb = new StringBuilder(HEADER);
        for (int i = 0; i < 7; i++) {
            sb.append('\n').append(node(i));
        }
        return sb.toString();
    }

    /** 含密码行的敏感状态（与 LocalReadAloudTest 第 11 条同款行形）。 */
    private static String sensitiveState() {
        return HEADER + "\n" + node(0) + "\nn1 [底栏] EditText 状态=E- \"请输入您的密码\" @[10,1400][900,1460]";
    }

    /* ① 标准档输出＝现值：没调过档位的玩家零变化。 */
    @Test
    public void standardPresetKeepsCurrentValue() {
        assertEquals(12, LocalReadAloud.DEFAULT_LIMIT);   // 现值本体，书称 12，若被人改动这里先响
        assertEquals(LocalReadAloud.DEFAULT_LIMIT, ReadAloudDensity.limitFor("standard"));
        assertEquals(LocalReadAloud.sentences(state25()),
                LocalReadAloud.sentences(state25(), ReadAloudDensity.limitFor("standard")));
    }

    /* ② 自定义档＝现值：玩家自己配的，不替他再改一次。 */
    @Test
    public void customPresetKeepsCurrentValue() {
        assertEquals(LocalReadAloud.DEFAULT_LIMIT, ReadAloudDensity.limitFor("custom"));
        assertEquals(LocalReadAloud.sentences(state25()),
                LocalReadAloud.sentences(state25(), ReadAloudDensity.limitFor("custom")));
    }

    /* ③ 单调：精简 ≤ 标准（精简给到 6，比标准严格更少）。 */
    @Test
    public void compactNotAboveStandard() {
        assertTrue("精简档上限 " + ReadAloudDensity.limitFor("compact")
                        + " 必须 ≤ 标准档 " + ReadAloudDensity.limitFor("standard"),
                ReadAloudDensity.limitFor("compact") <= ReadAloudDensity.limitFor("standard"));
        assertEquals(6, ReadAloudDensity.limitFor("compact"));
    }

    /* ④ 单调：详细 ≥ 标准（详细给到 20，比标准严格更多）。 */
    @Test
    public void detailedNotBelowStandard() {
        assertTrue("详细档上限 " + ReadAloudDensity.limitFor("detailed")
                        + " 必须 ≥ 标准档 " + ReadAloudDensity.limitFor("standard"),
                ReadAloudDensity.limitFor("detailed") >= ReadAloudDensity.limitFor("standard"));
        assertEquals(20, ReadAloudDensity.limitFor("detailed"));
    }

    /* ⑤ 非法输入一律回落现值且不抛：null／未知档名／空白串三种都测。 */
    @Test
    public void illegalPresetNamesFallBackWithoutThrowing() {
        List<String> bad = Arrays.asList(null, "", "   ", "banana", "STANDARD", "compact ");
        for (String preset : bad) {
            assertEquals("非法档名 [" + preset + "] 必须回落现值",
                    LocalReadAloud.DEFAULT_LIMIT, ReadAloudDensity.limitFor(preset));
        }
    }

    /* ⑥ 三个合法档位下一次念的条数确实不同：防「映射算了没人用」。 */
    @Test
    public void threePresetsProduceDifferentCounts() {
        String state = state25();
        int c = LocalReadAloud.sentences(state, ReadAloudDensity.limitFor("compact")).size();
        int s = LocalReadAloud.sentences(state, ReadAloudDensity.limitFor("standard")).size();
        int d = LocalReadAloud.sentences(state, ReadAloudDensity.limitFor("detailed")).size();
        assertEquals(7, c);    // 6 条正文 + 1 句尾巴
        assertEquals(13, s);   // 12 条正文 + 1 句尾巴
        assertEquals(21, d);   // 20 条正文 + 1 句尾巴
        assertNotEquals(c, s);
        assertNotEquals(s, d);
        assertNotEquals(c, d);
        // 同一件事经界面那条链（三参 readAloudLines）也必须成立，接线断则此句红：
        int cc = Match3ToolsActivity.readAloudLines(state, true,
                ReadAloudDensity.limitFor("compact")).size();
        int dd = Match3ToolsActivity.readAloudLines(state, true,
                ReadAloudDensity.limitFor("detailed")).size();
        assertNotEquals(cc, dd);
    }

    /* ⑦ 末句「还有几条没念」在新上限下自洽：精简档 6 条＋尾巴的编号与剩余数都写死。 */
    @Test
    public void tailSentenceStaysConsistentUnderCompactLimit() {
        List<String> out = LocalReadAloud.sentences(state25(), ReadAloudDensity.limitFor("compact"));
        assertEquals(7, out.size());
        assertEquals("第 7 条往后：还有 19 条没念，想听完请再说一次。", out.get(6));
        for (int i = 0; i < 6; i++) {
            assertTrue(out.get(i), out.get(i).startsWith("第 " + (i + 1) + " 条："));
        }
        // 正好等于上限时末句必须缺席（6 条屏读满 6 条，没有「没念」的尾巴）。
        List<String> exact = LocalReadAloud.sentences(state6(),
                ReadAloudDensity.limitFor("compact"));
        assertEquals(6, exact.size());
        assertFalse(exact.get(5), exact.get(5).contains("没念"));
        // 7 条屏在精简档恰好多一条没念：尾巴编号与剩余数逐字钉死。
        List<String> cut = LocalReadAloud.sentences(state7(),
                ReadAloudDensity.limitFor("compact"));
        assertEquals(7, cut.size());
        assertEquals("第 7 条往后：还有 1 条没念，想听完请再说一次。", cut.get(6));
        // 同一 7 条屏在标准档不截断：两档对同一状态结果不同。
        List<String> std = Match3ToolsActivity.readAloudLines(state7(), true,
                ReadAloudDensity.limitFor("standard"));
        assertEquals(7, std.size());
        assertFalse(std.get(6), std.get(6).contains("没念"));
    }

    /* ⑧ 敏感闸门不许被新路径绕开：任一档位下命中敏感都返回空列表。 */
    @Test
    public void sensitiveGateStillEmptyForEveryPreset() {
        String state = sensitiveState();
        for (String preset : Arrays.asList("compact", "standard", "detailed", "custom", null, "banana")) {
            List<String> out = LocalReadAloud.sentences(state, ReadAloudDensity.limitFor(preset));
            assertTrue("敏感页在档 [" + preset + "] 下仍被念了 " + out.size() + " 条", out.isEmpty());
            List<String> chained = Match3ToolsActivity.readAloudLines(state, true,
                    ReadAloudDensity.limitFor(preset));
            assertEquals(java.util.Collections.singletonList(Match3LineReader.GATE_BLOCK), chained);
        }
    }

    /* ⑨ 输出恒为正整数：任何输入都不许产出 0 或负数上限。 */
    @Test
    public void limitIsAlwaysPositive() {
        for (String preset : Arrays.asList(null, "", "   ", "compact", "standard", "detailed",
                "custom", "banana")) {
            assertTrue("上限必须为正：" + preset, ReadAloudDensity.limitFor(preset) > 0);
        }
    }

    /* ⑩ 主源集真有消费者（不是只被测试引用）：按钮回调读一次档名键并把映射传给那条链。 */
    @Test
    public void wiringReadsExistingPresetKeyAndPassesMappedLimit() throws IOException {
        List<String> src = activitySource();
        assertTrue("回调没把档位上限传给那条链", hasLineContaining(src,
                "SenseFieldReaderService.hasFreshState(), aloudLimitForPreset());"));
        int start = indexOfFirstLineContaining(src, "private int aloudLimitForPreset()");
        List<String> body = src.subList(start + 1, Math.min(src.size(), start + 6));
        assertTrue("读数出口没读既有键", hasLineContaining(body,
                ".getString(\"cue_preset\", CueSettings.PRESET_STANDARD)"));
        assertTrue("读数出口没接映射", hasLineContaining(body,
                "return ReadAloudDensity.limitFor(preset);"));
        // 且不许新增偏好键：这个新接缝里只许出现 cue_preset 这一个键名。
        assertFalse("接线读走了别的键", hasLineContaining(body, "getString(\"")
                && !exactlyOneKeyIn(body));
    }

    private static boolean exactlyOneKeyIn(List<String> lines) {
        int n = 0;
        for (String l : lines) {
            int from = 0;
            while ((from = l.indexOf("getString(\"", from)) >= 0) {
                n++;
                from++;
            }
        }
        return n == 1;
    }

    private static boolean hasLineContaining(List<String> lines, String needle) {
        for (String l : lines) {
            if (l.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static int indexOfFirstLineContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        throw new AssertionError("源码里没有这句：" + needle);
    }

    /** 读被测源文件本身，找不到就抛（照 ReadAloudFulfillmentTest 的既有写法，不许静默假绿）。 */
    private static List<String> activitySource() throws IOException {
        String[] candidates = {
                "src/main/java/com/openkhub/sensefield/Match3ToolsActivity.java",
                "android/app/src/main/java/com/openkhub/sensefield/Match3ToolsActivity.java",
        };
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; dir != null && up <= 5; up++, dir = dir.getParentFile()) {
            for (String rel : candidates) {
                File f = new File(dir, rel);
                if (f.isFile()) {
                    return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalStateException("找不到被测源文件，这条断言没接上电");
    }
}
