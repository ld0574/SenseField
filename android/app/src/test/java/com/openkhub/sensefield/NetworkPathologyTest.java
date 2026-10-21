package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/**
 * 任务书 48：把「网络通不通」写成一行结构化读数，并钉住它坐在采集侧而不是记录器里。
 *
 * <p>两层证据，边界与前几本同口径：
 * <ul>
 *   <li>读数形状是纯函数（{@link NetworkPathology}）：期望值全部手写死在下面，
 *       不拿被测函数的输出反推自己。</li>
 *   <li>接线侧只能用源码结构式断言钉住「这一跳真的在既有 device 采样链上」，并且断言坐在方法体内，
 *       不是全文本数个数——本机无设备、跑不了系统侧，任何一侧都不许宣称真机效果。</li>
 * </ul>
 *
 * <p>三条最容易播错的口径各有专条：没有网络＝{@code none}、读不到＝{@code unknown}（两者不许混）、
 * 平台取不到带宽时返回 0（0 一律记 {@code unknown}，绝不播成「0 kbps 弱网」）。
 *
 * <p>任务书 48R 收口：读数不再在 {@code HyperOsSystemProbe.java:62} 被原地丢弃，而是按拍板 1
 * 追加的那一行真进了既有 {@code device} 对象。原先那三条「连网络名字都不许出现在记录器」的反向断言
 * 由 48R 撤换成正向绑定断言——它们钉的是 48 半成品造出来的状态，不是任何既有合同。
 */
public final class NetworkPathologyTest {

    private static final long AT = 4321L;

    /** 传输码 0／1：本轮 javap -constants 在 android-36 平台 jar 上复验的 CELLULAR 与 WIFI。 */
    private static final int CELLULAR = 0;
    private static final int WIFI = 1;

    /**
     * 拍板 1 那一行的形状：键名常量与采集侧唯一出口成对。记录器里只许出现这一处，
     * 摘掉它下面每条正向绑定断言都必须真红——这就是「改回去就红」那条留的钩。
     */
    private static final String WIRED_PAIR =
            "HyperOsSystemProbe.NETWORK_READING_KEY, HyperOsSystemProbe.lastNetworkReading()";

    /* ---------- ① 无网络：none，且其余字段 unavailable，一个数也不造 ---------- */

    /**
     * 系统明说没有活动网络 ⇒ {@code type=none}，其余字段 {@code unavailable}。
     * 入参故意再塞一个 validated=true 与 5000 kbps：没有网络时谁都不许把它们播出去。
     */
    @Test
    public void noNetworkReadsAsNoneAndRefusesToCarryAnyOtherField() {
        String line = NetworkPathology.reading(AT, Collections.<Integer>emptyList(),
                Boolean.TRUE, Integer.valueOf(5000));
        assertEquals("net at=4321 type=none validated=unavailable down_kbps=unavailable", line);
        assertFalse("没有网络还许把带宽播出去: " + line, line.contains("5000"));
        assertFalse("没有网络不许把 validated 播成真: " + line, line.contains("validated=true"));
        assertEquals("none 这一档只许用点名的那个字面量", "none", NetworkPathology.NO_NETWORK);
    }

    /* ---------- ② VALIDATED：真／假各一条，两档不许并成一档 ---------- */

    @Test
    public void validatedTrueAndFalseAreWrittenApart() {
        assertEquals("net at=4321 type=wifi validated=true down_kbps=15000",
                NetworkPathology.reading(AT, setOf(WIFI), Boolean.TRUE, Integer.valueOf(15000)));
        assertEquals("net at=4321 type=wifi validated=false down_kbps=15000",
                NetworkPathology.reading(AT, setOf(WIFI), Boolean.FALSE, Integer.valueOf(15000)));
        assertNotEquals("验过与没验过必须是两条不同的读数",
                NetworkPathology.reading(AT, setOf(WIFI), Boolean.TRUE, Integer.valueOf(15000)),
                NetworkPathology.reading(AT, setOf(WIFI), Boolean.FALSE, Integer.valueOf(15000)));
    }

