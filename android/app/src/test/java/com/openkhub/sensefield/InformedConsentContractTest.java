package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * 任务书 49：把 {@code validation/protocols/INFORMED_CONSENT.md} 的八节正文、
 * 每条 {@code 文件:行号} 落点、两个同意键、真实弹窗文案与「关闭不清标记」的现状，
 * 全部接到可复跑的 JVM 判据上。
 *
 * <p><b>两条边界，先摆清楚：</b>
 * <ul>
 *   <li>本类只读源码与文档，不启动 Android、不连设备；读屏听感、按钮是否同样显著、
 *       法律效力都不在本类的证明范围内，文档里也按欠账写着。</li>
 *   <li>「关闭不会清除同意标记」是<b>双向锁</b>：既断言 {@code AssistantSettingsActivity.java:114}
 *       那一支现在只写 {@code key=false}、整个 {@code consentSwitch} 方法只有同意按钮写 consent，
 *       又要求文档逐字写着这句话。将来谁把撤回修成「顺带清除同意」，本类会红，提醒同步改文档。</li>
 * </ul>
 */
public final class InformedConsentContractTest {

    private static final String DOC_PATH = "validation/protocols/INFORMED_CONSENT.md";
    private static final String PACKAGE_DIR = "android/app/src/main/java/com/openkhub/sensefield";
    private static final String TRIAL_KIT = "validation/protocols/PLAYER_TRIAL_KIT.md";

    private static final String[] HEADINGS = {
            "一、这是什么与适用范围",
            "二、我们读什么、不读什么",
            "三、风险告知",
            "四、你的权利与撤回",
            "五、第三方与数据留存",
            "六、无障碍呈现",
            "七、理解度自检",
            "八、现状与待建（如实）",
    };

    private static final Pattern REFERENCE =
            Pattern.compile("([A-Za-z0-9_./-]+\\.(?:java|md|xml)):(\\d+)");

    /* ---------- ① 八节齐、逐字在、每节正文 ≥80 字 ---------- */

    @Test
    public void allEightSectionHeadingsArePresentVerbatimAndInOrder() {
        String doc = document();
        int previous = -1;
        for (String heading : HEADINGS) {
            String marker = "## " + heading;
            int at = doc.indexOf(marker);
            assertTrue("缺少小标题（或不是逐字一致）：" + marker, at >= 0);
            assertTrue("小标题顺序不对：" + marker, at > previous);
            assertEquals("小标题出现了不止一次：" + marker,
                    1, count(doc, marker));
            previous = at;
        }
    }

    @Test
    public void everySectionBodyHasAtLeastEightyNonWhitespaceCharacters() {
        String doc = document();
        for (String heading : HEADINGS) {
            String body = sectionBody(doc, heading);
            int size = nonWhitespaceCount(body);
            assertTrue("这一节正文不足 80 字（实测 " + size + "）：" + heading,
                    size >= 80);
        }
    }

    /* ---------- ② 每个 文件:行号 都指向真实文件、且总行数不小于该行号 ---------- */

