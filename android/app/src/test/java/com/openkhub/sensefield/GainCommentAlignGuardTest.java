package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.Test;

/**
 * 「增益这一半故意不接播放路」这句承诺的守卫（任务书 31）。
 *
 * <p>背景：{@link AudioContextLevel} 的 {@code Decision.gain} 在主源集里除自身外没有任何消费点，
 * 唯一被读的是 {@code decision().channel}（半边接了）；「不读 gain」这件事只写在注释里
 * （{@code CuePlayer} 的类注释、{@code volume()} 注释）。谁哪天顺手把 gain 乘进音量，
 * 门禁只会照旧剩那七条既存红，没人会知道。本类把这句注释变成一台会响的守卫：
 * <b>不改行为、不改数值、不新增功能、只锁代码路径</b>。
 *
 * <p><b>判据口径（源码文本尺子）</b>：命中只算「代码行」——扫描前先用 {@link #stripComments}
 * 把行注释（{@code //} 到行尾）与块注释／javadoc（{@code /* ... *}{@code /}，跨行时保留换行以维持
 * 行结构）剥掉，字符串字面量与字符字面量原样保留（所以串里的 {@code //} 不被误当注释）。
 * 剥完之后再数 {@code \.gain}，注释与 javadoc 里的「gain」提及一律不计。这条口径写在这里，
 * 不是留给读者心证。
 *
 * <p>期望值全部写死（0.70f／1.00f／1.00f、gain 消费 0 处、AudioContextLevelTest 含 gain 行基线 6 行），
 * 不从被测函数自己的输出反推。
 *
 * <p><b>证据边界</b>：本机无 adb、无声学设备。本类只证明「主源集没读 gain、音量出口仍是纯函数、
 * 三档常量逐字未变、被接的 channel 半边仍在」——证明不了「0.70 这一档玩家听不听得出来」，
 * 那属真机标定＋主观听感，本轮不碰。
 */
public final class GainCommentAlignGuardTest {

    /** 主源集里唯一允许出现 {@code \.gain} 的文件——它自己定义 gain 字段。 */
    private static final String AUDIO_CONTEXT_FILE = "AudioContextLevel.java";
    private static final String CUE_PLAYER_FILE = "CuePlayer.java";
    private static final String PKG_DIR = "com/openkhub/sensefield";

    /** 任务 0 当轮实测的基线：AudioContextLevelTest 里含 "gain" 的行数（:31 :60 :66 :104 :142 :158）。 */
    private static final int BASELINE_GAIN_TEST_LINES = 6;

    /**
     * 任务 0 当轮实测的接线拓扑：主源集里提到 AudioContextLevel 的文件白名单。
     * VoiceActivityGate 只在 javadoc 里提及；CuePlayer 是真实消费者。新增引用文件＝有人造绕过路。
     */
    private static final Set<String> KNOWN_AUDIO_CONTEXT_REFERENCERS =
            Set.of(AUDIO_CONTEXT_FILE, CUE_PLAYER_FILE, "VoiceActivityGate.java");

    /** 定位 app 模块根：优先「src/main/java」（cwd=app），其次「app/src/main/java」（cwd=repo）。 */
    private static Path appRoot() {
        Path direct = Path.of("src", "main", "java");
        if (Files.isDirectory(direct)) return Path.of(".");
        Path nested = Path.of("app", "src", "main", "java");
        if (Files.isDirectory(nested)) return Path.of("app");
        throw new IllegalStateException(
                "单测工作目录里找不到主源集，试了 src/main/java 与 app/src/main/java");
    }

    private static Path mainJavaRoot() {
        return appRoot().resolve(Path.of("src", "main", "java"));
    }

    private static Path mainSourceFile(String fileName) {
        return mainJavaRoot().resolve(PKG_DIR).resolve(fileName);
    }

