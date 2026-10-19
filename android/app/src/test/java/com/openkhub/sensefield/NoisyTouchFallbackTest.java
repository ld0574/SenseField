package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * 「环境太吵时关键提示改由触觉也带一份」这条接线的锁（任务书 20）。
 *
 * <p>被测面只有两处：{@link AudioContextLevel} 的迟滞档位判据，与 {@link CuePlayer} 新增的
 * 兜底判据 {@code needsTouchFallback(...)}／{@code acceptNoiseFloor(...)} 所在的这条链。
 * {@link VoiceActivityGate} 交出的噪声底也被钉住——它必须就是那个类自己判有话用的同一个量，
 * 不许有第二套平滑。
 *
 * <p>期望值全部写死（3／5／10 次连击、350／120 两条地板、0.70／1.00／1.00 三档增益），
 * 不拿被测函数自己的输出反推期望。
 *
 * <p>证据边界：本机无 adb、无 GPU、无声学设备。这里只证明「档位翻对了、兜底判据给对了、
 * 音量那条线一个数都没被乘进去」，证明不了真机上嘈杂处那一下振动玩家认不认得出来，
 * 也证明不了三档增益该是多少。音量这一半本活明确没接。
 */
public final class NoisyTouchFallbackTest {

    private static final float CALM = 60f;      // < QUIET_FLOOR 120
    private static final float MID = 200f;      // 两条地板之间
    private static final float LOUD = 900f;     // >= NOISY_FLOOR 350
    private static final CueRequest.Category[] ALL_CATEGORIES = CueRequest.Category.values();

    private static AudioContextLevel noisyContext() {
        AudioContextLevel context = AudioContextLevel.create();
        context.accept(LOUD);
        context.accept(LOUD);
        context.accept(LOUD);
        return context;
    }

    /** 读私有增益常量，用来把「三档表」和「音量出口」做成断言对照（不许靠注释）。 */
    private static float gainConstant(String name) throws Exception {
        Field field = AudioContextLevel.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getFloat(null);
    }

    private static String cuePlayerSource() throws Exception {
        Path candidate = Path.of("src/main/java/com/openkhub/sensefield/CuePlayer.java");
        if (!Files.isReadable(candidate)) {
            candidate = Path.of("app/src/main/java/com/openkhub/sensefield/CuePlayer.java");
        }
        assertTrue("单测工作目录里找不到被测源文件: " + candidate, Files.isReadable(candidate));
        return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
    }

    /** ① 连续三次高噪声底才进 NOISY；两次不算，被一次平静读数打断的两次更不算。 */
    @Test
    public void threeConsecutiveNoisyReadingsEnterNoisyAndTwoDoNot() {
        AudioContextLevel context = AudioContextLevel.create();

        // 两次不算。
        assertEquals(AudioContextLevel.Level.QUIET, context.accept(LOUD));
        assertEquals(AudioContextLevel.Level.QUIET, context.accept(LOUD));
        assertEquals(AudioContextLevel.Level.QUIET, context.level());

        // 第三次连击才翻档。
        assertEquals(AudioContextLevel.Level.NOISY, context.accept(LOUD));
        assertEquals(AudioContextLevel.Level.NOISY, context.level());

        // 连击被打断后必须从头数：LOUD、LOUD、CALM、LOUD 三次高读数也不许进 NOISY。
        AudioContextLevel broken = AudioContextLevel.create();
        broken.accept(LOUD);
        broken.accept(LOUD);
        broken.accept(CALM);
        assertEquals(AudioContextLevel.Level.QUIET, broken.accept(LOUD));
    }

    /** ② 退 NOISY 要五次平静连击，而且落点是 MODERATE，不是 QUIET。 */
    @Test
    public void leavingNoisyTakesFiveCalmReadingsAndLandsOnModerate() {
        AudioContextLevel context = noisyContext();
        assertEquals(AudioContextLevel.Level.NOISY, context.level());

        for (int i = 1; i <= 4; i++) {
            assertEquals("第 " + i + " 次平静读数还不许退档", AudioContextLevel.Level.NOISY,
                    context.accept(CALM));
        }
        assertEquals(AudioContextLevel.Level.MODERATE, context.accept(CALM));
        assertNotEquals("退 NOISY 决不许一步落回 QUIET", AudioContextLevel.Level.QUIET,
                context.level());
    }

