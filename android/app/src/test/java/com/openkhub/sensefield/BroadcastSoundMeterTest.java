package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * 报警器锁：{@link BroadcastSoundMeter} 的读数契约，加上「生产码真有人调用这条读数」的接线锁。
 *
 * <p>期望值全部在本类里写死，不拿被测函数自己的输出反推。{@code NearZoneRouting.stereoGains} 只作
 * 对照物读，本类不复制它的增益公式；{@code CuePlayer} 只读源码不改。
 *
 * <p>证据边界：本机无 adb。这里能证明「对交织立体声缓冲测出来的左右比值与合成律对得上、句子出口在
 * 生产路径上真被调用」，证明不了听障玩家一眼扫过去能不能判断出偏左、档位划得够不够粗——那属感官验收，
 * 留给真机那一轮。
 */
public final class BroadcastSoundMeterTest {

    private static final String LEFT = BroadcastSoundMeter.BALANCE_LEFT;
    private static final String CENTER = BroadcastSoundMeter.BALANCE_CENTER;
    private static final String RIGHT = BroadcastSoundMeter.BALANCE_RIGHT;

    /** 造一段恒定幅度的交织双声道缓冲：偶下标＝左、奇下标＝右，与 CuePlayer 的填充口径一致。 */
    private static short[] stereo(int frames, int leftValue, int rightValue) {
        short[] buffer = new short[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            buffer[frame * 2] = (short) leftValue;
            buffer[frame * 2 + 1] = (short) rightValue;
        }
        return buffer;
    }

    /** ① 全零＝静音档，且偏向退回居中，句子说的是「没有播出声音」。 */
    @Test
    public void allZeroBufferReadsAsSilenceAndCenter() {
        short[] silent = stereo(64, 0, 0);
        assertEquals(0, BroadcastSoundMeter.levelOf(silent));
        assertEquals(CENTER, BroadcastSoundMeter.balanceOf(silent));
        assertEquals(CENTER, BroadcastSoundMeter.read(silent).balance);
        assertEquals(0, BroadcastSoundMeter.read(silent).level);
        assertTrue(BroadcastSoundMeter.describe(silent).contains("没有播出声音"));
    }

    /** ② 左强右弱＝偏左。倍数取到阈值之上，不靠边界取巧。 */
    @Test
    public void strongerLeftChannelReadsAsLeft() {
        assertEquals(LEFT, BroadcastSoundMeter.balanceOf(stereo(64, 8000, 2000)));
        assertEquals(LEFT, BroadcastSoundMeter.balanceOf(stereo(64, 12000, 100)));
        assertEquals(LEFT, BroadcastSoundMeter.read(stereo(32, 8000, 2000)).balance);
    }

    /** ③ 右强左弱＝偏右，且与②镜像对称：同一组数字换个方向必须换个读数。 */
    @Test
    public void strongerRightChannelReadsAsRight() {
        assertEquals(RIGHT, BroadcastSoundMeter.balanceOf(stereo(64, 2000, 8000)));
        assertEquals(RIGHT, BroadcastSoundMeter.balanceOf(stereo(64, 100, 12000)));
        assertNotCenter("右强左弱", BroadcastSoundMeter.balanceOf(stereo(64, 2000, 8000)));
    }

    /** ④ 两通道等值＝居中。这条正是「把左右两路喂成同一个值」的哨兵：一旦生产填充塌成同值就红。 */
    @Test
    public void identicalChannelsAlwaysReadAsCenter() {
        for (int value : new int[] {1, 40, 500, 12000, 32767, -12000}) {
            assertEquals("两路同值必须居中: " + value,
                    CENTER, BroadcastSoundMeter.balanceOf(stereo(32, value, value)));
        }
        assertFalse("同值不许被读成偏左",
                LEFT.equals(BroadcastSoundMeter.balanceOf(stereo(32, 9000, 9000))));
        assertFalse("同值不许被读成偏右",
                RIGHT.equals(BroadcastSoundMeter.balanceOf(stereo(32, 9000, 9000))));
    }

