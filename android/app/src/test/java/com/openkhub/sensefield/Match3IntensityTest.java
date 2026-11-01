package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 档位→生效置信线映射的纯 JVM 单测：不碰 Android 类、不起 Robolectric，期望值全部手写死，
 * 一律不从被测函数的输出反推。覆盖任务书八点名的六项：四档各一条、六类非法输入各自回落、
 * 两条单调性、标准档等值回传（并且钉住「标准档生效线 == 包内配置的读数」）、未知档名等值回传，
 * 外加输出恒满足 0 &lt; mid ≤ high ≤ 1 这条不变量与两处边界饱和。
 */
public class Match3IntensityTest {

    /** 现网默认基准线（包内配置的 screen_type 读数，也是 Match3ToolsActivity 的字段默认值）。 */
    private static final float BASE_HIGH = 0.85f;
    private static final float BASE_MID = 0.60f;

    /** 浮点比较用 0f 容差：映射结果一律四舍五入到百分位，写死的期望值必须逐位对上。 */
    private static void assertLines(String label, float expectedHigh, float expectedMid, float[] actual) {
        assertEquals(label + " 生效高线", expectedHigh, actual[0], 0f);
        assertEquals(label + " 生效低线", expectedMid, actual[1], 0f);
    }

    /** 两条线的差按整数百分点比：避免浮点尾差把写死的期望值变成「差不多就行」。 */
    private static int centDelta(float from, float to) {
        return (int) Math.round((to - from) * 100.0d);
    }

    /* ---------- 四档各一条 ---------- */

    @Test
    public void standardPresetReturnsBaselineUnchanged() {
        assertLines("标准档", 0.85f, 0.60f, Match3Intensity.apply("standard", BASE_HIGH, BASE_MID));
    }

    @Test
    public void customPresetReturnsBaselineUnchanged() {
        // 自定义档是用户自己调出来的两条线，本类不该再改一次。
        assertLines("自定义档", 0.72f, 0.31f, Match3Intensity.apply("custom", 0.72f, 0.31f));
    }

    @Test
    public void compactPresetTightensBothLines() {
        assertLines("精简档", 0.90f, 0.65f, Match3Intensity.apply("compact", BASE_HIGH, BASE_MID));
    }

    @Test
    public void detailedPresetLoosensBothLines() {
        assertLines("详细档", 0.75f, 0.50f, Match3Intensity.apply("detailed", BASE_HIGH, BASE_MID));
    }

    /* ---------- 完成条件 2：默认行为零变化（标准档生效线 == 包内配置的读数） ---------- */

    @Test
    public void standardPresetKeepsExactPackagedConfigReading() throws Exception {
        float[] cfg = configLines();
        // 写死钉住现网读数：这条一旦不成立，说明有人改了配置，下面的等值断言就失去意义。
        assertLines("包内配置读数", 0.85f, 0.60f, cfg);
        assertLines("标准档对配置等值回传", cfg[0], cfg[1], Match3Intensity.apply("standard", cfg[0], cfg[1]));
    }

    /** 与既有读资产的单测同一口径：工作目录可能是 android/ 也可能是 android/app/。 */
    private static float[] configLines() throws Exception {
        Path path = Path.of("src/main/assets/jev/gating.json");
        if (!Files.isRegularFile(path)) path = Path.of("app/src/main/assets/jev/gating.json");
        JSONObject screenType = new JSONObject(new String(
                Files.readAllBytes(path), StandardCharsets.UTF_8)).getJSONObject("screen_type");
        return new float[]{(float) screenType.getDouble("high"), (float) screenType.getDouble("mid")};
    }

    /* ---------- 两条单调性 ---------- */

    @Test
    public void compactIsNeverLooserThanStandard() {
        float[][] bases = {{0.85f, 0.60f}, {0.80f, 0.55f}, {0.70f, 0.50f}, {0.95f, 0.40f},
                {1.00f, 0.95f}, {0.10f, 0.05f}};
        for (float[] base : bases) {
            float[] standard = Match3Intensity.apply("standard", base[0], base[1]);
            float[] compact = Match3Intensity.apply("compact", base[0], base[1]);
            assertTrue("精简档高线必须不低于标准档：" + base[0] + "/" + base[1], compact[0] >= standard[0]);
            assertTrue("精简档低线必须不低于标准档：" + base[0] + "/" + base[1], compact[1] >= standard[1]);
        }
    }

    @Test
    public void detailedIsNeverStricterThanStandard() {
        float[][] bases = {{0.85f, 0.60f}, {0.80f, 0.55f}, {0.70f, 0.50f}, {0.95f, 0.40f},
                {1.00f, 0.95f}, {0.10f, 0.05f}};
        for (float[] base : bases) {
            float[] standard = Match3Intensity.apply("standard", base[0], base[1]);
            float[] detailed = Match3Intensity.apply("detailed", base[0], base[1]);
            assertTrue("详细档高线必须不高于标准档：" + base[0] + "/" + base[1], detailed[0] <= standard[0]);
            assertTrue("详细档低线必须不高于标准档：" + base[0] + "/" + base[1], detailed[1] <= standard[1]);
        }
    }

    /* ---------- 六类非法输入：一律原样回落到基准线，不许造新数值 ---------- */

    @Test
    public void nanHighFallsBackToBaselineLines() {
        float[] out = Match3Intensity.apply("compact", Float.NaN, BASE_MID);
        assertTrue("NaN 高线不许被换成别的数", Float.isNaN(out[0]));
        assertEquals("NaN 时低线原样回传", BASE_MID, out[1], 0f);
    }

