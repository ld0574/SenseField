package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * {@link AnnounceWhy} 的纯 JVM 单测。
 *
 * <p>任务 2 的<b>硬判据</b>落在这里：新句字符数（Java {@code String.length()}，本串全是 BMP 码元、无代理对，
 * 故长度即字符数）≤ 被替换的旧句字符数，且用严格 {@code <=}，不用任何「长度差几个字以内」的宽松断言。
 * 旧句原文取自任务 0 锚点，由测试自己写死，不从被测函数输出反推。
 *
 * <p>另钉三条纪律：①只换不加——句尾既有要约「要我从上往下逐条读吗？」原样保留、只换头部；
 * ②两支都含理由且互不相同；③沿用 {@link WordingGuard} 口径，播报串里不得出现执行类越权词；
 * ④两支都不含播报坐标（无行列数字、无 {@code @[}）。
 */
public class AnnounceWhyTest {

    /** 被替换的旧句（任务 0 锚点原文，测试侧写死）。 */
    private static final String OLD = "这一屏我没看清楚，要我从上往下逐条读吗？";

    /** 句尾既有要约，只换头部时须原样保留。 */
    private static final String TAIL = "要我从上往下逐条读吗？";

    /** 一组覆盖低置信到弃权、阈值线漂移的输入读数。 */
    private static final double[][] READINGS = {
            {0.00d, 0.85d, 0.60d},
            {0.41d, 0.85d, 0.60d},
            {0.59d, 0.85d, 0.60d},
            {0.60d, 0.60d, 0.60d},
            {0.20d, 0.90d, 0.50d},
            {0.00d, 0.00d, 0.00d},
            {0.99d, 0.85d, 0.60d},   // 弃权支即便读数很高，也只说「判定层弃权」，不外泄数值
    };

    private static int oldLen() {
        return OLD.length();
    }

    /* 0. 基线自证：旧句长度就是 20（若被改动，后面所有 <= 判据失去意义）。 */
    @Test
    public void oldSentenceBaselineIsTwentyChars() {
        assertEquals(20, oldLen());
        assertEquals("AnnounceWhy 里登记的旧句必须与锚点逐字一致", OLD, AnnounceWhy.OLD_SPEECH);
    }

    /* 1. 硬判据（低置信支）：新句字符数 ≤ 旧句字符数，严格 <=。 */
    @Test
    public void lowBranchNewSentenceIsNotLongerThanOld() {
        for (double[] r : READINGS) {
            String out = AnnounceWhy.speech(false, r[0], r[1], r[2]);
            assertTrue("低置信新句超出旧句长度：" + out + " len=" + out.length() + " > " + oldLen(),
                    out.length() <= oldLen());
        }
    }

    /* 2. 硬判据（弃权支）：新句字符数 ≤ 旧句字符数，严格 <=。 */
    @Test
    public void abstainBranchNewSentenceIsNotLongerThanOld() {
        for (double[] r : READINGS) {
            String out = AnnounceWhy.speech(true, r[0], r[1], r[2]);
            assertTrue("弃权新句超出旧句长度：" + out + " len=" + out.length() + " > " + oldLen(),
                    out.length() <= oldLen());
        }
    }

    /* 3. 只换不加：句尾既有要约原样保留（尾部子串命中），且新句不等于旧句（头部已被理由替换）。 */
    @Test
    public void offerTailIsPreservedButHeadIsSwapped() {
        String low = AnnounceWhy.speech(false, 0.41d, 0.85d, 0.60d);
        String ab = AnnounceWhy.speech(true, 0.0d, 0.85d, 0.60d);
        assertTrue(low, low.endsWith(TAIL));
        assertTrue(ab, ab.endsWith(TAIL));
        assertNotEquals(OLD, low);
        assertNotEquals(OLD, ab);
    }

    /* 4. 两支都含「理由」，且彼此不同：低置信说没过低线，弃权说判定层弃权。 */
    @Test
    public void bothBranchesCarryADistinctReason() {
        String low = AnnounceWhy.speech(false, 0.41d, 0.85d, 0.60d);
        String ab = AnnounceWhy.speech(true, 0.0d, 0.85d, 0.60d);
        assertTrue(low, low.contains("没过低线"));
        assertTrue(ab, ab.contains("判定层弃权"));
        assertNotEquals(low, ab);
    }

    /* 5. 措辞守卫口径：播报串不得出现任何执行类越权词（复用 WordingGuard 黑名单）。 */
    @Test
    public void newSentencesCarryNoExecutionWording() {
        List<String> blacklist = WordingGuard.BLACKLIST;
        for (double[] r : READINGS) {
            for (boolean abstained : new boolean[]{false, true}) {
                String out = AnnounceWhy.speech(abstained, r[0], r[1], r[2]);
                for (String term : blacklist) {
                    assertFalse("命中越权词「" + term + "」：" + out, out.contains(term));
                }
            }
        }
    }

    /* 6. 两支都不含播报坐标：既无 @[ 形态，也无 ASCII 数字（行列读数一律留给只读展示行）。 */
    @Test
    public void newSentencesCarryNoCoordinates() {
        for (double[] r : READINGS) {
            for (boolean abstained : new boolean[]{false, true}) {
                String out = AnnounceWhy.speech(abstained, r[0], r[1], r[2]);
                assertFalse(out, out.contains("@["));
                for (int i = 0; i < out.length(); i++) {
                    char c = out.charAt(i);
                    assertFalse("播报串不该出现数字读数：" + out, c >= '0' && c <= '9');
                }
            }
        }
    }

    /* 7. 永不返回 null／空串。 */
    @Test
    public void neverReturnsNullOrEmpty() {
        for (double[] r : READINGS) {
            for (boolean abstained : new boolean[]{false, true}) {
                String out = AnnounceWhy.speech(abstained, r[0], r[1], r[2]);
                assertFalse(out == null);
                assertFalse(out.isEmpty());
            }
        }
    }
}
