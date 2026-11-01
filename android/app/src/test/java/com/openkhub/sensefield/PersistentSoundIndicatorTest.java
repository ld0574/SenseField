package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * 任务书 35 的锁：第 22 本那一句声音指示，除了既有的瞬时 Toast，还必须汇进
 * {@code AssistantController.setStatus} 那条常驻文本链，末环是 {@code AssistantOverlay.status}
 * → {@code UiKit.setTextIfChanged}。
 *
 * <p>版面取向照任务书拍死的走法：沿用现成的全角分号「；」合并，输入态在前、指示在后，
 * 顺序钉死；{@code history} 那条通路一个字都不许多接。期望串全部在本类里手写死，
 * 不拿被测函数自己的输出反推。
 *
 * <p>两类尺子各管一段：①—⑤ 是行为尺，直接调生产码里那个不碰 Android 类型的静态折叠函数；
 * ⑥—⑩ 是链尾报警器，读源码文本再断言（先把注释抹平，所以「把那行注释掉」与「把那行删掉」
 * 在这把尺子下等价，都当场变红）。中段行为尺看不见的那一跳，由后者盯住——与
 * {@code NoiseFloorFeedTest} ④⑤ 同构：光有「加断言就绿」不算，必须「断链就红」。
 *
 * <p>证据边界：本机无 adb、无声学设备。本类只证 JVM 侧「读数折得进去、链尾那一跳真在位」，
 * 证不了真机上那行常驻文本玩家看不看得见、会不会被系统限制吞掉、会不会挡住盘面。
 */
public final class PersistentSoundIndicatorTest {

    private static final String STATE_SEGMENT = "请戴耳机并确认游戏声音也从耳机播放后再说话";
    private static final String BASE = "助手可用";
    private static final String INDICATOR = "本机刚播出的提示音，响度中等，偏左。";
    private static final String JOIN = "；";

    // ---------- ① —⑤ 行为尺：直接调生产码上的静态折叠函数 ----------

    /** ① 指示为空（空串）时，既有 status 那行逐字不许变，含输入态已经拼在上面的那种。 */
    @Test
    public void absentIndicatorLeavesTheExistingStatusLineUntouched() {
        assertEquals("空指示不许改动纯基线行", BASE, AssistantController.mergeSoundIndicator(BASE, ""));
        assertEquals("null 指示同样不许改动纯基线行", BASE,
                AssistantController.mergeSoundIndicator(BASE, null));
        String withInputState = BASE + JOIN + STATE_SEGMENT;
        assertEquals("空指示不许改动已含输入态的既有行", withInputState,
                AssistantController.mergeSoundIndicator(withInputState, ""));
        assertEquals("空指示不许改动已含输入态的既有行（null 一侧）", withInputState,
                AssistantController.mergeSoundIndicator(withInputState, null));
    }

    /** ② 指示非空时，既有输入态不许被顶掉：两串按「；」共存，且逐字都能在原位找到。 */
    @Test
    public void presentIndicatorNeverPushesTheInputStateOffTheLine() {
        String merged = AssistantController.mergeSoundIndicator(BASE + JOIN + STATE_SEGMENT, INDICATOR);
        assertEquals("两串必须按既有分号约定共存，且不许多出任何分隔符",
                BASE + JOIN + STATE_SEGMENT + JOIN + INDICATOR, merged);
        assertTrue("输入态串必须还逐字在列: " + merged, merged.contains(STATE_SEGMENT));
        assertTrue("指示串必须逐字在列、不被改写: " + merged, merged.contains(INDICATOR));
        assertEquals("两串之间只许多出一个分隔符，不许出现第三个", 2, countMatches(merged, JOIN));
    }