    /** ⑤ 半帧（长度不是 2 的整数倍）不抛，尾巴那半个样本被丢弃而不是猜。 */
    @Test
    public void halfFrameDoesNotThrow() {
        short[] odd = {100, 200, 300, 400, 50};
        assertEquals(2L, BroadcastSoundMeter.frameCount(odd));
        // 帧数取整到 2，第 5 个样本（索引 4）不参与任何一道的能量。
        assertEquals(223.60679774997897, BroadcastSoundMeter.channelRms(odd, 0), 1e-9);
        assertEquals(316.22776601683796, BroadcastSoundMeter.channelRms(odd, 1), 1e-9);
        assertEquals(200.0, BroadcastSoundMeter.channelRms(stereo(1, 200, 200), 0), 0.0);
        int level = BroadcastSoundMeter.levelOf(odd);
        assertTrue("半帧读数必须落在有限档位内: " + level,
                level >= 0 && level < BroadcastSoundMeter.LOUDNESS_BAND_COUNT);
        assertTrue(BroadcastSoundMeter.describe(odd).length() > 0);
    }

    /** ⑥ 空数组不抛，按静音处理，并且留下一个读得到的读数。 */
    @Test
    public void emptyBufferDoesNotThrowAndStillYieldsAReading() {
        short[] empty = new short[0];
        assertEquals(0L, BroadcastSoundMeter.frameCount(empty));
        assertEquals(0, BroadcastSoundMeter.levelOf(empty));
        assertEquals(CENTER, BroadcastSoundMeter.balanceOf(empty));
        assertEquals("本机这条提示没有播出声音。", BroadcastSoundMeter.describe(empty));
    }

    /** ⑥ 的另一半：null 同样不抛，按静音处理。 */
    @Test
    public void nullBufferIsTreatedAsSilence() {
        assertEquals(0L, BroadcastSoundMeter.frameCount(null));
        assertEquals(0, BroadcastSoundMeter.levelOf(null));
        assertEquals(CENTER, BroadcastSoundMeter.balanceOf(null));
        assertEquals(0, BroadcastSoundMeter.read(null).level);
        assertTrue(BroadcastSoundMeter.describe(null).contains("没有播出声音"));
    }

    /** ⑦ 偏向阈值上下各一档，含恰好等于阈值那一侧取「偏」而不是「居中」。 */
    @Test
    public void balanceThresholdIsInclusiveOnBothSides() {
        double ratio = BroadcastSoundMeter.BALANCE_RATIO;
        // 恰好等于阈值：两边都算偏向。
        assertEquals(LEFT, BroadcastSoundMeter.balanceOf(ratio * 1000.0, 1000.0));
        assertEquals(RIGHT, BroadcastSoundMeter.balanceOf(1000.0, ratio * 1000.0));
        // 差一点点：仍是居中，不许把边界内的抖动报成方向。
        assertEquals(CENTER, BroadcastSoundMeter.balanceOf(ratio * 1000.0 - 1.0, 1000.0));
        assertEquals(CENTER, BroadcastSoundMeter.balanceOf(1000.0, ratio * 1000.0 - 1.0));
        // 双双静音不许被算成任何一侧。
        assertEquals(CENTER, BroadcastSoundMeter.balanceOf(0.0, 0.0));
    }

    /** ⑧ 强弱档单调：输入能量翻倍，档位只许持平或变高，一次都不许回落。 */
    @Test
    public void doublingEnergyNeverLowersTheBand() {
        int previous = -1;
        for (double amplitude = 1.0; amplitude <= 16384.0; amplitude *= 2.0) {
            int band = BroadcastSoundMeter.bandOf(amplitude, amplitude);
            assertTrue("幅度 " + amplitude + " 处档位从 " + previous + " 回落到 " + band,
                    band >= previous);
            previous = band;
        }
        // 缓冲级也走一遍：整段幅度翻倍，读数不降。
        int lastLevel = -1;
        for (int value : new int[] {1, 2, 4, 8, 16, 32, 64, 128, 512, 1024, 4096, 8192}) {
            int level = BroadcastSoundMeter.levelOf(stereo(16, value, value));
            assertTrue("幅度 " + value + " 处档位回落: " + lastLevel + " -> " + level,
                    level >= lastLevel);
            lastLevel = level;
        }
        assertEquals("翻倍扫到底必须真的爬到过顶档",
                BroadcastSoundMeter.LOUDNESS_BAND_COUNT - 1, lastLevel);
    }