    /* ---------- ③ 带宽取不到：0 与负数一律 unknown，绝不播成 0 kbps 弱网 ---------- */

    /** 平台 getLinkDownstreamBandwidthKbps() 取不到时返回 0：这一条专治「把 0 播成 0 kbps 弱网」。 */
    @Test
    public void zeroDownlinkIsNeverBroadcastAsZeroKbps() {
        for (int fake : new int[]{0, -1, -7, Integer.MIN_VALUE}) {
            String line = NetworkPathology.reading(AT, setOf(CELLULAR), Boolean.TRUE, Integer.valueOf(fake));
            assertTrue("取不到的带宽必须落 unknown，来的是 " + fake + ": " + line,
                    line.contains("down_kbps=unknown"));
            assertFalse("读数里不许出现 down_kbps=0: " + line, line.contains("down_kbps=0"));
            assertFalse("也不许出现「0 kbps」这种写法: " + line, line.contains("0 kbps"));
        }
        assertEquals("0 与缺失必须是同一档，不许一个播 0 一个播别的",
                NetworkPathology.reading(AT, setOf(CELLULAR), Boolean.TRUE, null),
                NetworkPathology.reading(AT, setOf(CELLULAR), Boolean.TRUE, Integer.valueOf(0)));
        assertEquals("unknown", NetworkPathology.downlinkName(null));
        assertEquals("unknown", NetworkPathology.downlinkName(Integer.valueOf(0)));
    }

    /** 真读到的正数才许带数字；本卷一个弱网阈值都不设，读数里不许有判词。 */
    @Test
    public void aRealDownlinkKeepsTheNumberAndCarriesNoVerdict() {
        String line = NetworkPathology.reading(AT, setOf(WIFI), Boolean.TRUE, Integer.valueOf(15000));
        assertTrue("正数带宽必须原样带出: " + line, line.contains("down_kbps=15000"));
        for (String verdict : new String[]{"weak", "strong", "poor", "弱", "差", "好"})
            assertFalse("读数不许自带强弱判词: " + verdict + " -> " + line, line.contains(verdict));
    }

    /* ---------- ④ 多传输并存：全部列名，不挑一个代表整体 ---------- */

    @Test
    public void everyCoexistingTransportIsNamedNotJustTheFirst() {
        assertEquals("net at=4321 type=cellular+wifi validated=true down_kbps=unknown",
                NetworkPathology.reading(AT, setOf(CELLULAR, WIFI), Boolean.TRUE, Integer.valueOf(0)));
        Collection<Integer> four = new LinkedHashSet<>(Arrays.asList(4, 1, 3, 0));
        String line = NetworkPathology.reading(AT, four, Boolean.TRUE, Integer.valueOf(2000));
        assertTrue("四个传输必须全列出来: " + line, line.contains("type=cellular+wifi+ethernet+vpn"));
        assertFalse("多传输不许只报第一个: " + line, line.contains("type=cellular "));
        assertEquals("0 kbps 那一档不许混进这一行", -1, line.indexOf("down_kbps=0"));
    }

    /* ---------- ⑤ 读不到：unknown，且绝不与 none 混写成同一档 ---------- */

    @Test
    public void unreadableStateIsUnknownAndNeverNone() {
        String unreadable = NetworkPathology.reading(AT, null, null, null);
        assertEquals("net at=4321 type=unknown validated=unknown down_kbps=unknown", unreadable);
        String none = NetworkPathology.reading(AT, Collections.<Integer>emptyList(), null, null);
        assertNotEquals("读不到不许播成「没有网络」", none, unreadable);
        assertTrue(unreadable.contains("type=unknown"));
        assertTrue(none.contains("type=none"));
        // 集合非空、但一个可用传输码也没有：仍算读不到，不许落 none。
        String onlyNullCode = NetworkPathology.reading(AT, Arrays.<Integer>asList((Integer) null),
                Boolean.TRUE, Integer.valueOf(900));
        assertTrue("空的可用传输码不许被播成 none: " + onlyNullCode,
                onlyNullCode.contains("type=unknown"));
    }

