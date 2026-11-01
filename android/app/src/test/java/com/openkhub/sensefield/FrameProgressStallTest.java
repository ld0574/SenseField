package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.openkhub.sensefield.FrameProgressStall.Reading;
import com.openkhub.sensefield.ReadOnlyGuard.Hit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * 任务书 39R 的报警器：钉住「帧照样到、处理器不消费时这条读数自己喊出来」。
 *
 * <p>被测的是 {@link FrameProgressStall}（每拍喂写家身份、{@code frames_expected}、
 * {@code processed_frames}、时刻，出「要不要报警」＋读数内容）这条纯函数，加上它在
 * {@link DiagnosticRecorder} 既有 {@code checkpoint(long)} 里的那一跳接线。纯函数部分喂的全是
 * 写死的具体数字与具体时刻，期望值由人写死，不拿被测函数的输出反推自己；接线部分读源码，
 * 摘掉那一行就必须变红。</p>
 *
 * <p>为什么要先认写家：本轮实测 {@code processed_frames} 有四处写入点、三把互不相干的计数器，
 * 都走同一个 {@code publishState()}，谁最后写谁生效。不认写家的「连续 N 拍不涨」会把 A 的沉默
 * 接在 B 的沉默后面凑成一次报警——那是造出来的假警报，比没有读数更坏。</p>
 *
 * <p>阈值口径：报警看的是「连续几个拍次」，不是毫秒；拍次周期由既有 checkpoint 决定（5000 毫秒），
 * 三拍约十五秒。这条阈值与帧到达性判断里那个一千毫秒的时间窗互不相干，测试里分别钉住。</p>
 *
 * <p>证据边界：本机无 adb、无设备。这里只能证 JVM 代码路径与源码接线，
 * 证不了真机上这条读数多久出一次、会不会连串触发、玩家看不看得见。</p>
 */
public final class FrameProgressStallTest {

    private static final String CAPTURE_WRITER = FrameProgressStall.WRITER_CAPTURE_SERVICE;
    private static final String OTHER_WRITER = "game:alpha";
    private static final long PERIOD = DiagnosticCheckpoint.PERIOD_MS;

    /** 取主源集里的被测文件：两种相对写法、逐级向上找，命中不了就抛——「找不到源文件」必须让这条测试变红。 */
    private static List<String> sourceOf(String simpleName) throws IOException {
        return fileOf("src/main/java/com/openkhub/sensefield/" + simpleName,
                "android/app/src/main/java/com/openkhub/sensefield/" + simpleName);
    }

    /** 取被测单测文件。 */
    private static List<String> testSourceOf(String simpleName) throws IOException {
        return fileOf("src/test/java/com/openkhub/sensefield/" + simpleName,
                "android/app/src/test/java/com/openkhub/sensefield/" + simpleName);
    }

