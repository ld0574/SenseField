package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.openkhub.sensefield.HyperOsHealthSnapshot.Guidance;
import com.openkhub.sensefield.HyperOsHealthSnapshot.ProjectionStatus;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * HyperOS 系统级静默停止的读数层与接线锁（任务书 40）。
 *
 * <p>两层证据，边界写清楚：
 * <ul>
 *   <li>判定规则是纯函数，期望值全部手写死在下面的用例里，不拿被测函数的输出反推自己。</li>
 *   <li>接线侧（{@code device} 事件采样链、引导页出口、{@code startForeground}）本机无 adb、
 *       无该品牌设备，跑不了 Android，只能用源码结构式断言钉住「这一跳真的在链路上」，
 *       并且断言坐在被覆写本体／被调用方法体内，不是全文本数个数。</li>
 * </ul>
 *
 * <p>欠账：息屏后自动恢复能不能走通、引导页玩家看不看得懂、系统游戏加速压不压悬浮窗，
 * 本类证明不了，一条也不当已验。
 */
public final class HyperOsHealthSnapshotTest {

    private static final long AT = 123456L;

    private static HyperOsHealthSnapshot read(ProjectionStatus projection, boolean screenOn,
                                              boolean previouslyGranted, boolean accessibilityEnabled,
                                              int backgroundStartMode) {
        return HyperOsHealthSnapshot.evaluate(AT, projection, screenOn, previouslyGranted,
                accessibilityEnabled, backgroundStartMode,
                HyperOsHealthSnapshot.needsBackgroundStartPrompt(projection, accessibilityEnabled));
    }

    /* ---------- ① 三条规则各自单独成立 ---------- */

    /** 投影 REVOKED＋亮屏＋此前授权过 ⇒ 只出 PROMPT_REAUTH。 */
    @Test
    public void revokedProjectionWithScreenOnAndPriorGrantAsksReauthorization() {
        Guidance action = read(ProjectionStatus.REVOKED, true, true, true,
                HyperOsHealthSnapshot.OP_MODE_ALLOWED).action();
        assertEquals(Guidance.PROMPT_REAUTH, action);
    }

    /** 同样的投影撤销，息屏 ⇒ NONE：不许在玩家看不见屏幕时重走授权。 */
    @Test
    public void revokedProjectionWithScreenOffGivesNoPrompt() {
        assertEquals(Guidance.NONE,
                read(ProjectionStatus.REVOKED, false, true, true,
                        HyperOsHealthSnapshot.OP_MODE_ALLOWED).action());
    }

    /** 亮屏但从没授权过（首启即被系统收回）⇒ NONE：没有「之前授权过」就不算恢复场景。 */
    @Test
    public void revokedProjectionWithoutPriorGrantGivesNoPrompt() {
        assertEquals(Guidance.NONE,
                read(ProjectionStatus.REVOKED, true, false, true,
                        HyperOsHealthSnapshot.OP_MODE_ALLOWED).action());
    }

    /** 无障碍服务不在系统授权列表里 ⇒ PROMPT_ACCESSIBILITY（只引导，绝不自动重开）。 */
    @Test
    public void accessibilityRevokedOutsideEnabledListAsksAccessibilityPrompt() {
        assertEquals(Guidance.PROMPT_ACCESSIBILITY,
                read(ProjectionStatus.ACTIVE, true, true, false,
                        HyperOsHealthSnapshot.OP_MODE_ALLOWED).action());
    }

    /** 后台弹出为 MODE_IGNORED 且本读数需要送引导 ⇒ PROMPT_BACKGROUND_START。 */
    @Test
    public void ignoredBackgroundStartAsksBackgroundPrompt() {
        HyperOsHealthSnapshot snapshot =
                read(ProjectionStatus.REVOKED, false, false, true,
                        HyperOsHealthSnapshot.OP_MODE_IGNORED);
        assertEquals(Guidance.PROMPT_BACKGROUND_START, snapshot.action());
    }

