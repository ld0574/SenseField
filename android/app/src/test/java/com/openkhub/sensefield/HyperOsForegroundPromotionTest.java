package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * 任务书 44：把「前台抬升被系统拒绝」从只进 {@code Log.w} 变成进既有 {@code device} 读数面，
 * 并把「清单未声明类型、本机拿不出可辩护依据」写成一条结构化读数（路线 B）。
 *
 * <p>两层证据，边界照任务书 40 的写法摆清楚：
 * <ul>
 *   <li>读数形状是纯函数（{@link HyperOsHealthSnapshot}），期望值全部手写死在下面，
 *       不拿被测函数的输出反推自己。</li>
 *   <li>接线侧（{@code onServiceConnected()} 的 catch 分支、既有 {@code device} 采样链、
 *       清单原文）本机无 adb、无该品牌设备，跑不了 Android，只能用源码结构式断言钉住
 *       「这一跳真的在链路上」，并且断言坐在被覆写本体／被 catch 块体内，不是全文本数个数。</li>
 * </ul>
 *
 * <p>欠账，一条也不当已验：抬升到底在真机上被不被拒、被拒后服务是不是真的更容易被省电策略
 * 结束、玩家能不能看懂这条读数。本类只证明「被拒这件事在本机有读数、有测试锁、不是静默吞掉」。
 */
public final class HyperOsForegroundPromotionTest {

    private static final long AT = 123456L;

    /** 派单/开工前实测的清单原文摘要（换行归一后的 sha256），路线 B 的逐字节不动锁。 */
    private static final String MANIFEST_SHA256 =
            "cca6ec5ae7224718d9530eee6a901c1cc9ef84f145ceaa5f5867a67542d131a1";

    /* ---------- ① 纯函数：类型闸门读数的形状（静态结论，不需要真机） ---------- */

    /** API 34 起要类型、清单没声明、本机没依据：三件事必须同时出现在一行里。 */
    @Test
    public void gateReadingStatesRequirementAndUndeclaredTypesAndNoLocalBasis() {
        assertEquals("api=34 requires_from=34 type_required=true declared=none"
                        + " basis=no_defensible_local_type",
                HyperOsHealthSnapshot.fgsTypeGateReading(34, HyperOsHealthSnapshot.FGS_UNDECLARED_TYPES));
        assertEquals("api=35 requires_from=34 type_required=true declared=none"
                        + " basis=no_defensible_local_type",
                HyperOsHealthSnapshot.fgsTypeGateReading(35, "none"));
    }

    /** 低于 34 不许谎报「必须有类型」：这一条按 API 级别算，不写死。 */
    @Test
    public void gateReadingMarksTypeRequirementOffBelowApi34() {
        assertEquals("api=33 requires_from=34 type_required=false declared=none"
                        + " basis=no_defensible_local_type",
                HyperOsHealthSnapshot.fgsTypeGateReading(33, "none"));
        assertEquals("api=29 requires_from=34 type_required=false declared=none"
                        + " basis=no_defensible_local_type",
                HyperOsHealthSnapshot.fgsTypeGateReading(29, "none"));
    }

    /** 声明值缺失／空白一律落 {@code none}，不许落空串被读成「声明了但读不出来」。 */
    @Test
    public void gateReadingTreatsBlankDeclaredTypesAsUndeclared() {
        assertTrue(HyperOsHealthSnapshot.fgsTypeGateReading(35, "").contains(" declared=none"));
        assertTrue(HyperOsHealthSnapshot.fgsTypeGateReading(35, null).contains(" declared=none"));
    }

    /** 静态行只说静态结论，一次也不许替真机宣布抬升结果。 */
    @Test
    public void gateReadingNeverClaimsTheRuntimeOutcome() {
        String line = HyperOsHealthSnapshot.fgsTypeGateReading(35, "none");
        for (String forbidden : new String[]{"call_threw", "promoted", "success", "ok", "killed"})
            assertFalse("静态行不许夹带运行时口吻: " + forbidden + " -> " + line,
                    line.contains(forbidden));
    }