    @Test
    public void nanMidFallsBackToBaselineLines() {
        float[] out = Match3Intensity.apply("detailed", BASE_HIGH, Float.NaN);
        assertEquals("NaN 时高线原样回传", BASE_HIGH, out[0], 0f);
        assertTrue("NaN 低线不许被换成别的数", Float.isNaN(out[1]));
    }

    @Test
    public void negativeBaselineFallsBackToBaselineLines() {
        assertLines("负数基准线", -0.50f, 0.30f, Match3Intensity.apply("compact", -0.50f, 0.30f));
    }

    @Test
    public void aboveOneBaselineFallsBackToBaselineLines() {
        assertLines("大于 1 的基准线", 1.50f, 0.60f, Match3Intensity.apply("detailed", 1.50f, 0.60f));
    }

    @Test
    public void invertedBaselineFallsBackToBaselineLines() {
        // high 小于 mid：不解释、不交换、不修值，原样回传。
        assertLines("high 低于 mid", 0.40f, 0.70f, Match3Intensity.apply("compact", 0.40f, 0.70f));
    }

    @Test
    public void unknownPresetNameFallsBackToBaselineLines() {
        assertLines("未知档名", 0.85f, 0.60f, Match3Intensity.apply("turbo", BASE_HIGH, BASE_MID));
        assertLines("空串档名", 0.85f, 0.60f, Match3Intensity.apply("", BASE_HIGH, BASE_MID));
    }

    @Test
    public void nullPresetFallsBackToBaselineLines() {
        assertLines("档名 null", 0.85f, 0.60f, Match3Intensity.apply(null, BASE_HIGH, BASE_MID));
    }

    /* ---------- 输出不变量与两处边界饱和 ---------- */

    @Test
    public void outputAlwaysKeepsMidPositiveAndOrderedBelowHigh() {
        String[] presets = {"compact", "standard", "detailed", "custom", "turbo", null};
        float[][] bases = {{0.85f, 0.60f}, {1.00f, 0.98f}, {0.30f, 0.10f},
                {0.10f, 0.05f}, {0.60f, 0.60f}, {0.99f, 0.01f}};
        for (String preset : presets) {
            for (float[] base : bases) {
                float[] out = Match3Intensity.apply(preset, base[0], base[1]);
                assertTrue(preset + " 基准 " + base[0] + "/" + base[1] + " 低线必须为正：" + out[1],
                        out[1] > 0f);
                assertTrue(preset + " 基准 " + base[0] + "/" + base[1] + " 低线不得越过生效高线：" + out[1],
                        out[1] <= out[0]);
                assertTrue(preset + " 基准 " + base[0] + "/" + base[1] + " 高线不得越过 1：" + out[0],
                        out[0] <= 1f);
            }
        }
    }

    @Test
    public void compactSaturatesAtOneInsteadOfInventingValue() {
        // 高线已经在 1.0，收严只能把偏移截短，不许输出 1.05 这种越界数。
        assertLines("精简档贴顶饱和", 1.00f, 1.00f, Match3Intensity.apply("compact", 1.00f, 0.95f));
    }

    @Test
    public void detailedSaturatesAtFloorInsteadOfCollapsingMid() {
        assertLines("详细档触底饱和", 0.05f, 0.05f, Match3Intensity.apply("detailed", 0.10f, 0.05f));
    }

    /* ---------- 完成条件 3：精简档与标准档的输出差必须打印出来 ---------- */

    @Test
    public void printCompactVersusStandardDifference() throws Exception {
        float[] cfg = configLines();
        float[] standard = Match3Intensity.apply("standard", cfg[0], cfg[1]);
        float[] compact = Match3Intensity.apply("compact", cfg[0], cfg[1]);
        float[] detailed = Match3Intensity.apply("detailed", cfg[0], cfg[1]);
        String line = "B8-DIFF 配置基准线 " + pct(cfg[0]) + "/" + pct(cfg[1])
                + " ｜ 标准档生效线 " + pct(standard[0]) + "/" + pct(standard[1])
                + " ｜ 精简档生效线 " + pct(compact[0]) + "/" + pct(compact[1])
                + " ｜ 精简减标准 = 高线 " + centDelta(standard[0], compact[0]) + " 个百分点"
                + "，低线 " + centDelta(standard[1], compact[1]) + " 个百分点"
                + " ｜ 详细档生效线 " + pct(detailed[0]) + "/" + pct(detailed[1])
                + " ｜ 详细减标准 = 高线 " + centDelta(standard[0], detailed[0]) + " 个百分点"
                + "，低线 " + centDelta(standard[1], detailed[1]) + " 个百分点";
        System.out.println(line);

        // 差值本身也是断言：打印不能只是打印，静默通过同样算没做到。
        assertEquals("精简档高线差", 5, centDelta(standard[0], compact[0]));
        assertEquals("精简档低线差", 5, centDelta(standard[1], compact[1]));
        assertEquals("详细档高线差", -10, centDelta(standard[0], detailed[0]));
        assertEquals("详细档低线差", -10, centDelta(standard[1], detailed[1]));
        // 输出串纪律：读数面这条串里不许出现坐标写法，也不许出现包名或模型文件名字面量。
        assertFalse(line, line.contains("@["));
        assertFalse(line, line.contains("com.openkhub"));
        assertFalse(line, line.contains("openkhub"));
        assertFalse(line, line.contains(".onnx"));
        assertFalse(line, line.contains(".ncnn"));
        assertFalse(line, line.contains(".bin"));
    }

    /** 生效线在读数面上的展示口径：两位小数，跟既有置信读数同宽。 */
    private static String pct(float ratio) {
        return String.format(Locale.US, "%.2f", ratio);
    }
}
