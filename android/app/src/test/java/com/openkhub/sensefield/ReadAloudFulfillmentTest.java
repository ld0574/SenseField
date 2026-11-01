package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.openkhub.sensefield.ReadOnlyGuard.Hit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/**
 * 任务书 18 的报警器：钉住「要我从上往下逐条读吗」这句承诺的兑现链，与 next_action 白名单闸门。
 *
 * <p>被测链是 {@link Match3ToolsActivity#readAloudLines(String, boolean)}（状态→待播句子，按钮回调走的就是它）
 * 与 {@link Match3ToolsActivity#gatedAnnouncement(String, JevThresholds.Decision, JevAnswer)}
 * （判定答案→允许出口的话术）。每个期望串都由人写死，一律不从被测函数的输出反推；
 * 压缩状态的行格式照 {@code SenseFieldReaderService} 的实际拼装手搓。
 *
 * <p>证据边界：本机无 adb、无 GPU，本类只证 JVM 代码路径，不证真机听感。
 */
public final class ReadAloudFulfillmentTest {

    private static final String HEADER = "app=com.example.board activity=BoardActivity dark=false";
    private static final String BAG = "n0 [顶栏] Button 可点 状态=E- \"背包\" @[30,90][150,170]";
    private static final String STEPS = "n1 [主内容] TextView 状态=E- \"剩余 8 步\" @[40,60][260,66]";
    private static final String ICON = "n2 [主内容] ImageView 可点 状态=E- \"\" @[50,30][60,40]";
    private static final String SHELL = "n3 [主内容] LinearLayout 状态=-- \"\" @[0,20][108,90]";
    private static final String PASSWORD = "n4 [底栏] EditText 状态=E- \"请输入支付密码\" @[10,40][90,46]";