    /** 反射读不到现值时按「不确定」处理：不许凭不确定就弹引导。 */
    @Test
    public void unreadableBackgroundStartModeNeverPrompts() {
        assertEquals(Guidance.NONE,
                read(ProjectionStatus.REVOKED, false, false, true,
                        HyperOsHealthSnapshot.OP_MODE_UNREADABLE).action());
    }

    /* ---------- ② 三条同时成立只出一个，优先级固定 ---------- */

    /** 投影＋无障碍＋后台弹出三条同时成立 ⇒ 只出投影那一条，优先级 投影 &gt; 无障碍 &gt; 后台弹出。 */
    @Test
    public void allThreeFaultsResolveToExactlyTheProjectionAction() {
        HyperOsHealthSnapshot snapshot = HyperOsHealthSnapshot.evaluate(AT,
                ProjectionStatus.REVOKED, true, true, false,
                HyperOsHealthSnapshot.OP_MODE_IGNORED, true);
        assertEquals("三条同时成立只许出一个动作", Guidance.PROMPT_REAUTH, snapshot.action());
        String line = snapshot.line();
        // 另外两条被压住，但事实必须写进同一行读数，不许丢。
        assertTrue("读数要留住无障碍被撤这一事实: " + line, line.contains(" a11y=revoked"));
        assertTrue("读数要留住后台弹出被拒这一事实: " + line, line.contains(" bg_start=ignored"));
        assertTrue("读数只能带一个 guidance 字段: " + line,
                line.indexOf(" guidance=") == line.lastIndexOf(" guidance="));
        assertEquals("读数只有一行: " + line, line, line.replace("\n", ""));
    }

    /** 投影那一条不成立时，无障碍排在后台弹出之前。 */
    @Test
    public void accessibilityOutranksBackgroundStart() {
        assertEquals(Guidance.PROMPT_ACCESSIBILITY, HyperOsHealthSnapshot.evaluate(AT,
                ProjectionStatus.ACTIVE, true, true, false,
                HyperOsHealthSnapshot.OP_MODE_IGNORED, true).action());
    }

    /** 单看一条一条也各自成立：只坏投影、只坏无障碍、只坏后台弹出。 */
    @Test
    public void eachFaultAloneSelectsItsOwnAction() {
        assertEquals(Guidance.PROMPT_REAUTH,
                read(ProjectionStatus.REVOKED, true, true, true,
                        HyperOsHealthSnapshot.OP_MODE_ALLOWED).action());
        assertEquals(Guidance.PROMPT_ACCESSIBILITY,
                read(ProjectionStatus.ACTIVE, true, true, false,
                        HyperOsHealthSnapshot.OP_MODE_ALLOWED).action());
        assertEquals(Guidance.PROMPT_BACKGROUND_START,
                read(ProjectionStatus.REVOKED, false, false, true,
                        HyperOsHealthSnapshot.OP_MODE_IGNORED).action());
    }

    /* ---------- ③ 一行结构化读数的内容 ---------- */

    @Test
    public void readingLineCarriesEveryInputVerbatim() {
        assertEquals("hyperos at=123456 proj=revoked screen=on prior_grant=granted"
                        + " a11y=enabled bg_start=allowed guidance=PROMPT_REAUTH",
                read(ProjectionStatus.REVOKED, true, true, true,
                        HyperOsHealthSnapshot.OP_MODE_ALLOWED).line());
        assertEquals("hyperos at=123456 proj=active screen=off prior_grant=none"
                        + " a11y=revoked bg_start=ignored guidance=PROMPT_ACCESSIBILITY",
                HyperOsHealthSnapshot.evaluate(AT, ProjectionStatus.ACTIVE, false, false, false,
                        HyperOsHealthSnapshot.OP_MODE_IGNORED, true).line());
        assertEquals("hyperos at=123456 proj=failed screen=on prior_grant=granted"
                        + " a11y=enabled bg_start=unreadable guidance=NONE",
                read(ProjectionStatus.FAILED, true, true, true,
                        HyperOsHealthSnapshot.OP_MODE_UNREADABLE).line());
    }

