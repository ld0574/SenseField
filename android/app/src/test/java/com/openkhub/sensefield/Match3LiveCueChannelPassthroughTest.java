package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * 书 24 的合同锁：{@link Match3LiveCuePolicy} 交付侧闸门读数不再把通道位回收成语音。
 *
 * <p>被测合同（与 {@code Match3LiveCuePolicyTest} 里改写过的旧合同同为一条）：
 * 语音位恒在，其余位原样跟随存储设置。掩码若被装回来，本类至少有一条断言变红。
 *
 * <p>边界声明：这里全是 JVM 单测，证的是通道位的裁决路径；
 * 「玩家到底听没听见方位音、触觉兜底感不感觉得到」属感官验收，不在本类能力范围内。
 */
public final class Match3LiveCueChannelPassthroughTest {

    private static final int SPEECH = CueRequest.CHANNEL_SPEECH;
    private static final int TONE = CueRequest.CHANNEL_TONE;
    private static final int HAPTIC = CueRequest.CHANNEL_HAPTIC;
    private static final int VISUAL = CueRequest.CHANNEL_VISUAL;

    /** 可配置的「存储设置」替身：{@code channels} 就是玩家存下来的通道位。 */
    private static final class StoredPolicy implements CueDispatcher.Policy {
        int channels;

        StoredPolicy(int channels) { this.channels = channels; }

        @Override public boolean categoryEnabled(CueRequest.Category category) { return true; }
        @Override public int enabledChannels() { return channels; }
        @Override public int enabledChannels(CueRequest.Category category) { return channels; }
        @Override public long dedupeWindowMs(CueRequest.Category category) { return 1200; }
    }

    private static final class RecordingRenderer implements CueDispatcher.Renderer {
        int tones;
        int haptics;
        int speech;

        @Override public boolean playTone(CueRequest request,
                                          CueDispatcher.PlaybackCallback callback) {
            tones++;
            return true;
        }
        @Override public boolean vibrate(CueRequest request) {
            haptics++;
            return true;
        }
        @Override public boolean speak(CueRequest request, boolean interrupt,
                                       CueDispatcher.PlaybackCallback callback) {
            speech++;
            callback.onStarted(request.createdAtMs);
            return true;
        }
        @Override public void stopSpeech() { }
    }

    private static final class SilentListener implements CueDispatcher.Listener {
        @Override public void onDispatch(CueRequest request,
                                         CueDispatcher.DispatchResult result) { }
        @Override public void onPlayback(CueRequest request, String channel,
                                         long atMs, String result) { }
    }

    private static Match3LiveCuePolicy policyOf(int storedChannels) {
        return new Match3LiveCuePolicy(new StoredPolicy(storedChannels));
    }

    // ① 存储开着音效位 ⇒ enabledChannels() 含音效位（掩码在时这里读回 2，必红）。
    @Test public void storedSoundEffectBitReachesEnabledChannels() {
        Match3LiveCuePolicy policy = policyOf(TONE | SPEECH);
        assertEquals(TONE | SPEECH, policy.enabledChannels());
        assertTrue("音效位必须透过闸门", (policy.enabledChannels() & TONE) != 0);
    }

    // ①+④ 带 Category 的重载同样放行音效位，不许一个放一个掩。
    @Test public void storedSoundEffectBitReachesEnabledChannelsForCategory() {
        Match3LiveCuePolicy policy = policyOf(TONE | SPEECH);
        assertEquals(TONE | SPEECH, policy.enabledChannels(CueRequest.Category.SYSTEM));
        assertTrue((policy.enabledChannels(CueRequest.Category.SYSTEM) & TONE) != 0);
    }

    // ② 存储关着音效位 ⇒ 不含音效位：拆掩码不等于放开一切，本类只补语音位。
    @Test public void clearedStoredSoundEffectBitIsNotReAdded() {
        Match3LiveCuePolicy policy = policyOf(SPEECH);
        assertEquals(SPEECH, policy.enabledChannels());
        assertEquals(SPEECH, policy.enabledChannels(CueRequest.Category.SYSTEM));
        assertEquals(0, policy.enabledChannels() & TONE);
    }

    // ③ 边界「存储把语音也关掉」：语音位仍被显式补上。
    @Test public void speechBitSurvivesEvenWhenStoredSpeechIsOff() {
        Match3LiveCuePolicy policy = policyOf(TONE | HAPTIC);
        assertEquals(TONE | SPEECH | HAPTIC, policy.enabledChannels());
        assertEquals(TONE | SPEECH | HAPTIC, policy.enabledChannels(CueRequest.Category.SYSTEM));
        assertTrue("语音位恒在", (policy.enabledChannels() & SPEECH) != 0);
    }

    // ③ 全量口径：16 种存储读数下语音位都在，且其余位逐位跟随存储。
    @Test public void speechBitIsPresentForEveryStoredReading() {
        for (int stored = 0; stored <= 15; stored++) {
            Match3LiveCuePolicy policy = policyOf(stored);
            assertEquals("stored=" + stored, stored | SPEECH, policy.enabledChannels());
            assertEquals("stored=" + stored, stored | SPEECH,
                    policy.enabledChannels(CueRequest.Category.SYSTEM));
        }
    }

    // ⑤ 触觉位在存储开启时透得过去（嘈杂时的触觉兜底靠这一位）。
    @Test public void storedHapticBitPassesThroughTheGate() {
        Match3LiveCuePolicy policy = policyOf(HAPTIC);
        assertEquals(HAPTIC | SPEECH, policy.enabledChannels());
        assertTrue((policy.enabledChannels(CueRequest.Category.SYSTEM) & HAPTIC) != 0);
    }