    /* ---------- ② 纯函数：被拒降级读数的形状与原因类别 ---------- */

    /** 缺类型这一类：一眼要能看出「这一跳抛异常了」和「按异常名归到哪一类」。 */
    @Test
    public void declineReadingCarriesCauseAndReasonCategory() {
        assertEquals("at=123456 result=call_threw cause=MissingForegroundServiceTypeException"
                        + " kind=missing_foreground_service_type sdk=35 declared=none",
                HyperOsHealthSnapshot.fgsDegradationReading(AT, "MissingForegroundServiceTypeException",
                        35, HyperOsHealthSnapshot.FGS_UNDECLARED_TYPES));
    }

    /** 原因类别只按类名字符串归类，认不出的一律落 other_runtime，不猜。 */
    @Test
    public void declineCauseCategoriesAreNameBasedOnly() {
        assertEquals("missing_foreground_service_type", HyperOsHealthSnapshot.fgsCauseCategory(
                "MissingForegroundServiceTypeException"));
        assertEquals("start_not_allowed", HyperOsHealthSnapshot.fgsCauseCategory(
                "ForegroundServiceStartNotAllowedException"));
        assertEquals("security", HyperOsHealthSnapshot.fgsCauseCategory("SecurityException"));
        assertEquals("other_runtime", HyperOsHealthSnapshot.fgsCauseCategory("IllegalStateException"));
        assertEquals("other_runtime", HyperOsHealthSnapshot.fgsCauseCategory("WhateverVendorError"));
        assertEquals("unreadable_cause", HyperOsHealthSnapshot.fgsCauseCategory(null));
        assertEquals("unreadable_cause", HyperOsHealthSnapshot.fgsCauseCategory(""));
    }

    /** 归不出类别也不许把降级读数写成空：被拒这件事本身必须留在读数里。 */
    @Test
    public void unclassifiedDeclineStillProducesADegradationReading() {
        String line = HyperOsHealthSnapshot.fgsDegradationReading(AT, "MysteryVendorCrash", 36, "none");
        assertNotEquals(HyperOsHealthSnapshot.NO_FGS_DEGRADATION_READING, line);
        assertTrue("被拒读数必须带 result=call_threw: " + line, line.contains("result=call_threw"));
        assertTrue("认不出的原因必须显式落 other_runtime: " + line, line.contains("kind=other_runtime"));
        assertTrue("读数必须留住 API 级别: " + line, line.contains(" sdk=36"));
    }

    /** 异常名读不出来时 cause 落 unknown、kind 落 unreadable_cause，仍不许退化成空读数。 */
    @Test
    public void unreadableCauseStillWritesADegradationReading() {
        String line = HyperOsHealthSnapshot.fgsDegradationReading(AT, null, 35, "none");
        assertTrue(line.contains("cause=unknown"));
        assertTrue(line.contains("kind=unreadable_cause"));
        assertFalse(line.isEmpty());
    }

    /** 空读数只表示「未观测」，绝不允许被当成抬升成功。 */
    @Test
    public void emptyDegradationReadingMeansNotObservedAndNeverSuccess() {
        assertEquals(HyperOsHealthSnapshot.NO_FGS_DEGRADATION_READING, "");
        assertNotEquals("空读数绝不能等于任何一条被拒读数",
                HyperOsHealthSnapshot.NO_FGS_DEGRADATION_READING,
                HyperOsHealthSnapshot.fgsDegradationReading(AT, "SecurityException", 35, "none"));
        for (String successWord : new String[]{"success", "promoted", "ok", "granted"})
            assertFalse("降级读数里不许出现成功口吻: " + successWord,
                    HyperOsHealthSnapshot.fgsDegradationReading(AT, "SecurityException", 35, "none")
                            .contains(successWord));
    }

