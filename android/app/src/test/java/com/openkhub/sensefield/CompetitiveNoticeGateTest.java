package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * 任务书 43·B 档的一次性风险告知闸门。
 *
 * <p>闸门是纯函数（三条入参一个出参），所以规则表逐格真跑；设置页只留一条放行判断，
 * 那条由源码级锁盯住——「被拦时值仍为关」如果只剩注释在承诺，这里就红。
 */
public final class CompetitiveNoticeGateTest {
    private static final String GATE_SOURCE = "CompetitiveNoticeGate.java";
    private static final String DEFAULTS_SOURCE = "CompetitiveDefaults.java";
    private static final String UI_SOURCE = "AlertSettingsActivity.java";

    /** 公告按效果口径点名的那些词，加 {@code WordingGuard.BLACKLIST}，文案一个都不许出现。 */
    private static final List<String> EXTRA_BANNED_TERMS = Arrays.asList(
            "外挂", "代练", "上分", "掉分", "透视", "自动过关", "自动连招", "自动点击",
            "自动执行", "帮你点", "替你操作", "代打", "胜率", "躺赢");

    /** ① 规则表逐格钉死：入参三值 ⇒ 唯一出参。 */
    @Test
    public void decisionTruthTableIsExactlyTheseEightRows() {
        assertEquals(CompetitiveNoticeGate.Outcome.NONE,
                CompetitiveNoticeGate.decide(false, false, false));
        assertEquals(CompetitiveNoticeGate.Outcome.NONE,
                CompetitiveNoticeGate.decide(false, true, false));
        assertEquals(CompetitiveNoticeGate.Outcome.NONE,
                CompetitiveNoticeGate.decide(true, false, false));
        assertEquals(CompetitiveNoticeGate.Outcome.NONE,
                CompetitiveNoticeGate.decide(true, true, false));
        assertEquals(CompetitiveNoticeGate.Outcome.SHOW_NOTICE,
                CompetitiveNoticeGate.decide(false, false, true));
        assertEquals(CompetitiveNoticeGate.Outcome.ENABLED,
                CompetitiveNoticeGate.decide(false, true, true));
        assertEquals(CompetitiveNoticeGate.Outcome.NONE,
                CompetitiveNoticeGate.decide(true, false, true));
        assertEquals(CompetitiveNoticeGate.Outcome.NONE,
                CompetitiveNoticeGate.decide(true, true, true));
    }

    /** ② 未看过告知时，主动开启任何一路都只返回 SHOW_NOTICE，且这一趟不许落 true。 */
    @Test
    public void unseenNoticeBlocksTheFirstActiveEnableWithoutWritingItOn() {
        CompetitiveNoticeGate.Outcome outcome = CompetitiveNoticeGate.decide(
                false, false, true);
        assertEquals(CompetitiveNoticeGate.Outcome.SHOW_NOTICE, outcome);
        assertFalse("SHOW_NOTICE 这一趟不许写值", CompetitiveNoticeGate.writesThrough(outcome));
        for (String key : Arrays.asList("cue_category_near", "cue_near_haptic", "cue_far_appear",
                "cue_category_peripheral", "cue_category_danger")) {
            // 装机读到的缺省值就是这条入参，所以「默认关」与「第一次开启被拦」是同一条链。
            assertEquals(key + " 装机后第一次主动开启必须只出告知",
                    CompetitiveNoticeGate.Outcome.SHOW_NOTICE,
                    CompetitiveNoticeGate.decide(CompetitiveDefaults.defaultFor(key), false, true));
        }
    }

    /** ③ 放行只认 ENABLED 与 NONE 两格；SHOW_NOTICE 是唯一不落值的一格。 */
    @Test
    public void onlyShowNoticeRefusesToWriteTheRequestedValue() {
        assertTrue(CompetitiveNoticeGate.writesThrough(CompetitiveNoticeGate.Outcome.NONE));
        assertTrue(CompetitiveNoticeGate.writesThrough(CompetitiveNoticeGate.Outcome.ENABLED));
        assertFalse(CompetitiveNoticeGate.writesThrough(CompetitiveNoticeGate.Outcome.SHOW_NOTICE));
    }