    /** ② 续：落到 MODERATE 后还要另计十次平静才回 QUIET（总共不是「五次就完事」）。 */
    @Test
    public void moderateStillNeedsTenCalmReadingsBeforeQuiet() {
        AudioContextLevel context = noisyContext();
        for (int i = 0; i < 5; i++) context.accept(CALM);
        assertEquals(AudioContextLevel.Level.MODERATE, context.level());

        for (int i = 1; i <= 9; i++) {
            assertEquals("第 " + i + " 次还不该回 QUIET", AudioContextLevel.Level.MODERATE,
                    context.accept(CALM));
        }
        assertEquals(AudioContextLevel.Level.QUIET, context.accept(CALM));
    }

    /** ③ 两条地板之间的游走既不确认也不破连击：短暂游走不许翻档。 */
    @Test
    public void readingsBetweenTheTwoFloorsNeverFlipALevel() {
        AudioContextLevel fromQuiet = AudioContextLevel.create();
        for (int i = 0; i < 20; i++) {
            assertEquals("地板之间的游走永远不该把 QUIET 抬进 NOISY", AudioContextLevel.Level.QUIET,
                    fromQuiet.accept(MID));
        }

        // 已经 NOISY 时，地板之间的读数既不算平静连击、也不清高噪声连击。
        AudioContextLevel fromNoisy = noisyContext();
        for (int i = 0; i < 12; i++) {
            assertEquals("地板之间不许把 NOISY 退掉", AudioContextLevel.Level.NOISY,
                    fromNoisy.accept(MID));
        }

        // 夹在中间的一枪不算断：LOUD、LOUD、MID、LOUD 仍然进 NOISY。
        AudioContextLevel stitched = AudioContextLevel.create();
        stitched.accept(LOUD);
        stitched.accept(LOUD);
        assertEquals(AudioContextLevel.Level.QUIET, stitched.accept(MID));
        assertEquals(AudioContextLevel.Level.NOISY, stitched.accept(LOUD));
    }

    /** ④ NaN、正负无穷、负数噪声底＝无信息：状态与上一步逐字相同。 */
    @Test
    public void unusableFloorsCarryNoInformationAndLeaveTheStepUntouched() {
        AudioContextLevel context = noisyContext();
        assertEquals(AudioContextLevel.Level.NOISY, context.level());

        for (float junk : new float[]{Float.NaN, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, -1f, -350f}) {
            AudioContextLevel.Decision before = context.decision();
            AudioContextLevel.Level level = context.accept(junk);
            AudioContextLevel.Decision after = context.decision();
            assertEquals("无信息读数 " + junk + " 不许改档位", AudioContextLevel.Level.NOISY, level);
            assertEquals("无信息读数 " + junk + " 不许改通道", before.channel, after.channel);
            assertEquals("无信息读数 " + junk + " 不许改增益", before.gain, after.gain, 0f);
        }

        // QUIET 侧同样不许被无信息读数推进连击：喂够多 NaN 之后仍然留在 QUIET。
        AudioContextLevel quiet = AudioContextLevel.create();
        for (int i = 0; i < 50; i++) quiet.accept(Float.NaN);
        assertEquals(AudioContextLevel.Level.QUIET, quiet.level());
    }

    /** ⑤ NOISY 时通道恰为 SPEECH_PLUS_TOUCH；QUIET 与 MODERATE 时都是 SPEECH。 */
    @Test
    public void onlyNoisyAsksTheTouchChannel() {
        AudioContextLevel quiet = AudioContextLevel.create();
        assertEquals(AudioContextLevel.Channel.SPEECH, quiet.decision().channel);

        AudioContextLevel moderate = noisyContext();
        for (int i = 0; i < 5; i++) moderate.accept(CALM);
        assertEquals(AudioContextLevel.Level.MODERATE, moderate.level());
        assertEquals(AudioContextLevel.Channel.SPEECH, moderate.decision().channel);

        AudioContextLevel noisy = noisyContext();
        assertEquals(AudioContextLevel.Level.NOISY, noisy.level());
        assertEquals(AudioContextLevel.Channel.SPEECH_PLUS_TOUCH, noisy.decision().channel);
    }