    /** ③ 拼接顺序钉死：基线在前、输入态居中、指示最后，谁也不许漂。 */
    @Test
    public void foldOrderIsFixedBaseThenInputStateThenIndicator() {
        String merged = AssistantController.mergeSoundIndicator(BASE + JOIN + STATE_SEGMENT, INDICATOR);
        assertTrue("基线必须排在最前: " + merged, merged.startsWith(BASE));
        int state = merged.indexOf(STATE_SEGMENT);
        int indicator = merged.indexOf(INDICATOR);
        assertTrue("输入态必须在基线之后: " + merged, state > BASE.length());
        assertTrue("指示必须排在输入态之后，顺序不许反: " + merged, indicator > state);
        assertEquals("同一组输入重复折叠必须逐字同结果（顺序与分隔符都不许漂）",
                merged, AssistantController.mergeSoundIndicator(BASE + JOIN + STATE_SEGMENT, INDICATOR));
    }

    /** ④ 同一条指示连着来两次，只在行尾占一处；换了新句子才追加，旧的那句不许残留成两处。 */
    @Test
    public void sameIndicatorArrivingTwiceAppendsOnlyOnce() {
        String once = AssistantController.mergeSoundIndicator(BASE, INDICATOR);
        assertEquals("重复的同一条指示不许再拼第二次", once,
                AssistantController.mergeSoundIndicator(once, INDICATOR));
        String other = "本机刚播出的提示音，响度很响，偏右。";
        assertEquals("换了新句子才允许追加，且仍用同一分号约定",
                once + JOIN + other, AssistantController.mergeSoundIndicator(once, other));
    }

    /** ⑤ 空行或 null 基线上只留指示本身；两头都空则仍是空，不许凭空造出分隔符。 */
    @Test
    public void foldKeepsOnlyTheIndicatorWhenThereIsNothingToKeepItOn() {
        assertEquals(INDICATOR, AssistantController.mergeSoundIndicator("", INDICATOR));
        assertEquals(INDICATOR, AssistantController.mergeSoundIndicator(null, INDICATOR));
        assertEquals("", AssistantController.mergeSoundIndicator("", ""));
        assertEquals(BASE, AssistantController.mergeSoundIndicator(BASE, ""));
    }

    /** ⑤ 的另一半：折进链里的必须是第 22 本那句原话，不许在链条上重写或复制一套措辞。 */
    @Test
    public void theReadingFlowsIntoTheLineVerbatim() {
        short[] buffer = new short[16];
        for (int frame = 0; frame < 8; frame++) {
            buffer[frame * 2] = 9000;
            buffer[frame * 2 + 1] = 700;
        }
        String sentence = BroadcastSoundMeter.describe(buffer);
        String merged = AssistantController.mergeSoundIndicator(BASE + JOIN + STATE_SEGMENT, sentence);
        assertEquals("常驻行必须原样带上读数那句，只多一个分号",
                BASE + JOIN + STATE_SEGMENT + JOIN + sentence, merged);
        assertEquals("那句读数的措辞不许在折叠时被改写", 1, countMatches(merged, sentence));
    }

    // ---------- ⑥ —⑩ 链尾报警器：读源码文本，断链即红 ----------

    private static final Pattern FOLD_HOP = Pattern.compile(
            "value\\s*=\\s*mergeSoundIndicator\\s*\\(\\s*value\\s*,\\s*soundIndicator\\s*\\)\\s*;");

    private static final Pattern INPUT_STATE_MERGE = Pattern.compile(
            "value\\s*\\+=\\s*\"" + JOIN + "\"\\s*\\+\\s*state\\s*\\.\\s*status\\s*;");

    private static final Pattern INDICATOR_ASSIGNMENT = Pattern.compile(
            "soundIndicator\\s*=\\s*sentence\\s*==\\s*null\\s*\\?\\s*" + "\"\"" + "\\s*:\\s*sentence\\s*;");

    private static final Pattern REENTER_CALL = Pattern.compile(
            "setStatus\\s*\\(\\s*statusBase\\s*\\)\\s*;");

    private static final Pattern AUDIT_HOP = Pattern.compile(
            "auditAudio\\s*\\(\\s*BROADCAST_METER_AUDIT_PREFIX\\s*\\+\\s*sentence\\s*\\)\\s*;");

    private static final Pattern HANDOFF_HOP = Pattern.compile(
            "current\\s*\\.\\s*showSoundIndicator\\s*\\(");