    /** 镜像表之外的传输码：叫不出名字就落 unknown，不替未知传输编一个名字。 */
    @Test
    public void unnamedTransportCodeFallsToUnknownNotAnInventedName() {
        assertEquals("net at=4321 type=unknown validated=false down_kbps=unknown",
                NetworkPathology.reading(AT, setOf(7), Boolean.FALSE, Integer.valueOf(0)));
        assertTrue("已知那一段仍要照常列名: ", NetworkPathology.reading(AT, setOf(CELLULAR, 11),
                Boolean.TRUE, Integer.valueOf(8000)).contains("type=cellular+unknown"));
        assertNotEquals("未知码不许被播成 none", "none", NetworkPathology.transportName(11));
    }

    /* ---------- ⑥ 镜像常量与时刻 ---------- */

    /** 镜像表必须与本轮 javap -constants 的实测值逐字相等，否则整行读数的口径就飘了。 */
    @Test
    public void mirroredPlatformConstantsMatchWhatJavapReported() {
        assertEquals(16, NetworkPathology.CAP_VALIDATED);
        assertEquals(0, NetworkPathology.TRANSPORT_CELLULAR);
        assertEquals(1, NetworkPathology.TRANSPORT_WIFI);
        assertEquals("镜像表点名的传输码只许这十个", 10, NetworkPathology.MIRRORED_TRANSPORTS.length);
        Set<Integer> distinct = new LinkedHashSet<>();
        for (int code : NetworkPathology.MIRRORED_TRANSPORTS) distinct.add(Integer.valueOf(code));
        assertEquals("镜像表里有重复传输码", 10, distinct.size());
        assertEquals("network_pathology", NetworkPathology.READING_KEY);
    }

    /** 时刻必须随读数一起出去，日志侧才排得动先后；取不到时刻也不许把这一项整个抹掉。 */
    @Test
    public void theSamplingMomentRidesAlongWithTheReading() {
        assertTrue(NetworkPathology.reading(123456L, setOf(WIFI), Boolean.TRUE, Integer.valueOf(1))
                .startsWith("net at=123456 "));
        assertTrue("采集时刻读不到时也要留住这一项: ",
                NetworkPathology.reading(-1L, setOf(WIFI), Boolean.TRUE, Integer.valueOf(1))
                        .contains(" at=-1"));
    }

    /* ---------- ⑦ 纯函数零平台依赖、零请求 ---------- */

    /** 整份文件逐行扫（含注释）：一个平台 API 名也不许出现，更不许 import 平台类型。 */
    @Test
    public void thePureClassTouchesNoPlatformApiAndSendsNoRequest() throws IOException {
        List<String> src = sourceOf("NetworkPathology.java");
        List<String> offenders = new ArrayList<>();
        for (String line : src) if (line.contains("android.")) offenders.add(line.trim());
        assertEquals("纯函数不许碰任何平台 API 名: " + offenders, 0, offenders.size());
        for (String line : src)
            assertFalse("纯函数不许 import 平台类型: " + line, line.trim().startsWith("import android."));
        String all = joined(src);
        for (String forbidden : new String[]{"ConnectivityManager", "getSystemService", "Socket",
                "Http", "URL", "openConnection", "NetworkRequest", "registerNetworkCallback",
                "Runtime.exec", "ProcessBuilder", "new Thread", "Executors."}) {
            assertFalse("纯函数里出现了 " + forbidden, all.contains(forbidden));
        }
        List<ReadOnlyGuard.Hit> hits = ReadOnlyGuard.scan("NetworkPathology.java", src);
        assertEquals("读数链上出现只读守卫点名的注入类 API: " + hits, 0, hits.size());
    }

    /* ---------- ⑧ 接线位置：那一跳在采集侧，记录器一个网络字都没多 ---------- */