    /** ⑨ 到顶饱和不回绕：满幅与顶档入口都停在顶档，不许绕回低档。 */
    @Test
    public void topBandSaturatesInsteadOfWrappingAround() {
        int top = BroadcastSoundMeter.LOUDNESS_BAND_COUNT - 1;
        assertEquals(top, BroadcastSoundMeter.bandOf(4096.0, 4096.0));
        assertEquals(top, BroadcastSoundMeter.bandOf(20000.0, 20000.0));
        assertEquals(top, BroadcastSoundMeter.levelOf(stereo(16, Short.MAX_VALUE, Short.MAX_VALUE)));
        assertEquals(top, BroadcastSoundMeter.levelOf(stereo(16, Short.MIN_VALUE, Short.MIN_VALUE)));
        assertEquals(top, BroadcastSoundMeter.bandOf(30000.0, 1.0));
        assertTrue("顶档之下那一档必须低于顶档",
                BroadcastSoundMeter.bandOf(4095.0, 4095.0) < top);
    }

    /** 档位与词表口径固定：0 档＝静音，档数不许浮动；分母按帧不按总样本。 */
    @Test
    public void bandCountAndWordTableAreFixedAndSilenceIsBandZero() {
        assertEquals(5, BroadcastSoundMeter.LOUDNESS_BAND_COUNT);
        assertEquals(BroadcastSoundMeter.LOUDNESS_WORDS.length, BroadcastSoundMeter.LOUDNESS_BAND_COUNT);
        assertEquals(4, BroadcastSoundMeter.LOUDNESS_BAND_UPPER_RMS.length);
        assertEquals("没有播出声音", BroadcastSoundMeter.LOUDNESS_WORDS[0]);
        for (double value : new double[] {0.0, 1.0, 7.0, 8.0, 63.0, 511.0, 4095.0, 32767.0}) {
            int band = BroadcastSoundMeter.bandOf(value, value);
            assertTrue("越界档位: " + value + " -> " + band,
                    band >= 0 && band < BroadcastSoundMeter.LOUDNESS_BAND_COUNT);
        }
        assertEquals(0, BroadcastSoundMeter.bandOf(0.0, 0.0));
        // 单声道那样拿样本总数当分母会把能量算少一半：这里钉住「每道按帧数」的口径。
        assertEquals(1000.0, BroadcastSoundMeter.channelRms(stereo(4, 1000, 0), 0), 0.0);
        assertEquals(0.0, BroadcastSoundMeter.channelRms(stereo(4, 1000, 0), 1), 0.0);
    }

    /** ⑩ 给人看的那句里不许有原始数字、坐标字面量，也不许把本机的偏置说成游戏里的声音在某方向。 */
    @Test
    public void sentenceCarriesNoRawNumberNoCoordinateAndNoGameDirectionClaim() {
        short[][] cases = {stereo(8, 0, 0), stereo(8, 500, 500), stereo(8, 9000, 700),
                stereo(8, 700, 9000), stereo(8, 32767, 32767)};
        for (short[] buffer : cases) {
            String sentence = BroadcastSoundMeter.describe(buffer);
            for (int i = 0; i < sentence.length(); i++) {
                char c = sentence.charAt(i);
                assertFalse("句子里不许出现原始数字: " + sentence, c >= '0' && c <= '9');
            }
            assertFalse("不许出现括号类坐标字面量: " + sentence, sentence.matches("(?s).*\\(.*\\).*"));
            assertFalse("不许出现逗号类坐标分隔符: " + sentence, sentence.contains(","));
            assertFalse("不许出现方括号: " + sentence,
                    sentence.contains("[") || sentence.contains("]"));
            for (String banned : new String[] {"x", "y", "坐标", "游戏", "偏置", "pan"}) {
                assertFalse("不许出现 " + banned + ": " + sentence, sentence.contains(banned));
            }
            assertTrue("句子必须点明是本机播出去的声音: " + sentence, sentence.contains("本机"));
        }
        // 三种偏向词都必须真的出现在某句里，不许偷偷只画强弱却宣称方向已做。
        assertTrue(BroadcastSoundMeter.describe(stereo(8, 9000, 700)).contains(LEFT));
        assertTrue(BroadcastSoundMeter.describe(stereo(8, 700, 9000)).contains(RIGHT));
        assertTrue(BroadcastSoundMeter.describe(stereo(8, 500, 500)).contains(CENTER));
    }