    /* ---------- ④ 自动恢复只走一次 ---------- */

    @Test
    public void reauthBudgetIsSpentOnceAndNeverReclaimed() {
        HyperOsHealthSnapshot.ReauthLatch latch = new HyperOsHealthSnapshot.ReauthLatch();
        HyperOsHealthSnapshot revoked =
                read(ProjectionStatus.REVOKED, true, true, true, HyperOsHealthSnapshot.OP_MODE_ALLOWED);
        assertEquals("第一次允许自动重走授权", HyperOsHealthSnapshot.REASK_READING,
                HyperOsHealthSnapshot.recoveryReadingFor(revoked, latch));
        assertEquals("第二次只落终态读数", HyperOsHealthSnapshot.REVOKED_FINAL_READING,
                HyperOsHealthSnapshot.recoveryReadingFor(revoked, latch));
        assertEquals("第三次仍不重试，不循环", HyperOsHealthSnapshot.REVOKED_FINAL_READING,
                HyperOsHealthSnapshot.recoveryReadingFor(revoked, latch));
        assertTrue(latch.spent());
        latch.reset();
        assertEquals("新授权成功后才重新给一次额度", HyperOsHealthSnapshot.REASK_READING,
                HyperOsHealthSnapshot.recoveryReadingFor(revoked, latch));
    }

    /** 只有 PROMPT_REAUTH 消耗恢复额度：另两条引导一次也不许吃掉投影那一次机会。 */
    @Test
    public void onlyReauthGuidanceConsumesTheRecoveryBudget() {
        HyperOsHealthSnapshot.ReauthLatch latch = new HyperOsHealthSnapshot.ReauthLatch();
        HyperOsHealthSnapshot none = HyperOsHealthSnapshot.evaluate(AT, ProjectionStatus.ACTIVE,
                true, true, true, HyperOsHealthSnapshot.OP_MODE_ALLOWED, true);
        assertEquals(Guidance.NONE, none.action());
        assertEquals(HyperOsHealthSnapshot.NO_RECOVERY_READING,
                HyperOsHealthSnapshot.recoveryReadingFor(none, latch));

        HyperOsHealthSnapshot accessibility = HyperOsHealthSnapshot.evaluate(AT,
                ProjectionStatus.ACTIVE, true, true, false, HyperOsHealthSnapshot.OP_MODE_ALLOWED, true);
        assertEquals(Guidance.PROMPT_ACCESSIBILITY, accessibility.action());
        assertEquals(HyperOsHealthSnapshot.NO_RECOVERY_READING,
                HyperOsHealthSnapshot.recoveryReadingFor(accessibility, latch));

        HyperOsHealthSnapshot background = HyperOsHealthSnapshot.evaluate(AT,
                ProjectionStatus.REVOKED, false, false, true, HyperOsHealthSnapshot.OP_MODE_IGNORED, true);
        assertEquals(Guidance.PROMPT_BACKGROUND_START, background.action());
        assertEquals(HyperOsHealthSnapshot.NO_RECOVERY_READING,
                HyperOsHealthSnapshot.recoveryReadingFor(background, latch));

        assertFalse("非投影引导不许消耗额度", latch.spent());
    }

    /** 终态读数用的就是任务书点名的那个名字，且只有一条。 */
    @Test
    public void terminalReadingIsTheNamedCaptureRevokedFinal() {
        assertEquals("capture_revoked_final", HyperOsHealthSnapshot.REVOKED_FINAL_READING);
        assertEquals(ProjectionStatus.REVOKED,
                read(ProjectionStatus.REVOKED, true, true, true,
                        HyperOsHealthSnapshot.OP_MODE_ALLOWED).projection());
    }