    /**
     * 网络这一跳必须坐在 {@code HyperOsSystemProbe.sample(...)} 的既有 device 采样体内，
     * 且采集侧那六个只读步骤一个不许少。<b>摘掉 {@code networkReading(context);} 这一跳，
     * 本条必须变红</b>（本卷反向验证的主判据）。
     */
    @Test
    public void theNetworkHopSitsInsideTheDeviceSampleBody() throws IOException {
        List<String> code = codeLinesOnly(sourceOf("HyperOsSystemProbe.java"));
        String sample = methodBody(code, "static HyperOsHealthSnapshot sample(Context context,");
        assertTrue("既有 device 采样链上没有网络这一跳: " + sample, sample.contains("networkReading(context);"));
        String hop = methodBody(code, "static String networkReading(Context context)");
        for (String kept : new String[]{"context.getSystemService(ConnectivityManager.class)",
                "manager.getActiveNetwork()", "manager.getNetworkCapabilities(active)",
                "capabilities.hasTransport(code)",
                "capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)",
                "capabilities.getLinkDownstreamBandwidthKbps()"}) {
            assertTrue("网络这一跳少了只读的一步: " + kept, hop.contains(kept));
        }
        assertEquals("真值那一档只许被发布一次；改成硬写 unknown 就是假装有读数", 1,
                countOccurrences(hop, "publishNetworkReading(atMs, transports, validated, downKbps)"));
        assertTrue("采集失败必须落回纯函数的 unknown 档: " + hop,
                hop.contains("catch (RuntimeException unreadable)"));
    }

    /**
     * 记录器仍一个网络 API 词也没有（撞口 3 的原文，本卷照旧不许翻），采集侧那一处发布也只许一次。
     *
     * <p><b>48R 撤换记录</b>：这里原有三条 {@code NetworkPathology}／{@code networkReading}／
     * {@code lastNetworkReading} 一个都不许出现在记录器的反向断言，钉的是判卷者书 48 自相矛盾造出来的
     * 半成品状态——一边要求「读数并进既有那个 {@code JSONObject}」，一边把该对象唯一构造点列进不许改组，
     * 缺口因此留在 {@code HyperOsSystemProbe.java:62}。那条自缚由 48R 解除，三条反向断言按拍板 2
     * 换成正向绑定断言：device 里确有此键，值就是采集侧最后那行读数。<b>摘掉接线那一行，本条必须真红。</b>
     */
    @Test
    public void theRecorderStillCarriesNoNetworkReadingAtAll() throws IOException {
        String recorder = joined(codeLinesOnly(sourceOf("DiagnosticRecorder.java")));
        for (String forbidden : new String[]{"ConnectivityManager", "NetworkCapabilities",
                "TelephonyManager", "SignalStrength", "ACCESS_NETWORK_STATE"}) {
            assertFalse("记录器里出现了 " + forbidden, recorder.contains(forbidden));
        }
        String device = methodBody(codeLinesOnly(sourceOf("DiagnosticRecorder.java")),
                "private synchronized void sampleDevice(long now)");
        assertTrue("device 那个 object(...) 里没有网络读数键：接线又落回 :62 那一格被原地丢弃",
                device.contains(WIRED_PAIR));
        assertTrue("device 里那个键的值不是采集侧最后那行读数: " + device,
                device.contains("HyperOsSystemProbe.lastNetworkReading()"));
        assertEquals("网络读数只许在采集侧发布一次", 1,
                countLinesContaining(codeLinesOnly(sourceOf("HyperOsSystemProbe.java")),
                        "lastNetworkReading = NetworkPathology.reading("));
    }

    /* ---------- ⑧R 任务书 48R：读数真接进 device 那一行（正向绑定，摘行必红） ---------- */