    @Test
    public void everyFileAndLineReferenceResolvesToARealFileWithEnoughLines() throws IOException {
        String doc = document();
        Matcher matcher = REFERENCE.matcher(doc);
        List<String> failures = new ArrayList<>();
        int checked = 0;
        while (matcher.find()) {
            String name = matcher.group(1);
            int line = Integer.parseInt(matcher.group(2));
            File file = resolveReference(name);
            checked++;
            if (file == null) {
                failures.add(name + ":" + line + " → 找不到文件");
                continue;
            }
            int total = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).size();
            if (total < line) {
                failures.add(name + ":" + line + " → 文件只有 " + total + " 行");
            }
        }
        assertTrue("没有扫到 文件:行号，判据没接上电", checked >= 20);
        assertTrue("文档存在编造或漂移的落点：" + failures, failures.isEmpty());
    }

    /* ---------- ③ 两个同意键逐字落在 AssistantSettings.java:14／15 ---------- */

    @Test
    public void consentKeyNamesAppearVerbatimOnTheirDocumentedSourceLines() throws IOException {
        List<String> settings = sourceLines("AssistantSettings.java");
        assertTrue("AssistantSettings.java 不足 15 行", settings.size() >= 15);
        assertTrue("第 14 行不再是 assistant_audio_consent：",
                settings.get(13).contains("\"assistant_audio_consent\""));
        assertTrue("第 15 行不再是 assistant_image_consent：",
                settings.get(14).contains("\"assistant_image_consent\""));

        String doc = document();
        assertTrue("文档没有点名 assistant_audio_consent", doc.contains("assistant_audio_consent"));
        assertTrue("文档没有点名 assistant_image_consent", doc.contains("assistant_image_consent"));
    }

    /* ---------- ④ 不许出现承诺性的「一键清除／一键删除」 ---------- */

    @Test
    public void documentDoesNotPromiseAnUnimplementedOneClickClear() {
        String doc = document();
        assertTrue("文档必须如实写出现状没有该入口",
                doc.contains("目前没有一键清除入口"));

        int found = 0;
        for (String line : doc.split("\n")) {
            for (String clause : line.split("[。；;！？!?，,]")) {
                for (String phrase : new String[]{"一键清除", "一键删除"}) {
                    int at = clause.indexOf(phrase);
                    if (at < 0) continue;
                    found++;
                    String prefix = clause.substring(0, at);
                    boolean negated = prefix.contains("没有")
                            || prefix.contains("不存在")
                            || prefix.contains("无")
                            || prefix.contains("不提供")
                            || prefix.contains("尚未")
                            || prefix.contains("未实现")
                            || prefix.contains("待建")
                            || prefix.contains("不做")
                            || prefix.contains("不能");
                    assertTrue("出现承诺性说法（同一句没有否定词）：" + clause, negated);
                }
            }
        }
        assertTrue("连「没有」这句都没扫到，判据可能空转", found >= 1);
    }

    /* ---------- ⑤ 真实弹窗：两个按钮在；不许出现「默认同意」「不同意就无法使用」 ---------- */

    @Test
    public void realDialogStillOffersAgreeAndCancelWithoutCoerciveCopy() throws IOException {
        String activity = String.join("\n", sourceLines("AssistantSettingsActivity.java"));
        String method = methodBody(activity, "private void consentSwitch(");
        assertTrue("真实弹窗正按钮不再是「同意并开启」", method.contains("\"同意并开启\""));
        assertTrue("真实弹窗负按钮不再是「取消」", method.contains("\"取消\""));
        assertTrue("正按钮应排在负按钮之前",
                method.indexOf("\"同意并开启\"") < method.indexOf("\"取消\""));

        assertFalse("AssistantSettingsActivity.java 出现「默认同意」",
                activity.contains("默认同意"));
        assertFalse("AssistantSettingsActivity.java 出现「不同意就无法使用」",
                activity.contains("不同意就无法使用"));

        String helpSource = String.join("\n", sourceLines("SettingHelpContent.java"));
        Set<String> keys = new LinkedHashSet<>();
        Matcher cases = Pattern.compile("case\\s+\"([^\"]+)\"").matcher(helpSource);
        while (cases.find()) keys.add(cases.group(1));
        assertTrue("没取到 SettingHelpContent 的键，判据没接上电", keys.size() > 50);

        StringBuilder returnedCopy = new StringBuilder();
        for (String key : keys) {
            returnedCopy.append(SettingHelpContent.title(key)).append('\n');
            returnedCopy.append(SettingHelpContent.summary(key)).append('\n');
            returnedCopy.append(SettingHelpContent.text(key)).append('\n');
            for (String item : SettingHelpContent.itemKeys(key)) {
                returnedCopy.append(SettingHelpContent.text(item)).append('\n');
            }
        }
        assertFalse("SettingHelpContent 的取回值出现「默认同意」",
                returnedCopy.toString().contains("默认同意"));
        assertFalse("SettingHelpContent 的取回值出现「不同意就无法使用」",
                returnedCopy.toString().contains("不同意就无法使用"));
    }

    /* ---------- ⑥ 双向锁：:114 那一支现在不清 consent；文档也这样写 ---------- */

    @Test
    public void closingTheSwitchStillLeavesTheConsentMarkerSet() throws IOException {
        List<String> lines = sourceLines("AssistantSettingsActivity.java");
        assertTrue("AssistantSettingsActivity.java 不足 114 行", lines.size() >= 114);
        String line114 = lines.get(113);
        assertTrue("第 114 行不再是 if (!checked) 分支： " + line114,
                line114.contains("if (!checked)"));
        assertTrue("第 114 行不再只写 key=false： " + line114,
                line114.contains("putBoolean(key, false)"));

        String source = String.join("\n", lines);
        String method = methodBody(source, "private void consentSwitch(");
        String branch = branchBody(method, "if (!checked)");
        assertTrue("关闭分支必须把功能开关写回 false", branch.contains("putBoolean(key, false)"));
        assertFalse("关闭分支开始清除 consent 键了：" + branch, branch.contains("consent"));
        assertFalse("关闭分支出现 remove(...)：" + branch, branch.contains("remove("));
        assertEquals("consentSwitch 里写 consent 的位置只许有同意按钮那一处",
                1, count(method, "putBoolean(consent"));
        assertFalse("consentSwitch 里出现了清除 consent 的 remove(...)",
                method.contains("remove(consent"));
    }

    @Test
    public void documentStatesThatClosingDoesNotClearTheConsentMarker() {
        String doc = document();
        assertTrue("文档缺少逐字声明：关闭不会清除同意标记",
                doc.contains("关闭不会清除同意标记"));
        assertTrue("文档没有把这句话落到 :114", doc.contains("AssistantSettingsActivity.java:114"));
    }

    /* ---------- ⑦ 不许绝对承诺 ---------- */

    @Test
    public void documentMakesNoAbsolutePromise() {
        String doc = document();
        assertFalse("文档出现「保证不封号」", doc.contains("保证不封号"));
        assertFalse("文档出现「绝对不会」", doc.contains("绝对不会"));
    }

    /* ---------- 附加：理解度题 ≥4、每题带答案与答错处理；交叉引用可核 ---------- */

    @Test
    public void comprehensionCheckHasAtLeastFourQuestionsWithAnswersAndRemedies() {
        String doc = document();
        int answers = count(doc, "正确答案：");
        int remedies = count(doc, "答错怎么办：");
        assertTrue("理解度题少于 4 道（答案数 " + answers + "）", answers >= 4);
        assertEquals("有题缺「答错怎么办」", answers, remedies);
        assertTrue("没有题号", count(doc, "**题 ") >= 4);
    }

    @Test
    public void documentCrossReferencesTheTrialKitSectionSeventeen() throws IOException {
        List<String> kit = Files.readAllLines(repoRoot().toPath().resolve(TRIAL_KIT),
                StandardCharsets.UTF_8);
        assertTrue("PLAYER_TRIAL_KIT.md 不足 17 行", kit.size() >= 17);
        assertEquals("PLAYER_TRIAL_KIT.md:17 不再是试用前说明与授权",
                "## 试用前说明与授权", kit.get(16).trim());
        String doc = document();
        assertTrue("文档没有引用 PLAYER_TRIAL_KIT.md:17", doc.contains("PLAYER_TRIAL_KIT.md:17"));
        assertTrue("文档没有写明两份授权不互相替代", doc.contains("不互相替代"));
    }

    /* ---------- ⑫⑬ 正面声明与「假话」防线（判卷者 2026-10-21 暗卷补） ---------- */

    @Test
    public void positiveDataScopeClaimsAreStatedVerbatim() {
        String sectionTwo = sectionBody(document(), "二、我们读什么、不读什么");
        for (String claim : new String[]{
                "**两条 AI 专项各自独立同意。**",
                "**不读聊天记录。**",
                "**不读游戏内存。**",
                "**不注入、不模拟点击。**"}) {
            assertTrue("第二节缺少这句正面声明：" + claim, sectionTwo.contains(claim));
        }
        String sectionFive = sectionBody(document(), "五、第三方与数据留存");
        assertTrue("第五节缺少「原始语音不上传、不保存」这句正面声明",
                sectionFive.contains("原始语音只在手机内存里临时处理，不上传、不保存音频文件"));
        assertTrue("第五节缺少安装标识的用途与落点",
                sectionFive.contains("X-SenseField-Installation"));
    }

    /**
     * ⑬ 第五节里凡提到上传／训练／出售，同一行必须给出 {@code 文件:行号} 落点，
     * 或明确标成待建／没有答案／需向服务方确认。
     *
     * <p>起因是判卷者的一刀：往第五节插一句「语音提问的音频也会上传服务端，用于改进语音识别模型」，
     * 当时 11 条判据全绿——因为旧判据只认识「一键清除」那一句。本测试补的就是这类「没有落点的数据流说法」。
     */
    @Test
    public void everyDataFlowClaimInSectionFiveCarriesALandingOrIsMarkedPending() {
        String sectionFive = sectionBody(document(), "五、第三方与数据留存");
        Pattern risky = Pattern.compile("上传|训练|出售|售卖");
        Pattern landing = Pattern.compile("[A-Za-z0-9_./-]+\\.(?:java|md|xml):\\d+");
        String[] pending = {"待建", "现状没有", "没有答案", "需向服务提供方确认"};
        int checked = 0;
        for (String line : sectionFive.split("\n")) {
            if (!risky.matcher(line).find()) continue;
            checked++;
            boolean ok = landing.matcher(line).find();
            for (String marker : pending) {
                if (line.contains(marker)) ok = true;
            }
            assertTrue("这句数据流说法既没有 文件:行号 落点，也没标待建／没有答案：" + line.trim(), ok);
        }
        assertTrue("第五节一条数据流说法都没扫到，这条判据在空转", checked >= 1);
    }

    /* ---------- 支撑：只读、无 Android 依赖 ---------- */

    private static String document() {
        try {
            return new String(Files.readAllBytes(repoRoot().toPath().resolve(DOC_PATH)),
                    StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("读不到 " + DOC_PATH + "，判据没接上电", error);
        }
    }

    private static String sectionBody(String doc, String heading) {
        String marker = "## " + heading;
        int at = doc.indexOf(marker);
        assertTrue("缺少小标题：" + marker, at >= 0);
        int bodyStart = doc.indexOf('\n', at);
        assertTrue("小标题后没有换行：" + marker, bodyStart >= 0);
        int next = doc.indexOf("\n## ", bodyStart + 1);
        return next < 0 ? doc.substring(bodyStart + 1) : doc.substring(bodyStart + 1, next);
    }

    private static int nonWhitespaceCount(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); ) {
            int cp = value.codePointAt(i);
            if (!Character.isWhitespace(cp)) count++;
            i += Character.charCount(cp);
        }
        return count;
    }

    private static List<String> sourceLines(String simpleName) throws IOException {
        File file = new File(repoRoot(), PACKAGE_DIR + "/" + simpleName);
        assertTrue("找不到被测源文件：" + simpleName, file.isFile());
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    }

    private static File resolveReference(String name) {
        File root = repoRoot();
        if (name.indexOf('/') >= 0) {
            File direct = new File(root, name);
            return direct.isFile() ? direct : null;
        }
        String[] roots = {
                PACKAGE_DIR,
                "android/app/src/main/res/xml",
                "android/app/src/main",
                "validation/protocols",
                "",
        };
        for (String directory : roots) {
            File candidate = directory.isEmpty()
                    ? new File(root, name)
                    : new File(root, directory + "/" + name);
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    private static File repoRoot() {
        File directory = new File("").getAbsoluteFile();
        for (int up = 0; directory != null && up <= 8; up++, directory = directory.getParentFile()) {
            if (new File(directory, DOC_PATH).isFile()
                    && new File(directory, PACKAGE_DIR + "/AssistantSettings.java").isFile()
                    && new File(directory, TRIAL_KIT).isFile()) {
                return directory;
            }
        }
        throw new IllegalStateException("找不到仓库根（向上八级都没有 " + DOC_PATH + "）");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue("找不到方法：" + signature, start >= 0);
        int open = source.indexOf('{', start);
        assertTrue("方法没有方法体：" + signature, open >= 0);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return source.substring(open, i + 1);
            }
        }
        throw new IllegalStateException("方法体括号不配平：" + signature);
    }

    private static String branchBody(String method, String condition) {
        int at = method.indexOf(condition);
        assertTrue("找不到分支：" + condition, at >= 0);
        int open = method.indexOf('{', at);
        assertTrue("分支没有块体：" + condition, open >= 0);
        int depth = 0;
        for (int i = open; i < method.length(); i++) {
            char c = method.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return method.substring(open, i + 1);
            }
        }
        throw new IllegalStateException("分支括号不配平：" + condition);
    }

    private static int count(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) return count;
            count++;
            from = at + needle.length();
        }
    }
}