    /* ---------- ⑤ 纯函数零 android.* ---------- */

    @Test
    public void thePureClassImportsNoAndroidApi() throws IOException {
        List<String> code = codeLinesOnly(sourceOf("HyperOsHealthSnapshot.java"));
        List<String> offenders = new ArrayList<>();
        for (String line : code) {
            if (line.contains("android.")) offenders.add(line.trim());
        }
        assertEquals("纯函数不许碰任何 android.*：" + offenders, 0, offenders.size());
        for (String line : codeLinesOnly(sourceOf("HyperOsHealthSnapshot.java"))) {
            assertFalse("纯函数不许 import android.*: " + line,
                    line.trim().startsWith("import android."));
        }
    }

    /* ---------- ⑥ 读数真进既有 device 事件，且既有字段一字未改 ---------- */

    /** 既有 device 字段的原样九行——被人改动就红。 */
    private static final List<String> SHIPPED_DEVICE_FIELDS = Arrays.asList(
            "            JSONObject sample = object(\"battery_temp_tenths_c\", batteryTempTenthsC,",
            "                    \"battery_level\", battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),",
            "                    \"battery_status\", batteryStatus,",
            "                    \"battery_plugged\", batteryPlugged,",
            "                    \"charging\", batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||",
            "                            batteryStatus == BatteryManager.BATTERY_STATUS_FULL,",
            "                    \"media_volume\", audio == null ? -1 : audio.getStreamVolume(AudioManager.STREAM_MUSIC),",
            "                    \"media_volume_max\", audio == null ? -1 : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),",
            "                    \"thermal_status\", thermalStatus,");

    @Test
    public void existingDeviceFieldsAreVerbatimAndNewReadingIsAppendedAfterThem()
            throws IOException {
        List<String> src = sourceOf("DiagnosticRecorder.java");
        int start = indexOfFirstLineContaining(src, "JSONObject sample = object(");
        List<String> block = src.subList(start, Math.min(src.size(), start + SHIPPED_DEVICE_FIELDS.size()));
        assertEquals("既有 device 字段被人改动或重排", SHIPPED_DEVICE_FIELDS, block);
        // 新键只能追加在既有九行之后，不许插进中间。
        assertTrue("读数键必须追加在既有字段之后: " + src.get(start + 9),
                src.get(start + 9).trim().startsWith(
                        "\"thermal_status_name\", thermalStatusName(thermalStatus)"));
        assertTrue(src.get(start + 10).contains("HyperOsHealthSnapshot.READING_KEY"));
        assertTrue(src.get(start + 11).contains("HyperOsHealthSnapshot.GUIDANCE_KEY"));
        assertEquals(HyperOsHealthSnapshot.READING_KEY, "hyperos_system_reading");
        assertEquals(HyperOsHealthSnapshot.GUIDANCE_KEY, "hyperos_guidance");
    }

    /**
     * 采集这一跳真的挂在既有 device 周期采样链上：sampleDevice 体内取读数、送引导，
     * 且仍由既有 record("device", …) 落盘。摘掉任意一跳，本条必须变红。
     */
    @Test
    public void deviceSamplingChainItselfProducesTheReadingAndRoutesTheGuidance() throws IOException {
        String body = methodBody(codeLinesOnly(sourceOf("DiagnosticRecorder.java")),
                "private synchronized void sampleDevice(long now)");
        assertTrue("采样链里没有取读数这一跳: " + body,
                body.contains("HyperOsSystemProbe.sample(context, hyperOsProjection)"));
        assertTrue("采样链里没有把引导动作送出去这一跳: " + body,
                body.contains("HyperOsSystemProbe.deliverGuidanceEdge(context, hyperOs.action())"));
        assertTrue("读数没进 device 事件: " + body, body.contains("HyperOsHealthSnapshot.READING_KEY"));
        assertTrue("引导动作没进 device 事件: " + body, body.contains("HyperOsHealthSnapshot.GUIDANCE_KEY"));
        assertTrue("既有 device 事件落点被换掉: " + body, body.contains("record(\"device\", sample)"));
    }

