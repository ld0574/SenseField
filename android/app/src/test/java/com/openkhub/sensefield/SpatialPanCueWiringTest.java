package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

/**
 * 「格子上算得出的左右方位」与「交换提示的提交」之间那条接线的锁。
 *
 * <p>被测的是 {@link Match3LiveCuePolicy} 里两个包级 static 纯函数：一个算 pan、一个算通道位。
 * 期望值全部写死，不拿被测函数自己的输出反推；{@code BoardPan} / {@code CueRequest} /
 * {@code CuePlayer} 只读不改，本类不复制它们的内部实现来倒推期望。
 *
 * <p>证据边界：本机无 adb、无 GPU。这里只能证明「提交里带的数是对的、请的通道是对的」，
 * 证明不了耳机里左半边听起来真偏左。
 */
public final class SpatialPanCueWiringTest {

    private static final int TONE = CueRequest.CHANNEL_TONE;
    private static final int SPEECH = CueRequest.CHANNEL_SPEECH;

    /** ① 最左列恰为 -1、最右列恰为 +1（取满摆幅，不是近似），中间列严格落在两端之间。 */
    @Test
    public void hintPanSwingsFullScaleAtBothEdges() {
        assertEquals(-1f, Match3LiveCuePolicy.hintPanOf(0, 0, 8, 8), 0f);
        assertEquals(-1f, Match3LiveCuePolicy.hintPanOf(7, 0, 8, 8), 0f);
        assertEquals(1f, Match3LiveCuePolicy.hintPanOf(0, 7, 8, 8), 0f);
        assertEquals(1f, Match3LiveCuePolicy.hintPanOf(5, 7, 8, 8), 0f);
        for (int col = 1; col <= 6; col++) {
            float pan = Match3LiveCuePolicy.hintPanOf(0, col, 8, 8);
            assertTrue("中间列不许取满端点: col=" + col, pan > -1f && pan < 1f);
        }
        // 从最左到最右单调变正：左边的格子不许比右边的格子听起来更靠右。
        float previous = -2f;
        for (int col = 0; col < 8; col++) {
            float pan = Match3LiveCuePolicy.hintPanOf(0, col, 8, 8);
            assertTrue("col=" + col + " 处 pan 必须随列号递增", pan > previous);
            previous = pan;
        }
    }

    /** ② 盘面尺寸不可用（零／负／单列盘）时 pan 回 NaN：宁可不出方位，也不猜一个居中。 */
    @Test
    public void hintPanIsNaNWhenBoardSizeUnavailable() {
        assertTrue(Float.isNaN(Match3LiveCuePolicy.hintPanOf(0, 0, 0, 8)));   // 无行
        assertTrue(Float.isNaN(Match3LiveCuePolicy.hintPanOf(0, 0, -1, 8)));  // 负行数
        assertTrue(Float.isNaN(Match3LiveCuePolicy.hintPanOf(0, 0, 8, 0)));   // 无列
        assertTrue(Float.isNaN(Match3LiveCuePolicy.hintPanOf(0, 0, 8, -3)));  // 负列数
        assertTrue(Float.isNaN(Match3LiveCuePolicy.hintPanOf(0, 0, 8, 1)));   // 单列盘无从分左右
        assertTrue(Float.isNaN(Match3LiveCuePolicy.hintPanOf(3, 2, 0, 0)));   // 行列同时不可用
    }

    /** ③ 列越界时 pan 回 NaN，而不是被夹成端点：越界读数本身无从推断左右。 */
    @Test
    public void hintPanIsNaNWhenColumnOutOfBoard() {
        assertTrue(Float.isNaN(Match3LiveCuePolicy.hintPanOf(0, -1, 8, 8)));
        assertTrue(Float.isNaN(Match3LiveCuePolicy.hintPanOf(0, 8, 8, 8)));
        assertTrue(Float.isNaN(Match3LiveCuePolicy.hintPanOf(4, 99, 8, 8)));
        assertTrue(Float.isNaN(Match3LiveCuePolicy.hintPanOf(0, Integer.MIN_VALUE, 8, 8)));
    }

    /**
     * ③ 的另一半：行数不参与左右映射（{@code BoardPan} 的既定设计，且该文件本轮禁改），
     * 所以「行越界」不会、也不该翻转左右读数——这里把它锁成「行怎么变，左右都不动」，
     * 而不是假装它回 NaN。
     */
    @Test
    public void rowDimensionNeverFlipsTheLeftRightReading() {
        assertEquals(Match3LiveCuePolicy.hintPanOf(0, 1, 8, 8),
                Match3LiveCuePolicy.hintPanOf(7, 1, 8, 8), 0f);
        assertEquals(Match3LiveCuePolicy.hintPanOf(0, 1, 8, 8),
                Match3LiveCuePolicy.hintPanOf(99, 1, 8, 8), 0f);
        assertTrue(Match3LiveCuePolicy.hintPanOf(99, 1, 8, 8) < 0f); // 第 1 列永远是左边
    }

