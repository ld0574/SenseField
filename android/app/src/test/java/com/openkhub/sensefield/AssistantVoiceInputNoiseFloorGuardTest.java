package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
 * 任务书 47 的报警器：钉住 {@code AssistantVoiceInput} 构造器里那一跳
 * {@code gate.observeNoiseFloor(() -> listener.noiseFloor(gate.noiseFloor()));}——
 * 闸门每重算一次自适应噪声底，就把同一个现值读数交给输出侧那条运输线。
 *
 * <p>装这卷之前它是一台不会响的报警器：把那一行注释掉，126 类／1235 条全量门禁与基线逐字相同，
 * 一条测试都不红（判卷者与任务书 46 的执行者各自独立实测）。本卷只给它装上守卫。
 *
 * <p>取法照仓里既有先例，不新写一套 IO 工具：{@code OutcomePhraseTest} 的
 * {@code sourceOf(...) + methodBody(...) + codeLinesOnly(...)}（断言坐在构造器本体里，
 * 而不是「全文件找得到这串字」），加 {@code NoiseFloorFeedTest} 的
 * {@code withoutComments(...)} 先抹平注释再匹配（所以「整行注释掉」与「整行删掉」在这把尺子下
 * 等价，都当场变红）。{@code AssistantVoiceInput} 需要 {@code Context}，JVM 里造不出来，
 * 本类一次也不尝试实例化。
 *
 * <p>三条主判据各自的落点：①「那一跳真在构造器体内、且全文件只此一处」由
 * {@link #constructorHandsTheGateNoiseFloorReadingToTheListenerExactlyOnce} 与
 * {@link #transportHopIsAttachedToTheGateJustBuilt} 钉住；②「交出去的是闸门现值而不是写死的数」由
 * {@link #hopHandsOverTheLiveGateReadingNotAFixedNumber} 钉住——把回调体换成
 * {@code listener.noiseFloor(0.0)} 时 ① 与本条同时红，这一支专防「只匹配方法名」的假守卫；
 * ③「{@code this.gate = } 恰一次」由 {@link #constructorAssignsItsGateExactlyOnce} 钉住，
 * 防有人改成两次、让 transport 挂在前一个被丢弃的闸门上。
 * {@link #theRubricIsBlindToEveryDetachedShape} 是本卷尺子的自检：整行删掉、回调体换成空实现、
 * 实参换成常量这三种摘法都必须让 ① 那条判据落空。
 *
 * <p>口径边界：本卷只加守卫，不改 {@code VoiceActivityGate}、{@code CuePlayer}、
 * {@code CaptureService} 的任何行为，不动 {@code noiseFloor} 的接口签名，也不往既有 126 个测试
 * 文件里改一个字的期望值。
 *
 * <p>证据边界：本机无 adb、无第二台设备、无声学设备。本类只证「这一跳在源码里接着、读的是闸门
 * 现值、断掉时 CI 会红」，证不了嘈杂环境里玩家实际听不听得见、触觉兜底生不生效。
 */
public final class AssistantVoiceInputNoiseFloorGuardTest {

    /** 被钉住的两个文件：运输线两端，扫描面仅此两个文件。 */
    private static final String VOICE_INPUT_FILE = "AssistantVoiceInput.java";
    private static final String GATE_FILE = "VoiceActivityGate.java";

    /** 全文件唯一的那个构造器签名；命中不了就是构造器被改坏，这条当场红而不是静默跳过。 */
    private static final String CONSTRUCTOR_SIGNATURE =
            "AssistantVoiceInput(Context context, Listener listener)";

    /** 构造器里闸门的那一次赋值。 */
    private static final String GATE_ASSIGNMENT = "this.gate = ";

    /**
     * 那一跳的合法形状：注册在 {@code gate} 上、观察者无参、实参必须是 {@code gate.noiseFloor()}
     * 这个现值出口。允许换行与给 lambda 体加一层花括号；实参换成常量、局部变量，或把回调体换成
     * 空实现，都不算命中——所以「只匹配方法名」的假守卫在这一条上过不了关。
     */
    private static final Pattern TRANSPORT_HOP = Pattern.compile(
            "(?:this\\s*\\.\\s*)?gate\\s*\\.\\s*observeNoiseFloor\\s*\\(\\s*\\(\\s*\\)\\s*->\\s*\\{?\\s*"
                    + "(?:this\\s*\\.\\s*)?listener\\s*\\.\\s*noiseFloor\\s*\\(\\s*"
                    + "(?:this\\s*\\.\\s*)?gate\\s*\\.\\s*noiseFloor\\s*\\(\\s*\\)\\s*\\)\\s*;?\\s*\\}?\\s*\\)"
                    + "\\s*;");

    /** 同一跳，只把实参捕获出来，好单独钉「交出去的是现值读数、不是写死的数」。 */
    private static final Pattern TRANSPORT_HOP_ARGUMENT = Pattern.compile(
            "listener\\s*\\.\\s*noiseFloor\\s*\\(\\s*(.*?)\\s*\\)\\s*;?\\s*\\}?\\s*\\)\\s*;");

    /** 生产里那一行在位时的写法，只用作 {@link #theRubricIsBlindToEveryDetachedShape} 的正例基准。 */
    private static final String SHIPPED_HOP =
            "        gate.observeNoiseFloor(() -> listener.noiseFloor(gate.noiseFloor()));\n";

    /* ---------- ① 那一跳坐在构造器本体里，且全文件只此一处 ---------- */

    @Test
    public void constructorHandsTheGateNoiseFloorReadingToTheListenerExactlyOnce() throws IOException {
        String code = compiledCodeOf(VOICE_INPUT_FILE);
        List<String> inConstructor = matchesOf(TRANSPORT_HOP, constructorBody(code));
        assertEquals("AssistantVoiceInput.java 构造器本体里 listener.noiseFloor(gate.noiseFloor()) "
                + "那一跳必须恰好 1 处，实测 " + inConstructor.size() + " 处: " + inConstructor,
                1, inConstructor.size());
        List<String> inFile = matchesOf(TRANSPORT_HOP, code);
        assertEquals("AssistantVoiceInput.java 全文件只许有构造器这一跳：挪进一个永不被调用的方法"
                + "就等于摘掉: " + inFile, 1, inFile.size());
    }

    /* ---------- ② 实参必须是闸门的现值读数，不是任何写死的数 ---------- */

    @Test
    public void hopHandsOverTheLiveGateReadingNotAFixedNumber() throws IOException {
        List<String> hops = matchesOf(TRANSPORT_HOP, constructorBody(compiledCodeOf(VOICE_INPUT_FILE)));
        assertEquals("构造器里能钉住实参的那一跳必须恰好 1 处，实测 " + hops.size()
                + " 处——把实参换成写死的数（形状在、值假）时这里就是 0 处", 1, hops.size());
        Matcher argument = TRANSPORT_HOP_ARGUMENT.matcher(hops.get(0));
        assertTrue("这一跳的实参没被抓出来，写法与 TRANSPORT_HOP 不一致: " + hops.get(0), argument.find());
        assertEquals("交给 listener 的必须是闸门自己的现值出口", "gate.noiseFloor()",
                oneSpace(argument.group(1)));
        assertFalse("实参里出现了字面数字或常量（报警器会响、读数是假的）: " + argument.group(1),
                argument.group(1).matches(".*\\d.*"));
    }

    /* ---------- ③ this.gate = 恰一次（防 transport 挂在前一个被丢弃的闸门上） ---------- */

    @Test
    public void constructorAssignsItsGateExactlyOnce() throws IOException {
        String code = compiledCodeOf(VOICE_INPUT_FILE);
        List<String> assignments = new ArrayList<>();
        for (String line : linesOf(constructorBody(code))) {
            if (line.contains(GATE_ASSIGNMENT)) assignments.add(line.trim());
        }
        assertEquals("AssistantVoiceInput.java 构造器里 this.gate = 只许赋值一次，第二次会让先挂上去的"
                + " transport 挂在被丢弃的闸门上: " + assignments, 1, assignments.size());
        assertEquals("AssistantVoiceInput.java 全文件 this.gate = 只许一处", 1,
                countOccurrences(code, GATE_ASSIGNMENT));
        assertEquals("AssistantVoiceInput.java 全文件 new VoiceActivityGate( 只许一处", 1,
                countOccurrences(code, "new VoiceActivityGate("));
    }

    /* ---------- ④ 时序：先有闸门，再往它身上挂这条 transport ---------- */

    @Test
    public void transportHopIsAttachedToTheGateJustBuilt() throws IOException {
        List<String> lines = linesOf(constructorBody(compiledCodeOf(VOICE_INPUT_FILE)));
        int built = indexOfLineContaining(lines, GATE_ASSIGNMENT);
        int attached = indexOfLineMatching(lines, TRANSPORT_HOP);
        assertTrue("这一跳必须排在闸门建好之后（赋值在第 " + built + " 行、挂载在第 " + attached
                + " 行），排在前头挂的就是还没赋值的字段", attached > built);
    }

    /* ---------- ⑤ 尺子自检：三种典型摘法都必须落到 ① 那条红判据上 ---------- */

    @Test
    public void theRubricIsBlindToEveryDetachedShape() {
        assertEquals("在位写法必须命中 1 处，否则这把尺子量不到任何东西",
                1, matchesOf(TRANSPORT_HOP, SHIPPED_HOP).size());
        List<String> detached = Arrays.asList(
                "",                                                                    // 整行删掉
                "        gate.observeNoiseFloor(() -> { });\n",                        // 回调体空实现
                "        gate.observeNoiseFloor(() -> listener.noiseFloor(0.0));\n",   // 形状在、值假
                "        gate.observeNoiseFloor(() -> listener.noiseFloor(quiet));\n", // 换成局部变量
                "        gate.observeNoiseFloor(null);\n");                             // 干脆不注册
        for (String shape : detached) {
            assertEquals("这种摘法必须让判据 ① 落空（命中 0 处）: [" + shape.trim() + "]",
                    0, matchesOf(TRANSPORT_HOP, shape).size());
        }
    }

    /* ---------- ⑥ 两端接口仍是这一跳所指的那两个，改名就等于把运输线换成另一件事 ---------- */

    @Test
    public void bothEndsStillDeclareTheApisThisHopTalksTo() throws IOException {
        String gate = compiledCodeOf(GATE_FILE);
        assertEquals("闸门侧注册入口 void observeNoiseFloor(NoiseFloorObserver observer) 只许一处", 1,
                countOccurrences(gate, "void observeNoiseFloor(NoiseFloorObserver observer)"));
        assertEquals("闸门侧现值出口 double noiseFloor() 只许一处", 1,
                countOccurrences(gate, "double noiseFloor()"));
        assertEquals("闸门侧那条边仍是在重算同一个 EMA 的那一处触发", 1,
                countOccurrences(gate, "observer.onFloorUpdated();"));
        assertEquals("输出侧 sink 仍须是 noiseFloor(double floor)：AssistantVoiceInput.java 里只许一处",
                1, countOccurrences(compiledCodeOf(VOICE_INPUT_FILE), "void noiseFloor(double floor)"));
    }

    /* ---------- 支撑（取法与 NoiseFloorFeedTest / OutcomePhraseTest 同形） ---------- */

    /** 取源码：从测试工作目录逐级向上找，命中不了就抛——「找不到被测源文件」必须让这条测试变红。 */
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

    /**
     * 「真正会被编译的文本」：行注释与块注释都抹成空格，字符串与字符字面量里的斜杠不算注释。
     * 不抹注释的话，「把那一行注释掉」在文本里仍然留着一模一样的字，报警器就白装。
     */
    private static String compiledCodeOf(String simpleName) throws IOException {
        return withoutComments(String.join("\n", sourceOf(simpleName)));
    }

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

    /** 构造器覆写本体：从签名那一行起做花括号配平，只取这一个方法体，别拿全文本凑数。 */
    private static String constructorBody(String code) {
        return methodBody(linesOf(code), CONSTRUCTOR_SIGNATURE);
    }

    private static String methodBody(List<String> src, String signatureNeedle) {
        int start = indexOfLineContaining(src, signatureNeedle);
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

    private static List<String> linesOf(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\r?\n", -1)) {
            if (!line.trim().isEmpty()) lines.add(line);
        }
        return lines;
    }

    private static List<String> matchesOf(Pattern pattern, String text) {
        List<String> found = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) found.add(m.group());
        return found;
    }

    private static int countOccurrences(String text, String needle) {
        int hits = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) hits++;
        return hits;
    }

    private static int indexOfLineContaining(List<String> lines, String needle) {
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).contains(needle)) return i;
        throw new AssertionError("源码里没有这句：" + needle);
    }

    private static int indexOfLineMatching(List<String> lines, Pattern pattern) {
        for (int i = 0; i < lines.size(); i++) if (pattern.matcher(lines.get(i)).find()) return i;
        throw new AssertionError("源码里没有这一跳：" + pattern.pattern());
    }

    /** 空白归一：只把「读的是哪个表达式」摊平了比，格式改动不该把守卫搞红。 */
    private static String oneSpace(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }
}