    /** 两个新键的名字必须是案卷里写的那两个，既有两键不许被改名。 */
    @Test
    public void newDeviceKeysAreTheCaseFileNamesAndOldKeysStayPut() {
        assertEquals("fgs_type_gate", HyperOsHealthSnapshot.FGS_GATE_KEY);
        assertEquals("fgs_degradation", HyperOsHealthSnapshot.FGS_DEGRADATION_KEY);
        assertEquals(34, HyperOsHealthSnapshot.FGS_TYPE_REQUIRED_FROM_API);
        assertEquals("none", HyperOsHealthSnapshot.FGS_UNDECLARED_TYPES);
        assertEquals("no_defensible_local_type", HyperOsHealthSnapshot.FGS_NO_LOCAL_BASIS);
        assertEquals("hyperos_system_reading", HyperOsHealthSnapshot.READING_KEY);
        assertEquals("hyperos_guidance", HyperOsHealthSnapshot.GUIDANCE_KEY);
    }

    /* ---------- ③ 接线：降级读数只在被拒路径上产生 ---------- */

    /**
     * 被拒路径上真的写读数：这一跳必须坐在 {@code catch (RuntimeException declined)} 块体内。
     * 摘掉这一跳，本条必须变红（反向验证 ①）。
     */
    @Test
    public void declinePathWritesTheDegradationReadingInsideTheCatchBlock() throws IOException {
        String body = methodBody(codeLinesOnly(sourceOf("SenseFieldReaderService.java")),
                "protected void onServiceConnected()");
        String catchBlock = catchBlock(body, "catch (RuntimeException declined)");
        assertTrue("被拒分支没写降级读数: " + catchBlock,
                catchBlock.contains("fgsDegradationReading = HyperOsHealthSnapshot.fgsDegradationReading("));
        assertTrue("降级读数没把异常名交给读数: " + catchBlock,
                catchBlock.contains("declined.getClass().getSimpleName()"));
        assertTrue("降级读数没带 API 级别: " + catchBlock,
                catchBlock.contains("android.os.Build.VERSION.SDK_INT"));
        assertTrue("降级读数没带清单声明事实: " + catchBlock,
                catchBlock.contains("MANIFEST_DECLARED_TYPES"));
        assertTrue("既有那行 Log.w 被摘掉了: " + catchBlock,
                catchBlock.contains("Log.w(TAG, \"前台抬升被系统拒绝: \""));
    }