    /** ⑥ 兜底判据是纯函数：只有 NOISY 且非闲聊类才加一次触觉。 */
    @Test
    public void fallbackPredicateAddsTouchOnlyForNoisyCriticalCues() {
        assertFalse("闲聊类在任何档位都不许加一次触觉",
                CuePlayer.needsTouchFallback(AudioContextLevel.Level.NOISY,
                        CueRequest.Category.ASSISTANT));
        for (CueRequest.Category category : ALL_CATEGORIES) {
            for (AudioContextLevel.Level level : AudioContextLevel.Level.values()) {
                boolean expected = level == AudioContextLevel.Level.NOISY
                        && category != CueRequest.Category.ASSISTANT;
                assertEquals("档位 " + level + " 分类 " + category, expected,
                        CuePlayer.needsTouchFallback(level, category));
            }
        }
        // 关键提示集：画面事件出来的那六档在 NOISY 时都必须带触觉，缺一档就没人兜底。
        for (CueRequest.Category category : ALL_CATEGORIES) {
            if (category == CueRequest.Category.ASSISTANT) continue;
            assertTrue("关键提示漏了兜底: " + category,
                    CuePlayer.needsTouchFallback(AudioContextLevel.Level.NOISY, category));
        }
    }

    /** ⑥ 续：判据必须纯——同输入同输出，且不许动任何被测状态。 */
    @Test
    public void fallbackPredicateIsDeterministicAndSideEffectFree() {
        AudioContextLevel context = AudioContextLevel.create();
        AudioContextLevel.Level level = context.level();

        boolean first = CuePlayer.needsTouchFallback(level, CueRequest.Category.DANGER);
        boolean second = CuePlayer.needsTouchFallback(level, CueRequest.Category.DANGER);
        assertEquals(first, second);
        assertFalse(first);
        // 判据本身不许把状态机推进一格。
        assertEquals(AudioContextLevel.Level.QUIET, context.level());
        assertEquals(AudioContextLevel.Channel.SPEECH, context.decision().channel);
    }

    /** 兜底判据与状态机的通道决定必须同进同退，否则接线处两套尺子会各说各话。 */
    @Test
    public void fallbackPredicateAgreesWithTheChannelDecision() {
        AudioContextLevel quiet = AudioContextLevel.create();
        assertAgreesWithChannel(quiet);

        AudioContextLevel noisy = noisyContext();
        assertAgreesWithChannel(noisy);

        AudioContextLevel moderate = noisyContext();
        for (int i = 0; i < 5; i++) moderate.accept(CALM);
        assertAgreesWithChannel(moderate);
    }

    private static void assertAgreesWithChannel(AudioContextLevel context) {
        AudioContextLevel.Level level = context.level();
        boolean channelAsksTouch =
                context.decision().channel == AudioContextLevel.Channel.SPEECH_PLUS_TOUCH;
        for (CueRequest.Category category : ALL_CATEGORIES) {
            assertEquals("档位 " + level + " 分类 " + category + " 两套尺子必须一致",
                    channelAsksTouch && category != CueRequest.Category.ASSISTANT,
                    CuePlayer.needsTouchFallback(level, category));
        }
    }

