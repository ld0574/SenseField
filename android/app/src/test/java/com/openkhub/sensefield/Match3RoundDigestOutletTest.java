package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.openkhub.sensefield.Match3ToolsActivity.DigestOutlet;
import com.openkhub.sensefield.ReadOnlyGuard.Hit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * 任务书 21 的报警器：钉住「回合战报能被玩家听见、也能亲手存下来」这条消费链。
 *
 * <p>被测的是 {@link Match3ToolsActivity#digestOutlet(String, String, long)}（战报文本 → 两个出口的决议，
 * 纯函数）与两个按钮回调在源码里的接线，加上 {@link Match3LiveService#roundDigestSnapshot()}
 * 那个只读取数出口的取值形状。每个期望值都由人写死：句子一律拿 {@link Match3RoundDigest#digest} 的
 * 当场输出当基准，不拿被测函数的输出反推自己。</p>
 *
 * <p>口径来源：战报生成器本体（空盘口径、条数口径）与那句 audit 都由更早的书钉死，本类只证
 * 「接出来这一步没有二次加工、没有绕开玩家的手、没有多算一次」。</p>
 *
 * <p>证据边界：本机无 adb、无 GPU。这里只能证 JVM 代码路径与源码接线，
 * 证不了真机上战报念出来顺不顺、玩家会不会真把它存去分享。</p>
 */
public final class Match3RoundDigestOutletTest {

    /** 一句有连线的战报（跑真实生成器拿基准，不手抄文案）。 */
    private static String digestWithRuns() {
        return Match3RoundDigest.digest(2, 0L, 137L, "bag:3");
    }

    /** 空盘战报：连线为 0、连锁等待帧为 0，生成器自带那句空盘话术。 */
    private static String digestEmptyBoard() {
        return Match3RoundDigest.digest(0, 0L, 91L, "bag:3");
    }

    /** 取源码：从测试工作目录逐级向上找，命中不了就抛——「找不到源文件」必须让这条测试变红。 */
    private static List<String> sourceOf(String simpleName) throws IOException {
        String rel = "src/main/java/com/openkhub/sensefield/" + simpleName;
        String fromRoot = "android/app/src/main/java/com/openkhub/sensefield/" + simpleName;
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; dir != null && up <= 5; up++, dir = dir.getParentFile()) {
            for (String candidate : new String[]{rel, fromRoot}) {
                File f = new File(dir, candidate);
                if (f.isFile()) {
                    return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalStateException("找不到被测源文件：" + simpleName
                + "（模块内与树根起两种相对写法、向上五级均未命中），这条断言没接上电");
    }

    /** 只看代码行：注释行（// 与 javadoc 的 * 开头）剔除，因为本类要在注释里点名那些既有话术。 */
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
        for (String line : lines) {
            if (line.contains(needle)) return true;
        }
        return false;
    }

    private static int indexOfFirstLineContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) return i;
        }
        throw new AssertionError("源码里没有这句：" + needle);
    }

    /** 花括号配平地取出一个方法的完整方法体（含签名行与收尾行），注释里的括号一并计入但不影响结论。 */
    private static String methodBody(List<String> src, String signatureNeedle) {
        int start = indexOfFirstLineContaining(src, signatureNeedle);
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

    private static List<String> linesOf(String body) {
        return Arrays.asList(body.split("\n", -1));
    }

    /* ---------- ① 战报文本逐字来自生成器，界面不自己拼一句 ---------- */

    @Test
    public void spokenTextIsTheGeneratorOutputVerbatim() {
        String baseline = digestWithRuns();
        DigestOutlet outlet = Match3ToolsActivity.digestOutlet(baseline, "8f14e45f-ea21-4c7a-9b0b-1c2d3e4f5061", 1L);
        assertTrue("有战报却不许不播", outlet.speak);
        assertEquals("出口必须逐字把生成器那句递出去，一个字符也不许改", baseline, outlet.speech);
    }

    @Test
    public void activitySourceComposesNoDigestSentenceOfItsOwn() throws IOException {
        List<String> code = codeLinesOnly(sourceOf("Match3ToolsActivity.java"));
        for (String fragment : new String[]{"这局处理了", "盘面上没看到连线", "条连线", "等连锁落定"}) {
            assertFalse("界面源码里出现了战报话术字面量，说明战报被界面自己拼了一份：" + fragment,
                    hasLineContaining(code, fragment));
        }
        // 反过来：界面确实只把取数出口的原话递给 announce，中间没有拼接表达式。
        assertTrue("念的出口没接在 announce 上", hasLineContaining(code, "announce(outlet.speech);"));
        assertFalse("播报实参里出现了字符串相加（战报被现场拼接）",
                hasLineContaining(code, "announce(outlet.speech +"));
        assertFalse("播报实参里出现了先拼再播（战报被现场拼接）",
                hasLineContaining(code, "String speech = \"这局"));
    }

    /* ---------- ② 空盘只播生成器自带那句，不编数字 ---------- */

    @Test
    public void emptyBoardAnnouncesOnlyTheGeneratorsOwnSentence() {
        String baseline = digestEmptyBoard();
        assertTrue("基准里就该带生成器自带的空盘话术", baseline.contains("盘面上没看到连线"));
        DigestOutlet outlet = Match3ToolsActivity.digestOutlet(baseline, "sess-1", 2L);
        assertEquals("空盘也只许播生成器那句", baseline, outlet.speech);
        assertTrue("空盘句里不许出现「0 条连线」这种替玩家把 0 说成读数的写法",
                !outlet.speech.contains("最多同时看到 0"));
        assertTrue("空盘句里不许出现「1 条连线」这种凭空补数", !outlet.speech.contains(" 1 条连线"));
    }

    @Test
    public void outletAddsNoDigitThatTheGeneratorDidNotSay() {
        for (String digest : new String[]{digestEmptyBoard(), digestWithRuns()}) {
            DigestOutlet outlet = Match3ToolsActivity.digestOutlet(digest, "sess-2", 123456L);
            assertEquals("决议不许给战报添字", digest, outlet.speech);
            assertEquals("决议不许给战报删字", digest.length(), outlet.speech.length());
        }
    }

    /* ---------- ③ 落盘文件名只含会话 id 与时刻，不含身份形状 ---------- */

    @Test
    public void exportFileNameIsSessionIdPlusMillisOnly() {
        String uuid = "8f14e45f-ea21-4c7a-9b0b-1c2d3e4f5061";
        DigestOutlet outlet = Match3ToolsActivity.digestOutlet(digestWithRuns(), uuid, 1729046400000L);
        assertEquals("文件名形状要照第 15 份书验收过的那条保存路：sensefield-<会话 id>-<时刻>.txt",
                "sensefield-" + uuid + "-1729046400000.txt", outlet.exportFileName);
        assertNotNull(outlet.exportFileName);
        assertEquals("名字里只有一个点（扩展名），不许出现目录里的点",
                1, outlet.exportFileName.length() - outlet.exportFileName.replace(".", "").length());
    }

    @Test
    public void hostileSessionIdCannotSmuggleAPathAModelOrAChatAppName() {
        // 占位符思路：用户目录／机型／聊天软件名一律用假形状喂进去，要证的是「这些进不到文件名里」。
        String[] hostile = {
                "C:" + File.separator + "Users" + File.separator + "Someone" + File.separator + "Files",
                "C:\\Someone\\diagnostics",
                "/data/data/com.example.app/files",
                "MODEL X/99 Pro",
                "Some.Chat",
                ".." + File.separator + ".." + File.separator + "etc" + File.separator + "passwd",
                "带中文的机型串",
                "   ",
                null,
        };
        for (String id : hostile) {
            String name = Match3ToolsActivity.digestOutlet(digestWithRuns(), id, 7L).exportFileName;
            assertNotNull("有战报就得给出文件名：" + id, name);
            assertEquals("掺了可疑形状的会话 id 必须整串换占位符，不许只削掉一半留在名字里：" + name,
                    "sensefield-nosession-7.txt", name);
            assertFalse("文件名里混进了目录分隔符：" + name, name.contains("/") || name.contains("\\"));
            assertFalse("文件名里混进了冒号（盘符形状）：" + name, name.contains(":"));
            assertFalse("文件名里混进了上层穿越（..）：" + name, name.contains(".."));
            assertFalse("文件名里混进了 Users 目录形状：" + name, name.contains("Users"));
            assertFalse("文件名里混进了机型串：" + name, name.contains("MODEL"));
            assertFalse("文件名里混进了聊天软件名：" + name, name.toLowerCase().contains("chat"));
        }
        // 合法 UUID 一个字符也不该被动到（否则「只含会话 id 与时刻」里的 id 就不再是那个 id）。
        String uuid = "8f14e45f-ea21-4c7a-9b0b-1c2d3e4f5061";
        assertEquals(uuid, Match3ToolsActivity.safeSessionIdForFile(uuid));
        assertEquals("消毒只管形状，不许顺手改大小写（那样 id 就不是那个 id 了）",
                uuid.toUpperCase(), Match3ToolsActivity.safeSessionIdForFile(uuid.toUpperCase()));
        assertEquals("超长 id 一样整串作废", "nosession",
                Match3ToolsActivity.safeSessionIdForFile(uuid + "x"));
    }

    @Test
    public void fileNameFormulaUsesNothingButSessionIdAndMillis() throws IOException {
        String formula = methodBody(codeLinesOnly(sourceOf("Match3ToolsActivity.java")),
                "static DigestOutlet digestOutlet(String digest, String sessionId, long exportedAtMillis)");
        assertTrue("名字里该有会话 id 这一项", formula.contains("safeSessionIdForFile(sessionId)"));
        assertTrue("名字里该有时刻这一项（由调用方传入）", formula.contains("exportedAtMillis"));
        for (String forbidden : new String[]{"Build.MODEL", "MANUFACTURER", "BRAND", "getExternalStorage",
                "Environment", "getPackageName", "getUser", "Http", "Socket", "OkHttp", "File.separator"}) {
            assertFalse("文件名公式里出现了 " + forbidden, formula.contains(forbidden));
        }
    }

    /* ---------- ④ 战报为空时不创建文档、不报错 ---------- */

    @Test
    public void blankDigestNeitherCreatesADocumentNorThrows() {
        for (String blank : new String[]{null, "", "   ", "\n\t "}) {
            DigestOutlet outlet = Match3ToolsActivity.digestOutlet(blank, "sess-3", 99L);
            assertFalse("没有战报不许播：" + blank, outlet.speak);
            assertNull("没有战报不许创建文档：" + blank, outlet.exportFileName);
            assertEquals("空战报不许播出一句编的话", "", outlet.speech);
        }
    }

    @Test
    public void saveCallbackShortCircuitsBeforeAnyFileOrIntentWork() throws IOException {
        List<String> body = linesOf(methodBody(sourceOf("Match3ToolsActivity.java"),
                "private void saveRoundDigest()"));
        int guard = indexOfFirstLineContaining(body, "if (outlet.exportFileName == null)");
        int stage = indexOfFirstLineContaining(body, "new File(new File(getCacheDir(), \"diagnostic-exports\")");
        int intent = indexOfFirstLineContaining(body, "Intent.ACTION_CREATE_DOCUMENT");
        assertTrue("空战报的短路必须排在落盘与发意图之前", guard < stage && guard < intent);
        assertTrue("空战报时只许给一句只读展示", hasLineContaining(body, "output.setText(NO_DIGEST_NOTE);"));
    }

    /* ---------- ⑤ 只读取数出口不改生成时机 ---------- */

    @Test
    public void readingTheOutletTwiceWithSameInputsIsByteIdentical() {
        String first = Match3RoundDigest.digest(1, 4L, 60L, "steps:5");
        String second = Match3RoundDigest.digest(1, 4L, 60L, "steps:5");
        assertEquals("同一输入两次生成必须逐字相同", first, second);
        DigestOutlet a = Match3ToolsActivity.digestOutlet(first, "sess-4", 5L);
        DigestOutlet b = Match3ToolsActivity.digestOutlet(second, "sess-4", 5L);
        assertEquals(a.speech, b.speech);
        assertEquals(a.exportFileName, b.exportFileName);
    }

    @Test
    public void readOnlyOutletOnlyReturnsTheSnapshotAndNeverRegenerates() throws IOException {
        List<String> src = sourceOf("Match3LiveService.java");
        assertTrue("取数出口必须是纯返回字段：读的时候不再算一次",
                hasLineContaining(src, "static String roundDigestSnapshot() { return lastRoundDigest; }"));
        List<String> code = codeLinesOnly(src);
        int calls = 0;
        for (String line : code) {
            String rest = line;
            while (rest.contains("Match3RoundDigest.digest(")) {
                calls++;
                rest = rest.substring(rest.indexOf("Match3RoundDigest.digest(") + 1);
            }
        }
        assertEquals("生成器在实况里只许被调两处：那句 audit ＋快照写入这一处（读出口不算）", 2, calls);
        assertEquals("快照写入点只许有一处", 1, countLinesStartingWith(code, "lastRoundDigest ="));
    }

    private static int countLinesStartingWith(List<String> lines, String prefix) {
        int n = 0;
        for (String line : lines) {
            if (line.trim().startsWith(prefix)) n++;
        }
        return n;
    }

    @Test
    public void auditSentenceStillOwnsTheDigestAndSnapshotSharesItsExactInputs() throws IOException {
        List<String> src = sourceOf("Match3LiveService.java");
        int audit = indexOfFirstLineContaining(src,
                "s.diagnostics.audit(\"Match3RoundDigest \" + Match3RoundDigest.digest(");
        assertEquals("那句 audit 的入参行必须紧跟其后",
                "s.visibleRuns, s.cascadeWaitingFrames, s.processedFrames, hint.goalKey));",
                src.get(audit + 1).trim());
        int snapshot = indexOfFirstLineContaining(src, "lastRoundDigest = Match3RoundDigest.digest(");
        assertTrue("快照必须写在同一处（回合落定的那个分支里），不许另起一个生成时机",
                snapshot > audit && snapshot - audit <= 6);
        String auditArgs = src.get(audit + 1).trim().replaceAll("[);]+$", "");
        String snapshotArgs = src.get(snapshot + 1).trim().replaceAll("[);]+$", "");
        assertEquals("快照与 audit 必须吃同一批瞬时读数（内容口径不许动）", auditArgs, snapshotArgs);
    }

    /* ---------- ⑥ 按钮回调真调到那条链 ---------- */

    @Test
    public void bothButtonCallbacksAreWiredToTheChainAndInjectNothing() throws IOException {
        List<String> src = sourceOf("Match3ToolsActivity.java");
        assertTrue("「听」的按钮没落地", hasLineContaining(src,
                "Button digestSpeak = UiKit.button(this, \"念这一盘战报\", false);"));
        assertTrue("「听」的按钮没接到回调", hasLineContaining(src,
                "digestSpeak.setOnClickListener(v -> announceRoundDigest());"));
        assertTrue("「存」的按钮没落地", hasLineContaining(src,
                "Button digestSave = UiKit.button(this, \"保存这一盘战报\", false);"));
        assertTrue("「存」的按钮没接到回调", hasLineContaining(src,
                "digestSave.setOnClickListener(v -> saveRoundDigest());"));

        String speakBody = methodBody(src, "private void announceRoundDigest()");
        assertTrue("「听」没从只读取数出口取战报",
                speakBody.contains("digestOutlet(Match3LiveService.roundDigestSnapshot(),"));
        assertTrue("「听」没走既有 announce 通道", speakBody.contains("announce(outlet.speech);"));

        String saveBody = methodBody(src, "private void saveRoundDigest()");
        assertTrue("「存」没从只读取数出口取战报",
                saveBody.contains("digestOutlet(Match3LiveService.roundDigestSnapshot(),"));
        assertTrue("「存」没落到既有那条 diagnostic-exports 缓存目录",
                saveBody.contains("new File(getCacheDir(), \"diagnostic-exports\")"));
        assertTrue("「存」没复用既有的创建文档意图",
                saveBody.contains("Intent target = new Intent(Intent.ACTION_CREATE_DOCUMENT)"));
        assertTrue("「存」没把缓存文件名交给玩家选位置",
                saveBody.contains("target.putExtra(Intent.EXTRA_TITLE, staged.getName());"));
        assertTrue("「存」没带上本书自己的请求码",
                saveBody.contains("startActivityForResult(target, REQ_SAVE_ROUND_DIGEST);"));
        String writeBody = methodBody(src, "private void writeDigestToChosenDocument(Uri destination)");
        assertTrue("玩家选完位置后没把文本搬过去", writeBody.contains("openOutputStream(destination)"));

        List<String> chain = new ArrayList<>();
        chain.addAll(linesOf(speakBody));
        chain.addAll(linesOf(saveBody));
        chain.addAll(linesOf(writeBody));
        for (String api : Arrays.asList("dispatchGesture", "injectInputEvent", "Http", "Socket", "OkHttp",
                "ACTION_SEND", "createChooser")) {
            assertFalse("战报链上出现了 " + api, hasLineContaining(chain, api));
        }
        List<Hit> hits = ReadOnlyGuard.scan("Match3ToolsActivity.java", chain);
        assertEquals("战报链上出现只读守卫点名的注入类 API：" + hits, 0, hits.size());
    }
}