    /** 主判据：device 的 {@code object(...)} 里确有此键，且值就是采集侧唯一出口那行读数。 */
    @Test
    public void deviceObjectCarriesTheKeyBoundToTheCollectorsLastLine() throws IOException {
        List<String> recorder = codeLinesOnly(sourceOf("DiagnosticRecorder.java"));
        String device = methodBody(recorder, "private synchronized void sampleDevice(long now)");
        assertTrue("device 里没有接线那一行（摘掉它本条必须红）: " + device, device.contains(WIRED_PAIR));
        assertEquals("接线只许那一行，记录器里别的一律不加", 1, countLinesContaining(recorder, WIRED_PAIR));
        int open = device.indexOf("JSONObject sample = object(");
        int sink = device.indexOf("record(\"device\", sample)");
        assertTrue("取不到 object(...) 到 record 之间那一段: open=" + open + " sink=" + sink,
                open >= 0 && sink > open);
        String pairs = device.substring(open, sink);
        assertTrue("键没落在 object(...) 的成对表里，读数进不了 device 事件: " + pairs, pairs.contains(WIRED_PAIR));
        assertEquals("采集侧出口在成对表里只许被引用一次", 1,
                countOccurrences(pairs, "HyperOsSystemProbe.lastNetworkReading()"));
        assertEquals("键名常量与纯函数那把键同源，不许出现第二个名字",
                NetworkPathology.READING_KEY, HyperOsSystemProbe.NETWORK_READING_KEY);
        assertEquals("这一把键的字面量仍是 network_pathology", "network_pathology",
                HyperOsSystemProbe.NETWORK_READING_KEY);
    }

    /** 撞口 3 精神：五个网络 API 词在记录器里必须仍是 0 命中，接线那一行也不许夹带任何一个。 */
    @Test
    public void theWiredLineCarriesNoNetworkApiWordAtAll() throws IOException {
        List<String> recorder = codeLinesOnly(sourceOf("DiagnosticRecorder.java"));
        String[] five = {"ConnectivityManager", "NetworkCapabilities", "TelephonyManager",
                "SignalStrength", "ACCESS_NETWORK_STATE"};
        for (String forbidden : five)
            assertEquals("五个网络 API 词在记录器里必须 0 命中: " + forbidden, 0,
                    countOccurrences(joined(recorder), forbidden));
        for (String line : recorder) if (line.contains("HyperOsSystemProbe.lastNetworkReading()"))
            for (String forbidden : five)
                assertFalse("接线那一行混进了 " + forbidden + ": " + line, line.contains(forbidden));
    }

    /** 首拍形状（拍板 3）：初值不许是 null；读不到时写「读不到」这一档，而不是干脆缺这个键。 */
    @Test
    public void theFirstFrameBindsAReadingAndNeverDropsTheKey() throws IOException {
        String first = HyperOsSystemProbe.lastNetworkReading();
        assertNotNull("lastNetworkReading 的初值不许是 null", first);
        assertFalse("初值不许是空串: " + first, first.trim().isEmpty());
        assertEquals("没采过时那一行必须落「读不到」档，不许编一条有网出来: " + first,
                "net at=-1 type=unknown validated=unknown down_kbps=unknown", first);
        assertFalse("读不到不许被播成「没有网络」: " + first, first.contains("type=none"));
        List<String> recorder = codeLinesOnly(sourceOf("DiagnosticRecorder.java"));
        assertEquals("键不许整块缺掉——记录器里那一行只许有一处", 1, countLinesContaining(recorder, WIRED_PAIR));
        String wired = recorder.get(indexOfFirstLineContaining(recorder, WIRED_PAIR));
        assertFalse("键不许挂在三元条件上——读不到时也要写这个键: " + wired, wired.contains("?"));
        assertFalse("键不许被 if 包起来——读不到时也要写这个键: " + wired, wired.contains("if ("));
    }

    /** 那一行先进 {@code lastDeviceSample}，检查点再整块带出：不新增事件、不另开一条落点。 */
    @Test
    public void networkReadingReachesTheCheckpointThroughLastDeviceSample() throws IOException {
        List<String> recorder = codeLinesOnly(sourceOf("DiagnosticRecorder.java"));
        String device = methodBody(recorder, "private synchronized void sampleDevice(long now)");
        int pair = device.indexOf(WIRED_PAIR);
        int stored = device.indexOf("lastDeviceSample = sample;");
        assertTrue("读数没先进 lastDeviceSample 再进检查点: pair=" + pair + " stored=" + stored,
                pair >= 0 && stored > pair);
        String checkpoint = methodBody(recorder, "private void checkpoint(long nowMs)");
        assertTrue("检查点不再整块带出 last_device（与 HyperOsForegroundPromotionTest:307-313 同一口径）: "
                + checkpoint, checkpoint.contains(
                        "data.put(\"last_device\", lastDeviceSample == null ? JSONObject.NULL : lastDeviceSample)"));
        assertEquals("device 事件的落点仍只许一句", 1,
                countOccurrences(joined(recorder), "record(\"device\", sample)"));
        assertFalse("读数不许在检查点里另写一条事件",
                checkpoint.contains("HyperOsSystemProbe.lastNetworkReading()"));
    }

