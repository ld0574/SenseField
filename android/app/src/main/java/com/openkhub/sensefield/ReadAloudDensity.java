package com.openkhub.sensefield;

/**
 * 「疏密档位 → 逐条读一次念几条」的纯 JVM 映射（不 import 任何 Android 类，也不读任何偏好）。
 *
 * <p>要解决的问题：设置面板早就有精简／标准／详细三键加一个自定义派生态，档名由
 * {@code CueSettings.applyPreset} 落成既有偏好键 {@code cue_preset}；{@code Match3Intensity} 已把同一
 * 档名翻译成两条置信线，管住「这一条说不说」。但「从上往下逐条读」的条数上限一直走
 * {@link LocalReadAloud#DEFAULT_LIMIT} 硬编码，从没看过档名——玩家从精简切到详细，一次念的条数一字不变。
 * 本类只做一件事：把已经存在的档名翻译成条数上限，<b>不新增偏好键、不新增控件、不改任何播报文案</b>。
 *
 * <p>四条口径（全部由 {@code ReadAloudDensityTest} 逐条钉死，期望值写死不从本类输出反推）：
 * <ol>
 *   <li><b>没调过档位的玩家零变化</b>：标准档与自定义档输出恒等于 {@link LocalReadAloud#DEFAULT_LIMIT}
 *       （现值）。自定义档是玩家自己配的，不替他再改一次。</li>
 *   <li><b>单调</b>：精简 ≤ 标准 ≤ 详细。方向与 {@code Match3Intensity} 第 2 条口径同向——精简收严门槛
 *       少播，逐条读也少念；详细放宽门槛多播，逐条读也多念。</li>
 *   <li><b>非法输入一律回落现值</b>：档名为 null、空白串、未知字面量时原样回 {@code DEFAULT_LIMIT}，
 *       绝不凭空造一个新数值，也绝不抛。</li>
 *   <li><b>输出恒为正整数</b>：不给「一条也不念」留口子（下游 {@code sentences} 对 {@code limit <= 0}
 *       另有兜底，本类从源头就不产出非正值）。</li>
 * </ol>
 *
 * <p>选值是情报不是拟合：6 与 20 分别落在「一屏核心两三条＋尾巴」与「压缩状态 60 节点上限的一半」
 * 量级上；「精简念 6 条够不够、详细念 20 条嫌不嫌啰嗦」属玩家主观节奏，留给真机那一轮定，本类不宣称听感。
 *
 * <p>档名字面量与 {@code CueSettings.PRESET_*} 中的两个同名值一致（那边 import 了 Android 类，
 * 本类不引用它，才留得住「纯 JVM」这条）；其余档名一律走第 3 条回落。
 */
public final class ReadAloudDensity {

    private static final String PRESET_COMPACT = "compact";
    private static final String PRESET_DETAILED = "detailed";

    /** 精简档上限：比现值少一半的量级，够读出顶栏与主内容前几条。 */
    static final int COMPACT_LIMIT = 6;

    /** 详细档上限：比现值宽一档，接近压缩状态常见长度但不顶满 60 节点。 */
    static final int DETAILED_LIMIT = 20;

    private ReadAloudDensity() {
    }

    /**
     * 唯一入口：档名 → 本次逐条读的条数上限。
     *
     * @param preset {@code cue_preset} 里存的档名；标准档／自定义档／未知档名／null／空白都回现值
     * @return 正整数上限，标准与自定义档恒等于 {@link LocalReadAloud#DEFAULT_LIMIT}
     */
    public static int limitFor(String preset) {
        if (PRESET_COMPACT.equals(preset)) {
            return COMPACT_LIMIT;
        }
        if (PRESET_DETAILED.equals(preset)) {
            return DETAILED_LIMIT;
        }
        return LocalReadAloud.DEFAULT_LIMIT;
    }
}