    private static String readUtf8(Path path) throws IOException {
        assertTrue("找不到被测文件: " + path, Files.isReadable(path));
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** 主源集里全部 .java 文件（递归）。返回前做一次非空校验，防止路径写错导致「扫了个空气」。 */
    private static List<Path> allMainJavaFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(mainJavaRoot())) {
            walk.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(Files::isRegularFile)
                    .forEach(files::add);
        }
        assertFalse("主源集扫描到 0 个 .java——尺子的路径一定是坏了", files.isEmpty());
        return files;
    }

    /**
     * 剥注释尺子的实现：去掉行注释与块注释／javadoc，字符串与字符字面量原样保留。
     * 块注释跨行时保留其中的换行，行号结构不乱（便于人读红名时定位）。
     */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            boolean nextSlash = i + 1 < n && src.charAt(i + 1) == '/';
            boolean nextStar = i + 1 < n && src.charAt(i + 1) == '*';
            if (c == '/' && nextSlash) {
                i += 2;
                while (i < n && src.charAt(i) != '\n') i++;
                continue; // 换行留给下一轮原样写出
            }
            if (c == '/' && nextStar) {
                i += 2;
                while (i < n && !(src.charAt(i) == '*' && i + 1 < n && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') out.append('\n');
                    i++;
                }
                i += 2; // 吃掉 */
                out.append(' ');
                continue;
            }
            if (c == '"' && i + 2 < n && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                out.append("\"\"\"");
                i += 3;
                while (i < n) {
                    if (src.charAt(i) == '\\' && i + 1 < n) {
                        out.append(src.charAt(i)).append(src.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (src.charAt(i) == '"' && i + 2 < n
                            && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                        out.append("\"\"\"");
                        i += 3;
                        break;
                    }
                    out.append(src.charAt(i));
                    i++;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                char quote = c;
                out.append(c);
                i++;
                while (i < n && src.charAt(i) != quote) {
                    if (src.charAt(i) == '\\' && i + 1 < n) {
                        out.append(src.charAt(i)).append(src.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (src.charAt(i) == '\n') break; // 未闭合兜底
                    out.append(src.charAt(i));
                    i++;
                }
                if (i < n) {
                    out.append(src.charAt(i));
                    i++;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static int countMatches(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    /** ① 主源集里、除 AudioContextLevel.java 外，任何 .java 的代码行都不得出现 {@code \.gain} 消费。 */
    @Test
    public void noGainConsumptionOutsideAudioContextLevelAcrossMainSourceSet() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (Path file : allMainJavaFiles()) {
            if (file.getFileName().toString().equals(AUDIO_CONTEXT_FILE)) continue;
            scanned++;
            String code = stripComments(readUtf8(file));
            int hits = countMatches(code, "\\.gain");
            if (hits != 0) {
                offenders.add(file.getFileName() + " -> " + hits);
            }
        }
        assertTrue("扫描文件数过少，尺子可能失效: " + scanned, scanned >= 100);
        assertTrue("主源集里出现了 gain 消费（本承诺要求 0 处）: " + offenders, offenders.isEmpty());
    }

    /** 正样本：同一条剥注释尺子必须抓得到 AudioContextLevel.java 自己那处 gain 赋值，证明不是「尺子坏了读成 0」。 */
    @Test
    public void gainRulerActuallyDetectsTheFieldAssignmentInAudioContextLevel() throws IOException {
        String code = stripComments(readUtf8(mainSourceFile(AUDIO_CONTEXT_FILE)));
        int hits = countMatches(code, "\\.gain");
        assertEquals("AudioContextLevel 里那处 this.gain = gain; 是代码行，尺子必须抓到（恰 1 处）", 1, hits);
        // 顺带证明「注释里的 gain 不计」：javadoc 的 @param gain 被剥掉后，字段声明行本身不带点前缀。
        assertEquals("剥注释后不该残留任何 '@param gain'", 0, countMatches(code, "@param\\s+gain"));
    }

    /** ② volume() 方法体内不得出现 gain 或 decision（代码行判据，先剥注释）。 */
    @Test
    public void volumeMethodBodyCarriesNeitherGainNorDecision() throws IOException {
        String source = readUtf8(mainSourceFile(CUE_PLAYER_FILE));
        Matcher m = Pattern.compile("private\\s+float\\s+volume\\s*\\(\\s*\\)\\s*\\{[^}]*\\}")
                .matcher(source);
        assertTrue("CuePlayer 里找不到 volume() 方法本体", m.find());
        String rawBody = m.group();
        String body = stripComments(rawBody);
        // 正锚点：确实是那个只读玩家设置的 volume()，不是抓到别的空壳。
        assertTrue("volume() 本体应仍读玩家设置 getInt(\"volume\"...): " + rawBody,
                body.contains("getInt(\"volume\""));
        assertEquals("volume() 方法体代码行里不许出现 gain: " + rawBody,
                0, countMatches(body, "(?i)\\bgain\\b"));
        assertEquals("volume() 方法体代码行里不许出现 decision: " + rawBody,
                0, countMatches(body, "(?i)\\bdecision\\b"));
    }

    /** ③ 三档增益常量逐字仍是 0.70f / 1.00f / 1.00f（防止有人「顺手修好」把数值改了）。 */
    @Test
    public void threeGainConstantsRemainVerbatim() throws IOException {
        String code = stripComments(readUtf8(mainSourceFile(AUDIO_CONTEXT_FILE)));
        assertEquals("QUIET_GAIN 必须逐字为 0.70f", 1,
                countMatches(code, "static\\s+final\\s+float\\s+QUIET_GAIN\\s*=\\s*0\\.70f"));
        assertEquals("MODERATE_GAIN 必须逐字为 1.00f", 1,
                countMatches(code, "static\\s+final\\s+float\\s+MODERATE_GAIN\\s*=\\s*1\\.00f"));
        assertEquals("NOISY_GAIN 必须逐字为 1.00f", 1,
                countMatches(code, "static\\s+final\\s+float\\s+NOISY_GAIN\\s*=\\s*1\\.00f"));
    }

    /** ④ 被接上的那一半（decision().channel）必须仍在 CuePlayer 真实读取——守卫不许把已接的半边也判死。 */
    @Test
    public void channelHalfIsStillReadOnTheCuePlayerPath() throws IOException {
        String code = stripComments(readUtf8(mainSourceFile(CUE_PLAYER_FILE)));
        int channelReads = countMatches(code, "decision\\(\\)\\s*\\.\\s*channel");
        assertTrue("CuePlayer 代码行里 decision().channel 必须仍被读（≥1），实测 " + channelReads,
                channelReads >= 1);
    }

    /** ⑤ 既有 AudioContextLevelTest 里含 "gain" 的行数不得跌破任务 0 抄下的基线。 */
    @Test
    public void existingGainAssertionsStayAtOrAboveBaseline() throws IOException {
        Path testFile = appRoot().resolve(Path.of("src", "test", "java", PKG_DIR,
                "AudioContextLevelTest.java"));
        String content = readUtf8(testFile);
        int gainLines = 0;
        for (String line : content.split("\n", -1)) {
            if (line.contains("gain")) gainLines++;
        }
        assertTrue("AudioContextLevelTest 含 gain 的行数（" + gainLines
                + "）不得跌破基线 " + BASELINE_GAIN_TEST_LINES,
                gainLines >= BASELINE_GAIN_TEST_LINES);
    }

    /** ⑥ 主源集里 AudioContextLevel 的引用文件数 ≥ 1，且引用拓扑不许多出「新造的绕过路径」。 */
    @Test
    public void audioContextLevelWiringTopologyIsUnchanged() throws IOException {
        Set<String> referencers = new TreeSet<>();
        for (Path file : allMainJavaFiles()) {
            String raw = readUtf8(file);
            if (countMatches(raw, "\\bAudioContextLevel\\b") > 0) {
                referencers.add(file.getFileName().toString());
            }
        }
        assertTrue("AudioContextLevel 必须仍被主源集引用（≥1）", referencers.size() >= 1);
        // 拓扑白名单：出现名单外的引用文件，说明有人新造了一条绕过 AudioContextLevel 的消费路。
        Set<String> beyondAllowList = new TreeSet<>(referencers);
        beyondAllowList.removeAll(KNOWN_AUDIO_CONTEXT_REFERENCERS);
        assertTrue("出现名单外的 AudioContextLevel 引用文件（疑似新造绕过路）: " + beyondAllowList,
                beyondAllowList.isEmpty());
        assertTrue("真实消费者 CuePlayer 必须仍在引用名单内: " + referencers,
                referencers.contains(CUE_PLAYER_FILE));
    }

    /** ⑦ 只有「①主源集无 gain 消费」与「②volume() 体内无 gain/decision」两条同时成立才许绿。 */
    @Test
    public void bothGainGuardsMustHoldTogether() throws IOException {
        int gainConsumptionOutside = 0;
        for (Path file : allMainJavaFiles()) {
            if (file.getFileName().toString().equals(AUDIO_CONTEXT_FILE)) continue;
            gainConsumptionOutside += countMatches(stripComments(readUtf8(file)), "\\.gain");
        }

        String source = readUtf8(mainSourceFile(CUE_PLAYER_FILE));
        Matcher m = Pattern.compile("private\\s+float\\s+volume\\s*\\(\\s*\\)\\s*\\{[^}]*\\}")
                .matcher(source);
        assertTrue("找不到 volume() 方法本体", m.find());
        String body = stripComments(m.group());
        int volumeBodyTokens = countMatches(body, "(?i)\\bgain\\b") + countMatches(body, "(?i)\\bdecision\\b");

        // 两条命中数必须同时为 0；只满足一条不许绿（防止「只补了 volume() 却在别处接 gain」这类半吊子）。
        assertTrue("两条守卫必须同时归零：主源集 gain 消费=" + gainConsumptionOutside
                + "，volume() 体 gain/decision=" + volumeBodyTokens,
                gainConsumptionOutside == 0 && volumeBodyTokens == 0);
    }
}