    /** ④ 玩家开着音效时，提示必须同时请「语音＋音效」两个位，一个都不许少。 */
    @Test
    public void hintChannelsAskSpeechAndToneTogether() {
        int asked = Match3LiveCuePolicy.hintChannelsForStored(TONE | SPEECH);
        assertEquals("必须恰为语音＋音效", TONE | SPEECH, asked);
        assertTrue((asked & TONE) != 0);
        assertTrue((asked & SPEECH) != 0);
        // 存储里多开的触觉／视觉位不许被这条提示顺手请上。
        int wide = Match3LiveCuePolicy.hintChannelsForStored(
                TONE | SPEECH | CueRequest.CHANNEL_HAPTIC | CueRequest.CHANNEL_VISUAL);
        assertEquals(TONE | SPEECH, wide);
    }

    /** ⑤ 策略／玩家拒绝音效的取值下，通道位必须退回只语音，不许硬塞音效。 */
    @Test
    public void hintChannelsFallBackToSpeechWhenToneRefused() {
        assertEquals(SPEECH, Match3LiveCuePolicy.hintChannelsForStored(SPEECH));
        assertEquals(SPEECH, Match3LiveCuePolicy.hintChannelsForStored(
                SPEECH | CueRequest.CHANNEL_HAPTIC | CueRequest.CHANNEL_VISUAL));
        assertEquals("全关时也只请语音，由派发闸门判 channels_disabled",
                SPEECH, Match3LiveCuePolicy.hintChannelsForStored(0));
        assertEquals("只开触觉时不许连带请音效",
                SPEECH, Match3LiveCuePolicy.hintChannelsForStored(CueRequest.CHANNEL_HAPTIC));
        for (int stored = 0; stored <= 15; stored++) {
            assertTrue("语音位恒在: stored=" + stored,
                    (Match3LiveCuePolicy.hintChannelsForStored(stored) & SPEECH) != 0);
            assertEquals("音效位只跟随存储开关: stored=" + stored,
                    (stored & TONE) != 0, (Match3LiveCuePolicy.hintChannelsForStored(stored) & TONE) != 0);
        }
    }

    /** ⑥ 同一格两次计算逐字相同：无随机、无状态、无「第二次就不一样」。 */
    @Test
    public void repeatedComputationIsBitIdentical() {
        for (int rows : new int[] {6, 8, 9}) {
            for (int cols : new int[] {5, 7, 8}) {
                for (int row = 0; row < rows; row++) {
                    for (int col = 0; col < cols; col++) {
                        int first = Float.floatToIntBits(
                                Match3LiveCuePolicy.hintPanOf(row, col, rows, cols));
                        int second = Float.floatToIntBits(
                                Match3LiveCuePolicy.hintPanOf(row, col, rows, cols));
                        assertEquals("同格两次 pan 必须逐比特相同", first, second);
                        assertEquals(Match3LiveCuePolicy.hintChannelsForStored(TONE | SPEECH),
                                Match3LiveCuePolicy.hintChannelsForStored(TONE | SPEECH));
                    }
                }
            }
        }
        // NaN 也必须稳定成 NaN，不许某次塌成 0。
        assertTrue(Float.isNaN(Match3LiveCuePolicy.hintPanOf(0, 0, 0, 0)));
        assertNotEquals(0f, Match3LiveCuePolicy.hintPanOf(0, 0, 0, 0), 0f);
    }

    /**
     * ⑦ 提交侧的 pan 与诊断读数 {@link BoardPan#readingOf} 对同一格方向一致：
     * 不许出现读数说左、声音说右。逐格扫过整块盘，符号与文本严格互证。
     */
    @Test
    public void panAndDiagnosticReadingNeverDisagreeOnDirection() {
        for (int cols : new int[] {6, 7, 8}) {
            for (int row = 0; row < 4; row++) {
                for (int col = 0; col < cols; col++) {
                    float pan = Match3LiveCuePolicy.hintPanOf(row, col, 8, cols);
                    String reading = BoardPan.readingOf(row, col, 8, cols);
                    if (Float.isNaN(pan)) {
                        assertEquals(BoardPan.UNKNOWN_READING, reading);
                        continue;
                    }
                    boolean readsLeft = reading.startsWith("-");
                    assertEquals("列 " + col + "/" + cols + " 的符号两边必须一致: pan="
                            + pan + " reading=" + reading, pan < 0f, readsLeft);
                    assertEquals("同一格必须是同一套映射出来的数: col=" + col,
                            String.format(java.util.Locale.US, "%.3f", pan), reading);
                }
            }
        }
        // 端点直读：最左列 -1.000、最右列 1.000，与提交的 pan 同号同值。
        assertEquals(-1f, Match3LiveCuePolicy.hintPanOf(0, 0, 8, 8), 0f);
        assertEquals("-1.000", BoardPan.readingOf(0, 0, 8, 8));
        assertEquals(1f, Match3LiveCuePolicy.hintPanOf(0, 7, 8, 8), 0f);
        assertEquals("1.000", BoardPan.readingOf(0, 7, 8, 8));
    }

