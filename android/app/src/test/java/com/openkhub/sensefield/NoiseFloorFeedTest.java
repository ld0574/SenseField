package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * 书 20R 的接线锁：VoiceActivityGate 每次重算自适应噪声底，那条搬运边必须把同一个读数送出去，
 * 一路推进 CuePlayer 那侧的分类器（AudioContextLevel），直到非助手类提示被判走触觉路。
 *
 * <p>钉的是行为，不是方法名：断言的输入是喂进现成 gate.accept 的 PCM 帧，期望值全部按既有
 * EMA（.98/.02）与既有地板（350／120、连击 3／5／10）预先手算写死，不拿被测函数自己的输出反推。
 * 把 VoiceActivityGate.accept 里那行搬运注释掉，本类当场变红——那才是「边真的在喂数」。
 * 现成的反射取方法名那条（NoisyTouchFallbackTest#fallbackSeamOutputsNothingButABoolean）
 * 在边缺失时照样绿，不能拿它当这条边的证据。
 *
 * <p>触觉路的判据与生产 carryTouchFallbackIfNoisy 用同一套尺子：
 * needsTouchFallback(level, category) 且 decision().channel == SPEECH_PLUS_TOUCH。
 *
 * <p>书 32 补的链尾报警器（④⑤）钉的是另一件事：上面三条锁的是「闸门把读数搬出去」这一段，
 * 最后一跳——CaptureService 把同一个读数交给 CuePlayer 入口——过去没有任何用例引用过，
 * 谁删掉那一行全套测试照样绿。④⑤ 改成读 CaptureService.java 这一个文件的源码文本再断言，
 * 先把注释抹平再匹配，所以「把那行注释掉」与「把那行删掉」在这把尺子下等价，都当场变红；
 * 同样不用反射取方法名或取返回类型（边缺失时那种断言照样绿）。
 *
 * <p>证据边界：本机无 adb、无声学设备。本类只证 JVM 侧「读数搬得出去、档位翻得对、
 * 兜底判据给对」，证不了真机上嘈杂处那一下振动玩家认不认得出来。
 */
public final class NoiseFloorFeedTest {

    private static final int RAMP_FRAMES = 300;   // 地板 80→≈349.4（350−270·0.98^300≈349.37）
    private static final int PUSH_FRAMES = 8;     // 350→400：第一帧 350.4，连续 8 帧 ≥350
    private static final int QUIET_FRAMES = 100;

    private static VoiceActivityGate.Output silentOutput() {
        return new VoiceActivityGate.Output() {
            @Override public void started() { }
            @Override public void pcm(short[] samples) { }
            @Override public void ended() { }
        };
    }

    private static short[] frame(int amplitude) {
        short[] samples = new short[160];
        Arrays.fill(samples, (short) amplitude);
        return samples;
    }

    /** 生产同款接线：搬运回调读的必须是 gate 自己的只读出口 noiseFloor()，不许另取 rms。 */
    private static List<Double> attachTransport(VoiceActivityGate gate) {
        List<Double> readings = new ArrayList<>();
        gate.observeNoiseFloor(() -> readings.add(gate.noiseFloor()));
        return readings;
    }

    /** 生产 carryTouchFallbackIfNoisy 的两把尺子，逐字同构。 */
    private static boolean touchPathRequired(AudioContextLevel context,
                                             CueRequest.Category category) {
        return CuePlayer.needsTouchFallback(context.level(), category)
                && context.decision().channel == AudioContextLevel.Channel.SPEECH_PLUS_TOUCH;
    }

    /** ① 每次重算都搬运一次：帧数＝读数条数，首条仍低于平静地板 120，末条与 gate 出口逐字相等。 */
    @Test public void everyRecomputedFloorIsCarriedOutOfTheGate() {
        VoiceActivityGate gate = new VoiceActivityGate(silentOutput());
        List<Double> readings = attachTransport(gate);
        for (int i = 0; i < RAMP_FRAMES; i++) gate.accept(frame(350), false);
        for (int i = 0; i < PUSH_FRAMES; i++) gate.accept(frame(400), false);

        assertEquals("搬运条数必须等于地板重算帧数（边被剪掉就是 0）",
                RAMP_FRAMES + PUSH_FRAMES, readings.size());
        assertTrue("首条读数应仍落在平静区: " + readings.get(0), readings.get(0) < 120d);
        assertEquals("末条搬运读数必须与只读出口逐字相等",
                gate.noiseFloor(), readings.get(readings.size() - 1), 0d);
        assertTrue("末条读数必须已越过 NOISY 地板 350: " + readings.get(readings.size() - 1),
                readings.get(readings.size() - 1) >= 350d);
    }

    /** ② 搬运出去的读数喂进分类器：连击 3 次 ≥350 才翻 NOISY，非助手类提示当场要走触觉路。 */
    @Test public void carriedNoisyReadingsFlipTheClassifierAndArmTheTouchPath() {
        VoiceActivityGate gate = new VoiceActivityGate(silentOutput());
        List<Double> readings = attachTransport(gate);
        AudioContextLevel context = AudioContextLevel.create();

        for (int i = 0; i < RAMP_FRAMES; i++) gate.accept(frame(350), false);
        for (int i = 0; i < PUSH_FRAMES; i++) {
            gate.accept(frame(400), false);
            context.accept(readings.get(readings.size() - 1).floatValue());
        }
        // 预演算：400 帧第一帧地板 350.4（连击 1），第二帧 350.8（连击 2），第三帧 351.1（连击 3 翻档）。
        assertEquals("搬运链末端档位必须是 NOISY（边被剪掉则永远停在 QUIET）",
                AudioContextLevel.Level.NOISY, context.level());
        for (CueRequest.Category category : CueRequest.Category.values()) {
            if (category == CueRequest.Category.ASSISTANT) {
                assertFalse("助手闲聊永远不许加振", touchPathRequired(context, category));
            } else {
                assertTrue("NOISY 时非助手类提示必须走触觉路: " + category,
                        touchPathRequired(context, category));
            }
        }
    }

