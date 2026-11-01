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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * 把「界面文案不出现执行类越权词（由仓库里的措辞守卫盯着）」这句对外声明，接到帮助文案的
 * <b>真实读取路</b>上（任务书 34R）。
 *
 * <p><b>为什么本类非装不可</b>：{@link WordingGuard} 的 {@code check}／{@code checkText} 在主源集里
 * 一条调用点都没有（派单当轮实测 {@code grep -rn "WordingGuard\.check" .../main/java/ | wc -l} = 0），
 * 测试侧也只有两条喂了真值；{@link SettingHelpContent} 里玩家真会读到的那一大堆中文，一条也没被扫过。
 * 声明说「守卫盯着帮助文案」，而机器从来没有盯过——本类把这句话变成可复跑判据。
 *
 * <p><b>扫描面（=四个访问器能取到的每一条真实中文）</b>：键从 {@code SettingHelpContent.java} 源码的
 * {@code case} 标签里取（派单板 3：不给生产侧加 {@code allKeys()} 之类的接口），值一律由<b>生产访问器
 * 当场调用</b>拿回来（本类不解析返回串，所以「文案改了但测试没跟着改」不可能在这里蒙混过关）：
 * <ul>
 *   <li>{@code title(key)} × 其全部 case 键；</li>
 *   <li>{@code summary(key)} × 其全部 case 键；</li>
 *   <li>{@code text(key)} × 其全部 case 键；</li>
 *   <li>{@code itemKeys(group)} 的 16 个组键各取其成员键，成员键再逐个送进 {@code text(...)}。</li>
 * </ul>
 *
 * <p><b>口径（既有语义，本类不自创判据）</b>：
 * <ul>
 *   <li>命中判定只用 {@link WordingGuard#checkText}，文件名与 1 基行号由它给出；</li>
 *   <li>注释行不算命中：取键前整份源码先过 {@link GainCommentAlignGuardTest#stripComments}
 *       （同一把尺子，不复制实现），所以 {@code //}／{@code /*}&hellip;{@code *}{@code /} 里藏的
 *       {@code case "…"} 既取不到键、也不算文案；</li>
 *   <li>{@code checkText} 按既有语义只读「字符串字面量内部」，所以返回串必须先按源码里那种
 *       {@code "…"} 的形状包一层再喂；包一层这件事由
 *       {@link #plantedExecutionPhraseOnTheSameCallPathYieldsExactlyOneHit()} 的正负样本夹住
 *       ——裸串判 0、包好判恰 1，两者同时成立才证明这条路真在跑、不是恒空转。</li>
 * </ul>
 *
 * <p><b>覆盖下限只许 ≥</b>：本轮实测 69／16／16／69（四个访问器的 case 标签，全文 170）、
 * 组键成员合计 53，被扫键单元下限取 69+16+69 = 154；有人加新键时读数只涨不跌，
 * 所以下限写成 ≥，绝不写死成恰好 170。
 *
 * <p><b>34R-2 补的三把兜底锁</b>（本卷唯一改动就是加这三支，零生产改动）：⑪ 逐键对照——每个从源码
 * {@code case} 标签取到的键，它当场走生产访问器拿到的返回值都不许等于<b>本访问器自己那条 {@code default}
 * 兜底串</b>，并且分访问器报数（title／summary／text 各自命中几个）；⑫ 那三条字符串兜底串彼此互不相同，
 * 防止一条通用兜底把 ⑪ 糊过去；⑬ {@code itemKeys} 那一支的兜底不是串而是空列表，按形状单独钉。
 * 违禁词判据仍是 ①／⑩，这三支只管另一件事：「扫的到底是 207 个键单元各自的本名文案，还是 3 条兜底串」。
 *
 * <p><b>扫出红是发现，不是待修的测试</b>：既有文案属行为合同。本类不许为了变绿而换词、
 * 加 {@code @Ignore}、或把「0 命中」松成「允许 N 处」；⑪ 的判据同样只有「逐键都 ≠」一种写法，
 * 不许松成「允许 K 个键落到兜底」。
 *
 * <p><b>证据边界</b>：本机无 adb、无设备。本类只证「JVM 里这条读取路上跑出来的每一条中文串
 * 过守卫是 0 命中」，证不了玩家实际读到哪一句、也证不了读屏软件念不念得顺——真机侧欠账原样上交。
 */
public final class SettingHelpCopyWordingGuardTest {

    /** 被测文案文件（只读，本卷一字不改）。 */
    private static final String HELP_SOURCE = "SettingHelpContent.java";
    private static final String GUARD_SOURCE = "WordingGuard.java";
    private static final String GUARD_TEST_SOURCE = "WordingGuardTest.java";

    /** 取键正则。<b>任务 2(a) 的反向验证就是改这一串</b>，所以它是唯一的取键入口常量。 */
    private static final String CASE_KEY_PATTERN_TEXT = "case\\s+\"([^\"]+)\"";
    private static final Pattern CASE_KEY = Pattern.compile(CASE_KEY_PATTERN_TEXT);
    private static final Pattern CASE_LABEL = Pattern.compile("case\\s+\"");
    private static final Pattern DEFAULT_COPY = Pattern.compile("default:\\s*return\\s+\"([^\"]+)\"");
    /** {@code itemKeys} 那一支的兜底形状：源码面必须是 {@code Collections.emptyList()}，不是字符串兜底。 */
    private static final Pattern DEFAULT_EMPTY_LIST =
            Pattern.compile("default:\\s*return\\s+Collections\\.emptyList\\(\\)");
    private static final Pattern TEST_ANNOTATION = Pattern.compile("(?m)^\\s*@Test\\b");
    private static final Pattern DEFAULT_BRANCH = Pattern.compile("(?m)^\\s*default\\s*:");

    /** 四个访问器的签名锚点（剥注释后的真实源码形状）。 */
    private static final String TITLE_SIG = "static String title(String key) {";
    private static final String SUMMARY_SIG = "static String summary(String key) {";
    private static final String ITEM_KEYS_SIG = "static List<String> itemKeys(String key) {";
    private static final String TEXT_SIG = "static String text(String key) {";

    /* ---------- 派单当轮实测的基线（只许 ≥，除两条「合同」类读数） ---------- */

    /** 69（title）+ 16（summary）+ 69（text）；组键成员送进 text 的那 53 个单元只许再往上加。 */
    private static final int MIN_SCANNED_KEY_UNITS = 154;
    private static final int MIN_CASE_LABELS_IN_FILE = 170;
    private static final int MIN_TITLE_KEYS = 69;
    private static final int MIN_SUMMARY_KEYS = 16;
    private static final int MIN_GROUP_KEYS = 16;
    private static final int MIN_TEXT_KEYS = 69;
    private static final int MIN_DEFAULT_BRANCHES = 4;

    /** ⑪／⑬ 用：16 个组键送进 {@code text(...)} 的成员键单元合计本轮实测 53，只许 ≥。 */
    private static final int MIN_TEXT_MEMBER_UNITS = 53;

    /** 合同类读数：这两条写死是故意的——它们记的就是「逐字未改」。 */
    private static final int WORDING_GUARD_TEST_ASSERTIONS = 19;
    private static final int BLACKLIST_SIZE = 11;

    /** 派卷快照（2026-10-20 07:12:39，HEAD=b19b198）的 sha256，按 LF 归一口径复算过、与快照逐字节同值。 */
    private static final String GUARD_SHA256 =
            "a4c3bc474359d12e15af26cd1852736183d09ca33e24222a215ccacc8ee7d412";
    private static final String GUARD_TEST_SHA256 =
            "bbf6bc6c16327cb6cab61beca1a438d16c5a44f7cf9ed500f149ead1cb45f78d";

    /** 落在四个 switch 的任何一个 case 之外的键——专门用来把 {@code default} 兜底支路走到。 */
    private static final String UNREACHABLE_KEY = "b34r_key_absent_from_every_switch";

    /** 11 词合同在本类里逐字复述一遍（顺序即 {@code WordingGuard} 的判定顺序）。 */
    private static final List<String> ELEVEN_PHRASES = Collections.unmodifiableList(Arrays.asList(
            "自动执行", "自动点击", "帮你点", "代打", "自动过关", "自动连招",
            "替你操作", "胜率", "上分", "躺赢", "代练"));

    /* ---------- 一次扫描的全部读数 ---------- */

    private static final class Sweep {
        final List<String> offenders = new ArrayList<>();
        final Map<String, Integer> membersByGroup = new LinkedHashMap<>();
        List<String> titleKeys = new ArrayList<>();
        List<String> summaryKeys = new ArrayList<>();
        List<String> groupKeys = new ArrayList<>();
        List<String> textKeys = new ArrayList<>();
        int scannedKeyUnits;
        int nullOrEmptyCopies;

        void consume(String pathLabel, String key, String copy) {
            scannedKeyUnits++;
            if (copy == null || copy.isEmpty()) {
                nullOrEmptyCopies++;
                offenders.add(pathLabel + "(" + key + ") 返回空串，0 命中这条会被读成空转");
                return;
            }
            List<WordingGuard.Hit> hits = scanCopy(copy);
            if (!hits.isEmpty()) {
                offenders.add(pathLabel + "(" + key + ") -> " + hits);
            }
        }
    }

    /**
     * ⑪ 用的读数器：一个访问器一份，记「逐键对照跑了几个键单元」「其中几个落到了自己那条 {@code default}
     * 兜底串」「几个是 null／空串」。分访问器各留一份，是为了让红名里能直接看出是哪一支 switch 坏了，
     * 也为了满足本卷「不许只给一个总数」的报数口径。
     */
    private static final class FallbackTally {
        final String accessor;
        final String defaultCopy;
        int checked;
        int fallenBack;
        int nullOrEmpty;
        final List<String> fallenKeys = new ArrayList<>();

        FallbackTally(String accessor, String defaultCopy) {
            this.accessor = accessor;
            this.defaultCopy = defaultCopy;
        }

        void check(String keyLabel, String copy) {
            checked++;
            if (copy == null || copy.isEmpty()) {
                nullOrEmpty++;
                return;
            }
            if (copy.equals(defaultCopy)) {
                fallenBack++;
                if (fallenKeys.size() < 12) {
                    fallenKeys.add(accessor + "(" + keyLabel + ")");
                }
            }
        }

        String describe() {
            return accessor + "：逐键对照 " + checked + " 个键单元，落到本访问器 default 兜底串 " + fallenBack
                    + " 个，null／空串 " + nullOrEmpty + " 个"
                    + (fallenKeys.isEmpty() ? "" : "；前 " + fallenKeys.size() + " 个键：" + fallenKeys);
        }
    }

    /** 从源码取一条字符串兜底串：取丢了当场红——那等于把逐键对照的尺子换成 null，本卷不许。 */
    private static String requireStringDefault(String accessor, String sourceDefault) {
        assertTrue(accessor + "() 的 default 兜底串在源码里取丢了（正则取空＝⑪ 的尺子没接上电，"
                + "「≠ 兜底」会白比），先核对生产侧那一条 default 的形状", sourceDefault != null && !sourceDefault.isEmpty());
        return sourceDefault;
    }

    /** 剥注释后的源码行，四个访问器共用一份。 */
    private static List<String> helpCodeLines() throws IOException {
        return linesOf(codeOnly(readSource("main", HELP_SOURCE)));
    }

    /** 全量扫一遍：键从源码取，值从生产访问器当场调，返回串包成字面量形状后喂 {@code checkText}。 */
    private static Sweep sweepRealHelpCopy() throws IOException {
        String code = GainCommentAlignGuardTest.stripComments(readSource("main", HELP_SOURCE));
        List<String> codeLines = linesOf(code);
        Sweep sweep = new Sweep();
        sweep.titleKeys = caseKeysOf(methodBodyOf(codeLines, TITLE_SIG));
        sweep.summaryKeys = caseKeysOf(methodBodyOf(codeLines, SUMMARY_SIG));
        sweep.groupKeys = caseKeysOf(methodBodyOf(codeLines, ITEM_KEYS_SIG));
        sweep.textKeys = caseKeysOf(methodBodyOf(codeLines, TEXT_SIG));

        for (String key : sweep.titleKeys) {
            sweep.consume("title", key, SettingHelpContent.title(key));
        }
        for (String key : sweep.summaryKeys) {
            sweep.consume("summary", key, SettingHelpContent.summary(key));
        }
        for (String key : sweep.textKeys) {
            sweep.consume("text", key, SettingHelpContent.text(key));
        }
        for (String group : sweep.groupKeys) {
            List<String> members = SettingHelpContent.itemKeys(group);
            sweep.membersByGroup.put(group, members.size());
            for (String member : members) {
                sweep.consume("text<-" + group, member, SettingHelpContent.text(member));
            }
        }
        return sweep;
    }

    /* ---------- 支撑（取法照仓里既有先例，不发明新机制） ---------- */

    /**
     * 取源码：与 {@code OutcomePhraseTest}/{@code AssistantVoiceInputNoiseFloorGuardTest} 的
     * {@code sourceOf} 同形——从测试工作目录逐级向上找，模块内与树根起两种相对写法都试；
     * 命中不了就抛，让「尺子没接上电」当场变红而不是静默假绿。
     */
    private static String readSource(String sourceSet, String simpleName) throws IOException {
        String[] candidates = {
                "src/" + sourceSet + "/java/com/openkhub/sensefield/" + simpleName,
                "android/app/src/" + sourceSet + "/java/com/openkhub/sensefield/" + simpleName,
        };
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; dir != null && up <= 5; up++, dir = dir.getParentFile()) {
            for (String rel : candidates) {
                File file = new File(dir, rel);
                if (file.isFile()) {
                    return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IllegalStateException("找不到被测源文件 " + simpleName + "（" + sourceSet
                + " 源集：模块内与树根两种相对写法、向上五级均未命中），这条断言没接上电");
    }

    /** 剥注释尺子直接复用 {@link GainCommentAlignGuardTest#stripComments}，本类不复制第二份实现。 */
    private static String codeOnly(String source) {
        return GainCommentAlignGuardTest.stripComments(source);
    }

    /** 访问器方法本体：从签名那一行起做花括号配平，只取这一个方法体，别拿全文本凑数。 */
    private static String methodBodyOf(List<String> codeLines, String signatureNeedle) {
        int start = -1;
        for (int i = 0; i < codeLines.size(); i++) {
            if (codeLines.get(i).contains(signatureNeedle)) {
                start = i;
                break;
            }
        }
        assertTrue("源码里找不到访问器签名：" + signatureNeedle + "（签名改名或被挪走，就等于把这条扫描路拆了）",
                start >= 0);
        StringBuilder body = new StringBuilder();
        int depth = 0;
        boolean started = false;
        for (int i = start; i < codeLines.size(); i++) {
            String line = codeLines.get(i);
            body.append(line).append('\n');
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '{') {
                    depth++;
                    started = true;
                } else if (c == '}') {
                    depth--;
                }
            }
            if (started && depth <= 0) {
                break;
            }
        }
        assertTrue("访问器方法体没配平：" + signatureNeedle, started && depth <= 0);
        return body.toString();
    }

    private static List<String> caseKeysOf(String body) {
        List<String> keys = new ArrayList<>();
        Matcher m = CASE_KEY.matcher(body);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    /** {@code default} 兜底串：返回 {@code null} 表示这一支不是字符串兜底（{@code itemKeys} 就是空列表）。 */
    private static String defaultCopyOf(String body) {
        Matcher m = DEFAULT_COPY.matcher(body);
        if (!m.find()) {
            return null;
        }
        String first = m.group(1);
        assertFalse("同一个 switch 里不该有两条字符串兜底支路：" + body, m.find());
        return first;
    }

    private static List<String> linesOf(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\r?\n", -1)) {
            if (!line.trim().isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static int countMatches(String text, Pattern pattern) {
        Matcher m = pattern.matcher(text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    /**
     * 真实调用路的那一层包装：把访问器返回的裸串按源码里 {@code return "…";} 的字面量形状包好再喂
     * {@code checkText}（{@code checkText} 按既有语义只看字符串字面量内部）。转义先做，保证串里
     * 万一出现引号或反斜杠也只是被转义、不会把字面量截断成漏扫。多行串按行各包一条，不留空档。
     */
    private static List<WordingGuard.Hit> scanCopy(String copy) {
        if (copy == null) {
            return new ArrayList<>();
        }
        StringBuilder wrapped = new StringBuilder();
        for (String line : copy.split("\r?\n", -1)) {
            if (wrapped.length() > 0) {
                wrapped.append('\n');
            }
            wrapped.append('"').append(line.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return WordingGuard.checkText(HELP_SOURCE, wrapped.toString());
    }

    /** LF 归一后的 sha256：本机与「按 LF 检出的 clone」应得同一串，避免行尾差异引起假红。 */
    private static String sha256Normalized(String source) {
        byte[] bytes = source.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("本机 JVM 取不到 SHA-256，这条合同判据没接上电", e);
        }
    }

    /* ================= 断言 ================= */

    /** ① 主判据：四个访问器取到的每一条真实中文，过守卫必须 0 命中。 */
    @Test
    public void everyRealHelpCopyReachedThroughTheFourAccessorsHitsNothing() throws IOException {
        Sweep sweep = sweepRealHelpCopy();
        assertTrue("帮助文案里扫出了执行类措辞（这是发现，不许换词、不许放松本断言）：" + sweep.offenders,
                sweep.offenders.isEmpty());
    }

    /** ② 覆盖下限：被扫键单元 ≥154（69+16+69），只许 ≥，不写死成恰好 170。 */
    @Test
    public void scannedKeyUnitsStayAtOrAboveTheCoverageFloor() throws IOException {
        Sweep sweep = sweepRealHelpCopy();
        assertTrue("被扫键单元 " + sweep.scannedKeyUnits + " 低于下限 " + MIN_SCANNED_KEY_UNITS
                + "（取键面缩水＝这条扫描路又开始空转）", sweep.scannedKeyUnits >= MIN_SCANNED_KEY_UNITS);
    }

    /** ③ 组键那一路：16 个组键每个至少把 1 个成员键送进 {@code text(...)}。 */
    @Test
    public void everyGroupKeyFeedsAtLeastOneMemberKeyIntoTheTextPath() throws IOException {
        Sweep sweep = sweepRealHelpCopy();
        assertTrue("组键只有 " + sweep.groupKeys.size() + " 个，少于本轮实测的 " + MIN_GROUP_KEYS,
                sweep.groupKeys.size() >= MIN_GROUP_KEYS);
        for (Map.Entry<String, Integer> e : sweep.membersByGroup.entrySet()) {
            assertTrue("组键 " + e.getKey() + " 送进 text 的成员键数为 " + e.getValue() + "，本路要求每组 ≥1",
                    e.getValue() >= 1);
        }
        assertEquals("每个组键都必须在读数里点名一次", sweep.groupKeys.size(), sweep.membersByGroup.size());
    }

    /**
     * ④ 正样本控制：同一条包装路上喂一句含「自动执行」的合成串，必须判出恰 1 命中；
     * 同一句不包装直接喂必须判 0——两条同时成立，才证明 ① 的 0 命中是扫出来的、不是恒空转。
     */
    @Test
    public void plantedExecutionPhraseOnTheSameCallPathYieldsExactlyOneHit() {
        String planted = "示例串：这一步会自动执行，只为证明扫描路在跑。";
        List<WordingGuard.Hit> wrapped = scanCopy(planted);
        assertEquals("包装后的合成串必须判出恰 1 命中，实得 " + wrapped, 1, wrapped.size());
        assertEquals(HELP_SOURCE, wrapped.get(0).file);
        assertEquals(1, wrapped.get(0).line);
        assertEquals("自动执行", wrapped.get(0).term);
        assertTrue("裸串不经字面量包装时守卫按既有语义看不见它（正因如此 ① 才必须包一层）："
                + WordingGuard.checkText(HELP_SOURCE, planted),
                WordingGuard.checkText(HELP_SOURCE, planted).isEmpty());
    }

    /** ⑤ 四条 {@code default} 兜底支路各自点名走一遍：三条中文兜底扫、空列表兜底钉形状。 */
    @Test
    public void allFourDefaultFallbackBranchesAreWalkedByName() throws IOException {
        List<String> codeLines = linesOf(codeOnly(readSource("main", HELP_SOURCE)));
        String titleDefault = defaultCopyOf(methodBodyOf(codeLines, TITLE_SIG));
        String summaryDefault = defaultCopyOf(methodBodyOf(codeLines, SUMMARY_SIG));
        String textDefault = defaultCopyOf(methodBodyOf(codeLines, TEXT_SIG));
        String itemKeysDefault = defaultCopyOf(methodBodyOf(codeLines, ITEM_KEYS_SIG));

        checkOneDefault("title", titleDefault);
        checkOneDefault("summary", summaryDefault);
        checkOneDefault("text", textDefault);

        // itemKeys 的兜底不是串而是空列表：当轮实测 :149 那一条 default 返回 Collections.emptyList()。
        assertTrue("itemKeys() 的 default 若哪天改成返回字符串兜底，本卷的「四条兜底」面就对不上；"
                + "请先把它按形状钉住再谈扫描", itemKeysDefault == null);
        assertEquals(Collections.<String>emptyList(), SettingHelpContent.itemKeys(UNREACHABLE_KEY));
        assertTrue("全文 default 支路少于本轮实测的 4 条："
                + countMatches(String.join("\n", codeLines), DEFAULT_BRANCH),
                countMatches(String.join("\n", codeLines), DEFAULT_BRANCH) >= MIN_DEFAULT_BRANCHES);
    }

    /** 一条字符串兜底：先用「不属于任何 case 的键」把它走到，再逐字对上源码那条 default，最后单独扫一遍。 */
    private static void checkOneDefault(String accessor, String sourceDefault) {
        assertTrue(accessor + "() 的 default 兜底串在源码里取丢了（正则取空＝扫描面漏了一条真会念到的句子）",
                sourceDefault != null);
        String reached = callAccessor(accessor, UNREACHABLE_KEY);
        assertEquals(accessor + "() 用一个不属于任何 case 的键走到的兜底串，必须逐字等于源码里那条 default",
                sourceDefault, reached);
        assertTrue(accessor + "() 的兜底串扫出了执行类措辞：" + scanCopy(reached),
                scanCopy(reached).isEmpty());
    }

    private static String callAccessor(String accessor, String key) {
        switch (accessor) {
            case "title":
                return SettingHelpContent.title(key);
            case "summary":
                return SettingHelpContent.summary(key);
            case "text":
                return SettingHelpContent.text(key);
            default:
                throw new IllegalStateException("未知的访问器名：" + accessor);
        }
    }

    /** ⑥ 取键面口径：键只从代码行取（注释里的 {@code case} 不算），且四个访问器取到的键合起来一个不漏。 */
    @Test
    public void keysComeFromCodeLinesOnlyAndNothingIsLeftOutOfTheFourAccessors() throws IOException {
        String raw = readSource("main", HELP_SOURCE);
        String code = codeOnly(raw);
        int rawLabels = countMatches(raw, CASE_LABEL);
        int codeLabels = countMatches(code, CASE_LABEL);
        assertEquals("注释里不许藏 case 标签：剥注释前后的 case 标签计数必须相等，"
                + "否则「取的键」和「读的文案」就不是同一张面", rawLabels, codeLabels);
        assertTrue("全文 case 标签 " + codeLabels + " 少于本轮实测的 " + MIN_CASE_LABELS_IN_FILE
                + "（访问器被拆薄＝扫描面缩水）", codeLabels >= MIN_CASE_LABELS_IN_FILE);

        Sweep sweep = sweepRealHelpCopy();
        assertTrue("title 键 " + sweep.titleKeys.size() + " 少于 " + MIN_TITLE_KEYS,
                sweep.titleKeys.size() >= MIN_TITLE_KEYS);
        assertTrue("summary 键 " + sweep.summaryKeys.size() + " 少于 " + MIN_SUMMARY_KEYS,
                sweep.summaryKeys.size() >= MIN_SUMMARY_KEYS);
        assertTrue("itemKeys 组键 " + sweep.groupKeys.size() + " 少于 " + MIN_GROUP_KEYS,
                sweep.groupKeys.size() >= MIN_GROUP_KEYS);
        assertTrue("text 键 " + sweep.textKeys.size() + " 少于 " + MIN_TEXT_KEYS,
                sweep.textKeys.size() >= MIN_TEXT_KEYS);
        int extracted = sweep.titleKeys.size() + sweep.summaryKeys.size() + sweep.groupKeys.size()
                + sweep.textKeys.size();
        assertEquals("四个访问器各取到的 case 键合起来必须等于全文 case 标签数（一个都不许落在扫描面外）",
                codeLabels, extracted);
    }

    /** ⑦ 反空转：每一条被扫的返回串都不许是 null／空串，否则「0 命中」是空出来的。 */
    @Test
    public void noScannedCopyIsNullOrEmptySoTheZeroHitRuleCannotGoVacuous() throws IOException {
        Sweep sweep = sweepRealHelpCopy();
        assertEquals("有访问器对在册键返回了 null 或空串——那样 0 命中是空出来的："
                + sweep.offenders, 0, sweep.nullOrEmptyCopies);
        assertTrue("被扫键单元 " + sweep.scannedKeyUnits + " 少于下限 " + MIN_SCANNED_KEY_UNITS,
                sweep.scannedKeyUnits >= MIN_SCANNED_KEY_UNITS);
    }

    /** ⑧ 守卫本体与它那 19 条断言逐字未改（派卷快照的 sha256 为准）。 */
    @Test
    public void wordingGuardAndItsNineteenAssertionsAreByteForByteFrozen() throws IOException {
        String guard = readSource("main", GUARD_SOURCE);
        String guardTest = readSource("test", GUARD_TEST_SOURCE);
        assertEquals("WordingGuard.java 一字未动这条合同破了（LF 归一后的 sha256 对不上派卷快照）",
                GUARD_SHA256, sha256Normalized(guard));
        assertEquals("WordingGuardTest.java 一字未动这条合同破了（LF 归一后的 sha256 对不上派卷快照）",
                GUARD_TEST_SHA256, sha256Normalized(guardTest));
        assertEquals("WordingGuardTest 的 @Test 条数不再是那 19 条",
                WORDING_GUARD_TEST_ASSERTIONS, countMatches(codeOnly(guardTest), TEST_ANNOTATION));
    }

    /** ⑨ 11 词合同逐字复述一遍并按顺序对齐运行时值（既有契约，本卷不改一个字）。 */
    @Test
    public void blackListIsStillExactlyTheElevenExecutionPhrasesInOrder() {
        assertEquals(BLACKLIST_SIZE, WordingGuard.BLACKLIST.size());
        assertEquals(ELEVEN_PHRASES, new ArrayList<>(WordingGuard.BLACKLIST));
    }

    /** ⑩ 第二把尺子：11 个词作为裸子串也不许出现在任何一条真实文案里（与 ① 的包装路口径互补）。 */
    @Test
    public void noBlacklistedPhraseIsASubstringOfAnyHelpCopyEither() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        String code = codeOnly(readSource("main", HELP_SOURCE));
        List<String> codeLines = linesOf(code);
        keys.addAll(caseKeysOf(methodBodyOf(codeLines, TITLE_SIG)));
        keys.addAll(caseKeysOf(methodBodyOf(codeLines, SUMMARY_SIG)));
        keys.addAll(caseKeysOf(methodBodyOf(codeLines, TEXT_SIG)));
        for (String group : caseKeysOf(methodBodyOf(codeLines, ITEM_KEYS_SIG))) {
            keys.addAll(SettingHelpContent.itemKeys(group));
        }
        for (String key : keys) {
            for (String term : ELEVEN_PHRASES) {
                for (String accessor : Arrays.asList("title", "summary", "text")) {
                    String copy = callAccessor(accessor, key);
                    if (copy != null && copy.contains(term)) {
                        offenders.add(accessor + "(" + key + ") 含「" + term + "」");
                    }
                }
            }
        }
        assertTrue("执行类措辞作为子串进了真实文案：" + offenders, offenders.isEmpty());
        assertTrue("这一把尺子的取键面少于本轮实测的 154 个键（title+summary+text 的 case 键 + 组成员键）",
                keys.size() >= MIN_SCANNED_KEY_UNITS);
    }

    /* ================= 任务书 34R-2 补的三把兜底锁 ================= */

    /**
     * ⑪ <b>主锁：逐键对照「返回值 ≠ 本访问器自己的 {@code default} 兜底串」</b>，分访问器报数。
     *
     * <p><b>这条为什么非装不可</b>：①／⑦ 只看「返回串非空＋串里 0 命中」，而那三条兜底串本身既非空
     * 也无违禁词；键面又是从源码 {@code case} 标签取的，②／⑥ 的条数下限照样不动。于是「扫了 207 个
     * 键单元各自的本名文案」与「只扫了 3 条兜底串」在旧那 10 条里读数完全一样——判卷者那次在每个字符串
     * 访问器开头插 {@code key = key + "!";}（每个键都落到自己 switch 的 {@code default}）的突变，
     * 旧 10 条全绿，本条按设计必红。
     *
     * <p><b>口径</b>：兜底串从源码那条 {@code default} 取（与 ⑤ 同一把尺子），本类不抄字面量；判据只有
     * 「逐键都 ≠」一种写法，不许写成「允许 K 个键落到兜底」。扫描面 69（title）＋16（summary）＋
     * 69（text 自身 case 键）＋53（16 个组键的成员键送进 text）＝207 个键单元，只许 ≥。
     * 末尾三条 {@code assertEquals} 是反空转控制：不属于任何 case 的键必须<b>真能</b>走到那条兜底串，
     * 否则「≠」是比了个寂寞。
     */
    @Test
    public void noRealKeyFallsBackToItsOwnAccessorDefaultCopy() throws IOException {
        List<String> codeLines = helpCodeLines();
        FallbackTally title = new FallbackTally("title",
                requireStringDefault("title", defaultCopyOf(methodBodyOf(codeLines, TITLE_SIG))));
        FallbackTally summary = new FallbackTally("summary",
                requireStringDefault("summary", defaultCopyOf(methodBodyOf(codeLines, SUMMARY_SIG))));
        FallbackTally text = new FallbackTally("text",
                requireStringDefault("text", defaultCopyOf(methodBodyOf(codeLines, TEXT_SIG))));

        for (String key : caseKeysOf(methodBodyOf(codeLines, TITLE_SIG))) {
            title.check(key, SettingHelpContent.title(key));
        }
        for (String key : caseKeysOf(methodBodyOf(codeLines, SUMMARY_SIG))) {
            summary.check(key, SettingHelpContent.summary(key));
        }
        for (String key : caseKeysOf(methodBodyOf(codeLines, TEXT_SIG))) {
            text.check(key, SettingHelpContent.text(key));
        }
        int memberUnits = 0;
        for (String group : caseKeysOf(methodBodyOf(codeLines, ITEM_KEYS_SIG))) {
            for (String member : SettingHelpContent.itemKeys(group)) {
                memberUnits++;
                text.check(group + "->" + member, SettingHelpContent.text(member));
            }
        }

        System.out.println("[b34r2 per-key fallback lock] " + title.describe() + " | " + summary.describe()
                + " | " + text.describe() + " | 组键成员单元 " + memberUnits);

        assertTrue("⑪ 自己的扫描面不许空转：title 逐键对照只有 " + title.checked + " 个键单元，少于本轮实测的 "
                + MIN_TITLE_KEYS + "（取键面一缩水，「逐键都 ≠」就成了比了个寂寞）", title.checked >= MIN_TITLE_KEYS);
        assertTrue("⑪ 自己的扫描面不许空转：summary 逐键对照只有 " + summary.checked + " 个键单元，少于本轮实测的 "
                + MIN_SUMMARY_KEYS, summary.checked >= MIN_SUMMARY_KEYS);
        assertTrue("⑪ 自己的扫描面不许空转：text 逐键对照（含组键成员）只有 " + text.checked + " 个键单元，少于本轮实测的 "
                + (MIN_TEXT_KEYS + MIN_TEXT_MEMBER_UNITS), text.checked >= MIN_TEXT_KEYS + MIN_TEXT_MEMBER_UNITS);
        assertEquals("⑪ 的读数里混进了 null／空串，「≠ 兜底」会因此变松（" + title.describe() + " | "
                + summary.describe() + " | " + text.describe() + "）",
                0, title.nullOrEmpty + summary.nullOrEmpty + text.nullOrEmpty);
        assertTrue("⑪ 主判据：有在册键走到的返回值等于本访问器那条 default 兜底串——这条扫描路正在把"
                + "「一堆各自的本名文案」读成「几条兜底串」，属于发现，不许放松判据。"
                + title.describe() + " | " + summary.describe() + " | " + text.describe(),
                title.fallenBack == 0 && summary.fallenBack == 0 && text.fallenBack == 0);

        assertEquals("反空转控制：title 用一个不属于任何 case 的键必须真走到它自己那条兜底串，"
                + "否则上面那条「≠」永远不可能红", title.defaultCopy, SettingHelpContent.title(UNREACHABLE_KEY));
        assertEquals("反空转控制：summary 同上", summary.defaultCopy,
                SettingHelpContent.summary(UNREACHABLE_KEY));
        assertEquals("反空转控制：text 同上", text.defaultCopy, SettingHelpContent.text(UNREACHABLE_KEY));
    }

    /**
     * ⑫ 三条字符串兜底串彼此<b>互不相同</b>：只要有人把 {@code title} 的兜底改成与 {@code text} 同一条
     * 通用串，⑪ 的「≠」就又被一条串糊过去了，所以这条与 ⑪ 成对。源码面与运行时面各比一遍（两两三条）。
     */
    @Test
    public void theThreeStringFallbackCopiesAreMutuallyDistinct() throws IOException {
        List<String> codeLines = helpCodeLines();
        String titleDefault = requireStringDefault("title", defaultCopyOf(methodBodyOf(codeLines, TITLE_SIG)));
        String summaryDefault = requireStringDefault("summary", defaultCopyOf(methodBodyOf(codeLines, SUMMARY_SIG)));
        String textDefault = requireStringDefault("text", defaultCopyOf(methodBodyOf(codeLines, TEXT_SIG)));

        assertNotEquals("⑫ title 与 summary 的 default 兜底串撞成同一条：一条通用兜底能把 ⑪ 糊过去",
                titleDefault, summaryDefault);
        assertNotEquals("⑫ title 与 text 的 default 兜底串撞成同一条：同上", titleDefault, textDefault);
        assertNotEquals("⑫ summary 与 text 的 default 兜底串撞成同一条：同上", summaryDefault, textDefault);

        String titleReached = SettingHelpContent.title(UNREACHABLE_KEY);
        String summaryReached = SettingHelpContent.summary(UNREACHABLE_KEY);
        String textReached = SettingHelpContent.text(UNREACHABLE_KEY);
        assertNotEquals("⑫ 运行时面 title／summary 走到的兜底串撞了", titleReached, summaryReached);
        assertNotEquals("⑫ 运行时面 title／text 走到的兜底串撞了", titleReached, textReached);
        assertNotEquals("⑫ 运行时面 summary／text 走到的兜底串撞了", summaryReached, textReached);
        System.out.println("[b34r2 distinct-fallback lock] 三条字符串兜底串互不相同，长度 "
                + titleReached.length() + "/" + summaryReached.length() + "/" + textReached.length());
    }

    /**
     * ⑬ {@code itemKeys} 那一支的兜底<b>按形状</b>单独钉（派单板 2：它不是字符串兜底）：源码面那条
     * {@code default} 必须是 {@code Collections.emptyList()}，运行时面用不属于任何 case 的键取到的是
     * size 恰为 0 的空列表；16 个在册组键一个都不许落到那条兜底——组键一旦整批落到兜底，53 个送进
     * {@code text(...)} 的成员键单元会一起蒸发，而 ②／⑥ 从源码取键的读数照样不变。
     */
    @Test
    public void itemKeysFallbackIsAnEmptyListAndNoRegisteredGroupKeyReachesIt() throws IOException {
        List<String> codeLines = helpCodeLines();
        String itemKeysBody = methodBodyOf(codeLines, ITEM_KEYS_SIG);
        assertTrue("⑬ itemKeys() 的 default 不再是 Collections.emptyList()（形状变了，本卷按形状钉它的前提就破了，"
                + "先核对生产侧再谈扫描）", DEFAULT_EMPTY_LIST.matcher(itemKeysBody).find());
        assertTrue("⑬ itemKeys() 那一支不该有字符串兜底串（有的话 ⑤ 的「四条兜底」面就对不上）",
                defaultCopyOf(itemKeysBody) == null);

        List<String> fallback = SettingHelpContent.itemKeys(UNREACHABLE_KEY);
        assertEquals("⑬ 兜底形状：不属于任何 case 的键必须拿到 size 恰为 0 的空列表", 0, fallback.size());
        assertEquals("⑬ 兜底形状：与 Collections.emptyList() 等值", Collections.<String>emptyList(), fallback);

        List<String> groupKeys = caseKeysOf(itemKeysBody);
        assertTrue("⑬ 组键只有 " + groupKeys.size() + " 个，少于本轮实测的 " + MIN_GROUP_KEYS,
                groupKeys.size() >= MIN_GROUP_KEYS);
        List<String> collapsedGroups = new ArrayList<>();
        int memberUnits = 0;
        for (String group : groupKeys) {
            List<String> members = SettingHelpContent.itemKeys(group);
            if (members.size() == 0) {
                collapsedGroups.add(group);
            }
            memberUnits += members.size();
        }
        System.out.println("[b34r2 itemKeys shape lock] 组键 " + groupKeys.size() + " 个，成员单元合计 "
                + memberUnits + " 个，落到空列表兜底的组键 " + collapsedGroups.size() + " 个");
        assertTrue("⑬ 有在册组键走到空列表兜底（成员键整批消失＝扫描路又开始空转）：" + collapsedGroups
                + "；当前组键 " + groupKeys.size() + " 个、成员单元 " + memberUnits + " 个",
                collapsedGroups.isEmpty());
        assertTrue("⑬ 组键成员单元合计 " + memberUnits + " 少于本轮实测的 " + MIN_TEXT_MEMBER_UNITS
                + "（送进 text 的那一路缩水）", memberUnits >= MIN_TEXT_MEMBER_UNITS);
    }
}