    /**
     * ⑧ 提交侧取的 pan 走的是纯函数，不是就地硬编码。
     *
     * <p>{@code Match3LiveService} 是 Android Service，JVM 里实例化不了，所以这条按代码路径锁：
     * 读源文件、只看交换提示那一颗提交，断言它调 {@code hintPanOf} 且不再出现字面 NaN 槽。
     */
    @Test
    public void submitTakesPanFromThePureFunction() throws Exception {
        String source = readServiceSource();
        String submit = hintSubmitBlock(source);
        assertTrue("提交必须调纯函数取 pan，实测片段:\n" + submit,
                submit.contains("Match3LiveCuePolicy.hintPanOf("));
        assertTrue("pan 槽不许再填字面 NaN", !submit.contains("Float.NaN, Float.NaN, 0, -1,"));
        assertTrue("通道位必须问策略，不许在 Service 里自己算",
                submit.contains("hintRequestedChannels()"));
        assertFalse("不许把 pan 硬编码成常量", submit.matches("(?s).*?,\\s*0f,\\s*Float\\.NaN.*"));
        // 入参口径与诊断读数同源：同一个 swap、同一对 rows(s)/cols(s)。
        assertTrue("pan 入参必须与读数同套: " + submit,
                submit.contains("hint.swap.fromRow") && submit.contains("hint.swap.fromCol")
                        && submit.contains("rows(s)") && submit.contains("cols(s)"));
    }

    /** 附加锁：pan 不可用时经 {@code CueRequest} 收边后仍是 NaN，不会在中途被换算成「正中间」。 */
    @Test
    public void unavailablePanStaysNaNThroughTheRequest() {
        CueRequest unavailable = hintRequest(Float.NaN);
        assertTrue(Float.isNaN(unavailable.pan));
        assertFalse(unavailable.hasPan());

        CueRequest leftmost = hintRequest(Match3LiveCuePolicy.hintPanOf(0, 0, 8, 8));
        assertEquals(-1f, leftmost.pan, 0f);
        assertTrue(leftmost.hasPan());
        assertEquals(1f, hintRequest(Match3LiveCuePolicy.hintPanOf(0, 7, 8, 8)).pan, 0f);
        // 越界值由 CueRequest 自己收边到 [-1,1]，本侧不夹两次。
        assertEquals(1f, hintRequest(7.5f).pan, 0f);
        assertEquals(-1f, hintRequest(-7.5f).pan, 0f);
    }

    /** 附加锁：泛型盘面上「左半盘为负、右半盘为正」这条玩家能听出来的直觉不许退级。 */
    @Test
    public void leftHalfIsAlwaysNegativeAndRightHalfPositive() {
        for (int cols : new int[] {4, 5, 6, 7, 8, 9}) {
            int mid = cols / 2;
            for (int col = 0; col < mid; col++) {
                assertTrue("cols=" + cols + " 第 " + col + " 列必须在左半",
                        Match3LiveCuePolicy.hintPanOf(0, col, 8, cols) < 0f);
            }
            for (int col = (cols + 1) / 2; col < cols; col++) {
                assertTrue("cols=" + cols + " 第 " + col + " 列必须在右半",
                        Match3LiveCuePolicy.hintPanOf(0, col, 8, cols) > 0f);
            }
        }
    }

    private static CueRequest hintRequest(float pan) {
        return new CueRequest("m3live", "m3live:hint:1", "m3live:hint:1", "消消乐交换提示",
                CueRequest.Category.SYSTEM, 70, 0L, Long.MAX_VALUE,
                Match3LiveCuePolicy.hintChannelsForStored(TONE | SPEECH), 0, 0, 0, "左上角交换",
                pan, Float.NaN, 0, -1, () -> true);
    }

    private static String readServiceSource() throws Exception {
        Path candidate = Path.of("src/main/java/com/openkhub/sensefield/Match3LiveService.java");
        if (!Files.isReadable(candidate)) {
            candidate = Path.of("app/src/main/java/com/openkhub/sensefield/Match3LiveService.java");
        }
        assertTrue("单测工作目录里找不到被测源文件: " + candidate.toAbsolutePath(),
                Files.isReadable(candidate));
        return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
    }

    /** 只取「消消乐交换提示」那一颗提交的语句，避免被同类里其它 CueRequest 蒙过去。 */
    private static String hintSubmitBlock(String source) {
        int key = source.indexOf("\"消消乐交换提示\"");
        assertTrue("源文件里找不到交换提示那颗提交", key >= 0);
        int start = source.lastIndexOf("dispatcher.submit(", key);
        assertTrue("交换提示不在 dispatcher.submit(...) 里", start >= 0);
        int end = source.indexOf("if (result.audioQueued()", key);
        assertTrue("找不到那颗提交的结尾", end > key);
        return source.substring(start, end);
    }
}