    /** ⑪ 分析器源文件里 {@code android.} 命中为 0：读数必须能在 JVM 里跑。 */
    @Test
    public void analyzerSourceHasZeroAndroidReferences() throws Exception {
        String source = readSource("BroadcastSoundMeter.java");
        int hits = countOccurrences(source, "android.");
        assertEquals("分析器里不许出现 android. 命中:\n" + dumpMatchingLines(source, "android."),
                0, hits);
        assertFalse(source.contains("import android"));
    }

    /** ⑫ 与既有平衡律对齐：同一个 pan 的理论增益比，必须等于实测缓冲的通道 RMS 比。 */
    @Test
    public void measuredRatioMatchesTheExistingBalanceLaw() {
        for (float pan : new float[] {-1f, -0.5f, -0.2f, 0f, 0.2f, 0.5f, 1f}) {
            short[] buffer = synthesizeLikeThePlayer(pan, 24);
            float[] gains = NearZoneRouting.stereoGains(pan, 1f);
            double left = BroadcastSoundMeter.channelRms(buffer, 0);
            double right = BroadcastSoundMeter.channelRms(buffer, 1);
            double theoretical = gains[0] / gains[1];
            assertEquals("pan=" + pan + " 实测比值必须跟合成律对得上",
                    theoretical, left / right, Math.abs(theoretical) * 0.01);
            assertEquals("pan=" + pan + " 的偏向必须与合成律同向",
                    expectedBalance(gains[0], gains[1]), BroadcastSoundMeter.balanceOf(buffer));
        }
    }

    /** ⑫ 的对照表独立写死，不拿被测函数反推：律说偏左的幅度，读数也必须说偏左。 */
    @Test
    public void balanceLawAndMeterAgreeOnTheHardCasedPans() {
        assertEquals(CENTER, BroadcastSoundMeter.balanceOf(synthesizeLikeThePlayer(0f, 16)));
        assertEquals(LEFT, BroadcastSoundMeter.balanceOf(synthesizeLikeThePlayer(-1f, 16)));
        assertEquals(RIGHT, BroadcastSoundMeter.balanceOf(synthesizeLikeThePlayer(1f, 16)));
        assertEquals(LEFT, BroadcastSoundMeter.balanceOf(synthesizeLikeThePlayer(-0.5f, 16)));
        assertEquals(RIGHT, BroadcastSoundMeter.balanceOf(synthesizeLikeThePlayer(0.5f, 16)));
        assertEquals(CENTER, BroadcastSoundMeter.balanceOf(synthesizeLikeThePlayer(-0.2f, 16)));
        assertEquals(CENTER, BroadcastSoundMeter.balanceOf(synthesizeLikeThePlayer(0.2f, 16)));
    }

    /** ⑬ 接线锁①：两条立体声写入路径上各取一次读数，一次播报更新一次。 */
    @Test
    public void playerReadsTheMeterOnBothStereoWritePaths() throws Exception {
        String source = readSource("CuePlayer.java");
        assertEquals("两条立体声播报路径必须各取一次读数",
                2, countOccurrences(source, "BroadcastSoundMeter.describe("));
        assertEquals("除声明外必须恰有两处真实出口调用",
                2, countOccurrences(source, "reportBroadcastMeter(broadcastMeter)"));
        // AudioTrack 参数一字不许动：两处立体声掩码与两处写入调用仍逐字在原位。
        assertEquals(2, countOccurrences(source, "setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)"));
        assertTrue(source.contains("track.write(mixed, 0, mixed.length, AudioTrack.WRITE_BLOCKING)"));
        assertTrue(source.contains("track.write(samples, 0, samples.length, AudioTrack.WRITE_BLOCKING)"));
    }

    /** ⑬ 接线锁②：出口是既有那块玩家可见文字面，且没有为此新增 import。 */
    @Test
    public void outletIsTheExistingPlayerVisibleTextSurface() throws Exception {
        String source = readSource("CuePlayer.java");
        int outlet = source.indexOf("private void reportBroadcastMeter(String sentence)");
        assertTrue("找不到出口方法本体", outlet >= 0);
        String body = source.substring(outlet, source.indexOf("\n    }", outlet));
        assertTrue("出口必须回到主线程上的既有文字面:\n" + body, body.contains("mainHandler.post"));
        assertTrue("用的是既有 Toast 文字面:\n" + body, body.contains("android.widget.Toast.makeText"));
        assertTrue("停了不许再弹:\n" + body, body.contains("if (closed"));
        String head = source.substring(0, source.indexOf("final class CuePlayer"));
        assertFalse("不许为接线新增 android.widget 的 import", head.contains("import android.widget."));
    }