    /**
     * ⑦ 音量侧没有被这条链碰到：三档增益常量原封不动，音量出口仍是「玩家设置／100」的纯函数，
     * 全文件没有任何 {@code decision().gain} 读数，三处 {@code track.setVolume(volume())} 逐字仍在。
     * 谁把增益乘进音量，这一条当场变红。
     */
    @Test
    public void gainTableIsNeverAppliedToTheVolumeOutlet() throws Exception {
        // 增益表＝本活明确没接的那一半，数值必须还是这三档。
        assertEquals(0.70f, gainConstant("QUIET_GAIN"), 0f);
        assertEquals(1.00f, gainConstant("MODERATE_GAIN"), 0f);
        assertEquals(1.00f, gainConstant("NOISY_GAIN"), 0f);

        String source = cuePlayerSource();
        Matcher volumeMethod = Pattern.compile("private float volume\\(\\)\\s*\\{[^}]*\\}")
                .matcher(source);
        assertTrue("CuePlayer 里找不到 volume() 这个方法本体", volumeMethod.find());
        String body = volumeMethod.group();
        assertTrue("音量出口必须仍然只读玩家自己的设置: " + body,
                body.contains("getInt(\"volume\", 45)"));
        assertTrue("音量出口必须仍然是 /100f 的夹取，不许有乘子: " + body,
                body.contains("Math.max(0f, Math.min(1f, volumePercent / 100f))"));
        assertFalse("音量本体里不许出现任何增益字样: " + body,
                Pattern.compile("(?i)gain|audiocontext|decision").matcher(body).find());

        int setVolumeCount = source.split("track\\.setVolume\\(volume\\(\\)\\);", -1).length - 1;
        assertEquals("三处 track.setVolume(volume()) 必须逐字仍在，不多不少", 3, setVolumeCount);
        assertFalse("播放路径上不许读 decision().gain", source.contains(".gain"));
    }

    /**
     * 兜底判据的返回类型必须只有「要不要振」这一个布尔位——结构上就带不走增益。
     * 这条与上一条合起来才叫「把增益常量与音量出口做成断言对照」，不是写句注释。
     */
    @Test
    public void fallbackSeamOutputsNothingButABoolean() throws Exception {
        assertEquals(boolean.class, CuePlayer.class
                .getDeclaredMethod("needsTouchFallback", AudioContextLevel.Level.class,
                        CueRequest.Category.class).getReturnType());
        assertEquals(void.class, CuePlayer.class
                .getDeclaredMethod("acceptNoiseFloor", double.class).getReturnType());
    }

    /** 感知侧那个只读出口交出的就是判据用的同一个量：初值 80，安静时按同一条 EMA 走，不含第二套平滑。 */
    @Test
    public void noiseFloorExitIsTheSameQuantityTheSpeechGateJudgesOn() {
        VoiceActivityGate gate = new VoiceActivityGate(new VoiceActivityGate.Output() {
            @Override public void started() { }
            @Override public void pcm(short[] samples) { }
            @Override public void ended() { }
        });
        assertEquals(80d, gate.noiseFloor(), 0d);

        short[] silence = new short[160];
        for (int i = 0; i < 10; i++) gate.accept(silence, false);
        assertEquals("只读出口必须等于 noise*0.98 连乘十次，不许另起平滑",
                80d * Math.pow(0.98d, 10d), gate.noiseFloor(), 1e-9d);

        // 高于地板的那一枪根本不更新 EMA，出口也必须跟着不动。
        double before = gate.noiseFloor();
        short[] loud = new short[160];
        java.util.Arrays.fill(loud, (short) 2000);
        for (int i = 0; i < 5; i++) gate.accept(loud, true);
        assertEquals("高于地板时 EMA 不动，出口也不许动", before, gate.noiseFloor(), 0d);
    }

    /** reset() 不许偷偷把噪声底抹成初值——兜底档位靠的是累计 EMA，抹平会当场假静。 */
    @Test
    public void gateResetKeepsTheAccumulatedFloor() {
        VoiceActivityGate gate = new VoiceActivityGate(new VoiceActivityGate.Output() {
            @Override public void started() { }
            @Override public void pcm(short[] samples) { }
            @Override public void ended() { }
        });
        short[] silence = new short[160];
        for (int i = 0; i < 20; i++) gate.accept(silence, false);
        double lowered = gate.noiseFloor();
        assertTrue("安静二十帧后噪声底应已下降且仍为正: " + lowered, lowered < 80d && lowered > 0d);
        gate.reset();
        assertEquals(lowered, gate.noiseFloor(), 0d);
    }
}
