package com.openkhub.sensefield;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 对抗性（竞技对局里才会用到「多知道一点位置」那种效果）那五路提示的<b>缺省表</b>。
 *
 * <p>这张表存在的唯一理由：同一批缺省值在代码里有三处写法——设置页的勾选框缺省参数、装机
 * 写值、读取兜底。三处各自写字面量时，任何一处漂开都会出现「新装机关了而某处还在开」，所以
 * 三处一律从这里取值，一致性由 {@code CompetitiveDefaultSurfaceTest} 逐键比。
 *
 * <p><b>本次合同变更</b>：这五路一律缺省<b>关</b>，不再按档位（精简／标准／详细）分别给开。
 * 详细档位仍然带着它原有的非对抗性丰富度（外观播报、语音密度、间隔），只是不再替玩家把
 * 敌人提示打开——开与不开由玩家在设置页主动决定，主动开启前要先过一次风险告知
 * （{@link CompetitiveNoticeGate}）。
 *
 * <p>已存过值的用户一律原样读回：缺省只在「从未存过这个键」时生效，写值处也只在缺键时才写。
 *
 * <p>纯 JVM：不 import 任何 {@code android.*} 类。
 */
final class CompetitiveDefaults {
    /** 小地图近区敌人提醒。 */
    static final String CATEGORY_NEAR = CueSettings.PREF_CATEGORY_NEAR;
    /** 近区敌人震动。 */
    static final String NEAR_HAPTIC = CueSettings.PREF_NEAR_HAPTIC;
    /** 远处新头像提示音。 */
    static final String FAR_APPEAR = CueSettings.PREF_FAR_APPEAR;
    /** 屏幕边缘威胁。 */
    static final String CATEGORY_PERIPHERAL = CueSettings.PREF_CATEGORY_PERIPHERAL;
    /** 危险接近。 */
    static final String CATEGORY_DANGER = CueSettings.PREF_CATEGORY_DANGER;

    /** 五路一律缺省关。翻回 {@code true} 必须让测试真红，不许靠注释维持这个承诺。 */
    static final boolean CATEGORY_NEAR_DEFAULT = false;
    static final boolean NEAR_HAPTIC_DEFAULT = false;
    static final boolean FAR_APPEAR_DEFAULT = false;
    static final boolean CATEGORY_PERIPHERAL_DEFAULT = false;
    static final boolean CATEGORY_DANGER_DEFAULT = false;

    private static final Set<String> KEYS = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList(CATEGORY_NEAR, NEAR_HAPTIC, FAR_APPEAR,
                    CATEGORY_PERIPHERAL, CATEGORY_DANGER)));

    private CompetitiveDefaults() {
    }

    /** 这五路是不是对抗性那五路（消消乐、助手问答、读屏、逐条读都不在里面）。 */
    static boolean isCompetitiveKey(String key) {
        return key != null && KEYS.contains(key);
    }

    /** 归属页与兜底共用的缺省读数；非对抗性键返回调用方自己的缺省，不在本表射程内。 */
    static boolean defaultFor(String key) {
        if (CATEGORY_NEAR.equals(key)) return CATEGORY_NEAR_DEFAULT;
        if (NEAR_HAPTIC.equals(key)) return NEAR_HAPTIC_DEFAULT;
        if (FAR_APPEAR.equals(key)) return FAR_APPEAR_DEFAULT;
        if (CATEGORY_PERIPHERAL.equals(key)) return CATEGORY_PERIPHERAL_DEFAULT;
        if (CATEGORY_DANGER.equals(key)) return CATEGORY_DANGER_DEFAULT;
        throw new IllegalArgumentException("不在对抗性五键表里的键：" + key);
    }

    /** 逐键比对用的键表，顺序即案卷叙述顺序。 */
    static Set<String> keys() {
        return KEYS;
    }
}