    /** ⑭ 反假读数锁：单声道那条播报路上不许出现任何左右能量比较。 */
    @Test
    public void monoPathIsNeverReadForLeftRightEnergy() throws Exception {
        String source = readSource("CuePlayer.java");
        int mono = source.indexOf("setChannelMask(AudioFormat.CHANNEL_OUT_MONO)");
        assertTrue("单声道那条路应当仍在原处", mono >= 0);
        int write = source.indexOf("track.write(pcm16k, offset, count,", mono);
        assertTrue("找不到单声道写入点", write > mono);
        String monoRegion = source.substring(mono, write);
        assertEquals("单声道路上不许取读数", 0, countOccurrences(monoRegion, "BroadcastSoundMeter"));
        assertEquals("单声道路上不许走这条出口", 0, countOccurrences(monoRegion, "reportBroadcastMeter"));
        // 交织双声道那两处填充仍是左右分开填的同一个口径：偶下标左、奇下标右。
        assertTrue(source.contains("stereo[i * 2] = saturate(left);"));
        assertTrue(source.contains("stereo[i * 2 + 1] = saturate(right);"));
        assertTrue(source.contains("stereo[i * 2] = (short) Math.round(mono[i] * gains[0]);"));
        assertTrue(source.contains("stereo[i * 2 + 1] = (short) Math.round(mono[i] * gains[1]);"));
    }

    /** ⑮ 采集侧那条唯一可采的路仍是单声道：本轮不许宣称能读出「游戏里的声音在哪个方向」。 */
    @Test
    public void captureSideStaysMonoAndUnadvertised() throws Exception {
        String voice = readSource("AssistantVoiceInput.java");
        assertTrue("采集通道掩码必须仍是单声道", voice.contains("AudioFormat.CHANNEL_IN_MONO"));
        assertFalse("采集侧不许接这台读数", voice.contains("BroadcastSoundMeter"));
        String meter = readSource("BroadcastSoundMeter.java");
        assertFalse("读数类不许自称能测游戏里的声音", meter.contains("游戏里的声音在"));
    }

    private static void assertNotCenter(String which, String balance) {
        assertFalse(which + " 必须报出方向而不是居中", CENTER.equals(balance));
    }

    /** 期望偏向独立按律的增益大小关系写死，与被测读数是两套互证的写法。 */
    private static String expectedBalance(float gainLeft, float gainRight) {
        if (gainLeft > gainRight * BroadcastSoundMeter.BALANCE_RATIO) return LEFT;
        if (gainRight > gainLeft * BroadcastSoundMeter.BALANCE_RATIO) return RIGHT;
        return CENTER;
    }

    /**
     * 按 {@code CuePlayer} 的既有填充口径合成一段方波立体声（偶下标＝左、奇下标＝右）。
     * 幅度取 12000：整型取整带来的比值误差压到百分之一以下，又不撞削顶。
     */
    private static short[] synthesizeLikeThePlayer(float pan, int frames) {
        float[] gains = NearZoneRouting.stereoGains(pan, 1f);
        short[] buffer = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            short sample = (short) ((i % 2 == 0) ? 12000 : -12000);
            buffer[i * 2] = (short) Math.round(sample * gains[0]);
            buffer[i * 2 + 1] = (short) Math.round(sample * gains[1]);
        }
        return buffer;
    }

    private static String readSource(String fileName) throws Exception {
        String tail = "com/openkhub/sensefield/" + fileName;
        Path candidate = Path.of("src/main/java/" + tail);
        if (!Files.isReadable(candidate)) candidate = Path.of("app/src/main/java/" + tail);
        assertTrue("单测工作目录里找不到被测源文件: " + candidate.toAbsolutePath(),
                Files.isReadable(candidate));
        return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) count++;
        return count;
    }

    private static String dumpMatchingLines(String source, String needle) {
        StringBuilder out = new StringBuilder();
        for (String line : source.split("\n")) {
            if (line.contains(needle)) out.append(line).append('\n');
        }
        return out.length() == 0 ? "(无命中行)" : out.toString();
    }
}