    /** ⑥ 折叠那一跳必须恰有一处、确实坐在 {@code setStatus} 体内、且在送到常驻出口之前。 */
    @Test
    public void indicatorIsFoldedBeforeThePersistentHopGetsTheValue() throws Exception {
        String code = withoutComments(readSource("AssistantController.java"));
        assertEquals("折叠那一跳必须在全文件恰好 1 处（删掉或注释掉本条当场变红），实际 "
                + matchesOf(FOLD_HOP, code).size() + " 处", 1, matchesOf(FOLD_HOP, code).size());
        String body = blockOf(code, "private void setStatus(String value)");
        assertTrue("setStatus 体内必须有折叠那一跳:\n" + body.trim(), FOLD_HOP.matcher(body).find());
        int fold = body.indexOf("mergeSoundIndicator");
        int terminal = body.indexOf("overlay.status(value)");
        int host = body.indexOf("host.status(value)");
        assertTrue("折叠必须发生在 overlay.status 出口之前:\n" + body.trim(), fold >= 0 && fold < terminal);
        assertTrue("折叠必须发生在 host.status 出口之前:\n" + body.trim(), fold >= 0 && fold < host);
        assertFalse("读数只许进 status 那一条，不许顺手接进 history:\n" + body.trim(),
                body.contains("overlay.history("));
    }

    /** ⑦ 不许为了腾版面动既有件：输入态合并仍在原位；入口方法只此一处且确实重入同一条链。 */
    @Test
    public void existingInputStateMergeSurvivesAndEntryReentersTheSameChain() throws Exception {
        String code = withoutComments(readSource("AssistantController.java"));
        assertEquals("输入态那段合并不许被删或被改，全文件恰好 1 处",
                1, matchesOf(INPUT_STATE_MERGE, code).size());
        String entry = blockOf(code, "void showSoundIndicator(String sentence)");
        assertEquals("入口方法体内赋值必须恰好 1 处", 1, matchesOf(INDICATOR_ASSIGNMENT, entry).size());
        assertEquals("入口必须重入同一条 setStatus 链，不许另起通路",
                1, matchesOf(REENTER_CALL, entry).size());
        assertEquals("重入用的基线字段必须先于折叠被记下",
                1, countMatches(blockOf(code, "private void setStatus(String value)"),
                        "statusBase = value;"));
    }

    /** ⑧ Toast 那条瞬时出口一字不许改弱，第二条出口只是多 ride 既有槽位一次。 */
    @Test
    public void cuePlayerAddsTheSecondOutletWithoutWeakeningTheToast() throws Exception {
        String code = withoutComments(readSource("CuePlayer.java"));
        String body = blockOf(code, "private void reportBroadcastMeter(String sentence)");
        assertEquals("送去既有槽位的那一跳必须恰好 1 处", 1, matchesOf(AUDIT_HOP, body).size());
        assertTrue("既有 Toast 出口必须原样还在:\n" + body.trim(),
                body.contains("android.widget.Toast.makeText"));
        assertTrue("Toast 仍必须回到主线程:\n" + body.trim(), body.contains("mainHandler.post"));
        assertTrue("停了不许再发:\n" + body.trim(), body.contains("if (closed"));
        assertEquals("两条播报路径上的出口调用仍恰为 2 处（第 22 本锚点不许动）",
                2, countMatches(code, "reportBroadcastMeter(broadcastMeter);"));
        assertEquals("读数产生点仍恰为 2 处", 2,
                countMatches(code, "BroadcastSoundMeter.describe("));
    }

    /** ⑨ 那一跳确实坐在 CaptureService 本来就装好的那条监听槽里，且既有两段一个没少。 */
    @Test
    public void slotOwnerRoutesTheReadingIntoTheAssistantItAlreadyHolds() throws Exception {
        String code = withoutComments(readSource("CaptureService.java"));
        String slot = blockOf(code, "cuePlayer.setAudioAuditListener(message ->");
        assertEquals("交给助手的那一跳必须恰好 1 处（删掉或注释掉本条当场变红）",
                1, matchesOf(HANDOFF_HOP, slot).size());
        assertTrue("必须按既有前缀认领这行，不许自造第三条通路:\n" + slot.trim(),
                slot.contains("message.startsWith(CuePlayer.BROADCAST_METER_AUDIT_PREFIX)"));
        assertTrue("实参必须是剥掉前缀的那句原话:\n" + slot.trim(),
                slot.contains("CuePlayer.BROADCAST_METER_AUDIT_PREFIX.length()"));
        assertTrue("槽位既有的诊断落盘一段不许被顶掉:\n" + slot.trim(),
                slot.contains("recorder.audit(message)"));
        assertTrue("槽位既有的通知刷新一段不许被顶掉:\n" + slot.trim(),
                slot.contains("AlertTts status="));
        assertEquals("这条槽位在全文件只许装一次", 1,
                countMatches(code, "cuePlayer.setAudioAuditListener("));
    }