    /** 记录器只走采集侧那一个出口，不直接引纯函数类，也不因接线多任何东西。 */
    @Test
    public void theRecorderReachesTheReadingOnlyViaTheCollectorExport() throws IOException {
        List<String> recorder = codeLinesOnly(sourceOf("DiagnosticRecorder.java"));
        String all = joined(recorder);
        assertEquals("记录器不许直接引纯函数类，只许走采集侧出口", 0, countOccurrences(all, "NetworkPathology"));
        assertEquals("采集侧唯一出口在记录器里只许被引用一次", 1,
                countLinesContaining(recorder, "HyperOsSystemProbe.lastNetworkReading()"));
        for (String forbidden : new String[]{"networkReading(", "NetworkRequest",
                "registerNetworkCallback", "registerDefaultNetworkCallback", "NetworkCapabilities",
                "ConnectivityManager", "TelephonyManager", "SignalStrength", "ACCESS_NETWORK_STATE",
                "Socket", "openConnection", "WifiManager"}) {
            assertFalse("接线不许在记录器里新增 " + forbidden, all.contains(forbidden));
        }
        assertEquals("采集侧键名常量只许定义一次", 1,
                countLinesContaining(codeLinesOnly(sourceOf("HyperOsSystemProbe.java")),
                        "static final String NETWORK_READING_KEY"));
    }

    /** 渲染形状：这一对键值过一遍 device 用的那个 {@code object(...)}，键读得到、那行也读得到。 */
    @Test
    public void thePairRenderedThroughTheDeviceHelperShowsTheCollectorsLine() {
        String firstFrame = DiagnosticRecorder.object(HyperOsSystemProbe.NETWORK_READING_KEY,
                HyperOsSystemProbe.lastNetworkReading()).toString();
        assertEquals("device 里那一键渲染出的不是采集侧最后那行读数: " + firstFrame,
                "{\"network_pathology\":\"net at=-1 type=unknown validated=unknown down_kbps=unknown\"}",
                firstFrame);
        String filled = DiagnosticRecorder.object(HyperOsSystemProbe.NETWORK_READING_KEY,
                NetworkPathology.reading(1234L, Collections.<Integer>singletonList(WIFI),
                        Boolean.TRUE, Integer.valueOf(15000))).toString();
        assertTrue("填满那一拍必须渲染出「有没有网、验过没有、下行估多少」: " + filled,
                filled.contains("\"net at=1234 type=wifi validated=true down_kbps=15000\""));
    }

    /* ---------- ⑨ 既有 device 字段一字未改 ---------- */

    /** 既有九字段逐行原样、既有四键顺序未动、落点仍是那一句 {@code record("device", sample)}。 */
    @Test
    public void existingDeviceFieldsAreVerbatimAndInOrder() throws IOException {
        List<String> src = sourceOf("DiagnosticRecorder.java");
        int start = indexOfFirstLineContaining(src, "JSONObject sample = object(");
        List<String> kept = Arrays.asList(
                "                    \"media_volume\", audio == null ? -1 : audio.getStreamVolume(AudioManager.STREAM_MUSIC),",
                "                    \"media_volume_max\", audio == null ? -1 : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),",
                "                    \"thermal_status\", thermalStatus,",
                "                    \"thermal_status_name\", thermalStatusName(thermalStatus),");
        for (int i = 0; i < kept.size(); i++)
            assertEquals("既有 device 字段被人改动（第 " + (start + 7 + i) + " 行）",
                    kept.get(i), src.get(start + 6 + i));
        assertTrue(src.get(start + 10).contains("HyperOsHealthSnapshot.READING_KEY"));
        assertTrue(src.get(start + 11).contains("HyperOsHealthSnapshot.GUIDANCE_KEY"));
        int gate = indexOfFirstLineContaining(src, "HyperOsHealthSnapshot.FGS_GATE_KEY");
        int decline = indexOfFirstLineContaining(src, "HyperOsHealthSnapshot.FGS_DEGRADATION_KEY");
        assertTrue("既有键被挪了位置: gate=" + gate, gate > start + 11);
        assertTrue("降级键必须仍排在闸门键之后", decline > gate);
        assertEquals("device 事件的落点只许有一句", 1, countOccurrences(joined(src), "record(\"device\", sample)"));
        assertNotEquals("既有读数键不许被改名", HyperOsHealthSnapshot.READING_KEY, NetworkPathology.READING_KEY);
    }