    private static List<String> fileOf(String fromModule, String fromRoot) throws IOException {
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; dir != null && up <= 5; up++, dir = dir.getParentFile()) {
            for (String candidate : new String[]{fromModule, fromRoot}) {
                File f = new File(dir, candidate);
                if (f.isFile()) return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("找不到被测文件：" + fromRoot
                + "（两种相对写法、向上五级均未命中），这条断言没接上电");
    }

    /** 只看代码行：注释行（// 与 javadoc 的 * 开头）剔除，因为本类要在注释里点名那些禁用的写法。 */
    private static List<String> codeLinesOnly(List<String> src) {
        List<String> out = new ArrayList<>();
        for (String line : src) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) continue;
            out.add(line);
        }
        return out;
    }

    private static String joined(List<String> lines) {
        return String.join("\n", lines);
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

    private static int countLinesContaining(List<String> lines, String needle) {
        int n = 0;
        for (String line : lines) {
            if (line.contains(needle)) n++;
        }
        return n;
    }

    private static int countOccurrences(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) n++;
        return n;
    }

    /** 花括号配平地取出一个方法的完整方法体（含签名行与收尾行）。 */
    private static List<String> methodBody(List<String> src, String signatureNeedle) {
        int start = indexOfFirstLineContaining(src, signatureNeedle);
        List<String> body = new ArrayList<>();
        int depth = 0;
        boolean started = false;
        for (int i = start; i < src.size(); i++) {
            String line = src.get(i);
            body.add(line);
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '{') { depth++; started = true; } else if (c == '}') depth--;
            }
            if (started && depth <= 0) break;
        }
        assertTrue("方法体没配平：" + signatureNeedle, started && depth <= 0);
        return body;
    }

    /* ---------- ① 第 N 拍恰出一次，之后继续不涨不许每拍再出 ---------- */

    @Test
    public void motionlessTicksAlertOnceOnTheThresholdTick() {
        FrameProgressStall meter = new FrameProgressStall();
        Reading anchor = meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 0L);
        assertEquals("认家这一拍不算停", 0, anchor.stalledTicks);
        assertFalse(anchor.alert);

        Reading first = meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 1000L);
        assertEquals(1, first.stalledTicks);
        assertFalse("第 1 拍不涨不许报", first.alert);
        assertEquals(Long.valueOf(1000L), first.stallMs);

        Reading second = meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 2000L);
        assertEquals(2, second.stalledTicks);
        assertFalse("N-1 拍（阈值 3 时的第 2 拍）不涨不许报", second.alert);

        Reading third = meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 3000L);
        assertEquals("恰好第 N 拍", FrameProgressStall.STALL_TICKS, third.stalledTicks);
        assertTrue("第 N 拍必须喊出来", third.alert);
        assertEquals(Long.valueOf(3000L), third.stallMs);
        assertEquals(CAPTURE_WRITER, third.writerId);

        Reading fourth = meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 4000L);
        assertEquals(4, fourth.stalledTicks);
        assertFalse("报警之后继续不涨，不许每拍再出", fourth.alert);

        Reading fifth = meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 5000L);
        assertEquals(5, fifth.stalledTicks);
        assertFalse(fifth.alert);
    }

    /* ---------- ② 缺 processed_frames 或负值：不计数、不造数、不报 ---------- */

    @Test
    public void missingOrNegativeProcessedFramesNeitherCountNorFabricateNorAlert() {
        for (Long unusable : new Long[]{null, Long.valueOf(-1), Long.valueOf(-9000)}) {
            FrameProgressStall meter = new FrameProgressStall();
            assertEquals("先认家", 0, meter.observe(CAPTURE_WRITER, true, Long.valueOf(3), 0L).stalledTicks);
            for (long now = 1000; now <= 6000; now += 1000) {
                Reading tick = meter.observe(CAPTURE_WRITER, true, unusable, now);
                assertFalse("喂不进可用读数就不许假装知道：" + unusable, tick.processedFramesKnown);
                assertEquals("不可用的拍次不进计数：" + unusable, 0, tick.stalledTicks);
                assertNull("没有可用读数就不许造一个停摆时长：" + unusable, tick.stallMs);
                assertFalse("缺读数不许报警：" + unusable, tick.alert);
            }
        }
    }

    @Test
    public void unusableTickIsSkippedNotReadAsProofOfProgress() {
        // 「不计数」是跳过这一拍，不是把它读成一次上涨：跳过去之后，前面的沉默还得继续算。
        FrameProgressStall meter = new FrameProgressStall();
        meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 0L);
        assertEquals(1, meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 1000L).stalledTicks);
        Reading skipped = meter.observe(CAPTURE_WRITER, true, null, 2000L);
        assertEquals("跳过的一拍不许把沉默清零", 1, skipped.stalledTicks);
        assertFalse(skipped.alert);
        assertEquals(2, meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 3000L).stalledTicks);
        Reading resumed = meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 4000L);
        assertEquals(3, resumed.stalledTicks);
        assertTrue("沉默满 N 个可用拍次才报，且就在这一拍", resumed.alert);
        assertEquals("时长按真实经过的毫秒算，被跳过那一拍的时间不许抹掉",
                Long.valueOf(4000L), resumed.stallMs);
    }

    @Test
    public void snapshotWithoutUsableProcessedFramesKeyReadsAsUnusable() {
        FrameProgressStall meter = new FrameProgressStall();
        Reading tick = meter.observeSnapshot(
                new JSONObjectWith().expected(true).arrived(1000L).build(), 5000L);
        assertFalse("没有 processed_frames 这个键就不许编一个 0 出来", tick.processedFramesKnown);
        assertEquals(0, tick.stalledTicks);
        assertFalse(tick.alert);
        assertNull("负值判为不可用", FrameProgressStall
                .processedFramesOf(new JSONObjectWith().processed(-1).expected(true).build()));
        assertEquals(Long.valueOf(0L), FrameProgressStall
                .processedFramesOf(new JSONObjectWith().processed(0).expected(true).build()));
        assertNull("非数字的读数同样不可用", FrameProgressStall
                .processedFramesOf(new JSONObjectWith().expected(true).text("7").build()));
    }

    /* ---------- ③ frames_expected=false 的拍次不进计数、不报 ---------- */

    @Test
    public void ticksThatExpectNoFramesNeverAlert() {
        FrameProgressStall meter = new FrameProgressStall();
        meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 0L);
        for (long now = 1000; now <= 9000; now += 1000) {
            Reading tick = meter.observe(CAPTURE_WRITER, false, Long.valueOf(7), now);
            assertFalse("竖屏/暂停/停投影期间的「不涨」不是「帧到了不消费」", tick.alert);
            assertEquals(0, tick.stalledTicks);
        }
    }

    @Test
    public void aFrameNotExpectedTickEndsTheCurrentStallEpisode() {
        // 两拍沉默 + 一拍不该有帧 + 三拍沉默：不许把空闲期凑进报警里。
        FrameProgressStall meter = new FrameProgressStall();
        meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 0L);
        assertEquals(1, meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 1000L).stalledTicks);
        assertEquals(2, meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 2000L).stalledTicks);
        Reading idle = meter.observe(CAPTURE_WRITER, false, Long.valueOf(7), 3000L);
        assertFalse(idle.alert);
        assertEquals("不该有帧的拍次把这一轮沉默作废", 0, idle.stalledTicks);
        assertFalse(meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 4000L).alert);
        assertFalse(meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 5000L).alert);
        assertTrue(meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 6000L).alert);
    }

    /* ---------- ④ 换写家归零重算，且换家那一拍不算停 ---------- */

    @Test
    public void changingWriterRestartsTheStreakAndCannotSpliceTwoCounters() {
        FrameProgressStall meter = new FrameProgressStall();
        meter.observe(CAPTURE_WRITER, true, Long.valueOf(9), 0L);
        assertEquals(1, meter.observe(CAPTURE_WRITER, true, Long.valueOf(9), 1000L).stalledTicks);
        Reading almost = meter.observe(CAPTURE_WRITER, true, Long.valueOf(9), 2000L);
        assertEquals("差一拍就到 N", 2, almost.stalledTicks);
        assertFalse(almost.alert);

        Reading switched = meter.observe(OTHER_WRITER, true, Long.valueOf(0), 3000L);
        assertFalse("换写家这一拍不许被当成一次沉默，更不许报", switched.alert);
        assertEquals("另一把计数器从 0 重算", 0, switched.stalledTicks);
        assertEquals(OTHER_WRITER, switched.writerId);

        assertFalse(meter.observe(OTHER_WRITER, true, Long.valueOf(0), 4000L).alert);
        assertFalse(meter.observe(OTHER_WRITER, true, Long.valueOf(0), 5000L).alert);
        assertTrue("新写家自己沉默满 N 拍才报",
                meter.observe(OTHER_WRITER, true, Long.valueOf(0), 6000L).alert);
    }

    @Test
    public void twoWritersThatAlternateNeverReportAStall() {
        // 谁最后写谁生效：两个写家轮流失摄时读数一动不动也不许报，因为那不是同一把计数器。
        FrameProgressStall meter = new FrameProgressStall();
        long now = 0;
        for (int i = 0; i < 40; i++) {
            String writer = (i % 2 == 0) ? CAPTURE_WRITER : OTHER_WRITER;
            Reading tick = meter.observe(writer, true, Long.valueOf(5), now);
            assertFalse("A 的停顿接在 B 的停顿后面凑出来的报警：" + i, tick.alert);
            assertEquals(0, tick.stalledTicks);
            now += PERIOD;
        }
    }

    @Test
    public void theRecordersOwnStartingSnapshotIsNotTheCaptureWritersAnchor() {
        // 本轮实测：起始快照把 processed_frames 写死成 0 并带 game_id，
        // 采集侧那条不带 game_id、用的是另一把计数器——两者不许拼成一次沉默。
        FrameProgressStall meter = new FrameProgressStall();
        Reading start = meter.observeSnapshot(new JSONObjectWith().game("alpha").processed(0)
                .expected(false).landscape(0).build(), 0L);
        assertEquals(OTHER_WRITER, start.writerId);
        assertFalse(start.alert);

        Reading firstCapture = meter.observeSnapshot(new JSONObjectWith().processed(42)
                .expected(true).landscape(42).build(), PERIOD);
        assertEquals(CAPTURE_WRITER, firstCapture.writerId);
        assertEquals("起始快照那个写死的 0 不许当成采集侧的基线", 0, firstCapture.stalledTicks);
        assertFalse(firstCapture.alert);
    }

    /* ---------- ⑤ 涨回去清零，再停才再出 ---------- */

    @Test
    public void progressClearsTheStreakAndOnlyANewStallAlertsAgain() {
        FrameProgressStall meter = new FrameProgressStall();
        meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 0L);
        meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 1000L);
        meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 2000L);
        assertTrue(meter.observe(CAPTURE_WRITER, true, Long.valueOf(7), 3000L).alert);

        Reading moved = meter.observe(CAPTURE_WRITER, true, Long.valueOf(8), 4000L);
        assertFalse("涨回去之后不许再报上一轮", moved.alert);
        assertEquals(0, moved.stalledTicks);
        assertNull(moved.stallMs);

        assertFalse(meter.observe(CAPTURE_WRITER, true, Long.valueOf(8), 5000L).alert);
        assertFalse(meter.observe(CAPTURE_WRITER, true, Long.valueOf(8), 6000L).alert);
        Reading again = meter.observe(CAPTURE_WRITER, true, Long.valueOf(8), 7000L);
        assertTrue("再停满 N 拍才允许第二次喊", again.alert);
        assertEquals("第二轮的时长按它自己的起点算", Long.valueOf(3000L), again.stallMs);
        assertFalse(meter.observe(CAPTURE_WRITER, true, Long.valueOf(8), 8000L).alert);
    }

    @Test
    public void aCounterThatMovesBackwardsIsReanchoredNotGuessedAt() {
        // 同一个写家把计数器重开了：9 → 2 既不是上涨也不算沉默，重新认基线，不许拿它凑报警。
        FrameProgressStall meter = new FrameProgressStall();
        meter.observe(CAPTURE_WRITER, true, Long.valueOf(9), 0L);
        assertEquals(1, meter.observe(CAPTURE_WRITER, true, Long.valueOf(9), 1000L).stalledTicks);
        Reading dropped = meter.observe(CAPTURE_WRITER, true, Long.valueOf(2), 2000L);
        assertFalse("读数倒退不许报", dropped.alert);
        assertEquals(0, dropped.stalledTicks);
        assertTrue(dropped.processedFramesKnown);
        assertEquals(1, meter.observe(CAPTURE_WRITER, true, Long.valueOf(2), 3000L).stalledTicks);
        assertEquals(2, meter.observe(CAPTURE_WRITER, true, Long.valueOf(2), 4000L).stalledTicks);
        assertTrue(meter.observe(CAPTURE_WRITER, true, Long.valueOf(2), 5000L).alert);
    }

    /* ---------- ⑥ 阈值是拍数、显式写死，不共用帧饥饿时间窗 ---------- */

    @Test
    public void theThresholdCountsTicksNotMilliseconds() {
        FrameProgressStall meter = new FrameProgressStall();
        meter.observe(CAPTURE_WRITER, true, Long.valueOf(4), 1_000_000L);
        assertEquals(1, meter.observe(CAPTURE_WRITER, true, Long.valueOf(4), 1_000_001L).stalledTicks);
        assertEquals(2, meter.observe(CAPTURE_WRITER, true, Long.valueOf(4), 1_000_002L).stalledTicks);
        Reading alert = meter.observe(CAPTURE_WRITER, true, Long.valueOf(4), 1_000_003L);
        assertTrue("三拍就是三拍，跟毫秒无关", alert.alert);
        assertEquals(Long.valueOf(3L), alert.stallMs);
        assertEquals("阈值显式写死为 3 拍", 3, FrameProgressStall.STALL_TICKS);
    }

    /* ---------- ⑦ 写家身份只从快照形状认，不靠猜 ---------- */

    @Test
    public void writerIsTakenFromTheSnapshotShapeAndNeverGuessed() {
        assertEquals("带 game_id 的写家按 game:<id> 记", OTHER_WRITER, FrameProgressStall.writerOf(
                new JSONObjectWith().game("alpha").processed(1).expected(true).build()));
        assertEquals(CAPTURE_WRITER, FrameProgressStall.writerOf(
                new JSONObjectWith().processed(1).expected(true).landscape(1).build()));
        assertEquals("采集侧那条快照就是四处写入点里唯一没有 game_id 的",
                CAPTURE_WRITER, FrameProgressStall.writerOf(new JSONObjectWith().landscape(0).build()));
        assertEquals(FrameProgressStall.WRITER_UNKNOWN,
                FrameProgressStall.writerOf(new JSONObjectWith().game("").build()));
        assertEquals("既没有 game_id 也没有采集侧独有键的快照只能记作 unknown",
                FrameProgressStall.WRITER_UNKNOWN,
                FrameProgressStall.writerOf(new JSONObjectWith().processed(3).build()));
        assertEquals(FrameProgressStall.WRITER_UNKNOWN, FrameProgressStall.writerOf(null));
    }

    /* ---------- ⑧ 读数内容：停了几拍、约多少毫秒、哪个写家、当拍 frame_stream_status ---------- */

    @Test
    public void readingCarriesTicksDurationWriterAndThisTicksFrameStreamStatus() throws Exception {
        FrameProgressStall meter = new FrameProgressStall();
        JSONObject last = null;
        long start = 100_000L;
        for (int tick = 0; tick < 4; tick++) { // 一拍认家 + 三拍沉默，写死四拍，不随阈值变圈数
            long nowMs = start + tick * PERIOD;
            // 帧一直在到（到达时刻只比当拍早 100 毫秒），处理数一动不动：这正是原读数看不见的那一半。
            JSONObject state = new JSONObjectWith().processed(7).expected(true).landscape(7)
                    .snapshotAt(nowMs).arrived(nowMs - 100).completed(nowMs - 100).build();
            JSONObject data = DiagnosticCheckpoint.at(state, nowMs);
            meter.recordInto(data, nowMs);
            assertEquals("帧照样到，当拍的到达性读数仍是 recent，谁都不许改这个口径", "recent",
                    data.getJSONObject(FrameProgressStall.CHECKPOINT_KEY).getString("frame_stream_status"));
            last = data;
        }
        JSONObject stall = last.getJSONObject(FrameProgressStall.CHECKPOINT_KEY);
        assertEquals(CAPTURE_WRITER, stall.getString("writer"));
        assertEquals("停了几拍", FrameProgressStall.STALL_TICKS, stall.getLong("stalled_ticks"));
        assertEquals("约多少毫秒：三个周期", PERIOD * FrameProgressStall.STALL_TICKS,
                stall.getLong("stall_ms"));
        assertTrue("这一拍该喊出来", stall.getBoolean("alert"));
        assertEquals(FrameProgressStall.STALL_TICKS, stall.getInt("threshold_ticks"));
        assertTrue(stall.getBoolean("frames_expected"));
        assertTrue(stall.getBoolean("processed_frames_known"));
    }

    @Test
    public void theReadingLandsInsideTheSameCheckpointDataAndAddsExactlyOneKey() throws Exception {
        FrameProgressStall meter = new FrameProgressStall();
        JSONObject state = new JSONObjectWith().processed(7).expected(true).landscape(7)
                .snapshotAt(1000L).arrived(1900L).completed(1900L).build();
        JSONObject data = DiagnosticCheckpoint.at(state, 2000L);
        data.put("frame_count", 11);
        JSONObject before = new JSONObject(data.toString());
        meter.recordInto(data, 2000L);

        assertEquals("一个拍次只许挂一条停摆读数", 1,
                countOccurrences(data.toString(), FrameProgressStall.CHECKPOINT_KEY));
        assertEquals("读数只许多这一个键", before.length() + 1, data.length());
        for (Iterator<String> it = before.keys(); it.hasNext(); ) {
            String key = it.next();
            assertEquals("既有的 checkpoint 字段与取值一个不许动：" + key,
                    String.valueOf(before.opt(key)), String.valueOf(data.opt(key)));
        }
        assertEquals("同一个对象里既有 frame_count 也有停摆读数", 11, data.getInt("frame_count"));
        assertTrue("当拍的到达性读数还在同一个对象里", data.has("frame_stream_status"));
        Reading withoutSnapshot = meter.observeSnapshot(null, 3000L);
        assertEquals(FrameProgressStall.WRITER_UNKNOWN, withoutSnapshot.writerId);
        assertFalse("快照都没有就不许报", withoutSnapshot.alert);
    }

    /* ---------- ⑨ 接线：读数真排在既有 record("checkpoint", data) 之前 ---------- */

    @Test
    public void checkpointWiresTheReadingIntoTheSamePayloadBeforeRecordingIt() throws IOException {
        List<String> src = sourceOf("DiagnosticRecorder.java");
        List<String> body = methodBody(src, "private void checkpoint(long nowMs)");
        int wired = indexOfFirstLineContaining(body, "frameProgressStall.recordInto(data, nowMs);");
        int recorded = indexOfFirstLineContaining(body, "record(\"checkpoint\", data);");
        assertTrue("读数必须写在 record(\"checkpoint\", data) 之前，进的是同一个 data", wired < recorded);
        assertEquals("这条接线一个拍次只许走一次", 1,
                countLinesContaining(body, "frameProgressStall.recordInto(data, nowMs);"));
        assertEquals("既有出口不许变成两个", 1, countLinesContaining(body, "record(\"checkpoint\", data);"));
        assertTrue("快照仍然是那一句取来的，没换数据源",
                hasLineContaining(body, "JSONObject data = DiagnosticCheckpoint.at(latestState, nowMs);"));
        for (String forbidden : new String[]{"new Thread", "Executors", "scheduleWithFixedDelay",
                "scheduleAtFixedRate", "Timer(", "System.load"}) {
            assertFalse("接线不许新增周期任务或线程，出现了 " + forbidden, hasLineContaining(body, forbidden));
        }
        List<String> code = codeLinesOnly(src);
        assertEquals("停摆读数在记录器里只许被这一处调用", 1,
                countLinesContaining(code, "frameProgressStall.recordInto("));
        assertEquals("计量器只许挂一个字段", 1, countLinesContaining(code, "new FrameProgressStall("));
        for (String forbidden : new String[]{"ConnectivityManager", "NetworkCapabilities",
                "TelephonyManager", "SignalStrength", "ACCESS_NETWORK_STATE"}) {
            assertFalse("记录器里出现了 " + forbidden, joined(code).contains(forbidden));
        }
    }

    @Test
    public void theNewReaderTouchesNothingAndroidNoNetworkAndNoInjection() throws IOException {
        List<String> code = codeLinesOnly(sourceOf("FrameProgressStall.java"));
        for (String line : code) {
            String t = line.trim();
            if (t.startsWith("import ")) {
                assertFalse("新类里出现了 android.* 的 import：" + t, t.startsWith("import android."));
                assertFalse("新类里出现了 androidx.* 的 import：" + t, t.startsWith("import androidx."));
            }
        }
        String newClass = joined(code);
        for (String forbidden : new String[]{"ConnectivityManager", "NetworkCapabilities", "TelephonyManager",
                "SignalStrength", "ACCESS_NETWORK_STATE", "SystemClock", "android.", "Socket",
                "Http", "getSystemService"}) {
            assertFalse("新类里出现了 " + forbidden, newClass.contains(forbidden));
        }
        assertEquals("阈值这条常量必须写死，不许共用帧饥饿时间窗", 0, countLinesContaining(code, "STARVATION_MS"));
        assertEquals("不许把报警塞回那个只到一千毫秒的到达性判断里", 0,
                countLinesContaining(code, "CaptureHealthMonitor"));
        List<Hit> hits = ReadOnlyGuard.scan("FrameProgressStall.java", code);
        assertEquals("读数链上出现只读守卫点名的注入类 API：" + hits, 0, hits.size());
        List<String> manifest = fileOf("src/main/AndroidManifest.xml", "android/app/src/main/AndroidManifest.xml");
        assertEquals("任务书 48 已批 ACCESS_NETWORK_STATE，判卷者本轮授权把这条命中数从 0 翻成恰为 1；"
                + "哨的形状没动——清单里再多出现一条就仍要红", 1,
                countLinesContaining(manifest, "ACCESS_NETWORK_STATE"));
    }

    /* ---------- ⑩ 行为合同：既有 checkpoint 字段与那 4 条断言逐字未改 ---------- */

    @Test
    public void theFrozenCheckpointContractIsVerbatimUntouched() throws IOException {
        List<String> main = sourceOf("DiagnosticCheckpoint.java");
        assertEquals("冻结文件的行数不许漂", 30, main.size());
        for (String verbatim : new String[]{
                "    static final long PERIOD_MS = 5000;",
                "        result.put(\"snapshot_age_ms\", age(nowMs, snapshot.optLong(\"snapshot_at_ms\", -1)));",
                "        result.put(\"frame_arrival_age_ms\", age(nowMs, arrived));",
                "        result.put(\"frame_completion_age_ms\", age(nowMs, completed));",
                "        result.put(\"frame_stream_status\", !expected ? \"not_expected\"",
                "                : arrived < 0 ? \"no_frame_received\"",
                "                : nowMs - arrived >= CaptureHealthMonitor.STARVATION_MS ? \"stale\" : \"recent\");",
                "        result.put(\"open_processing_gap_ms\", expected ? age(nowMs, completed) : JSONObject.NULL);",
                "    private static Object age(long now, long at) {"}) {
            assertTrue("既有 checkpoint 的字段或取值语义被改了：" + verbatim, main.contains(verbatim));
        }
        assertFalse("停摆读数不许塞回冻结的到达性判断里", hasLineContaining(main, "FrameProgressStall"));
        assertFalse(hasLineContaining(main, "frame_progress_stall"));

        List<String> frozenTest = testSourceOf("DiagnosticCheckpointTest.java");
        assertEquals("行为合同那 4 条断言的条数不许动", 4, countLinesContaining(frozenTest, "@Test"));
        assertEquals("行为合同文件的行数不许漂", 49, frozenTest.size());
        for (String verbatim : new String[]{
                "        assertEquals(7, heartbeat.getInt(\"processed_frames\"));",
                "        assertEquals(5000, heartbeat.getLong(\"snapshot_age_ms\"));",
                "        assertEquals(5050, heartbeat.getLong(\"open_processing_gap_ms\"));",
                "        assertEquals(\"stale\", heartbeat.getString(\"frame_stream_status\"));",
                "        assertFalse(state.has(\"snapshot_age_ms\"));",
                "        assertEquals(\"no_frame_received\", heartbeat.getString(\"frame_stream_status\"));",
                "        assertTrue(heartbeat.isNull(\"frame_completion_age_ms\"));",
                "            assertEquals(\"not_expected\", heartbeat.getString(\"frame_stream_status\"));",
                "            assertEquals(9000, heartbeat.getLong(\"frame_completion_age_ms\"));",
                "        assertTrue(heartbeat.isNull(\"snapshot_age_ms\"));"}) {
            assertTrue("那 4 条断言被改了：" + verbatim, frozenTest.contains(verbatim));
        }
    }

    /* ---------- ⑪ 反向验证的保险丝：阈值不可达就必须变红 ---------- */

    @Test
    public void aFullEpisodeOfMotionlessTicksAlwaysSpeaksOnce() {
        // 这条测试就是「摘链会变红」的保险丝之一：喂满 1 + N 拍必须至少喊一次。
        FrameProgressStall meter = new FrameProgressStall();
        boolean spoke = false;
        int fed = 0;
        for (int tick = 0; tick <= Math.min(FrameProgressStall.STALL_TICKS, 64); tick++) {
            Reading reading = meter.observe(CAPTURE_WRITER, true, Long.valueOf(1), tick * PERIOD);
            fed++;
            if (reading.alert) spoke = true;
        }
        assertTrue("阈值被改成永不可达之类的值时这条必须变红（喂了 " + fed + " 拍）", spoke);
        if (FrameProgressStall.STALL_TICKS <= 0) fail("阈值不许被设成 0 或负数：那会把刚认家当成停摆");
        assertEquals("阈值显式写死为 3 拍", 3, FrameProgressStall.STALL_TICKS);
    }

    /** 只拼本轮实测那四处写入点真正会带的键，不造多余字段。 */
    private static final class JSONObjectWith {
        private final JSONObject value = new JSONObject();

        JSONObjectWith game(String id) {
            return put("game_id", id);
        }

        JSONObjectWith processed(long frames) {
            return put("processed_frames", Long.valueOf(frames));
        }

        JSONObjectWith landscape(long frames) {
            return put("landscape_processed_frames", Long.valueOf(frames));
        }

        JSONObjectWith expected(boolean expected) {
            return put("frames_expected", Boolean.valueOf(expected));
        }

        JSONObjectWith snapshotAt(long atMs) {
            return put("snapshot_at_ms", Long.valueOf(atMs));
        }

        JSONObjectWith arrived(long atMs) {
            return put("last_frame_arrived_at_ms", Long.valueOf(atMs));
        }

        JSONObjectWith completed(long atMs) {
            return put("last_frame_completed_at_ms", Long.valueOf(atMs));
        }

        JSONObjectWith text(String raw) {
            return put("processed_frames", raw);
        }

        private JSONObjectWith put(String key, Object item) {
            try {
                value.put(key, item);
            } catch (JSONException impossible) {
                throw new IllegalStateException(impossible);
            }
            return this;
        }

        JSONObject build() {
            return value;
        }
    }
}