    /** ⑩ 链尾最后一跳仍是那块常驻文本面：{@code AssistantOverlay.status} → {@code setTextIfChanged}。 */
    @Test
    public void terminalHopStillWritesTheStatusLineIntoTheOverlayTextView() throws Exception {
        String overlay = readSource("AssistantOverlay.java");
        assertTrue("常驻文本这一跳必须走 setTextIfChanged:\n" + statusLineOf(overlay),
                statusLineOf(overlay).contains("UiKit.setTextIfChanged(status, value)"));
        assertEquals("history 那一行仍然只归对话记录，不许被借去挂瞬时读数", 1,
                countMatches(withoutComments(overlay), "setTextIfChanged(history"));
        String code = withoutComments(readSource("AssistantController.java"));
        assertEquals("送进 history 通道的调用点仍恰为 1 处（本卷没往里多接一次）",
                1, countMatches(code, "overlay.history("));
    }

    // ---------- 公用取数与尺子 ----------

    /** 取 {@code AssistantOverlay} 里 status / history 那两行方法本体所在的那一行。 */
    private static String statusLineOf(String overlay) {
        for (String line : overlay.split("\n")) {
            if (line.contains("void status(String value)")) return line;
        }
        throw new AssertionError("AssistantOverlay 里找不到 void status(String value) 那一行");
    }

    /**
     * 从锚点起取第一个配平的花括号块内部文本。锚点必须在目标文件里唯一，否则先红在这里。
     * 注释已被抹平，所以「注释掉那一行」与「删掉那一行」等价。
     */
    private static String blockOf(String text, String anchor) {
        int first = text.indexOf(anchor);
        assertTrue("找不到锚点（被改名或删掉都会红）: " + anchor, first >= 0);
        assertEquals("锚点必须唯一，否则锁不住具体那一跳: " + anchor, -1,
                text.indexOf(anchor, first + 1));
        int open = text.indexOf('{', first);
        assertTrue("锚点后面找不到方法体起点: " + anchor, open >= 0);
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(open + 1, i);
            }
        }
        throw new AssertionError("花括号不配平，锚点: " + anchor);
    }

    /** 行注释与块注释都抹成空格；字符串字面量里的斜杠不算注释（同 NoiseFloorFeedTest 口径）。 */
    private static String withoutComments(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && source.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) i++;
                i += 2;
                code.append(' ');
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                code.append(c);
                i++;
                while (i < n) {
                    char t = source.charAt(i);
                    code.append(t);
                    i++;
                    if (t == '\\' && i < n) {
                        code.append(source.charAt(i));
                        i++;
                        continue;
                    }
                    if (t == quote || t == '\n') break;
                }
                continue;
            }
            code.append(c);
            i++;
        }
        return code.toString();
    }

    private static java.util.List<String> matchesOf(Pattern pattern, String text) {
        java.util.List<String> found = new java.util.ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) found.add(m.group());
        return found;
    }

    private static int countMatches(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) count++;
        return count;
    }

    /** 单测工作目录可能是模块目录或其上一层，两种相对写法都试；不写死任何绝对路径。 */
    private static String readSource(String fileName) throws Exception {
        String tail = "com/openkhub/sensefield/" + fileName;
        Path candidate = Path.of("src/main/java/" + tail);
        if (!Files.isReadable(candidate)) candidate = Path.of("app/src/main/java/" + tail);
        assertTrue("单测工作目录里找不到被测源文件: " + fileName, Files.isReadable(candidate));
        return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
    }
}