    // ⑥ 存储全 0：不抛异常，退回「只有语音」。
    @Test public void zeroStoredChannelsFallsBackToSpeechOnlyWithoutThrowing() {
        Match3LiveCuePolicy policy = policyOf(0);
        assertEquals(SPEECH, policy.enabledChannels());
        assertEquals(SPEECH, policy.enabledChannels(CueRequest.Category.SYSTEM));
        assertEquals(SPEECH, policy.hintRequestedChannels());
    }

    // ④ 无参版与带 Category 版对同一份存储读数口径一致，逐分类核对。
    @Test public void bothOverloadsReportTheSameChannelsForOneStoredReading() {
        for (int stored : new int[]{0, TONE, SPEECH, HAPTIC, VISUAL, TONE | HAPTIC,
                TONE | SPEECH | HAPTIC | VISUAL}) {
            Match3LiveCuePolicy policy = policyOf(stored);
            for (CueRequest.Category category : CueRequest.Category.values()) {
                assertEquals("stored=" + stored + " category=" + category,
                        policy.enabledChannels(), policy.enabledChannels(category));
            }
        }
    }

    // ⑦ 提交侧与闸门侧对同一份存储读数不互相矛盾：请到的位一定被闸门放行（本次要闭合的那对）。
    @Test public void submitSideRequestIsAlwaysSatisfiedByTheGate() {
        for (int stored = 0; stored <= 15; stored++) {
            StoredPolicy settings = new StoredPolicy(stored);
            Match3LiveCuePolicy policy = new Match3LiveCuePolicy(settings);
            int requested = policy.hintRequestedChannels();
            int granted = policy.enabledChannels(CueRequest.Category.SYSTEM);
            assertEquals("stored=" + stored + " 请到的位必须被放行", requested, requested & granted);
            assertTrue("提交侧语音位恒在", (requested & SPEECH) != 0);
            assertTrue("闸门侧语音位恒在", (granted & SPEECH) != 0);
        }
    }

    // 端到端：闸门确实被 CueDispatcher 用来裁通道（书 24 任务 0 前提②的复现）。
    @Test public void dispatcherGrantsSoundEffectWhenPlayerKeptItOn() {
        Match3LiveCuePolicy policy = policyOf(TONE | SPEECH);
        RecordingRenderer renderer = new RecordingRenderer();
        CueDispatcher dispatcher = new CueDispatcher(renderer, policy, new SilentListener(),
                () -> 100);
        CueDispatcher.DispatchResult result = dispatcher.submit(request(TONE | SPEECH, 70));

        assertEquals("ACCEPTED", result.outcome);
        assertEquals(TONE | SPEECH, result.acceptedChannels);
        assertEquals(1, renderer.tones);
        assertEquals(1, renderer.speech);
    }

    // 端到端：触觉兜底在存储开触觉位时真的走得到 renderer（掩码在时这里恒为 0）。
    @Test public void dispatcherReachesHapticRendererWhenPlayerKeptItOn() {
        Match3LiveCuePolicy policy = policyOf(HAPTIC | SPEECH);
        RecordingRenderer renderer = new RecordingRenderer();
        CueDispatcher dispatcher = new CueDispatcher(renderer, policy, new SilentListener(),
                () -> 100);
        CueDispatcher.DispatchResult result = dispatcher.submit(request(HAPTIC | SPEECH, 70));

        assertEquals("ACCEPTED", result.outcome);
        assertEquals(HAPTIC | SPEECH, result.acceptedChannels);
        assertEquals(1, renderer.haptics);
    }

    // ⑧ 类注释不再声明 speech only，也不许留回收掩码的字面形（防退级复扫）。
    @Test public void classCommentNoLongerDeclaresSpeechOnly() throws IOException {
        String source = policySource();
        // 注释是按 Javadoc 换行排版的，先把星号与空白折叠成单空格再判词面，免得被折行骗过去。
        String prose = source.toLowerCase().replace('*', ' ').replaceAll("\\s+", " ");
        assertFalse("类注释仍在声明 speech only", prose.contains("speech only"));
        assertTrue("类注释应声明新合同", prose.contains("always granted"));
        // 掩码的字面形用拼接构造，避免本测试文件自身被同一把尺子扫到。
        String maskShape = "enabledChannels" + "() & ";
        assertFalse("交付侧闸门里仍留有回收掩码", source.contains(maskShape));
    }

    /** 从测试 JVM 的工作目录向上找被测源文件，只按模块相对路径找，找不到即失败（不许静默跳过）。 */
    private static String policySource() throws IOException {
        Path relative = Paths.get("src", "main", "java", "com", "openkhub", "sensefield",
                "Match3LiveCuePolicy.java");
        Path moduleForm = Paths.get("android", "app").resolve(relative);
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up <= 5 && dir != null; up++) {
            for (Path candidate : new Path[]{dir.resolve(relative), dir.resolve(moduleForm)}) {
                if (Files.isRegularFile(candidate)) {
                    return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
                }
            }
            dir = dir.getParent();
        }
        throw new AssertionError("未找到 Match3LiveCuePolicy.java 源文件，无法核对类注释");
    }

    private static CueRequest request(int requestedChannels, int priority) {
        return new CueRequest("b24", "b24:" + requestedChannels, "b24:announce",
                "书24通道穿透", CueRequest.Category.SYSTEM, priority, 100, 1000,
                requestedChannels, 0, 0, 0, "棋局更新");
    }
}