    private static String stateOf(String... nodes) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (String n : nodes) {
            sb.append(n).append('\n');
        }
        return sb.toString();
    }

    private static String nodeLine(int i) {
        return "n" + i + " [主内容] Button 可点 状态=E- \"格子" + i + "\" @[10,20][30,40]";
    }

    private static JevAnswer choice(String id, String picked, double confidence) {
        Map<String, Double> probs = new LinkedHashMap<>();
        probs.put(picked, confidence);
        return new JevAnswer(id, JevQuestion.TYPE_CHOICE, picked, 0d, 0d, probs, confidence);
    }

    private static JevAnswer score(String id, double value, double confidence) {
        Map<String, Double> probs = new HashMap<>();
        probs.put("不必播报", 0.01d);
        probs.put("必须立即播报", confidence);
        return new JevAnswer(id, JevQuestion.TYPE_SCORE, null, value, 0d, probs, confidence);
    }

    private static boolean hasLineContaining(List<String> lines, String needle) {
        for (String line : lines) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /* ---------- ① 句子逐字等于 LocalReadAloud 的输出，且不含坐标片段 ---------- */

    @Test
    public void announcedLinesAreVerbatimGeneratorOutputWithoutCoordinates() {
        String state = stateOf(BAG, STEPS, ICON, SHELL);
        List<String> lines = Match3ToolsActivity.readAloudLines(state, true);
        assertEquals(Arrays.asList("第 1 条：背包，可点", "第 2 条：剩余 8 步，不可点",
                "第 3 条：没有文字的控件，可点"), lines);
        // 双保险：链上没有任何加工，逐字等于生成器自己的输出（容器空壳由生成器跳过，不在这里补规则）。
        assertEquals(LocalReadAloud.sentences(state), lines);
        for (String s : lines) {
            assertFalse("漏出坐标：" + s, s.contains("@["));
            assertFalse("漏出坐标片段：" + s, s.contains("]["));
            assertFalse("漏出状态位：" + s, s.contains("状态="));
            assertFalse("漏出包名：" + s, s.contains("com.example"));
        }
    }

    /* ---------- ② 敏感页返回空列表时，界面一条明细也不播 ---------- */

    @Test
    public void gateBlockIsTheOnlyThingAnnouncedOnSensitivePage() {
        String state = stateOf(BAG, STEPS, PASSWORD);
        assertEquals("密码或凭证", Match3Gate.sensitiveHit(state));
        assertTrue(LocalReadAloud.sentences(state).isEmpty());
        List<String> lines = Match3ToolsActivity.readAloudLines(state, true);
        assertEquals(Collections.singletonList(
                "本地敏感闸门命中：这一屏我不逐条读，内容不上云、不播报细节，建议手动处理。"), lines);
        for (String s : lines) {
            assertFalse("敏感明细外泄：" + s, s.contains("支付密码"));
            assertFalse("敏感页仍在念条目：" + s, s.contains("第 1 条"));
            assertFalse("敏感页仍在念条目：" + s, s.contains("背包"));
        }
    }

    /* ---------- ③ 状态不新鲜：不播明细，只给既有提示；空状态干脆一句也不播 ---------- */

    @Test
    public void staleStateGivesExistingHintAndNoItemLines() {
        List<String> lines = Match3ToolsActivity.readAloudLines(stateOf(BAG, STEPS), false);
        assertEquals(1, lines.size());
        assertEquals("为什么没播：读屏状态服务还没有可用状态，请先开启无障碍里的读屏状态服务，再切一次屏幕。",
                lines.get(0));
        // 同一句话与 judgeScreen() 第二条提前返回同源，不是本轮新造的句子。
        assertEquals(DecisionWhy.explain(DecisionWhy.Kind.NOT_READY, 0.0d, 0.0d, 0.0d, null), lines.get(0));
        for (String s : lines) {
            assertFalse("不新鲜还在念明细：" + s, s.contains("第 1 条"));
            assertFalse("不新鲜还在念明细：" + s, s.contains("背包"));
        }
    }

    @Test
    public void freshButEmptyStateAnnouncesNothing() {
        assertTrue(Match3ToolsActivity.readAloudLines("", true).isEmpty());
        assertTrue(Match3ToolsActivity.readAloudLines(null, true).isEmpty());
        assertTrue(Match3ToolsActivity.readAloudLines(stateOf(SHELL), true).isEmpty());
    }

    /* ---------- ④ 上限外那条「还有 N 条没念」原样保留 ---------- */

    @Test
    public void overLimitTailSentenceIsKeptVerbatim() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < 14; i++) {
            sb.append(nodeLine(i)).append('\n');
        }
        String state = sb.toString();
        List<String> lines = Match3ToolsActivity.readAloudLines(state, true);
        assertEquals(13, lines.size());
        assertEquals("第 1 条：格子0，可点", lines.get(0));
        assertEquals("第 12 条：格子11，可点", lines.get(11));
        assertEquals("第 13 条往后：还有 2 条没念，想听完请再说一次。", lines.get(12));
        assertEquals(LocalReadAloud.sentences(state), lines);
    }

    /* ---------- ⑤ 白名单外的答案不产生行为：只回落既有兜底话术 ---------- */

    @Test
    public void nextActionOutsideWhitelistFallsBackToExistingUncertainSpeech() {
        JevAnswer a = choice("next_action", "直接结算并充值", 0.99d);
        assertEquals(JevThresholds.Decision.DIRECT_ANNOUNCE, JevThresholds.DEFAULT.decide(a));
        assertFalse(JevThresholds.inWhitelist(a, JevThresholds.defaultWhitelist()));
        String spoken = Match3ToolsActivity.gatedAnnouncement("next_action",
                JevThresholds.Decision.DIRECT_ANNOUNCE, a);
        assertEquals("我没看清当前局面，请让我重新扫描", spoken);
        assertEquals(JevThresholds.UNCERTAIN_SPEECH, spoken);
        assertFalse("名单外的指令被念了出去：" + spoken, spoken.contains("充值"));
    }

    @Test
    public void nonChoiceNextActionIsNotWhitelistedEither() {
        JevAnswer a = score("next_action", 3d, 0.99d);
        assertFalse(JevThresholds.inWhitelist(a, JevThresholds.defaultWhitelist()));
        assertEquals(JevThresholds.UNCERTAIN_SPEECH, Match3ToolsActivity.gatedAnnouncement(
                "next_action", JevThresholds.Decision.DIRECT_ANNOUNCE, a));
        // 答案缺失同样不许念成指令，也不许炸。
        assertEquals(JevThresholds.UNCERTAIN_SPEECH, Match3ToolsActivity.gatedAnnouncement(
                "next_action", JevThresholds.Decision.UNCERTAIN, null));
    }

    @Test
    public void nextActionInsideWhitelistStillExitsUnchanged() {
        JevAnswer a = choice("next_action", "scan_board", 0.95d);
        assertTrue(JevThresholds.inWhitelist(a, JevThresholds.defaultWhitelist()));
        assertEquals("next_action：scan_board", Match3ToolsActivity.gatedAnnouncement(
                "next_action", JevThresholds.Decision.DIRECT_ANNOUNCE, a));
        // 闸门只管「哪些答案可以出口」，不管档位：名单内的中置信答案照旧出口，没有顺手扩成任何操作。
        assertEquals("next_action：read_menu", Match3ToolsActivity.gatedAnnouncement(
                "next_action", JevThresholds.Decision.CONFIRM, choice("next_action", "read_menu", 0.7d)));
    }

    @Test
    public void otherAnswerKeysAreNotGatedByTheWhitelist() {
        JevAnswer a = score("danger_level", 2.4d, 0.99d);
        assertEquals("danger_level：等级 2.4", Match3ToolsActivity.gatedAnnouncement(
                "danger_level", JevThresholds.Decision.DIRECT_ANNOUNCE, a));
    }

    @Test
    public void whitelistContentIsUntouched() {
        Set<String> expected = new HashSet<>(Arrays.asList("announce_match", "scan_board", "read_menu", "wait"));
        assertEquals(expected, JevThresholds.defaultWhitelist());
    }

    /* ---------- ⑥ 按钮的点击回调真的调到了那条链 ---------- */

    @Test
    public void buttonCallbackAndGateExitAreWiredInSourceAndInjectNothing() throws IOException {
        List<String> src = activitySource();
        assertTrue("按钮没落地", hasLineContaining(src,
                "Button readAloud = UiKit.button(this, \"逐条读这一屏\", false);"));
        assertTrue("点击回调没接到链上", hasLineContaining(src,
                "readAloud.setOnClickListener(v -> readAloudThisScreen());"));
        int start = indexOfFirstLineContaining(src, "private void readAloudThisScreen()");
        List<String> body = new ArrayList<>();
        for (int i = start + 1; i < src.size() && !src.get(i).trim().equals("}"); i++) {
            body.add(src.get(i));
        }
        assertTrue("回调没取已缓存的压缩状态", hasLineContaining(body,
                "readAloudLines(SenseFieldReaderService.latestState(),"));
        assertTrue("回调没走既有播报通道", hasLineContaining(body, "announce(line);"));
        assertTrue("白名单闸门没接到播报出口", hasLineContaining(src,
                "announce(gatedAnnouncement(e.getKey(), d, a));"));
        // 这条链只做朗读与只读展示：既没有输入注入，也没有网络。
        assertTrue("链上出现注入类 API：" + ReadOnlyGuard.scan("Match3ToolsActivity.java", body),
                ReadOnlyGuard.scan("Match3ToolsActivity.java", body).isEmpty());
        for (String api : Arrays.asList("dispatchGesture", "injectInputEvent", "Http", "Socket", "OkHttp")) {
            assertFalse("链上出现 " + api, hasLineContaining(body, api));
        }
        List<Hit> hits = ReadOnlyGuard.scan("Match3ToolsActivity.java", body);
        assertEquals(0, hits.size());
    }

    /**
     * 读被测源文件本身：从测试工作目录逐级向上找，命中不了就抛——
     * 「找不到源文件」必须让这条测试变红，不许静默跳过变成假绿。
     */
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
        throw new IllegalStateException("找不到被测源文件：两个相对写法（模块内与树根起）向上五级均未命中，"
                + "这条断言没接上电");
    }

    private static int indexOfFirstLineContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                return i;
            }
        }
        throw new AssertionError("源码里没有这句：" + needle);
    }

    /* ---------- ⑦ 改名续账（任务书 45）：生产源码只剩新名，玩家听到的句子逐字没动 ---------- */

    /**
     * 直接读被测源文件本身钉三件事：① 高置信档的 case 用的是新枚举名；② 出口句子仍是
     * 「高置信·直接播，不用确认」一字未改；③ 旧标识符在这个文件里 0 命中。
     * 旧名按 {@code WordingGuard.word(char...)} 同法用 char 数组拼，免得这条反例断言
     * 把被禁的标识符又写回源码里、自己变成一次命中。
     */
    @Test
    public void renamedEnumIsTheOnlyIdentifierLeftAndOutletSentenceIsVerbatim() throws IOException {
        List<String> src = activitySource();
        assertTrue("高置信档的 case 没换成新枚举名", hasLineContaining(src,
                "case DIRECT_ANNOUNCE: return \"高置信·直接播，不用确认\";"));
        String oldName = new String(new char[]{'A', 'U', 'T', 'O', '_', 'E', 'X', 'E', 'C', 'U', 'T', 'E'});
        for (String line : src) {
            assertFalse("源码里仍留有旧标识符：" + line, line.contains(oldName));
        }
        // 闸门那句注释随迁成新名字，读起来仍然指同一个档位（不许出现"不碰 X"里 X 已经不存在的情况）
        assertTrue("闸门注释没随迁", hasLineContaining(src,
                "也不碰 DIRECT_ANNOUNCE 的语义"));
    }
}