    /** ④ 告知看过后再开就能动，而且不重复打断。 */
    @Test
    public void onceTheNoticeWasSeenTheActiveEnableGoesThroughOnceAndQuietly() {
        assertEquals(CompetitiveNoticeGate.Outcome.ENABLED,
                CompetitiveNoticeGate.decide(false, true, true));
        assertTrue(CompetitiveNoticeGate.writesThrough(
                CompetitiveNoticeGate.decide(false, true, true)));
        // 已经开着的人再点别的路径也不该被重复告知：不是主动开启一律 NONE。
        assertEquals(CompetitiveNoticeGate.Outcome.NONE,
                CompetitiveNoticeGate.decide(true, true, false));
    }

    /** ⑤ 老用户显式 true：本卷不许因为改默认而把他挡在门外或替他改值。 */
    @Test
    public void anAlreadyOnCompetitiveKeyIsNeitherReblockedNorAnnounced() {
        for (boolean noticeSeen : new boolean[] {false, true}) {
            assertEquals("存过 true 的老用户不是本次新开启",
                    CompetitiveNoticeGate.Outcome.NONE,
                    CompetitiveNoticeGate.decide(true, noticeSeen, true));
        }
    }

    /** ⑥ 告知文案必须含两句事实，逐字。 */
    @Test
    public void noticeCopyCarriesBothMandatedFactsVerbatim() {
        String copy = String.join("\n", CompetitiveNoticeGate.NOTICE_LINES);
        assertTrue("缺了「不是医疗建议」那句：" + copy,
                copy.contains("这不是医疗建议，本应用不诊断任何疾病"));
        assertTrue("缺了「可能违反服务条款」那句：" + copy, copy.contains(
                "在竞技对局中使用画面辅助可能违反该游戏的服务条款，后果由账号持有人承担"));
        assertTrue("缺了「本次仍为关」那句：" + copy, copy.contains("现在这一路仍是关闭的"));
        assertFalse("文案不许承诺任何优势或结果", copy.contains("一定"));
    }

    /** ⑦ 文案过 {@code WordingGuard.BLACKLIST} 加公告点名词：实跑一遍，再逐字手查一遍。 */
    @Test
    public void noticeCopySurvivesBothTheWordingBlacklistAndTheAnnouncementTerms() {
        String copy = String.join("\n", CompetitiveNoticeGate.NOTICE_LINES) + "\n"
                + CompetitiveNoticeGate.NOTICE_TITLE;
        for (String term : WordingGuard.BLACKLIST) {
            assertFalse("黑名单词进了告知文案：" + term, copy.contains(term));
        }
        assertTrue(WordingGuard.checkText(GATE_SOURCE, copy).isEmpty());
        for (String term : EXTRA_BANNED_TERMS) {
            assertFalse("公告点名词进了告知文案：" + term, copy.contains(term));
        }
        // 告知不许替本卷背书：既不许说消除了风险，也不许说已经合规。
        assertFalse(copy.contains("消除"));
        assertFalse(copy.contains("合规"));
    }