    /* ---------- ⑩ 三处撞口的新形状 ---------- */

    /** 撞口 1：条数哨只从 9 抬到已批的 10，形状保留；同文件那三条 3／18／2 一字未动。 */
    @Test
    public void collisionOneStillGuardsThePermissionCount() throws IOException {
        List<String> guard = testSourceOf("HyperOsForegroundPromotionTest.java");
        assertEquals("权限条数哨不许被删成两处或删掉", 1,
                countLinesContaining(guard, "清单条目不许新增（uses-permission）"));
        assertTrue("权限条数没抬到已批的 10: " + lineWith(guard, "uses-permission）"),
                hasLineContaining(guard, "assertEquals(\"清单条目不许新增（uses-permission）\", 10,"
                        + " countOccurrences(text, \"<uses-permission\"));"));
        assertFalse("旧的 9 那条形状不许还留在哨里", hasLineContaining(guard,
                "assertEquals(\"清单条目不许新增（uses-permission）\", 9,"));
        for (String untouched : new String[]{"清单条目不许新增（service 元素）\", 3",
                "清单条目不许新增（activity 元素）\", 18", "清单条目不许新增（provider 元素）\", 2"}) {
            assertTrue("撞口 1 点名不许动的那条被改动: " + untouched, hasLineContaining(guard, untouched));
        }
    }

    /** 撞口 2：命中文案按判卷者本轮授权翻成「恰为 1」，哨的形状没动——再多一条仍要红。 */
    @Test
    public void collisionTwoIsFlippedToExactlyOneAndStillGuards() throws IOException {
        List<String> guard = testSourceOf("FrameProgressStallTest.java");
        assertEquals("该权限的命中哨只许一处", 1,
                countLinesContaining(guard, "countLinesContaining(manifest, \"ACCESS_NETWORK_STATE\")"));
        assertTrue("翻转必须写明属判卷者本轮授权: " + lineWith(guard, "ACCESS_NETWORK_STATE\")"),
                hasLineContaining(guard, "判卷者本轮授权把这条命中数从 0 翻成恰为 1；"));
        assertTrue("翻转后哨的期望值必须是 1", hasLineContaining(guard,
                "哨的形状没动——清单里再多出现一条就仍要红\", 1,"));
        assertFalse("旧的「必须仍是 0」不许还留在文件里", hasLineContaining(guard, "命中数必须仍是 0"));
    }

    /** 撞口 3：五个网络词的数组与那条 assertFalse 一字未动，本卷不许翻它。 */
    @Test
    public void collisionThreeStaysArmedVerbatim() throws IOException {
        List<String> guard = testSourceOf("FrameProgressStallTest.java");
        assertTrue("记录器网络词数组第一行被改动: ", hasLineContaining(guard,
                "        for (String forbidden : new String[]{\"ConnectivityManager\", \"NetworkCapabilities\","));
        assertTrue("记录器网络词数组第二行被改动: ", hasLineContaining(guard,
                "                \"TelephonyManager\", \"SignalStrength\", \"ACCESS_NETWORK_STATE\"}) {"));
        assertTrue("记录器网络词那条 assertFalse 被改动: ", hasLineContaining(guard,
                "            assertFalse(\"记录器里出现了 \" + forbidden, joined(code).contains(forbidden));"));
    }