    /** 成功路径不产生该读数：全覆写本体里只许有「进 try 前清一次」+「catch 里写一次」两处赋值。 */
    @Test
    public void successPathProducesNoDegradationReading() throws IOException {
        String body = methodBody(codeLinesOnly(sourceOf("SenseFieldReaderService.java")),
                "protected void onServiceConnected()");
        List<String> lines = Arrays.asList(body.split("\n", -1));
        List<Integer> writes = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("fgsDegradationReading = ")) writes.add(i);
        }
        assertEquals("抬升读数只许有两处赋值（进 try 前清一次 + 被拒分支写一次）: " + linesOf(lines, writes),
                2, writes.size());
        int tryAt = indexOfFirstLineContaining(lines, "try {");
        int catchAt = indexOfFirstLineContaining(lines, "} catch (RuntimeException declined) {");
        assertTrue("清空读数这一跳必须排在 try 之前，成功路径才会留空: " + linesOf(lines, writes),
                writes.get(0) < tryAt);
        assertTrue("写读数这一跳必须落在 catch 之后（被拒路径），不许落在成功路径里",
                writes.get(1) > catchAt);
        // 成功路径本体（try 到 catch 之间）一次也不许赋值。
        for (int i = tryAt; i < catchAt; i++)
            assertFalse("成功路径里出现了抬升读数赋值: " + lines.get(i),
                    lines.get(i).contains("fgsDegradationReading = "));
    }

    /** 捕获面不许缩小成某一种异常，也不许改成静默吞掉（这正是本卷要修的病）。 */
    @Test
    public void catchFaceIsNotNarrowedAndNeverSwallowsSilently() throws IOException {
        String body = methodBody(codeLinesOnly(sourceOf("SenseFieldReaderService.java")),
                "protected void onServiceConnected()");
        assertTrue("捕获面被缩小成具体异常类型: " + body,
                body.contains("} catch (RuntimeException declined) {"));
        assertEquals("本卷这一跳只许有一个 catch 面", 1, countOccurrences(body, "catch ("));
        String catchBlock = catchBlock(body, "catch (RuntimeException declined)");
        List<String> code = new ArrayList<>();
        for (String line : Arrays.asList(catchBlock.split("\n", -1))) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*")
                    || trimmed.startsWith("*") || trimmed.equals("{") || trimmed.startsWith("}")) continue;
            code.add(trimmed);
        }
        assertTrue("被拒分支被改成静默吞掉（只剩空块）: " + catchBlock, code.size() >= 2);
    }

    /**
     * {@code startForeground} 仍在 {@code onServiceConnected()} 覆写本体里，且判空那一跳逐字在位：
     * 把 {@code notificationManager != null} 改成恒假，本条必须变红（反向验证 ②）。
     */
    @Test
    public void startForegroundStillSitsBehindTheVerbatimNullGuardInTheOverride() throws IOException {
        String body = methodBody(codeLinesOnly(sourceOf("SenseFieldReaderService.java")),
                "protected void onServiceConnected()");
        assertTrue("判空那一跳被改写（本卷不许动它）: " + body,
                body.contains("if (notificationManager != null) {"));
        List<String> lines = Arrays.asList(body.split("\n", -1));
        int guard = indexOfFirstLineContaining(lines, "if (notificationManager != null) {");
        int call = indexOfFirstLineContaining(lines, "startForeground(READER_FOREGROUND_NOTIFICATION_ID,");
        assertTrue("抬前台必须坐在那道判空之后（判空被改成恒假就没有这一跳）", guard < call);
        assertTrue("抬前台不在 onServiceConnected 覆写本体里: " + body, call >= 0);
        assertTrue("必须复用既有通知渠道: " + body, body.contains("CaptureService.CHANNEL"));
        assertFalse("不许新增通知渠道: " + body, body.contains("createNotificationChannel"));
        for (String kept : new String[]{"setServiceInfo(info);", "connected = true;",
                "instance = new java.lang.ref.WeakReference<>(this);",
                "info.setMotionEventSources(android.view.InputDevice.SOURCE_TOUCHSCREEN);",
                "Log.i(TAG, \"读屏状态服务已抬为前台服务\");"}) {
            assertTrue("既有 onServiceConnected 逻辑被改动: " + kept, body.contains(kept));
        }
    }

    /** 抬升这一跳不许引入线程／调度器／新渠道（任务 1 的四条「不新增」）。 */
    @Test
    public void theNewHopsStartNoThreadOrSchedulerOrChannel() throws IOException {
        String body = methodBody(codeLinesOnly(sourceOf("SenseFieldReaderService.java")),
                "protected void onServiceConnected()");
        for (String forbidden : new String[]{"new Thread", "Executors.", "ScheduledExecutorService",
                "scheduleAtFixedRate", "scheduleWithFixedDelay", "Timer(", "new HandlerThread",
                "createNotificationChannel", "postDelayed"}) {
            assertFalse("抬升这一跳不许新增 " + forbidden, body.contains(forbidden));
        }
    }

    /* ---------- ④ 接线：两条读数挂既有 device 事件，既有九字段一字未改 ---------- */

    /** 既有 device 字段的原样九行 + 既有两键：被人改动或重排就红。 */
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
    public void newReadingsAreAppendedAfterTheShippedDeviceFieldsAndKeys() throws IOException {
        List<String> src = sourceOf("DiagnosticRecorder.java");
        int start = indexOfFirstLineContaining(src, "JSONObject sample = object(");
        List<String> block = src.subList(start, Math.min(src.size(), start + SHIPPED_DEVICE_FIELDS.size()));
        assertEquals("既有 device 九字段被人改动或重排", SHIPPED_DEVICE_FIELDS, block);
        assertTrue("第九行位置被人挪走: " + src.get(start + 9),
                src.get(start + 9).trim().startsWith("\"thermal_status_name\", thermalStatusName(thermalStatus)"));
        assertTrue(src.get(start + 10).contains("HyperOsHealthSnapshot.READING_KEY"));
        assertTrue(src.get(start + 11).contains("HyperOsHealthSnapshot.GUIDANCE_KEY"));
        // 本卷两键只能追加在既有两键之后，不许插进中间。
        int gate = indexOfFirstLineContaining(src, "HyperOsHealthSnapshot.FGS_GATE_KEY");
        int decline = indexOfFirstLineContaining(src, "HyperOsHealthSnapshot.FGS_DEGRADATION_KEY");
        assertTrue("新键插到了既有字段中间（gate=" + gate + "）", gate > start + 11);
        assertTrue("降级键必须排在闸门键之后", decline > gate);
    }

    /** 采集这两跳真的挂在既有 device 采样链上，且仍由既有 {@code record("device", …)} 落盘。 */
    @Test
    public void deviceSamplingChainItselfCarriesBothPromotionReadings() throws IOException {
        String body = methodBody(codeLinesOnly(sourceOf("DiagnosticRecorder.java")),
                "private synchronized void sampleDevice(long now)");
        assertTrue("闸门读数没接进采样链: " + body,
                body.contains("HyperOsHealthSnapshot.FGS_GATE_KEY"));
        assertTrue("降级读数没接进采样链: " + body,
                body.contains("HyperOsHealthSnapshot.FGS_DEGRADATION_KEY"));
        assertTrue("采样链没取本服务的降级读数: " + body,
                body.contains("SenseFieldReaderService.fgsDegradationReading()"));
        assertTrue("闸门读数没拿 API 级别: " + body,
                body.contains("Build.VERSION.SDK_INT"));
        assertTrue("闸门读数没拿清单声明: " + body,
                body.contains("SenseFieldReaderService.MANIFEST_DECLARED_TYPES"));
        assertTrue("既有 device 事件落点被换掉: " + body, body.contains("record(\"device\", sample)"));
        assertTrue("既有 hyperos 读数落点被换掉: " + body,
                body.contains("HyperOsHealthSnapshot.READING_KEY, hyperOs.line()"));
        assertFalse("采样链不许为这两条读数新建线程: " + body, body.contains("new Thread"));
    }

    /** 检查点把整块 last_device 带出，本卷两键自动进检查点读数；这条落点不许被人摘掉。 */
    @Test
    public void checkpointStillPublishesTheWholeDeviceSample() throws IOException {
        String body = methodBody(codeLinesOnly(sourceOf("DiagnosticRecorder.java")),
                "private void checkpoint(long nowMs)");
        assertTrue("检查点不再带出 last_device: " + body,
                body.contains("data.put(\"last_device\", lastDeviceSample == null ? JSONObject.NULL : lastDeviceSample)"));
    }

    /* ---------- ⑤ 路线 B：清单逐字节未动 ---------- */

    /** 清单原文摘要与开工前逐字相同：改一个字节就红。 */
    @Test
    public void manifestBytesAreUnchangedFromTheCaseFileBaseline() throws IOException {
        assertEquals("AndroidManifest.xml 被改过（路线 B 要求清单逐字节不动）",
                MANIFEST_SHA256, sha256Hex(lfNormalizedManifestBytes()));
    }

    /** 本服务一个类型都没声明，且全清单只许有既有那两处类型声明、三处 service 条目、零 property 条目。 */
    @Test
    public void readerServiceDeclaresNoTypeAndTheManifestGainedNoEntry() throws IOException {
        List<String> lines = Files.readAllLines(manifestFile().toPath(), StandardCharsets.UTF_8);
        String text = String.join("\n", lines);
        assertEquals("全清单的 foregroundServiceType 只许是既有投影那两处", 2,
                countOccurrences(text, "android:foregroundServiceType"));
        assertEquals("清单条目不许新增（service 元素）", 3, countOccurrences(text, "<service"));
        assertEquals("清单条目不许新增（activity 元素）", 18, countOccurrences(text, "<activity"));
        assertEquals("清单条目不许新增（provider 元素）", 2, countOccurrences(text, "<provider"));
        assertEquals("清单条目不许新增（uses-permission）", 9, countOccurrences(text, "<uses-permission"));
        assertEquals("SPECIAL_USE 要补的 property 条目本卷一条也不许加", 0, countOccurrences(text, "<property"));
    }

    /** {@code .SenseFieldReaderService} 那个 service 元素内部不许出现任何类型声明。 */
    @Test
    public void theAccessibilityServiceElementItselfCarriesNoForegroundType() throws IOException {
        List<String> lines = Files.readAllLines(manifestFile().toPath(), StandardCharsets.UTF_8);
        int named = indexOfFirstLineContaining(lines, "android:name=\".SenseFieldReaderService\"");
        int start = named;
        while (start >= 0 && !lines.get(start).trim().equals("<service")) start--;
        assertTrue("清单里找不到读屏服务的 service 元素，这条断言没接上电", start >= 0);
        int end = start;
        while (end < lines.size() && !lines.get(end).trim().equals("</service>")) end++;
        assertTrue("service 元素没收口，这条断言没接上电", end < lines.size());
        assertTrue("元素跨度不合理，怕是找错了块（" + (end - start) + " 行）", end - start <= 20);
        String element = String.join("\n", lines.subList(start, end + 1));
        assertFalse("读屏服务已经声明了类型（与本卷结论不符）: " + element,
                element.contains("foregroundServiceType"));
        assertTrue("元素里没有无障碍授权动作，怕是找错了块: " + element,
                element.contains("android.accessibilityservice"));
    }

    /** 本服务侧的「清单声明了什么」必须与清单原文同口径：都是 none。 */
    @Test
    public void theDeclaredTypesConstantAgreesWithTheManifest() throws IOException {
        assertEquals(HyperOsHealthSnapshot.FGS_UNDECLARED_TYPES,
                SenseFieldReaderService.MANIFEST_DECLARED_TYPES);
    }

    /* ---------- 仪器 ---------- */

    private static File manifestFile() {
        String fromModule = "src/main/AndroidManifest.xml";
        String fromRoot = "android/app/src/main/AndroidManifest.xml";
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; dir != null && up <= 5; up++, dir = dir.getParentFile()) {
            for (String candidate : new String[]{fromModule, fromRoot}) {
                File file = new File(dir, candidate);
                if (file.isFile()) return file;
            }
        }
        throw new IllegalStateException("找不到 AndroidManifest.xml（模块内与树根起两种相对写法、"
                + "向上五级均未命中），这条断言没接上电");
    }

    /** 摘要按「CRLF→LF 归一后的原始字节」算：本机工作树是纯 LF，归一后与开工前读数逐字相同。 */
    private static byte[] lfNormalizedManifestBytes() throws IOException {
        return new String(Files.readAllBytes(manifestFile().toPath()), StandardCharsets.UTF_8)
                .replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM 没有 SHA-256，这条断言没接上电", impossible);
        }
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

    private static List<String> linesOf(List<String> lines, List<Integer> indexes) {
        List<String> out = new ArrayList<>();
        for (int i : indexes) out.add(i + ": " + lines.get(i).trim());
        return out;
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

    /** 从 catch 那一行起做花括号配平，取出 catch 块本体（含头行），别拿全文本凑数。 */
    private static String catchBlock(String body, String catchNeedle) {
        List<String> lines = Arrays.asList(body.split("\n", -1));
        int start = indexOfFirstLineContaining(lines, catchNeedle);
        StringBuilder out = new StringBuilder();
        int depth = 0;
        boolean started = false;
        for (int i = start; i < lines.size(); i++) {
            String line = lines.get(i);
            out.append(line).append('\n');
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '{') { depth++; started = true; }
                else if (c == '}' && started) depth--;
            }
            if (started && depth <= 0) break;
        }
        assertTrue("catch 块没配平：" + catchNeedle, started && depth <= 0);
        assertTrue("catch 块只取到头部一行，块体没取出来: " + out, out.toString().split("\n", -1).length > 2);
        return out.toString();
    }

    private static int countOccurrences(String text, String needle) {
        int hits = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) hits++;
        return hits;
    }
}
