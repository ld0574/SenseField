package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * 「保存诊断包」那条路上新接的脱敏标注出口的纯 JVM 单测：期望值全部手写死，一律不从被测方法的输出反推。
 *
 * <p>锁住四件事：
 * <ol>
 *   <li>① 挑源方法不会把图片二进制或整段原始事件日志塞进标注（白名单只有 metadata.json／summary.json，
 *       外加单份长度上限）；</li>
 *   <li>② 空会话返回空列表而不是崩（导出器对空文本会抛 {@link IllegalArgumentException}，空挡必须在挑源这一层拦掉）；</li>
 *   <li>③ 传给导出器的每个 {@link JevAnnotationExporter.Source#text} 都过得了占位符：产出行里
 *       「含用户目录数字段／盘符串／机型串／聊天软件名」的判断全为假；</li>
 *   <li>④ 状态行文案带着「几条」与「替换了几处」两个数字读数，替换数即使是 0 也不许在界面上省掉。</li>
 * </ol>
 *
 * <p>被测的两个方法是 {@code DiagnosticsActivity} 里的<b>私有</b>静态纯函数（任务书要求「私有」，
 * 所以这里用反射调用，而不是把可见性放宽给测试）。本类不 import 任何 {@code android.*}、不联网、
 * 不实例化界面，也不写死任何绝对路径。
 */
public class DiagnosticsAnnotationRouteTest {

    /** 合成夹具用的身份串（不是任何真实机器上的值）：用户目录数字段、盘符串、机型串、聊天软件名。 */
    private static final String FAKE_USER_DIR = "C:\\\\Users\\\\987654\\\\work\\\\sensefield";
    private static final String FAKE_DRIVE_PATH = "D:\\\\diag\\\\cache";
    private static final String FAKE_MODEL = "Redmi K40";
    private static final String FAKE_CHAT_APP = "WeChat";

    /** 原始事件日志的形状：一整段 JSONL，标记串用来证明它一个字都没进标注。 */
    private static final String RAW_LOG_MARKER = "RAW_EVENT_LOG_MARKER";

    /**
     * 身份扫描面，独立于被测规则表手写：用户目录（含数字段）、盘符绝对路径、网盘目录名、
     * 手机品牌机型串、聊天软件名。产出行里任何一条命中都算漏脱敏。
     */
    private static final Pattern[] IDENTITY_SCAN = {
            Pattern.compile("(?i)[a-z]:[\\\\/]{1,2}(?:users|home)[\\\\/]"),
            Pattern.compile("(?i)(?:users|home)[\\\\/]{1,2}[0-9]{3,}"),
            Pattern.compile("(?i)[a-z]:[\\\\/]{1,2}[^\\\\s\"']"),
            Pattern.compile("(?i)appdata"),
            Pattern.compile("(?i)onedrive|skydrive|dropbox|google[ _-]?drive|gdrive|icloud|"
                    + "百度网盘|腾讯微云|天翼云盘|坚果云|阿里云盘|夸克网盘"),
            Pattern.compile("(?i)huawei|honor|xiaomi|redmi|oppo|vivo|realme|oneplus|samsung|galaxy|"
                    + "motorola|nubia|meizu|google[ _-]?pixel|mate[ ]?[0-9]+|"
                    + "华为|荣耀|小米|红米|一加|三星|魅族|努比亚"),
            Pattern.compile("(?i)wechat|weixin|mqq|dingtalk|feishu|telegram|whatsapp|slack|"
                    + "messenger|微信|钉钉|飞书|企业微信"),
    };

    /* ==================== 反射入口：被测的两个私有静态纯函数 ==================== */

    @SuppressWarnings("unchecked")
    private static List<JevAnnotationExporter.Source> pick(Map<String, String> texts) throws Exception {
        Method method = DiagnosticsActivity.class.getDeclaredMethod("annotationSources", Map.class);
        method.setAccessible(true);
        return (List<JevAnnotationExporter.Source>) method.invoke(null, texts);
    }

    private static String readout(JevAnnotationExporter.Result result) throws Exception {
        Method method = DiagnosticsActivity.class.getDeclaredMethod("annotationReadout",
                JevAnnotationExporter.Result.class);
        method.setAccessible(true);
        return (String) method.invoke(null, result);
    }

    /* ==================== 夹具 ==================== */

    /** 一份带全部四类身份串的结构化读数；JSON 里的反斜杠按 JSON 规则转义。 */
    private static String identityBearingMetadata() {
        return "{\"schema\":\"sensefield.diagnostics\",\"session_id\":\"diag-1-00000000-0000-0000-0000-000000000000\","
                + "\"model\":\"" + FAKE_MODEL + "\","
                + "\"cache_dir\":\"" + FAKE_USER_DIR + "\","
                + "\"cache_root\":\"" + FAKE_DRIVE_PATH + "\","
                + "\"share_target\":\"" + FAKE_CHAT_APP + "\"}";
    }

    private static String summaryReading() {
        return "{\"outcome\":\"abstained\",\"frames_processed\":42,\"speech_completed\":false,"
                + "\"first_frame_path\":\"" + FAKE_USER_DIR + "\\\\images\"}";
    }

    /** 一整段原始事件日志（JSONL，不是单个 JSON 对象），外加一条身份串。 */
    private static String rawEventLog() {
        StringBuilder sb = new StringBuilder(RAW_LOG_MARKER);
        for (int i = 0; i < 400; i++) {
            sb.append("\n{\"elapsed_ms\":").append(i).append(",\"event\":\"frame\"}");
        }
        return sb.append("\n").append(FAKE_USER_DIR).toString();
    }

    /** 一段「像 JPEG 头部」的二进制载荷（用 ISO-8859-1 摊成字符串，标记字节足够判形）。 */
    private static String imageBytes() {
        byte[] header = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
                'J', 'F', 'I', 'F', 0x00, 0x01, 0x02, 0x03, (byte) 0xFF, (byte) 0xD9};
        return new String(header, StandardCharsets.ISO_8859_1);
    }

    /** 一份「典型会话目录」的全部候选文本：两份结构化读数 ＋ 原始日志 ＋ 图片 ＋ 元素小图。 */
    private static Map<String, String> sessionTexts() {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("metadata.json", identityBearingMetadata());
        texts.put("summary.json", summaryReading());
        texts.put("events.jsonl", rawEventLog());
        texts.put("images/screen-1-1.jpg", imageBytes());
        texts.put("elements/u1-1.png", imageBytes());
        return texts;
    }

    private static List<String> namesOf(List<JevAnnotationExporter.Source> sources) {
        List<String> out = new ArrayList<>();
        for (JevAnnotationExporter.Source source : sources) {
            out.add(source.name);
        }
        return out;
    }

    private static List<String> linesOf(String jsonLines) {
        List<String> out = new ArrayList<>();
        for (String line : jsonLines.split("\n")) {
            if (!line.trim().isEmpty()) out.add(line);
        }
        return out;
    }

    /** 产出里是否还留着任何身份字面量（③ 的判断口径，四条都要为假）。 */
    private static List<String> identityHits(String value) {
        List<String> hits = new ArrayList<>();
        for (Pattern pattern : IDENTITY_SCAN) {
            Matcher matcher = pattern.matcher(value);
            if (matcher.find()) hits.add(pattern + " -> " + matcher.group());
        }
        return hits;
    }

    private static Map<String, Integer> counts(String... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], Integer.parseInt(pairs[i + 1]));
        }
        return map;
    }

    /* ==================== ① 挑源：图片二进制与整段原始日志进不来 ==================== */

    /* 1. 白名单只有两份结构化读数，原始事件日志与图片一律被挡在外面。 */
    @Test
    public void pickerKeepsOnlyStructuredReadings() throws Exception {
        List<JevAnnotationExporter.Source> picked = pick(sessionTexts());
        assertEquals(Arrays.asList("metadata.json", "summary.json"), namesOf(picked));
    }

    /* 2. 挑出来的文本里不许有原始日志的标记串，也不许有图片的魔数字节。 */
    @Test
    public void pickedTextsCarryNoRawLogAndNoImagePayload() throws Exception {
        for (JevAnnotationExporter.Source source : pick(sessionTexts())) {
            assertFalse(source.name + " 把整段原始日志塞进了标注", source.text.contains(RAW_LOG_MARKER));
            assertFalse(source.name + " 把图片二进制塞进了标注",
                    source.text.indexOf((char) 0xFF) >= 0 || source.text.contains("JFIF"));
        }
    }

    /* 3. 白名单文件被塞进超长文本（「原始日志改名塞进白名单」的形状）时按长度上限挡掉。 */
    @Test
    public void pickerDropsOversizedAndBlankWhitelistTexts() throws Exception {
        Map<String, String> sneaky = new LinkedHashMap<>();
        StringBuilder huge = new StringBuilder("{\"blob\":\"");
        for (int i = 0; i < 70000; i++) huge.append('x');
        huge.append("\"}");
        sneaky.put("metadata.json", huge.toString());
        sneaky.put("summary.json", "   \n  ");
        assertTrue("超长或空白文本不该进标注，实际 " + namesOf(pick(sneaky)), pick(sneaky).isEmpty());
    }

    /* ==================== ② 空会话返回空列表而不是崩 ==================== */

    /* 4. 空映射（没有一份读数的会话）返回空列表；null 映射同样不崩。 */
    @Test
    public void emptySessionReturnsEmptyListWithoutCrashing() throws Exception {
        assertTrue(pick(new LinkedHashMap<>()).isEmpty());
        assertTrue(pick(null).isEmpty());
        Map<String, String> onlyOtherFiles = new LinkedHashMap<>();
        onlyOtherFiles.put("events.jsonl", rawEventLog());
        assertTrue(pick(onlyOtherFiles).isEmpty());
    }

    /* 5. 空会话不会喂给导出器：调用方按「空列表＝这条记录没有可标注的文本读数」处理，导出器根本不会被惊动。 */
    @Test
    public void emptySessionNeverReachesTheExporter() throws Exception {
        List<JevAnnotationExporter.Source> picked = pick(new LinkedHashMap<>());
        assertEquals(0, picked.size());
        try {
            JevAnnotationExporter.export(java.util.Collections.singletonList(
                    new JevAnnotationExporter.Source("metadata.json", "   ")));
            org.junit.Assert.fail("空文本本就该被导出器拒绝，说明这一层的空挡不是摆设");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("empty text"));
        }
    }

    /* ==================== ③ 每个 Source.text 都过占位符 ==================== */

    /* 6. 挑出来的每一份源文本，自己就带着四类身份串（夹具正对照），过一遍脱敏后全部为假。 */
    @Test
    public void everyPickedSourceTextIsDesensitised() throws Exception {
        List<JevAnnotationExporter.Source> picked = pick(sessionTexts());
        boolean sawIdentity = false;
        for (JevAnnotationExporter.Source source : picked) {
            assertFalse(source.name + " 的源文本应按白名单可脱敏形状进入", identityHits(source.name).size() > 0);
            assertFalse("夹具失效：" + source.name + " 没带身份串", identityHits(source.text).isEmpty());
            List<String> leftovers = identityHits(JevAnnotationExporter.desensitize(source.text));
            assertTrue(source.name + " 脱敏后仍留着 " + leftovers, leftovers.isEmpty());
            sawIdentity = true;
        }
        assertTrue("正对照：夹具必须真的带身份串，否则 ③ 是空判", sawIdentity);
    }

    /* 7. 端到端：挑源 → 导出，产出的每一行都不含用户目录数字段／盘符串／机型串／聊天软件名，且替换计数非零。 */
    @Test
    public void exportedLinesAreFreeOfIdentityLiterals() throws Exception {
        JevAnnotationExporter.Result result = JevAnnotationExporter.export(pick(sessionTexts()));
        List<String> lines = linesOf(result.jsonLines);
        assertTrue("两份读数至少各产一行，实际 " + lines.size(), lines.size() >= 6);
        for (String line : lines) {
            List<String> leftovers = identityHits(line);
            assertTrue("产出行漏脱敏：" + leftovers + " <- " + line, leftovers.isEmpty());
            assertFalse(line.contains(RAW_LOG_MARKER));
        }
        assertTrue("产出里必须留下占位符", result.jsonLines.contains(JevAnnotationExporter.PLACEHOLDER_USER_DIR));
        assertTrue(result.jsonLines.contains(JevAnnotationExporter.PLACEHOLDER_DEVICE));
        assertTrue(result.jsonLines.contains(JevAnnotationExporter.PLACEHOLDER_CHAT_APP));
        assertTrue("有身份串就该有替换计数，实际 " + result.replacementCount, result.replacementCount > 0);
        assertEquals(result.unitCount, lines.size());
    }

    /* 8. 挑源是同一份输入的唯一入口：两次导出的文本逐字相同（可复现，语料才敢给社区）。 */
    @Test
    public void exportThroughThePickerIsReproducible() throws Exception {
        String first = JevAnnotationExporter.export(pick(sessionTexts())).jsonLines;
        String second = JevAnnotationExporter.export(pick(sessionTexts())).jsonLines;
        assertEquals(first, second);
    }

    /* ==================== ④ 状态行文案带两个数字读数 ==================== */

    /* 9. 手写期望值：「几条」与「替换了几处」两个数按字面进句子，顺序与措辞钉死。 */
    @Test
    public void statusReadoutCarriesBothNumericReadings() throws Exception {
        JevAnnotationExporter.Result result = new JevAnnotationExporter.Result("{}", 70, 3,
                counts(JevAnnotationExporter.CATEGORY_USER_DIR, "2",
                        JevAnnotationExporter.CATEGORY_DEVICE, "1"),
                counts("metadata.json", "66", "summary.json", "4"));
        assertEquals("已挑出 70 条脱敏标注，机器替换了 3 处身份路径，请在下一步选择保存位置", readout(result));
        assertTrue(readout(result).contains(String.valueOf(result.unitCount)));
        assertTrue(readout(result).contains(String.valueOf(result.replacementCount)));
    }

    /* 10. 替换数为 0 也必须在场：玩家看得见「机器替我改了几处」，0 也是同意的一部分。 */
    @Test
    public void statusReadoutKeepsTheReplacementCountEvenWhenZero() throws Exception {
        JevAnnotationExporter.Result clean = new JevAnnotationExporter.Result("{}", 12, 0,
                counts(), counts("metadata.json", "12"));
        String text = readout(clean);
        assertTrue("零替换也要播得出替换数：" + text, text.contains("替换了 0 处"));
        assertTrue(text.contains("12 条"));
    }

    /* 11. 大计数不被截断成「几千」这类约数：句子里出现的就是 Result 里的原始读数。 */
    @Test
    public void statusReadoutUsesRawReadoutsNotRoundedOnes() throws Exception {
        JevAnnotationExporter.Result busy = new JevAnnotationExporter.Result("{}", 1234, 5678,
                counts(JevAnnotationExporter.CATEGORY_REPO_ROOT, "5678"),
                counts("metadata.json", "1234"));
        String text = readout(busy);
        assertTrue(text, text.contains("1234"));
        assertTrue(text, text.contains("5678"));
        assertFalse(text.contains("1.2千"));
    }
}