    /* ---------- ⑪ 清单：只多这一条权限，第二条权限不许出现 ---------- */

    @Test
    public void manifestCarriesOneNetworkPermissionAndNoSecondPermission() throws IOException {
        List<String> manifest = fileOf("src/main/AndroidManifest.xml",
                "android/app/src/main/AndroidManifest.xml");
        assertEquals("已批的 ACCESS_NETWORK_STATE 必须恰有一条", 1,
                countLinesContaining(manifest, "ACCESS_NETWORK_STATE"));
        assertEquals("全清单 uses-permission 必须恰为已批的十条", 10,
                countOccurrences(joined(manifest), "<uses-permission"));
        for (String notGranted : new String[]{"ACCESS_WIFI_STATE", "READ_PHONE_STATE",
                "ACCESS_FINE_LOCATION", "CHANGE_WIFI_STATE"})
            assertEquals("本卷只批了 ACCESS_NETWORK_STATE，清单里不该出现 " + notGranted, 0,
                    countLinesContaining(manifest, notGranted));
        String probe = joined(codeLinesOnly(sourceOf("HyperOsSystemProbe.java")));
        for (String platformManager : new String[]{"WifiManager", "TelephonyManager"})
            assertFalse("信号强度那一半未裁，采集侧不许碰 " + platformManager, probe.contains(platformManager));
    }

    /* ---------- ⑫ 采集侧不新增线程、回调与请求 ---------- */

    @Test
    public void theCollectionHopStartsNoThreadNoCallbackAndNoRequest() throws IOException {
        String probe = joined(codeLinesOnly(sourceOf("HyperOsSystemProbe.java")));
        for (String forbidden : new String[]{"new Thread", "Executors.", "ScheduledExecutorService",
                "scheduleAtFixedRate", "scheduleWithFixedDelay", "Timer(", "new HandlerThread",
                "registerNetworkCallback", "registerDefaultNetworkCallback", "NetworkRequest",
                "Socket", "Http", "openConnection"}) {
            assertFalse("采集侧不许新增 " + forbidden, probe.contains(forbidden));
        }
        String hop = methodBody(codeLinesOnly(sourceOf("HyperOsSystemProbe.java")),
                "static String networkReading(Context context)");
        assertEquals("网络这一跳只许有一个 catch 面，不许缩小成某一种异常", 1, countOccurrences(hop, "catch ("));
    }

    /* ---------- 仪器（与前几本同一把尺） ---------- */

    private static Set<Integer> setOf(int... codes) {
        Set<Integer> out = new LinkedHashSet<>();
        for (int code : codes) out.add(Integer.valueOf(code));
        return out;
    }

    private static List<String> sourceOf(String simpleName) throws IOException {
        return fileOf("src/main/java/com/openkhub/sensefield/" + simpleName,
                "android/app/src/main/java/com/openkhub/sensefield/" + simpleName);
    }

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

    /** 只看代码行：注释行（// 与 javadoc 的 * 开头）剔除，因为注释里要点名那些禁用的写法。 */
    private static List<String> codeLinesOnly(List<String> src) {
        List<String> out = new ArrayList<>();
        for (String line : src) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")) continue;
            out.add(line);
        }
        return out;
    }

    private static String joined(List<String> lines) { return String.join("\n", lines); }

    private static boolean hasLineContaining(List<String> lines, String needle) {
        for (String line : lines) if (line.contains(needle)) return true;
        return false;
    }

    private static String lineWith(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++)
            if (lines.get(i).contains(needle)) return (i + 1) + ": " + lines.get(i).trim();
        return "（没有一行含 " + needle + "）";
    }

    private static int indexOfFirstLineContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(needle)) return i;
        throw new AssertionError("源码里没有这句：" + needle);
    }

    private static int countLinesContaining(List<String> lines, String needle) {
        int n = 0;
        for (String line : lines) if (line.contains(needle)) n++;
        return n;
    }

    private static int countOccurrences(String text, String needle) {
        int n = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) n++;
        return n;
    }

    /** 花括号配平地取出方法体（含签名行），别拿全文本凑数。 */
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
}
