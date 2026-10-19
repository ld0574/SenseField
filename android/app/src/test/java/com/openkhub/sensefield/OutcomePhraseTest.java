package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * 任务书 37 的报警器：钉住「这一步点下去会怎样」这一句结果语义——四个结构量各自能出句、
 * 没读数就出空串、句内不出坐标形状、长度预算显式且兜得住，以及那一跳接线真进了工具页
 * 那条既有 {@code announce} 的同一个参数串。
 *
 * <p>被测的是 {@link OutcomePhrase}（纯 JVM）与 {@code Match3ToolsActivity#sampleAndAnnounce}
 * 在源码里的那一处拼接。期望串一律由人写死，不拿被测函数的输出反推自己；接线那一支按
 * {@code LocalIssueReportTest} 的 {@code methodBody} 取法读源码，不看运行时。
 *
 * <p>三条主判据各自的落点：①「数据缺失出空串」由 {@link #allFourQuantitiesZeroYieldsNoSentence} 与
 * {@link #missingOrUnreadableDataYieldsNoSentence} 钉住；②「空串时既有文案逐字相同」由
 * {@link #emptyPhraseKeepsTheExistingSentenceByteForByte} 与
 * {@link #emptyPhraseKeepsTheRealCoachSentenceByteForByte} 钉住；③「摘链必须变红」由
 * {@link #phraseIsSplicedIntoTheExistingAnnounceArgument} 钉住——把那一句实参换成空串、或把接线整条摘掉，
 * 这条立刻红。
 *
 * <p>口径边界：本类不改 {@code HintLabel} 的 20 字标签预算、不改 {@code Match3Narrator} 的两句／48 字
 * 预算、不动 {@code WordingGuard} 黑名单，也不动 {@code HintLabel} 那条私有坐标形状判据；测试里的尺子是
 * 它的<b>口径镜像</b>（同一字符类），既不复用实现、也不给它开后门。
 *
 * <p>证据边界：本机无 adb、无声学设备。这里只能证 JVM 代码路径与源码接线，证不了这一句在真机上
 * 念不念得完、加长后的整条播报玩家听不听得懂。
 */
public final class OutcomePhraseTest {

    /** 工具页那条推荐句的既有形状：象限＋交换句＋（可选）解说，句尾一个句号。 */
    private static final String BASE_SENTENCE = "左上区域，第 3 行，第 2 列和第 3 列交换，兼顾任务。";

    /** 计数取样：含个位、十位、百位与 0，够把中文数字与坐标形状两条尺子都跑满。 */
    private static final int[] SAMPLES = {0, 1, 2, 3, 5, 9, 10, 11, 20, 21, 99, 100, 105, 144, 999};

    /* ---------- ① 四个量各单独驱动出句 ---------- */

    @Test
    public void matchedCellsAloneSaysHowManyPiecesGoAway() {
        assertEquals("这一步会消掉五个。", OutcomePhrase.of(0, 5, 0, 0));
    }

    @Test
    public void runsFormedAloneSaysHowManyGroupsAreFormed() {
        assertEquals("这一步成两组。", OutcomePhrase.of(2, 0, 0, 0));
    }

    @Test
    public void longestRunAloneSaysTheLongestRun() {
        assertEquals("这一步最长四连。", OutcomePhrase.of(0, 0, 4, 0));
    }

    @Test
    public void adjacentIceAloneSaysThereIsIceToBreak() {
        assertEquals("这一步旁边有一块冰可破。", OutcomePhrase.of(0, 0, 0, 1));
    }

    @Test
    public void allFourQuantitiesTogetherMakeOneSentenceInFixedOrder() {
        assertEquals("这一步会消掉五个、成两组、最长四连、旁边有一块冰可破。",
                OutcomePhrase.of(2, 5, 4, 1));
    }

    /* ---------- ② 没读数就闭嘴：全 0 与数据缺失都出空串 ---------- */

    @Test
    public void allFourQuantitiesZeroYieldsNoSentence() {
        assertEquals("", OutcomePhrase.of(0, 0, 0, 0));
        assertEquals("四个量全为 0 的走法不该出句", "", OutcomePhrase.forSwap(zeroDataSwap()));
    }

    @Test
    public void missingOrUnreadableDataYieldsNoSentence() {
        assertEquals("走法本身缺失时不许造句", "", OutcomePhrase.forSwap(null));
        assertEquals("计数为负是坏读数", "", OutcomePhrase.of(0, -1, 0, 0));
        assertEquals("", OutcomePhrase.of(-2, 0, 0, 0));
        assertEquals("超出可播报口径的读数不造句", "",
                OutcomePhrase.of(0, OutcomePhrase.MAX_COUNT + 1, 0, 0));
        assertEquals("", OutcomePhrase.of(0, 0, 0, OutcomePhrase.MAX_COUNT + 1));
        assertEquals("特殊动作没有普通连组读数，不硬凑", "", OutcomePhrase.forSwap(birdLineSwap()));
        assertEquals("本类不认识的规则口径不承诺结果", "",
                OutcomePhrase.of(1, 3, 3, 0, Match3Action.Type.SWAP, "line_horizontal"));
    }

    @Test
    public void absentTypeAndRuleAreTreatedAsDefaultsAndStillSpeak() {
        assertEquals("这一步会消掉三个、成一组、最长三连。",
                OutcomePhrase.of(1, 3, 3, 0, null, null));
        assertEquals("规则串留空也算缺省", "这一步会消掉三个、成一组、最长三连。",
                OutcomePhrase.of(1, 3, 3, 0, Match3Action.Type.SWAP, "   "));
    }

    /* ---------- ③ 句内只许数量，不许坐标形状 ---------- */

    @Test
    public void noSentenceCarriesACoordinateShape() {
        for (int cells : SAMPLES) {
            for (int runs : SAMPLES) {
                String byClears = OutcomePhrase.of(runs, cells, 0, 0);
                assertFalse("消子句漏出坐标形状：" + byClears, carriesCoordinateShape(byClears));
                assertEquals("只要有一个读数就该出句", cells > 0 || runs > 0, !byClears.isEmpty());
            }
        }
        for (int longest : SAMPLES) {
            for (int ice : SAMPLES) {
                String byRuns = OutcomePhrase.of(0, 0, longest, ice);
                assertFalse("长连／破冰句漏出坐标形状：" + byRuns, carriesCoordinateShape(byRuns));
                assertEquals("只要有一个读数就该出句", longest > 0 || ice > 0, !byRuns.isEmpty());
            }
        }
    }

    @Test
    public void countsAreWrittenAsChineseNumeralsNotArabicDigits() {
        assertEquals("这一步会消掉两个。", OutcomePhrase.of(0, 2, 0, 0));
        assertEquals("这一步会消掉十个。", OutcomePhrase.of(0, 10, 0, 0));
        assertEquals("这一步会消掉十一个。", OutcomePhrase.of(0, 11, 0, 0));
        assertEquals("这一步会消掉二十个。", OutcomePhrase.of(0, 20, 0, 0));
        assertEquals("这一步会消掉一百零五个。", OutcomePhrase.of(0, 105, 0, 0));
        assertEquals("这一步会消掉一百四十四个。", OutcomePhrase.of(0, 144, 0, 0));
        assertEquals("这一步成二百组。", OutcomePhrase.of(200, 0, 0, 0));
        assertEquals("这一步会消掉九百九十九个。", OutcomePhrase.of(0, 999, 0, 0));
    }

    @Test
    public void phrasePromisesNothingAndHedgesNothing() {
        String phrase = OutcomePhrase.of(2, 5, 4, 1);
        for (String banned : new String[]{"最优", "必过", "稳赢", "更快", "上分", "好像", "拿不准",
                "可能", "概率", "胜率"}) {
            assertFalse("句子里出现了" + banned + "：" + phrase, phrase.contains(banned));
        }
        assertTrue("执行类措辞黑名单一条也不许命中",
                WordingGuard.checkText("OutcomePhrase.java", phrase).isEmpty());
    }

    /* ---------- ④ 长度预算：显式常量、被钉住、超限整句不播 ---------- */

    @Test
    public void lengthBudgetIsPinnedAndNeverTruncatesMidSentence() {
        assertEquals("长度上限是本类自定的显式常量，不许被人顺手改掉", 40, OutcomePhrase.MAX_PHRASE_CHARS);
        String realisticWorst = OutcomePhrase.of(4, 144, 12, 48);
        assertEquals("这一步会消掉一百四十四个、成四组、最长十二连、旁边有四十八块冰可破。", realisticWorst);
        assertTrue("现实盘面里最长的组合也必须播得出来",
                realisticWorst.length() > 0 && realisticWorst.length() <= OutcomePhrase.MAX_PHRASE_CHARS);
        assertEquals("四个数同时顶到口径上限时整句不播，而不是截半句", "",
                OutcomePhrase.of(999, 999, 999, 999));
    }

    @Test
    public void phraseDoesNotBorrowTheHintLabelBudget() throws IOException {
        for (String line : codeLinesOnly(sourceOf("OutcomePhrase.java"))) {
            assertFalse("结果句不许共用高亮标签那 20 字预算：" + line.trim(),
                    line.contains("HintLabel"));
        }
    }

    /* ---------- ⑤ 空串时既有文案逐字不变 ---------- */

    @Test
    public void emptyPhraseKeepsTheExistingSentenceByteForByte() {
        Match3Board.Swap nothingToSay = zeroDataSwap();
        String composed = BASE_SENTENCE + OutcomePhrase.forSwap(nothingToSay);
        assertEquals("没有读数时整条文案必须与改前逐字相同", BASE_SENTENCE, composed);
        assertEquals("同一条走法走生产入口也得空串", "", OutcomePhrase.forSwap(nothingToSay));
    }

    @Test
    public void emptyPhraseKeepsTheRealCoachSentenceByteForByte() {
        Match3Board.Swap swap = new Match3Board.Swap(2, 1, 2, 2, 0, 0, 0, 0);
        char[] pieces = {'R', 'O', 'Y', 'G', 'B', 'P'};
        Match3Position.Cell[][] cells = new Match3Position.Cell[8][8];
        for (int r = 0; r < 8; r++) for (int c = 0; c < 8; c++)
                cells[r][c] = Match3Position.Cell.animal(pieces[(r + c) % pieces.length]);
        Match3Position position = new Match3Position(cells);
        String speech = Match3Coach.swapSpeechWithDirection(swap, position.rows, position.cols, position) + "。";
        assertEquals("基准必须仍是 coach 对这块盘的真实出句（左上方＋青蛙和河马＋同行左右交换），"
                + "谁把它换成手写固定串或非生产同形的调用，这条当场红",
                "左上方可以消除，青蛙和河马左右交换。", speech);
        assertEquals("接上这一跳之后，无读数的推荐句一个字也不许多出来",
                speech, speech + OutcomePhrase.forSwap(swap));
        String withData = speech + OutcomePhrase.forSwap(new Match3Board.Swap(2, 1, 2, 2, 2, 5, 4, 1));
        assertFalse("有读数时才许多出一句", speech.equals(withData));
        assertTrue("多出来的那一句必须是结果语义：" + withData,
                withData.endsWith("这一步会消掉五个、成两组、最长四连、旁边有一块冰可破。"));
    }

    /* ---------- ⑥ 既有三句与解说文案一字未改 ---------- */

    @Test
    public void existingThreeSentencesAndNarrationStayVerbatim() throws IOException {
        List<String> code = codeLinesOnly(sourceOf("Match3ToolsActivity.java"));
        for (String fixed : new String[]{"截图中的目标已经完成。", "截图中的剩余步数已经用完。",
                "没有找到可直接消除的交换。"}) {
            assertEquals("既有那句固定文案被改动或挪走了：" + fixed, 1, countLinesContaining(code, fixed));
        }
        assertTrue("解说调用不再紧跟在那三句之后（拼接点被挪动或改写）",
                hasLineContaining(code, "+ Match3Narrator.narrate(board, position.rows, position.cols));"));
        String body = methodBody(sourceOf("Match3ToolsActivity.java"), "private void sampleAndAnnounce()");
        assertEquals("结果句只许接在推荐那一条，不许同时挤进解说那一条",
                1, countLinesContaining(codeLinesOnly(linesOf(body)), "OutcomePhrase"));
    }

    /* ---------- ⑦ 接线断言：那句真进了既有 announce 的参数串 ---------- */

    @Test
    public void phraseIsSplicedIntoTheExistingAnnounceArgument() throws IOException {
        String body = methodBody(sourceOf("Match3ToolsActivity.java"), "private void sampleAndAnnounce()");
        List<String> code = codeLinesOnly(linesOf(body));
        String splice = "announce(speech + OutcomePhrase.forSwap(s));";
        assertTrue("结果句没有并进工具页那条既有播报的参数串里", hasLineContaining(code, splice));
        assertFalse("那条播报又退回成只念定位句（接线被摘掉）", hasLineContaining(code, "announce(speech);"));
        assertEquals("本卷只许加一处，且加进既有条请求：这条播报仍由同一个 announce 调用发出",
                1, countLinesContaining(code, splice));
        assertTrue("拼接必须排在解说之后（speech 已含 value.reason 那段解说）",
                body.indexOf("\"，\"+value.reason") < body.indexOf(splice));
    }

    @Test
    public void noSecondAnnounceNoNewChannelAndNoNewControl() throws IOException {
        List<String> body = codeLinesOnly(
                linesOf(methodBody(sourceOf("Match3ToolsActivity.java"), "private void sampleAndAnnounce()")));
        assertEquals("取数与出句仍只有既有的两条 announce，一条也没多加",
                2, countLinesNotContaining(body, "announce(", "sampleAndAnnounce("));
        assertFalse("不许为结果句新开第二次播报调用",
                hasLineContaining(body, "announce(OutcomePhrase"));
        for (String forbidden : new String[]{"performHapticFeedback", "Vibrator", "vibrate(",
                "REQUESTED_CHANNELS +", "MATCH3_LIVE_HAPTIC", "addView", "new Button"}) {
            for (String line : body) {
                assertFalse("接线里出现了新通道或新控件：" + line.trim(), line.contains(forbidden));
            }
        }
    }

    @Test
    public void outcomePhraseSourceStaysFreeOfAndroidImports() throws IOException {
        for (String line : sourceOf("OutcomePhrase.java")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ")) {
                assertFalse("纯函数里出现了 Android 依赖：" + trimmed, trimmed.contains("android"));
            }
        }
    }

    /* ---------- 支撑 ---------- */

    /** 四个量全为 0 的普通交换——与 {@code Match3Board} 里特殊动作那种「没有普通连组读数」同形。 */
    private static Match3Board.Swap zeroDataSwap() {
        return new Match3Board.Swap(1, 1, 1, 2, 0, 0, 0, 0);
    }

    /** 魔力鸟组合：类型不是普通交换，四个量在 {@code Match3Action.special} 里恒为 0，这里故意喂非零以验闸门。 */
    private static Match3Board.Swap birdLineSwap() {
        return new Match3Board.Swap(1, 1, 1, 2, 3, 5, 4, 1, new BitSet(), Collections.emptyList(),
                Match3Action.Type.BIRD_LINE, "combo_bird_line");
    }

    /**
     * 坐标形状尺子：与 {@code HintLabel} 那条私有判据同一字符类（方括号、{@code @}／{@code #}／{@code %}、
     * 换行与任何阿拉伯数字）。本卷不改它、不绕它，只在测试里按同一口径复量一次。
     */
    private static boolean carriesCoordinateShape(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '[' || c == ']' || c == '@' || c == '#' || c == '%' || c == '\n' || c == '\r'
                    || c >= '0' && c <= '9') {
                return true;
            }
        }
        return false;
    }

    /** 取源码：从测试工作目录逐级向上找，命中不了就抛——「找不到被测源文件」必须让这条测试变红。 */
    private static List<String> sourceOf(String simpleName) throws IOException {
        String rel = "src/main/java/com/openkhub/sensefield/" + simpleName;
        String fromRoot = "android/app/src/main/java/com/openkhub/sensefield/" + simpleName;
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; dir != null && up <= 5; up++, dir = dir.getParentFile()) {
            for (String candidate : new String[]{rel, fromRoot}) {
                File f = new File(dir, candidate);
                if (f.isFile()) return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("找不到被测源文件：" + simpleName
                + "（模块内与树根起两种相对写法、向上五级均未命中），这条断言没接上电");
    }

    private static List<String> linesOf(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\r?\n", -1)) lines.add(line);
        return lines;
    }

    /** 只看代码行：注释行剔除，注释里写的接线不算接了线。 */
    private static List<String> codeLinesOnly(List<String> src) {
        List<String> out = new ArrayList<>();
        for (String line : src) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) continue;
            out.add(line);
        }
        return out;
    }

    private static boolean hasLineContaining(List<String> lines, String needle) {
        for (String line : lines) if (line.contains(needle)) return true;
        return false;
    }

    private static int countLinesContaining(List<String> lines, String needle) {
        int n = 0;
        for (String line : lines) if (line.contains(needle)) n++;
        return n;
    }

    /** 含 needle 但不含 exclusion 的行数——{@code sampleAndAnnounce(} 里也含着 {@code announce(}，得排掉。 */
    private static int countLinesNotContaining(List<String> lines, String needle, String exclusion) {
        int n = 0;
        for (String line : lines) {
            if (line.contains(needle) && !line.contains(exclusion)) n++;
        }
        return n;
    }

    private static String methodBody(List<String> src, String signatureNeedle) {
        int start = -1;
        for (int i = 0; i < src.size(); i++) if (src.get(i).contains(signatureNeedle)) { start = i; break; }
        assertTrue("源码里没有这个签名：" + signatureNeedle, start >= 0);
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        boolean started = false;
        for (int i = start; i < src.size(); i++) {
            String line = src.get(i);
            sb.append(line).append('\n');
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '{') { depth++; started = true; } else if (c == '}') depth--;
            }
            if (started && depth <= 0) break;
        }
        assertTrue("方法体没配平：" + signatureNeedle, started && depth <= 0);
        return sb.toString();
    }
}
