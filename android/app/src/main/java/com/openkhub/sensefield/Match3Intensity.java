package com.openkhub.sensefield;

/**
 * 消消乐读屏播报的「档位 → 生效置信线」映射（纯 JVM，不 import 任何 Android 类，也不读任何偏好）。
 *
 * <p>要解决的问题：设置面板早就有四档（精简／标准／详细／自定义），档位由
 * {@code CueSettings.applyPreset} 落成偏好键，但消消乐这条路的「说不说」只看
 * {@code assets/jev/gating.json} 里的两条置信线，从没看过档名。用户在「详细」和「精简」之间来回切，
 * 消消乐播报的疏密一字不变。本类只做一件事：把已经存在的档名翻译成两条生效线，
 * <b>不新增偏好键、不新增控件、不改任何播报文案</b>。
 *
 * <p>四条口径（全部由 {@code Match3IntensityTest} 逐条钉死，期望值写死不从本类输出反推）：
 * <ol>
 *   <li><b>没调过的用户零变化</b>：标准档与自定义档原样回传输入的两条基准线。标准档的回传值必须与
 *       gating.json 的读数逐位相等；自定义档是用户自己配的，不该再被本类改一次。</li>
 *   <li><b>单调</b>：精简档的 high 不低于标准档的 high、mid 同理（门槛收严 ⇒ 少播）；详细档的
 *       high、mid 不高于标准档（门槛放宽 ⇒ 多播）。方向按档名的字面常识定。</li>
 *   <li><b>铁边界</b>：任何非法输入（NaN、负数、大于 1、high 小于 mid、未知档名、档名为 null）一律
 *       回落到「原样基准线」——照原值回传，绝不凭空造一个新数值来「修好」它。输入本来就合法时，
 *       输出恒满足 0 &lt; mid ≤ high ≤ 1（偏移只在边界处饱和，见下）。</li>
 *   <li><b>只平移、不改带宽</b>：偏移量同时作用于两条线，保持高中低三档的相对关系不变，改的只是疏密。</li>
 * </ol>
 *
 * <p>档名字面量与 {@code CueSettings.PRESET_*} 五个常量同值（那边 import 了 Android 类，
 * 本类不引用它，才留得住「纯 JVM」这条）；四个名字之外的一切档名都按未知处理，走第 3 条回落。
 * 本类不产生任何字符串输出，因此不可能带出坐标写法或包名／模型名字面量。
 */
public final class Match3Intensity {

    private static final String PRESET_COMPACT = "compact";
    private static final String PRESET_DETAILED = "detailed";

    /**
     * 精简档收严量／详细档放宽量，单位是置信度绝对值。
     *
     * <p>取值理由（选值属情报，不是从语料拟合出来的）：现网 gating.json 的分带宽度是 0.25
     * （high 0.85 − mid 0.60），而置信读数只展示到两位小数，所以 0.05 是这套读数里<b>看得见的最小
     * 一步</b>。精简档取一步（0.05）：够让「同一屏」在标准档播、在精简档落到中低档，又不把 mid 顶到
     * high 那一条线上（0.60 + 0.05 = 0.65，仍留 0.20 带宽）。详细档取两步（0.10）：mid 落到 0.50，
     * 还在读屏判定常见的置信区间内；再多一步就把 mid 压到 0.40，接近「猜也算播」的下沿，多播会变成错播。
     */
    static final float COMPACT_TIGHTEN = 0.05f;
    static final float DETAILED_LOOSEN = 0.10f;

    /** 低线饱和下界：详细档放宽时 mid 不许塌到 0（0 &lt; mid 是输出的硬条件）。 */
    private static final float MID_FLOOR = 0.05f;

    private Match3Intensity() {
    }

    /**
     * 唯一入口：档名 + 配置读出的两条基准线 → 本次生效的两条线。
     *
     * @param preset   {@code cue_preset} 里存的档名；未知档名与 null 都按非法输入回落
     * @param baseHigh gating.json（或缺失时的默认值）读出的高线
     * @param baseMid  同上的低线
     * @return 长度为 2 的数组：{@code [0]} 是生效 high，{@code [1]} 是生效 mid
     */
    public static float[] apply(String preset, float baseHigh, float baseMid) {
        // 第 3 条铁边界先判：基准线本身不合法就不做任何偏移，原样回传，不造新数值。
        if (!legalLine(baseHigh) || !legalLine(baseMid) || baseHigh < baseMid) {
            return new float[]{baseHigh, baseMid};
        }
        // 标准档／自定义档／未知档名：等值回传（第 1 条，零变化）。
        if (PRESET_COMPACT.equals(preset)) {
            return shifted(baseHigh, baseMid, COMPACT_TIGHTEN);
        }
        if (PRESET_DETAILED.equals(preset)) {
            return shifted(baseHigh, baseMid, -DETAILED_LOOSEN);
        }
        return new float[]{baseHigh, baseMid};
    }

    /** 一条基准线是否合法：0 与 1 之间的有限值；NaN／无穷／负数／大于 1 全算非法。 */
    private static boolean legalLine(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value) && value > 0f && value <= 1f;
    }

    /**
     * 同向平移两条线，只在硬边界处饱和：high 不越过 1、也不越过原来那条低线（两条线不许交叉），
     * mid 不越过平移后的 high、也不跌破下界。饱和只是把偏移量截短，不引入新的数值来源。
     */
    private static float[] shifted(float baseHigh, float baseMid, float delta) {
        float high = clampTo(baseHigh + delta, 1f, baseMid);
        float mid = clampTo(baseMid + delta, high, MID_FLOOR);
        // 两位小数对齐：偏移后仍是可读的百分数，读数面与单测都不必处理浮点尾差。
        return new float[]{roundToCent(high), roundToCent(mid)};
    }

    /** 把 value 夹进 [floor, ceiling]；ceiling 已经保证不小于 floor。 */
    private static float clampTo(float value, float ceiling, float floor) {
        if (value > ceiling) return ceiling;
        if (value < floor) return floor;
        return value;
    }

    /** 四舍五入到百分位（0.01），与置信读数展示两位小数同口径。 */
    private static float roundToCent(float value) {
        return Math.round(value * 100f) / 100f;
    }
}