    /** 不新增周期任务、不新增线程：采样链上不许出现新的调度器或线程。 */
    @Test
    public void theNewReadingStartsNoExtraSchedulerOrThread() throws IOException {
        List<String> code = codeLinesOnly(sourceOf("HyperOsSystemProbe.java"));
        StringBuilder joined = new StringBuilder();
        for (String line : code) joined.append(line);
        String src = joined.toString();
        for (String forbidden : new String[]{"new Thread", "Executors.", "ScheduledExecutorService",
                "scheduleAtFixedRate", "scheduleWithFixedDelay", "Timer(", "new HandlerThread"}) {
            assertFalse("采集侧不许新增调度/线程: " + forbidden, src.contains(forbidden));
        }
    }

    /* ---------- ⑦ 引导只走既有出口，不新增弹窗/授权页 ---------- */

    @Test
    public void guidanceLeavesOnlyThroughTheExistingAuthorizationPage() throws IOException {
        String body = methodBody(codeLinesOnly(sourceOf("HyperOsSystemProbe.java")),
                "static boolean deliverGuidance(Context context, HyperOsHealthSnapshot.Guidance action)");
        assertTrue("引导没走既有引导页出口: " + body,
                body.contains("new Intent(context, CapturePermissionsActivity.class)"));
        assertTrue("引导没复用既有 EXTRA_START 授权流程: " + body,
                body.contains("CapturePermissionsActivity.EXTRA_START"));
        assertTrue("NONE 动作不许送任何页面: " + body,
                body.contains("action == HyperOsHealthSnapshot.Guidance.NONE) return false"));
        assertFalse("不许新增对话框类型: " + body, body.contains("AlertDialog"));
        assertFalse("不许新增 Toast 型弹窗: " + body, body.contains("Toast"));
    }

    /** 本卷不许新增授权 Activity／弹窗类型：引导页只许是既有那一个。 */
    @Test
    public void noNewAuthorizationActivityOrDialogIsIntroduced() throws IOException {
        for (String simpleName : new String[]{"HyperOsSystemProbe.java", "HyperOsHealthSnapshot.java"}) {
            List<String> code = codeLinesOnly(sourceOf(simpleName));
            for (String line : code) {
                assertFalse(simpleName + " 不许声明 Activity: " + line,
                        line.contains("extends Activity") || line.contains("extends UiActivity"));
                assertFalse(simpleName + " 不许引入弹窗类: " + line,
                        line.contains("AlertDialog") || line.contains("Dialog("));
            }
        }
    }

    /* ---------- ⑧ 后台弹出只用反射读，不反射写 ---------- */

    @Test
    public void backgroundStartPermissionIsOnlyReadNeverWritten() throws IOException {
        String body = methodBody(codeLinesOnly(sourceOf("HyperOsSystemProbe.java")),
                "static int backgroundStartMode(Context context)");
        assertTrue("没有反射读 checkOpNoThrow: " + body,
                body.contains("\"checkOpNoThrow\", String.class, int.class, String.class"));
        assertTrue("读的是任务书点名的那个操作: " + body,
                body.contains("BACKGROUND_START_OP"));
        assertEquals("OP_BACKGROUND_START_ACTIVITY", HyperOsSystemProbe.BACKGROUND_START_OP);
        for (String writeApi : new String[]{"startOp", "noteOp", "opChangedNote", "setMode",
                "setUidMode", "startWatchingMode", "permissionSet", "Settings.System.put",
                "Settings.Secure.put", "Settings.Global.put"}) {
            assertFalse("不许反射写系统设置: " + writeApi, body.contains(writeApi));
        }
    }