    /** ⑧ 闸门与缺省表两个新类都是纯 JVM：零 {@code android.*} import。 */
    @Test
    public void bothNewClassesImportNothingFromAndroid() throws IOException {
        for (String name : Arrays.asList(GATE_SOURCE, DEFAULTS_SOURCE)) {
            List<String> lines = sourceOf(name);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                assertFalse(name + ":" + (i + 1) + " 引了 Android 类：" + line,
                        line.startsWith("import android."));
            }
            assertTrue(name + " 没写它承诺的纯 JVM 说明",
                    String.join("\n", lines).contains("纯 JVM"));
        }
    }

    /** ⑨ 闸门源码本身也不许留字面量黑名单词（守卫扫的是生产源码的行）。 */
    @Test
    public void theGateSourceHasZeroBlacklistHits() throws IOException {
        List<String> lines = sourceOf(GATE_SOURCE);
        assertTrue(WordingGuard.check(GATE_SOURCE, lines).isEmpty());
    }

    /** ⑩ 设置页只有一条放行闸门，写值语句必须排在它后面且全页只有一处。 */
    @Test
    public void settingsPageRoutesEveryWriteThroughTheSingleGate() throws IOException {
        String text = String.join("\n", sourceOf(UI_SOURCE));
        int gate = text.indexOf("CompetitiveNoticeGate.decide(");
        int refusal = text.indexOf("CompetitiveNoticeGate.writesThrough(");
        int write = text.indexOf("preferences.edit().putBoolean(key, checked).apply();");
        assertTrue("设置页没接判定闸门", gate > 0);
        assertTrue("设置页没按闸门结果决定放行", refusal > 0);
        assertTrue("写值排在闸门之前，等于绕闸", refusal < write);
        assertEquals("对抗性勾选的写值语句必须恰有一处", 1, countOf(text,
                "preferences.edit().putBoolean(key, checked).apply();"));
        assertTrue("被拦时没把勾选框退回原值",
                text.contains("((CheckBox) button).setChecked(stored)"));
        assertTrue("闸门只圈对抗性五键，非竞技那几路不进闸",
                text.contains("CompetitiveDefaults.isCompetitiveKey(key)"));
    }

    /** ⑪ 「已看过」那条键不是提示偏好：记它时不许顺手把档位改成自定义。 */
    @Test
    public void markingTheNoticeSeenDoesNotRewriteThePreset() throws IOException {
        String text = String.join("\n", sourceOf(UI_SOURCE));
        assertFalse(CompetitiveDefaults.isCompetitiveKey(CompetitiveNoticeGate.NOTICE_SEEN_KEY));
        String body = blockOf(text, "private void showCompetitiveNotice(");
        assertTrue("告知没落到既有卡片形态上：" + body, body.contains("UiKit.accentCard(this)"));
        assertTrue(body.contains("CompetitiveNoticeGate.NOTICE_LINES"));
        assertTrue(body.contains("CompetitiveNoticeGate.NOTICE_SEEN_KEY"));
        assertFalse("记「已看过」时不许改档位", body.contains("markCustom"));
        assertEquals("告知只许显示一次", 1, countOf(text, "UiKit.add(container, notice, 6);"));
    }

    private static int countOf(String text, String needle) {
        int n = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(needle, from);
            if (at < 0) return n;
            n++;
            from = at + needle.length();
        }
    }

    /** 取 {@code needle} 之后第一个配平的花括号块（含大括号），找不到就抛。 */
    private static String blockOf(String text, String needle) {
        int at = text.indexOf(needle);
        assertTrue("案卷里找不到这段接线：" + needle, at >= 0);
        int open = text.indexOf('{', at);
        assertTrue("方法没有方法体：" + needle, open >= 0);
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(open, i + 1);
            }
        }
        throw new IllegalStateException("方法体括号不配平：" + needle);
    }

    /** 找不到被测源文件就抛，不许静默假绿（照 ReadAloudDensityTest 的既有写法）。 */
    private static List<String> sourceOf(String simpleName) throws IOException {
        String[] candidates = {
                "src/main/java/com/openkhub/sensefield/" + simpleName,
                "android/app/src/main/java/com/openkhub/sensefield/" + simpleName,
        };
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; dir != null && up <= 5; up++, dir = dir.getParentFile()) {
            for (String rel : candidates) {
                File f = new File(dir, rel);
                if (f.isFile()) return Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("找不到被测源文件 " + simpleName + "，这条断言没接上电");
    }
}
