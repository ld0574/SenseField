package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 任务书 33 的报警器：钉住「认不出界面时在本地落一份脱敏记录」这条链——字段白名单、只落本地、
 * 有上限有节流、写坏了也只在读数里留一句「这次没写成」。
 *
 * <p>被测的是 {@link LocalIssueReport}（纯 JVM：字段摊平、渲染、节流与上限记账、落盘）与
 * {@link DiagnosticRecorder#issueReportData}（读数形状），再加 {@code DiagnosticRecorder.java}
 * 里那一处触发行与 {@code LocalIssueReport.java} 的调用面。</p>
 *
 * <p>期望值都由人写死：样本一律拿真实的 {@link Match3UnknownElements} 采样器产出，
 * 不用被测函数的输出反推自己。</p>
 *
 * <p>证据边界：本机无 adb。这里只能证 JVM 代码路径、落盘字节与源码接线，
 * 证不了真机上这份文件在文件管理器里看不看得见、开发者拿到后能不能复现那一盘。</p>
 */
public final class LocalIssueReportTest {

    private static final int CELL_ROW = 2;
    private static final int CELL_COL = 1;
    private static final int BOARD_ROWS = 7;
    private static final int BOARD_COLS = 8;
    private static final int BASE_PIXEL = 0x203040;
    private static final int CORE_PIXEL = 0xe0d0a0;

    /** 与既有采样测试同一套做法：256 像素底＋一块 8×8 内部细节，才有资格成为样本。 */
    private static int[] patch(int base, int core) {
        int[] pixels = new int[256];
        Arrays.fill(pixels, 0xff000000 | base);
        for (int y = 4; y < 12; y++) for (int x = 4; x < 12; x++) pixels[y * 16 + x] = 0xff000000 | core;
        return pixels;
    }

    private static Match3UnknownElements.Observation review() {
        return new Match3UnknownElements.Observation(
                Match3Position.Cell.obstacle(Match3Position.Kind.SURFACE, -1), patch(BASE_PIXEL, CORE_PIXEL));
    }

    /** 4×4 盘面里只有 (2,1) 一格认不出：走真实采样器，稳定三帧后给出第一个样本。 */
    private static Match3UnknownElements.Sample firstSample() {
        Match3UnknownElements groups = new Match3UnknownElements();
        Match3UnknownElements.Observation[][] grid = new Match3UnknownElements.Observation[4][4];
        grid[CELL_ROW][CELL_COL] = review();
        for (long at : new long[]{100L, 600L, 1100L}) {
            List<Match3UnknownElements.Sample> samples = groups.observe(grid, at);
            if (!samples.isEmpty()) return samples.get(0);
        }
        throw new IllegalStateException("一个稳定的认不出格子没有产出样本，这条测试没接上电");
    }

    private static BoardGeometry geometry() {
        return new BoardGeometry(1080, 1920, 100, 200, 980, 1700, BOARD_ROWS, BOARD_COLS);
    }

    private static LocalIssueReport.Snapshot snapshot(String appVersion) {
        return LocalIssueReport.read(DiagnosticGame.MATCH3, geometry(), firstSample(), appVersion);
    }

    private static LocalIssueReport.Pending payload(String appVersion) {
        return LocalIssueReport.render(snapshot(appVersion), 1, 1,
                LocalIssueReport.FILE_PREFIX + "1" + LocalIssueReport.FILE_SUFFIX);
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

    /** 只看代码行：注释行剔除，注释掉的触发行不算接线。 */
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

    private static File sessionDirectory(String tag) throws IOException {
        File root = Files.createTempDirectory(tag).toFile();
        File session = new File(root, "diag-" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID());
        assertTrue(session.mkdirs());
        return session;
    }

    /* ---------- ① 字段白名单：报告里只许出现列过的键，且必带的字段一个不能少 ---------- */

    @Test
    public void reportCarriesNothingButTheWhitelistedKeys() {
        LocalIssueReport.Pending job = payload("1.2.3");
        assertNotNull("一个正常样本就该有一份待落盘载荷", job);
        String text = job.text;
        Set<String> allowed = new HashSet<>(Arrays.asList(
                "record", "schema", "package", "game", "board_rows", "board_cols", "unknown_families",
                "unknown_samples", "family_limit", "samples_per_family_limit", "stable_samples_required",
                "sample_gap_ms", "review_threshold", "review_confidence_band", "unresolved_reason",
                "unresolved_cell_kind", "app_version", "delivery"));
        for (String line : text.split("\n")) {
            if (line.isEmpty()) continue;
            int eq = line.indexOf('=');
            assertTrue("报告里出现了没有键的一行：" + line, eq > 0);
            String key = line.substring(0, eq);
            assertTrue("白名单之外的字段进了报告：" + key, allowed.contains(key));
        }
        for (String required : Arrays.asList("package=", "game=", "board_rows=", "board_cols=",
                "unknown_families=", "unknown_samples=", "review_threshold=", "review_confidence_band=",
                "unresolved_reason=", "app_version=")) {
            assertTrue("必带字段缺失：" + required, text.contains(required));
        }
    }

    @Test
    public void whitelistValuesComeFromTheExistingReadingsNotFromReassembly() {
        String text = payload("1.2.3").text;
        assertTrue("包名要用既有常量", text.contains("package=" + GameLauncher.HAPPY_ANIPOP_PACKAGE + "\n"));
        assertTrue("游戏显示名要用既有注册值", text.contains("game=" + DiagnosticGame.MATCH3.label + "\n"));
        assertTrue("盘面形状取当轮几何", text.contains("board_rows=" + BOARD_ROWS + "\n"));
        assertTrue("盘面形状取当轮几何", text.contains("board_cols=" + BOARD_COLS + "\n"));
        assertTrue("应用版本走既有取值出口", text.contains("app_version=1.2.3\n"));
    }

    /* ---------- ② 坐标序列与像素描述符进不了报告 ---------- */

    @Test
    public void cellCoordinatesAndPixelDescriptorsNeverEnterTheReport() {
        Match3UnknownElements.Sample sample = firstSample();
        assertEquals(CELL_ROW, sample.row);
        assertEquals(CELL_COL, sample.col);
        String text = payload("1.2.3").text;
        assertFalse("报告里出现了逗号（坐标序列的形状）：" + text, text.contains(","));
        assertFalse("逐格行坐标进了报告", text.contains("row=" + CELL_ROW));
        assertFalse("逐格列坐标进了报告", text.contains("col=" + CELL_COL));
        assertFalse("样本行号以其他形状溜进来", text.contains("r" + CELL_ROW + "c" + CELL_COL));
        assertFalse("像素底色的十六进制进了报告", text.contains(Integer.toHexString(BASE_PIXEL)));
        assertFalse("像素核心的十六进制进了报告", text.contains(Integer.toHexString(CORE_PIXEL)));
        assertFalse("家族标识不该出现在这份记录里", text.contains("family_id"));
        assertEquals("盘面行列数以外的形状计数不该出现", 2, countOccurrences(text, "board_"));
    }

    private static int countOccurrences(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    /* ---------- ③ 脏输入：屏幕原文／设备号／路径／坐标都进不来 ---------- */

    @Test
    public void dirtyVersionTextCannotSmuggleScreenTextDeviceIdentityOrPath() {
        String hostile = "1.0 (C:\\Users\\888777\\OneDrive\\Desktop\\shot.png 华为Mate 60 微信截图) rows=3 cols=5 奖励已发放";
        LocalIssueReport.Pending job = payload(hostile);
        assertNotNull(job);
        String text = job.text;
        assertTrue("脏版本必须整值作废，不许留下半截", text.contains("app_version=" + LocalIssueReport.UNAVAILABLE + "\n"));
        for (String forbidden : new String[]{"888777", "OneDrive", "Desktop", "shot.png", "Users",
                "华为", "微信", "Mate", "奖励已发放", "rows=3", "cols=5", "C:"}) {
            assertFalse("脏输入里这一段进了报告：" + forbidden, text.contains(forbidden));
        }
        assertTrue("整篇文本再过一遍既有脱敏规则必须一字不变（说明身份形状已被吃干净）",
                JevAnnotationExporter.desensitize(text).equals(text));
        assertEquals("作废后的取值不带任何路径分隔符", -1, text.indexOf('\\'));
        assertEquals("报告正文里不许出现正斜杠（路径形状）", -1, text.indexOf('/'));
    }

    @Test
    public void desensitisationPassIsLiveBehindTheFieldGate() {
        java.util.Map<String, Integer> sink = new java.util.LinkedHashMap<>();
        String stripped = JevAnnotationExporter.desensitize("com.wechat.app", sink);
        assertTrue("既有规则必须真的命中聊天软件名这一段",
                stripped.contains(JevAnnotationExporter.PLACEHOLDER_CHAT_APP));
        assertNotNull("命中要按既有类别计数", sink.get(JevAnnotationExporter.CATEGORY_CHAT_APP));
        assertEquals("身份形状被替换后不再满足取值形状，整值作废（不是削掉一半留下半截）",
                LocalIssueReport.UNAVAILABLE, LocalIssueReport.admitVersion("com.wechat.app"));
        assertEquals("干净取值一字不动", "1.2.3", LocalIssueReport.admitVersion("1.2.3"));
        assertEquals("空取值只能落成替身", LocalIssueReport.UNAVAILABLE, LocalIssueReport.admitVersion(null));
    }

    @Test
    public void reportTextCarriesNoAccountOrDriveShapedLiteral() {
        String text = payload("1.2.3-beta+7").text;
        assertFalse("出现了盘符形状", java.util.regex.Pattern.compile("(?i)[a-z]:[\\\\/]").matcher(text).find());
        assertFalse("出现了用户目录形状", java.util.regex.Pattern.compile("(?i)(users|home)[\\\\/]{1,2}")
                .matcher(text).find());
        assertFalse("出现了数字账号段", java.util.regex.Pattern.compile("[0-9]{6,}").matcher(text).find());
        assertFalse("出现了行首斜杠（绝对路径形状）", text.contains("\n/"));
        assertEquals("文件名不含目录分隔符", LocalIssueReport.FILE_PREFIX + "1" + LocalIssueReport.FILE_SUFFIX,
                LocalIssueReport.safeFileName("unrecognized-screen-1.txt"));
        assertNull("带路径形状的名字一律作废", LocalIssueReport.safeFileName("../unrecognized-screen-1.txt"));
        assertNull("带盘符的名字一律作废", LocalIssueReport.safeFileName("C:\\unrecognized-screen-1.txt"));
    }

    /* ---------- ④ 家族数为 0 不生成 ---------- */

    @Test
    public void zeroFamiliesProduceNoFileAndNoPayload() throws IOException {
        File session = sessionDirectory("b33-empty");
        try {
            LocalIssueReport.Writer writer = new LocalIssueReport.Writer(session);
            List<LocalIssueReport.Pending> out = new ArrayList<>();
            writer.observe(null, 5_000L, out::add);
            assertEquals("认不出家族数为 0 时不许生成", LocalIssueReport.STATUS_EMPTY, writer.status());
            assertTrue(out.isEmpty());
            assertEquals("不生成就不许把样本记进账", 0, writer.sampleCount());
            assertEquals(0, writer.fileCount());
            assertEquals(0, listedReports(session).length);
            assertNull("家族数为 0 时渲染直接给空载荷",
                    LocalIssueReport.render(snapshot("1.2.3"), 0, 9, "unrecognized-screen-1.txt"));
        } finally {
            DiagnosticArchive.delete(session);
            DiagnosticArchive.delete(session.getParentFile());
        }
    }

    private static File[] listedReports(File directory) {
        File[] found = directory.listFiles((dir, name) -> name.startsWith(LocalIssueReport.FILE_PREFIX)
                && name.endsWith(LocalIssueReport.FILE_SUFFIX));
        return found == null ? new File[0] : found;
    }

    /* ---------- ⑤ 节流与硬上限 ---------- */

    @Test
    public void repeatedTriggersInsideTheGapDoNotAddFiles() throws IOException {
        File session = sessionDirectory("b33-throttle");
        try {
            LocalIssueReport.Writer writer = new LocalIssueReport.Writer(session);
            LocalIssueReport.Snapshot snap = snapshot("1.2.3");
            List<LocalIssueReport.Pending> out = new ArrayList<>();
            long at = 10_000L;
            for (int i = 0; i < 40; i++) {
                writer.observe(snap, at, out::add);
                while (!out.isEmpty()) writer.settle(out.remove(0));
                at += 100L;   // 40 次触发只跨 3.9 秒，全部落在一个节流窗口里
            }
            assertEquals("间隔没到就不许再生成第二份", 1, writer.fileCount());
            assertEquals(LocalIssueReport.STATUS_THROTTLED, writer.status());
            assertEquals(1, listedReports(session).length);
            assertEquals("样本照常记账，只是不落第二份", 40, writer.sampleCount());
            assertEquals(1, writer.familyCount());
        } finally {
            DiagnosticArchive.delete(session);
            DiagnosticArchive.delete(session.getParentFile());
        }
    }

    @Test
    public void fileCountStopsAtTheHardCap() throws IOException {
        File session = sessionDirectory("b33-cap");
        try {
            LocalIssueReport.Writer writer = new LocalIssueReport.Writer(session);
            LocalIssueReport.Snapshot snap = snapshot("1.2.3");
            List<LocalIssueReport.Pending> out = new ArrayList<>();
            long at = 0L;
            for (int i = 0; i < LocalIssueReport.MAX_REPORT_FILES + 12; i++) {
                writer.observe(snap, at, out::add);
                while (!out.isEmpty()) writer.settle(out.remove(0));
                at += LocalIssueReport.MIN_GAP_MS;
            }
            assertEquals("文件数必须停在硬上限", LocalIssueReport.MAX_REPORT_FILES, writer.fileCount());
            assertEquals(LocalIssueReport.STATUS_FILE_LIMIT, writer.status());
            assertEquals(LocalIssueReport.MAX_REPORT_FILES, listedReports(session).length);
            for (File file : listedReports(session)) {
                long bytes = file.length();
                assertTrue("单文件字节数必须停在硬上限内：" + bytes,
                        bytes > 0 && bytes <= LocalIssueReport.MAX_REPORT_BYTES);
            }
        } finally {
            DiagnosticArchive.delete(session);
            DiagnosticArchive.delete(session.getParentFile());
        }
    }

    @Test
    public void gapAndLimitsReuseTheSamplerConstants() {
        assertEquals("节流间隔不许另立时间口径", Match3UnknownElements.SAMPLE_GAP_MS, LocalIssueReport.MIN_GAP_MS);
        String text = payload("1.2.3").text;
        assertTrue(text.contains("family_limit=" + Match3UnknownElements.MAX_FAMILIES + "\n"));
        assertTrue(text.contains("samples_per_family_limit=" + Match3UnknownElements.MAX_SAMPLES_PER_FAMILY + "\n"));
        assertTrue(text.contains("stable_samples_required=" + Match3UnknownElements.STABLE_SAMPLES + "\n"));
        assertTrue(text.contains("sample_gap_ms=" + Match3UnknownElements.SAMPLE_GAP_MS + "\n"));
        assertTrue(text.contains("review_threshold=" + Float.toString(Match3AnimalAppearance.MAXIMUM) + "\n"));
        assertTrue(text.contains("review_confidence_band=" + Float.toString(Match3AnimalAppearance.MARGIN) + "\n"));
        assertTrue("弃权原因取采样器现成的两值之一",
                text.contains("unresolved_reason=identity_or_rule_unverified\n")
                        || text.contains("unresolved_reason=cover_unverified\n"));
        assertTrue(text.contains("unresolved_cell_kind=" + Match3Position.Kind.SURFACE.name() + "\n"));
    }

    /* ---------- ⑥ 写盘失败静默，但读数里看得见「这次没写成」 ---------- */

    @Test
    public void writeFailureIsSwallowedButShowsUpInTheReadout() throws IOException {
        File blocker = Files.createTempFile("b33-not-a-directory", ".bin").toFile();
        try {
            LocalIssueReport.Writer writer = new LocalIssueReport.Writer(
                    new File(blocker, "diag-1-00000000-0000-0000-0000-000000000000"));
            List<LocalIssueReport.Pending> out = new ArrayList<>();
            writer.observe(snapshot("1.2.3"), 1_000L, out::add);
            assertEquals(1, out.size());
            String status = writer.settle(out.get(0));
            assertEquals("写坏了只回一个读数，不许抛出去", LocalIssueReport.STATUS_NOT_WRITTEN, status);
            assertEquals(1, writer.notWrittenCount());

            JSONObject data = DiagnosticRecorder.issueReportData(out.get(0), status, writer);
            assertEquals(LocalIssueReport.STATUS_NOT_WRITTEN, data.optString("status"));
            assertFalse(data.optBoolean("written"));
            assertTrue("既有诊断读数里必须看得见这次没写成", data.optBoolean("not_written_this_time"));
            assertEquals(1, data.optInt("not_written"));
            assertEquals(LocalIssueReport.RECORD_TAG, data.optString("record"));
            assertEquals(out.get(0).fileName, data.optString("file"));
            assertEquals(out.get(0).bytes, data.optInt("written_bytes"));
            assertEquals(1, data.optInt("unknown_families"));
        } finally {
            Files.deleteIfExists(blocker.toPath());
        }
    }

    @Test
    public void successfulWriteLandsInTheExistingSessionDirectoryOnly() throws IOException {
        File session = sessionDirectory("b33-write");
        try {
            LocalIssueReport.Writer writer = new LocalIssueReport.Writer(session);
            List<LocalIssueReport.Pending> out = new ArrayList<>();
            writer.observe(snapshot("1.2.3"), 2_000L, out::add);
            assertEquals(1, out.size());
            assertEquals(LocalIssueReport.STATUS_WRITTEN, writer.settle(out.get(0)));
            File written = new File(session, out.get(0).fileName);
            assertTrue(written.isFile());
            assertEquals(0, writer.notWrittenCount());
            String text = new String(Files.readAllBytes(written.toPath()), StandardCharsets.UTF_8);
            assertEquals("落盘字节与渲染载荷逐字相同", out.get(0).text, text);
            assertEquals("一次落盘只写一个附属文件", 1, listedReports(session).length);
            assertEquals("附属文件之外不许在会话目录里新建目录", 0,
                    session.listFiles((dir, name) -> new File(dir, name).isDirectory()).length);
            JSONObject data = DiagnosticRecorder.issueReportData(out.get(0), LocalIssueReport.STATUS_WRITTEN, writer);
            assertTrue(data.optBoolean("written"));
            assertFalse(data.optBoolean("not_written_this_time"));
            assertEquals("local_file_only_no_upload_no_share_no_prompt", data.optString("scope"));
        } finally {
            DiagnosticArchive.delete(session);
            DiagnosticArchive.delete(session.getParentFile());
        }
    }

    /* ---------- ⑦ 源码接线：触发行只此一处，落盘只在 IO 线程，新类不碰网络与外发 ---------- */

    @Test
    public void diagnosticConsumerOwnsTheSingleTriggerLine() throws IOException {
        List<String> code = codeLinesOnly(sourceOf("DiagnosticRecorder.java"));
        String trigger = "issueReports.observe(LocalIssueReport.read(game, geometry, sample, appVersion), sample.at, this::deliverIssueReport);";
        assertEquals("触发行必须逐字存在且只有一处", 1, countLinesContaining(code, trigger));
        String body = methodBody(sourceOf("DiagnosticRecorder.java"),
                "void elementSample(Bitmap frame,BoardGeometry geometry,Match3UnknownElements.Sample sample,");
        assertTrue("触发行必须在样本消费方法体内", body.contains(trigger));
        assertTrue("触发行必须排在画面采样锁之前，不许在锁内同步落盘",
                body.indexOf(trigger) < body.indexOf("synchronized(imageStateLock)"));
        assertTrue("触发行必须在方法体内", body.contains(trigger));
        String deliver = methodBody(sourceOf("DiagnosticRecorder.java"),
                "private void deliverIssueReport(LocalIssueReport.Pending job)");
        assertTrue("落盘必须投递到既有诊断 IO 工作线程", deliver.contains("IO.execute("));
        assertTrue("落盘只走 Writer 的 settle", deliver.contains("issueReports.settle(job)"));
        assertTrue("写没写成都要递到既有诊断记录行", deliver.contains("record(\"LocalIssueReport\", issueReportData(job, status, issueReports))"));
        assertEquals("除本类外不许有第二个触发点", 1, countLinesContaining(code, "issueReports.observe("));
    }

    @Test
    public void reportWriterNeverTouchesANetworkShareOrStorageApi() throws IOException {
        List<String> src = sourceOf("LocalIssueReport.java");
        List<String> code = codeLinesOnly(src);
        for (String api : new String[]{"HttpURLConnection", "HttpsURLConnection", "OkHttp", "Retrofit",
                "URL(", "openConnection", "java.net.Socket", "DatagramSocket", "Intent", "ACTION_SEND",
                "createChooser", "FileProvider", "getExternalStorageDirectory", "Environment",
                "UploadManager", "WorkManager", "AlarmManager", "SharedPreferences"}) {
            assertFalse("本地记录器里出现了 " + api, hasLineContaining(code, api));
        }
        for (String line : src) {
            String t = line.trim();
            if (t.startsWith("import ")) {
                assertFalse("本地记录器不许 import 任何 android 类：" + t, t.contains("android"));
            }
        }
        assertEquals("落盘只许走既有诊断出口一处", 1, countLinesContaining(code, "DiagnosticArchive.writeJson("));
        assertTrue("写盘异常必须吞掉", hasLineContaining(code, "catch (Exception failure)"));
    }

    @Test
    public void samplerConstantsAndDesensitisationRulesStayUntouched() throws IOException {
        // 本卷不许改采样常量、判据语义，也不许在报告侧重写一套剔除规则；这里钉住「只有取值，没有第二套尺子」。
        List<String> sampler = codeLinesOnly(sourceOf("Match3UnknownElements.java"));
        assertEquals("采样常量表不许被动", 1,
                countLinesContaining(sampler, "static final int MAX_FAMILIES=24, MAX_SAMPLES_PER_FAMILY=2, STABLE_SAMPLES=3;"));
        assertEquals("采样间隔不许被动", 1,
                countLinesContaining(sampler, "static final long SAMPLE_GAP_MS=10000, FRESH_MS=2400;"));
        assertEquals("判据入口不许被动", 1, countLinesContaining(sampler, "static boolean needsReview(Match3Position.Cell cell) {"));
        List<String> report = codeLinesOnly(sourceOf("LocalIssueReport.java"));
        assertEquals("报告侧只许有一条字段准入形状规则，不许再写剔除规则表", 1,
                countLinesContaining(report, "Pattern.compile("));
        assertFalse("不许出现第二套占位符字面量", hasLineContaining(report, "PLACEHOLDER_"));
        assertFalse("不许出现大小写无关的身份正则（那是既有规则表的形状）", hasLineContaining(report, "(?i)"));
        assertEquals("脱敏只许调既有出口", 3, countLinesContaining(report, "JevAnnotationExporter.desensitize("));
        assertFalse("不许出现第二套品牌或应用名名单", hasLineContaining(report, "RULES"));
    }
}