    /** 无障碍自检只读授权列表，绝不自动重开服务。 */
    @Test
    public void accessibilitySelfCheckIsReadOnlyAndNeverReopensTheService() throws IOException {
        String body = methodBody(codeLinesOnly(sourceOf("HyperOsSystemProbe.java")),
                "static boolean isAccessibilityServiceEnabled(Context context)");
        assertTrue("自检没读授权列表: " + body,
                body.contains("getEnabledAccessibilityServiceList("));
        for (String writeApi : new String[]{"enableAccessibilityService", "putInt", "Settings.Secure",
                "performGlobalAction", "dispatchGesture", "injectInputEvent"}) {
            assertFalse("自检不许动授权开关: " + writeApi, body.contains(writeApi));
        }
    }

    /* ---------- ⑨ 投影自动恢复只接在 onStop 之后 ---------- */

    @Test
    public void recoveryHopSitsInsideOnStopAfterTheExistingRevokedHandling() throws IOException {
        String body = methodBody(codeLinesOnly(sourceOf("CaptureService.java")),
                "@Override public void onStop() {");
        List<String> lines = linesOf(body);
        int revoke = indexOfFirstLineContaining(lines, "captureHealth.revoke()");
        int revokedLog = indexOfFirstLineContaining(lines,
                "logCaptureHealth(\"REVOKED\", \"projection_stopped\")");
        int stopped = indexOfFirstLineContaining(lines, "stopSelfResult(stopThroughStartId)");
        int recovery = indexOfFirstLineContaining(lines, "attemptProjectionRecoveryAfterRevocation()");
        assertTrue("恢复这一跳必须排在既有撤销处理之后", revoke < revokedLog
                && revokedLog < stopped && stopped < recovery);
        // 恢复本体必须坐在 onStop 之后被调用的那个方法里，且只经额度判定发一次。
        String recoveryBody = methodBody(codeLinesOnly(sourceOf("CaptureService.java")),
                "private void attemptProjectionRecoveryAfterRevocation()");
        assertTrue("恢复没走额度判定: " + recoveryBody,
                recoveryBody.contains("HyperOsHealthSnapshot.recoveryReadingFor(snapshot, projectionReauthLatch)"));
        assertTrue("只有取到额度才送引导: " + recoveryBody,
                recoveryBody.contains("HyperOsHealthSnapshot.REASK_READING.equals(recovery)"));
        assertTrue("送引导走既有出口: " + recoveryBody,
                recoveryBody.contains("HyperOsSystemProbe.deliverGuidance(this, snapshot.action())"));
        assertTrue("读数不许丢: " + recoveryBody, recoveryBody.contains("logHyperOsReading(recovery, snapshot)"));
    }

    /** 终态读数用的名字必须真的落到既有 audit 出口，而不是只写在常量里。 */
    @Test
    public void terminalReadingReachesTheExistingDiagnosticAuditOutlet() throws IOException {
        String body = methodBody(codeLinesOnly(sourceOf("CaptureService.java")),
                "private void logHyperOsReading(String recovery, HyperOsHealthSnapshot snapshot)");
        assertTrue("读数没进既有诊断归档出口: " + body, body.contains("recorder.audit(message)"));
        assertTrue("读数没带结构化那一行: " + body, body.contains("snapshot.line()"));
    }

    /* ---------- ⑩ startForeground 被钉在该覆写本体里 ---------- */

