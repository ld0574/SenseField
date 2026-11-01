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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * 任务书 43·B 档的默认面仪器（三处九点）。
 *
 * <p>「新装机关了吗」「老用户的值被动了吗」两件事在这里都是<b>真跑</b>出来的：
 * {@link CueSettings#applyPreset} 是静态方法、只吃 {@code SharedPreferences}，测试用一只手写
 * 的内存偏好存储直接调用它，读回真实落库值。只有设置页那五个勾选框（需要 {@code Context}，
 * 全仓 119 个测试类零个起 Robolectric）退成源码级锁：五处缺省参数必须引用同一张共享表，
 * 漂开即红。
 *
 * <p>本类同时是对抗性的：<b>把任一处的新缺省翻回 {@code true} 必然变红</b>——改字面量撞
 * 「逐键取自共享表」那条，改共享表常量撞真跑的装机/老用户两条。
 */
public final class CompetitiveDefaultSurfaceTest {
    private static final String SETTINGS_SOURCE = "CueSettings.java";
    private static final String UI_SOURCE = "AlertSettingsActivity.java";

    /** 一键三样：键名令牌（字面量或常量）、该键在共享表里的缺省令牌、人类可读名。 */
    private static final class KeyExpectation {
        final String label;
        final List<String> keyTokens;
        final String defaultToken;

        KeyExpectation(String label, List<String> keyTokens, String defaultToken) {
            this.label = label;
            this.keyTokens = keyTokens;
            this.defaultToken = defaultToken;
        }
    }

    /** 对抗性五键的期望表，逐键比三处写法，别让 UI 与兜底漂开。 */
    private static final List<KeyExpectation> TABLE = Arrays.asList(
            new KeyExpectation("附近敌人提醒（小地图近区）", Arrays.asList(
                    "\"cue_category_near\"", "PREF_CATEGORY_NEAR",
                    "CompetitiveDefaults.CATEGORY_NEAR"), "CompetitiveDefaults.CATEGORY_NEAR_DEFAULT"),
            new KeyExpectation("附近敌人震动", Arrays.asList(
                    "\"cue_near_haptic\"", "PREF_NEAR_HAPTIC",
                    "CompetitiveDefaults.NEAR_HAPTIC"), "CompetitiveDefaults.NEAR_HAPTIC_DEFAULT"),
            new KeyExpectation("远处新敌人提示音", Arrays.asList(
                    "\"cue_far_appear\"", "PREF_FAR_APPEAR",
                    "CompetitiveDefaults.FAR_APPEAR"), "CompetitiveDefaults.FAR_APPEAR_DEFAULT"),
            new KeyExpectation("屏幕边缘威胁", Arrays.asList(
                    "\"cue_category_peripheral\"", "PREF_CATEGORY_PERIPHERAL",
                    "CompetitiveDefaults.CATEGORY_PERIPHERAL"),
                    "CompetitiveDefaults.CATEGORY_PERIPHERAL_DEFAULT"),
            new KeyExpectation("危险接近", Arrays.asList(
                    "\"cue_category_danger\"", "PREF_CATEGORY_DANGER",
                    "CompetitiveDefaults.CATEGORY_DANGER"), "CompetitiveDefaults.CATEGORY_DANGER_DEFAULT"));

    private static final List<String> FIVE_LITERALS = Arrays.asList("cue_category_near",
            "cue_near_haptic", "cue_far_appear", "cue_category_peripheral", "cue_category_danger");

    /** ① 共享表本身：五路一律关。翻回 true 时这一条与下面所有真跑条目一起红。 */
    @Test
    public void allFiveCompetitiveDefaultsAreOffInOneSharedTable() {
        assertFalse(CompetitiveDefaults.CATEGORY_NEAR_DEFAULT);
        assertFalse(CompetitiveDefaults.NEAR_HAPTIC_DEFAULT);
        assertFalse(CompetitiveDefaults.FAR_APPEAR_DEFAULT);
        assertFalse(CompetitiveDefaults.CATEGORY_PERIPHERAL_DEFAULT);
        assertFalse(CompetitiveDefaults.CATEGORY_DANGER_DEFAULT);
        for (String key : FIVE_LITERALS) {
            assertFalse(key + " 的缺省必须是关", CompetitiveDefaults.defaultFor(key));
        }
    }

    /** ② 全新装机（空偏好存储）跑一遍档位物化：五键全部落库为关。 */
    @Test
    public void freshInstallMaterializesAllFiveCompetitiveKeysAsOff() {
        FakePreferences store = new FakePreferences();
        CueSettings.applyPreset(store, CueSettings.PRESET_STANDARD);
        for (String key : FIVE_LITERALS) {
            assertTrue(key + " 装机时就该落库", store.contains(key));
            assertFalse(key + " 新装机关着", store.getBoolean(key, true));
        }
    }

    /** ③ 详细档位不再替玩家打开那几路，但它的非对抗性丰富度一项没少。 */
    @Test
    public void detailedPresetStopsTurningCompetitiveKeysOnButKeepsItsOwnRichness() {
        FakePreferences store = new FakePreferences();
        CueSettings.applyPreset(store, CueSettings.PRESET_DETAILED);
        for (String key : FIVE_LITERALS) {
            assertFalse("详细档位不许替玩家打开 " + key, store.getBoolean(key, true));
        }
        assertTrue("外观播报仍按详细档位", store.getBoolean("cue_speak_appear", false));
        assertEquals("语音间隔仍按详细档位", 1000, store.getInt("cue_vision_speech_gap_ms", 2000));
        assertTrue("通道缺省没被动", store.getBoolean("cue_channel_tone", false));
        assertTrue("小地图新目标这一路本卷无权改", store.getBoolean("cue_category_vision", false));
    }

    /** ④ 老用户显式 true 一律原样：跑真实档位重刷后读回仍是 true，contains 仍为真。 */
    @Test
    public void explicitTrueStoredByAnOldUserSurvivesEveryPresetRewrite() {
        for (String preset : Arrays.asList(CueSettings.PRESET_COMPACT,
                CueSettings.PRESET_STANDARD, CueSettings.PRESET_DETAILED)) {
            FakePreferences store = new FakePreferences();
            for (String key : FIVE_LITERALS) store.set(key, true);
            CueSettings.applyPreset(store, preset);
            for (String key : FIVE_LITERALS) {
                assertTrue(key + " 仍算设过值", store.contains(key));
                assertTrue(key + " 被 " + preset + " 档位静默改值了",
                        store.getBoolean(key, false));
            }
        }
    }

    /** ⑤ 反向锁：只护住了五键，非对抗性键的重刷行为一字未改。 */
    @Test
    public void presetRewriteStillOverwritesTheNonCompetitiveKeysItAlwaysOverwrote() {
        FakePreferences store = new FakePreferences();
        store.set("cue_speak_appear", true);
        store.set("cue_vision_speech_gap_ms", 1000);
        CueSettings.applyPreset(store, CueSettings.PRESET_STANDARD);
        assertFalse("非对抗性键的保护面本卷没扩大", store.getBoolean("cue_speak_appear", true));
        assertEquals(2000, store.getInt("cue_vision_speech_gap_ms", 1000));
    }

    /** ⑥ 设置页那五个勾选框：缺省参数逐键引用共享表，且装机时为 unchecked。 */
    @Test
    public void freshInstallShowsAllFiveCompetitiveTogglesUnchecked() throws IOException {
        List<String> lines = sourceOf(UI_SOURCE);
        String text = String.join("\n", lines);
        int checkedStatements = 0;
        for (KeyExpectation expectation : TABLE) {
            List<String> calls = callsMentioningAny(text, "addToggle", expectation.keyTokens);
            assertEquals(expectation.label + " 在设置页应恰有一处缺省参数", 1, calls.size());
            assertTrue(expectation.label + " 的 UI 缺省没引用共享表：" + calls.get(0),
                    calls.get(0).contains(expectation.defaultToken));
            checkedStatements++;
        }
        assertEquals("设置页对抗性缺省点总数漂了", 5, checkedStatements);
        // 装机（该键从未存过）时这一句就是勾选框状态：五路全不勾。
        FakePreferences store = new FakePreferences();
        for (String key : FIVE_LITERALS) {
            assertFalse("第一次进设置页 " + key + " 不该是勾上的",
                    store.getBoolean(key, CompetitiveDefaults.defaultFor(key)));
        }
    }

    /** ⑦ 三处九点互相一致：装机写值与读取兜底都只能引用同一张表，且缺省点总数不许漂。 */
    @Test
    public void theThreeDefaultWritingsAgreeKeyByKey() throws IOException {
        String text = String.join("\n", sourceOf(SETTINGS_SOURCE));
        int writeSites = 0;
        int readSites = 0;
        for (KeyExpectation expectation : TABLE) {
            List<String> writes = new ArrayList<>();
            writes.addAll(callsMentioningAny(text, "putBoolean", expectation.keyTokens));
            writes.addAll(callsMentioningAny(text, "putDefaultUnlessSet", expectation.keyTokens));
            List<String> reads = callsMentioningAny(text, "getBoolean", expectation.keyTokens);
            assertFalse(expectation.label + " 一处装机写值都没有", writes.isEmpty());
            assertEquals(expectation.label + " 的读取兜点多于一处", 1, reads.size());
            for (String call : writes) {
                assertTrue(expectation.label + " 的装机写值没引用共享表：" + call,
                        call.contains(expectation.defaultToken));
            }
            for (String call : reads) {
                assertTrue(expectation.label + " 的兜底没引用共享表：" + call,
                        call.contains(expectation.defaultToken));
                assertNoBareBooleanDefault(call, expectation.label);
            }
            writeSites += writes.size();
            readSites += reads.size();
        }
        // 九点清点：UI 5 处 + 装机写值 8 处（构造器三处、applyPreset 五处）+ 兜底 5 处。
        assertEquals("CueSettings 装机写值点总数漂了", 8, writeSites);
        assertEquals("CueSettings 读取兜底点总数漂了", 5, readSites);
    }

    /** ⑧ 兜底不许写成裸字面量：写裸值就是给「一半改了一半没改」留门。 */
    @Test
    public void noBareBooleanDefaultIsLeftAnywhereForTheCompetitiveKeys() throws IOException {
        for (String file : Arrays.asList(SETTINGS_SOURCE, UI_SOURCE)) {
            String text = String.join("\n", sourceOf(file));
            for (KeyExpectation expectation : TABLE) {
                for (String name : Arrays.asList("getBoolean", "putBoolean",
                        "putDefaultUnlessSet", "addToggle")) {
                    for (String call : callsMentioningAny(text, name, expectation.keyTokens)) {
                        assertNoBareBooleanDefault(call, file + " " + expectation.label);
                    }
                }
            }
        }
    }

    /** ⑨ 键表只圈这五路：非对抗性那几键不许被闸门或共享表圈进去。 */
    @Test
    public void theCompetitiveKeySetIsExactlyTheFiveAndNothingElse() {
        for (String key : FIVE_LITERALS) assertTrue(key, CompetitiveDefaults.isCompetitiveKey(key));
        assertFalse(CompetitiveDefaults.isCompetitiveKey("cue_category_vision"));
        assertFalse(CompetitiveDefaults.isCompetitiveKey("cue_channel_tone"));
        assertFalse(CompetitiveDefaults.isCompetitiveKey("cue_channel_speech"));
        assertFalse(CompetitiveDefaults.isCompetitiveKey("cue_category_player"));
        assertFalse(CompetitiveDefaults.isCompetitiveKey("cue_category_system"));
        assertFalse(CompetitiveDefaults.isCompetitiveKey("cue_speak_appear"));
        assertFalse(CompetitiveDefaults.isCompetitiveKey(null));
        assertEquals(5, CompetitiveDefaults.keys().size());
        assertTrue(CompetitiveDefaults.keys().containsAll(FIVE_LITERALS));
    }

    /** ⑩ 非竞技路径零变化的反向锁：消消乐、助手、读屏、逐条读都不认得这五键与闸门。 */
    @Test
    public void nonCompetitivePathsReferenceNoneOfTheFiveKeysOrTheGate() throws IOException {
        List<String> names = new ArrayList<>(match3SourceNames());
        names.addAll(Arrays.asList("LocalReadAloud.java", "DecisionWhy.java", "AnnounceWhy.java",
                "ReadAloudDensity.java", "AssistantController.java", "Match3ToolsActivity.java"));
        assertFalse("没扫到任何消消乐源文件，这条是假绿", match3SourceNames().isEmpty());
        for (String name : names) {
            String text = String.join("\n", sourceOf(name));
            assertFalse(name + " 被接进了对抗性缺省表", text.contains("CompetitiveDefaults"));
            assertFalse(name + " 被接进了风险告知闸门", text.contains("CompetitiveNoticeGate"));
            for (String key : FIVE_LITERALS) {
                assertFalse(name + " 读走了对抗性键 " + key, text.contains("\"" + key + "\""));
                assertFalse(name + " 引用了对抗性键常量 " + key, text.contains(keySuffix(key)));
            }
        }
    }

    /** 五键在 {@code CueSettings} 里的常量名后缀，用来抓「消消乐偷偷读走常量」这种间接引用。 */
    private static String keySuffix(String literalKey) {
        if ("cue_category_near".equals(literalKey)) return "PREF_CATEGORY_NEAR";
        if ("cue_near_haptic".equals(literalKey)) return "PREF_NEAR_HAPTIC";
        if ("cue_far_appear".equals(literalKey)) return "PREF_FAR_APPEAR";
        if ("cue_category_peripheral".equals(literalKey)) return "PREF_CATEGORY_PERIPHERAL";
        return "PREF_CATEGORY_DANGER";
    }

    private static List<String> match3SourceNames() {
        File dir = sourcePackageDir();
        File[] found = dir == null ? null : dir.listFiles((d, name) ->
                name.startsWith("Match3") && name.endsWith(".java"));
        if (found == null) return Collections.emptyList();
        List<String> names = new ArrayList<>();
        for (File file : found) names.add(file.getName());
        Collections.sort(names);
        return names;
    }

    private static void assertNoBareBooleanDefault(String call, String label) {
        Matcher matcher = Pattern.compile(",\\s*(true|false)\\s*\\)").matcher(call);
        assertFalse(label + " 还留着裸布尔缺省：" + call, matcher.find());
    }

    /** 从每个 {@code name(} 的起点扫到配平的右括号，拿到整条调用的文本。 */
    private static List<String> callsOf(String text, String name) {
        List<String> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int at = text.indexOf(name + "(", from);
            if (at < 0) return out;
            int depth = 0;
            int end = -1;
            for (int i = at + name.length(); i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                }
            }
            if (end < 0) throw new IllegalStateException(name + " 调用括号不配平，扫描没接上电");
            out.add(text.substring(at, end + 1));
            from = at + name.length();
        }
    }

    private static List<String> callsMentioningAny(String text, String name, List<String> tokens) {
        List<String> out = new ArrayList<>();
        Set<String> dedupe = new HashSet<>();
        for (String call : callsOf(text, name)) {
            for (String token : tokens) {
                if (call.contains(token) && dedupe.add(name + "|" + call)) {
                    out.add(call);
                    break;
                }
            }
        }
        return out;
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

    private static File sourcePackageDir() {
        File dir = new File("").getAbsoluteFile();
        for (int up = 0; dir != null && up <= 5; up++, dir = dir.getParentFile()) {
            File candidate = new File(dir,
                    "src/main/java/com/openkhub/sensefield");
            if (candidate.isDirectory()) return candidate;
            candidate = new File(dir, "android/app/src/main/java/com/openkhub/sensefield");
            if (candidate.isDirectory()) return candidate;
        }
        return null;
    }

    /** 手写内存偏好存储：真跑 applyPreset，读回真实落库值。 */
    private static final class FakePreferences implements android.content.SharedPreferences {
        private final Map<String, Object> values = new LinkedHashMap<>();

        void set(String key, Object value) {
            values.put(key, value);
        }

        @Override public Map<String, ?> getAll() {
            return new LinkedHashMap<String, Object>(values);
        }

        @Override public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }

        @Override public long getLong(String key, long defValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }

        @Override public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }

        @Override public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @Override public Set<String> getStringSet(String key, Set<String> defValues) {
            throw new UnsupportedOperationException("本卷用不到 string set");
        }

        @Override public android.content.SharedPreferences.Editor edit() {
            return new FakeEditor();
        }

        @Override public void registerOnSharedPreferenceChangeListener(
                android.content.SharedPreferences.OnSharedPreferenceChangeListener listener) {
        }

        @Override public void unregisterOnSharedPreferenceChangeListener(
                android.content.SharedPreferences.OnSharedPreferenceChangeListener listener) {
        }

        private final class FakeEditor implements android.content.SharedPreferences.Editor {
            private final Map<String, Object> pending = new LinkedHashMap<>();

            @Override public android.content.SharedPreferences.Editor putBoolean(String key,
                                                                                  boolean value) {
                pending.put(key, value);
                return this;
            }

            @Override public android.content.SharedPreferences.Editor putInt(String key,
                                                                             int value) {
                pending.put(key, value);
                return this;
            }

            @Override public android.content.SharedPreferences.Editor putLong(String key,
                                                                              long value) {
                pending.put(key, value);
                return this;
            }

            @Override public android.content.SharedPreferences.Editor putFloat(String key,
                                                                               float value) {
                pending.put(key, value);
                return this;
            }

            @Override public android.content.SharedPreferences.Editor putString(String key,
                                                                                String value) {
                pending.put(key, value);
                return this;
            }

            @Override public android.content.SharedPreferences.Editor putStringSet(
                    String key, Set<String> values) {
                throw new UnsupportedOperationException("本卷用不到 string set");
            }

            @Override public android.content.SharedPreferences.Editor remove(String key) {
                pending.put(key, REMOVED);
                return this;
            }

            @Override public android.content.SharedPreferences.Editor clear() {
                pending.clear();
                values.clear();
                return this;
            }

            @Override public void apply() {
                flush();
            }

            @Override public boolean commit() {
                flush();
                return true;
            }

            private void flush() {
                for (Map.Entry<String, Object> entry : pending.entrySet()) {
                    if (entry.getValue() == REMOVED) values.remove(entry.getKey());
                    else values.put(entry.getKey(), entry.getValue());
                }
                pending.clear();
            }
        }

        private static final Object REMOVED = new Object();
    }
}