    /** ③ 安静房间同样有搬运（否则边可能被做成「只在吵时响」的偷工版），但档位与触觉路都不许动。 */
    @Test public void quietRoomIsAlsoCarriedButNeverArmsTheTouchPath() {
        VoiceActivityGate gate = new VoiceActivityGate(silentOutput());
        List<Double> readings = attachTransport(gate);
        AudioContextLevel context = AudioContextLevel.create();
        for (int i = 0; i < QUIET_FRAMES; i++) {
            gate.accept(frame(10), false);
            context.accept(readings.get(readings.size() - 1).floatValue());
        }
        assertEquals(QUIET_FRAMES, readings.size());
        assertEquals(AudioContextLevel.Level.QUIET, context.level());
        for (CueRequest.Category category : CueRequest.Category.values()) {
            assertFalse("安静房间任何提示都不许多振一下: " + category,
                    touchPathRequired(context, category));
        }
    }

    // ---------- 链尾报警器（书 32）：只盯最后一跳，中段那三条用例的视野之外 ----------

    /** 扫描面＝这一个文件的字符级内容，全仓不在本条断言的视野里。 */
    private static final String CAPTURE_SERVICE_FILE =
            "src/main/java/com/openkhub/sensefield/CaptureService.java";

    /**
     * 被钉住的是那一跳的写法本身：null 守卫 + 把 floor 交给 CuePlayer 的入口。
     * 守卫与调用允许换行与加花括号，但守卫不许消失、方法名与实参不许改——把那行删掉或整行注释掉，
     * 本模式在剥掉注释的代码文本里就找不到，当场变红。
     */
    private static final Pattern LAST_HOP = Pattern.compile(
            "if\\s*\\(\\s*player\\s*!=\\s*null\\s*\\)\\s*\\{?\\s*"
                    + "player\\s*\\.\\s*acceptNoiseFloor\\s*\\(\\s*floor\\s*\\)\\s*;");

    /** 链尾所在的覆写本体：观察器回调 noiseFloor(double floor)。允许体内有一层花括号。 */
    private static final Pattern NOISE_FLOOR_CALLBACK = Pattern.compile(
            "void\\s+noiseFloor\\s*\\(\\s*double\\s+floor\\s*\\)\\s*\\{((?:[^{}]|\\{[^{}]*\\})*)\\}");

    /** 读回 CaptureService.java 并剥掉全部注释，得到「真正会被编译的文本」。 */
    private static String captureServiceCode() throws Exception {
        Path candidate = Path.of(CAPTURE_SERVICE_FILE);
        if (!Files.isReadable(candidate)) {
            candidate = Path.of("app/" + CAPTURE_SERVICE_FILE);
        }
        assertTrue("单测工作目录里找不到链尾所在的源文件: " + candidate, Files.isReadable(candidate));
        return withoutComments(new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8));
    }

    /**
     * 行注释与块注释都抹成空格，字符串与字符字面量里的斜杠不算注释。
     * 不抹注释的话，「把那一行注释掉」在文本里仍然留着一模一样的字，报警器就白装。
     */
    private static String withoutComments(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            char next = i + 1 < n ? source.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < n && source.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && next == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) i++;
                i += 2;
                code.append(' ');
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                code.append(c);
                i++;
                while (i < n) {
                    char t = source.charAt(i);
                    code.append(t);
                    i++;
                    if (t == '\\' && i < n) {
                        code.append(source.charAt(i));
                        i++;
                        continue;
                    }
                    if (t == quote || t == '\n') break;
                }
                continue;
            }
            code.append(c);
            i++;
        }
        return code.toString();
    }

    private static List<String> matchesOf(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) found.add(m.group());
        return found;
    }

    /**
     * ④ 最后一跳逐字在列、且全文件只此一处。今天谁把 `CaptureService.java` 里那句
     * {@code if (player != null) player.acceptNoiseFloor(floor);} 删掉或注释掉，
     * 中段那三条用例照样全绿——这条不会。
     */
    @Test public void lastHopHandsTheFloorToTheCuePlayerExactlyOnce() throws Exception {
        List<String> hops = matchesOf(LAST_HOP, captureServiceCode());
        assertEquals("链尾那一跳（null 守卫 + 把读数交给 CuePlayer 入口）必须在 "
                + CAPTURE_SERVICE_FILE + " 里恰好出现 1 处，实际 " + hops.size() + " 处: " + hops,
                1, hops.size());
    }

    /**
     * ⑤ 那一跳确实坐在观察器回调本体里：光「文件里有这行文本」还不够，
     * 挪进一个永不被调用的方法就又是一次静默失效。扫描面仍是同一个文件。
     */
    @Test public void lastHopSitsInsideTheNoiseFloorCallbackBody() throws Exception {
        String code = captureServiceCode();
        List<String> callbacks = matchesOf(NOISE_FLOOR_CALLBACK, code);
        assertEquals("noiseFloor(double floor) 这个覆写本体必须在 " + CAPTURE_SERVICE_FILE
                + " 里恰好出现 1 处，实际 " + callbacks.size() + " 处", 1, callbacks.size());
        String body = callbacks.get(0);
        assertTrue("观察器回调必须把同一个 floor 交给 CuePlayer 入口: " + body.trim(),
                LAST_HOP.matcher(body).find());
    }
}