    /**
     * 断言坐在 {@code onServiceConnected()} 覆写本体里：只数全文本还剩几个 startForeground
     * 是证明不了这一跳的。
     */
    @Test
    public void startForegroundIsCalledInsideTheOnServiceConnectedOverride() throws IOException {
        List<String> code = codeLinesOnly(sourceOf("SenseFieldReaderService.java"));
        String body = methodBody(code, "protected void onServiceConnected()");
        assertTrue("onServiceConnected 本体里没有 startForeground 这一跳: " + body,
                body.contains("startForeground(READER_FOREGROUND_NOTIFICATION_ID,"));
        assertTrue("抬前台必须复用既有通知渠道: " + body,
                body.contains("CaptureService.CHANNEL"));
        assertFalse("不许新增通知渠道: " + body, body.contains("createNotificationChannel"));
        assertTrue("抬前台失败必须降级而不是打断连接: " + body,
                body.contains("catch (RuntimeException declined)"));
        // 既有句柄登记与 connected 置位不许被这一跳挤掉。
        for (String kept : new String[]{"setServiceInfo(info);", "connected = true;",
                "instance = new java.lang.ref.WeakReference<>(this);"}) {
            assertTrue("既有连接处理被改动: " + kept, body.contains(kept));
        }
    }

    /** 全树只许有既有那两个通知渠道，本卷一个也不新增。 */
    @Test
    public void theMainSourceSetStillCreatesOnlyTheExistingNotificationChannels() throws IOException {
        List<String> created = new ArrayList<>();
        for (File file : mainSourceFiles()) {
            for (String line : codeLinesOnly(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))) {
                if (!line.contains("createNotificationChannel")) continue;
                Matcher matcher = Pattern.compile("(\\w+\\.CHANNEL|\"[^\"]+\")").matcher(line);
                created.add(file.getName() + " " + (matcher.find() ? matcher.group(1) : "?"));
            }
        }
        assertEquals("createNotificationChannel 只许出现在既有采集/消消乐两处: " + created, 2, created.size());
    }

    /* ---------- 仪器 ---------- */

    private static File mainSourceRoot() {
        String[] candidates = {"src/main/java", "app/src/main/java", "../app/src/main/java",
                "../src/main/java"};
        for (String candidate : candidates) {
            File directory = new File(candidate);
            if (directory.isDirectory()) return directory;
        }
        throw new AssertionError("主源码集找不到，这条断言没接上电");
    }

    private static List<File> mainSourceFiles() throws IOException {
        List<File> out = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> walk =
                     Files.walk(mainSourceRoot().toPath())) {
            walk.filter(p -> p.toString().replace('\\', '/').endsWith(".java"))
                    .forEach(p -> out.add(p.toFile()));
        }
        assertFalse("主源码集为空，断言证明不了任何事", out.isEmpty());
        return out;
    }

    private static List<String> sourceOf(String simpleName) throws IOException {
        String rel = "src/main/java/com/openkhub/sensefield/" + simpleName;
        String fromRoot = "android/app/src/main/java/com/openkhub/sensefield/" + simpleName;
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; dir != null && up <= 5; up++, dir = dir.getParentFile()) {
            for (String candidate : new String[]{rel, fromRoot}) {
                File file = new File(dir, candidate);
                if (file.isFile()) return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("找不到被测源文件：" + simpleName
                + "（模块内与树根起两种相对写法、向上五级均未命中），这条断言没接上电");
    }

    private static List<String> codeLinesOnly(List<String> src) {
        List<String> out = new ArrayList<>();
        for (String line : src) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) continue;
            out.add(line);
        }
        return out;
    }

    private static int indexOfFirstLineContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(needle)) return i;
        throw new AssertionError("源码里没有这句：" + needle);
    }

    private static String methodBody(List<String> src, String signatureNeedle) {
        int start = indexOfFirstLineContaining(src, signatureNeedle);
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean started = false;
        for (int i = start; i < src.size(); i++) {
            String line = src.get(i);
            out.append(line).append('\n');
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '{') { depth++; started = true; } else if (c == '}') depth--;
            }
            if (started && depth <= 0) break;
        }
        assertTrue("方法体没配平：" + signatureNeedle, started && depth <= 0);
        return out.toString();
    }

    private static List<String> linesOf(String body) {
        return Arrays.asList(body.split("\n", -1));
    }
}
