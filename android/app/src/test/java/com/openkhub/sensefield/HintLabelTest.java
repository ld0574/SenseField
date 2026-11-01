package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;

import java.util.BitSet;
import java.util.Collections;
import org.junit.Test;

/**
 * Hand-written expectations for {@link HintLabel}. Every expected label below is a literal I
 * typed, never something the generator produced at run time; the coordinate and wording cases
 * build their offending input character by character so this source itself stays clean.
 */
public final class HintLabelTest {

    private static final HintLabel.Tier CONFIRMED = HintLabel.Tier.CONFIRMED;
    private static final HintLabel.Tier PROBABLE = HintLabel.Tier.PROBABLE;
    private static final HintLabel.Tier UNSURE = HintLabel.Tier.UNSURE;

    private static BoardGeometry geometry(int rows, int cols) {
        return new BoardGeometry(1260, 2800, 140, 900, 1120, 1880, rows, cols);
    }

    private static String word(char... cs) {
        return new String(cs);
    }

    private static String repeat(char c, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(c);
        return out.toString();
    }

    @Test
    public void confirmedTapLabelIsExactlyThisShortLine() {
        assertEquals("左上区域，红狐狸可点", HintLabel.of("左上", "红狐狸", Boolean.TRUE, CONFIRMED));
        assertEquals("右下区域，青蛙交换", HintLabel.of("右下", "青蛙", Boolean.FALSE, CONFIRMED));
        assertEquals("右上区域，紫猫头鹰交换", HintLabel.of("右上", "紫猫头鹰", Boolean.FALSE, CONFIRMED));
    }

    @Test
    public void probableAndUnsureTiersAppendTheirHedgeWords() {
        assertEquals("左上区域，红狐狸可点，可能", HintLabel.of("左上", "红狐狸", Boolean.TRUE, PROBABLE));
        assertEquals("左下区域，小鸡交换，拿不准", HintLabel.of("左下", "小鸡", Boolean.FALSE, UNSURE));
    }

    @Test
    public void missingPieceNameIsOmittedRatherThanInvented() {
        assertEquals("左下区域，可点", HintLabel.of("左下", null, Boolean.TRUE, CONFIRMED));
        assertEquals("左下区域，可点", HintLabel.of("左下", "   ", Boolean.TRUE, CONFIRMED));
        assertEquals("左上区域，交换，可能", HintLabel.of("左上", "", Boolean.FALSE, PROBABLE));
    }

    @Test
    public void unknownTierAndNullSemanticsProduceSilence() {
        assertEquals("", HintLabel.of("左上", "红狐狸", Boolean.TRUE, HintLabel.Tier.UNKNOWN));
        assertEquals("", HintLabel.of("左上", "红狐狸", Boolean.TRUE, null));
        assertEquals("", HintLabel.of("左上", "红狐狸", null, CONFIRMED));
        assertEquals("", HintLabel.of(null, "红狐狸", Boolean.TRUE, CONFIRMED));
    }

    @Test
    public void regionOutsideTheQuadrantVocabularyProducesSilence() {
        assertEquals("", HintLabel.of("中上", "红狐狸", Boolean.TRUE, CONFIRMED));
        assertEquals("", HintLabel.of("unknown", "红狐狸", Boolean.TRUE, CONFIRMED));
        assertEquals("", HintLabel.of("左上角", "红狐狸", Boolean.TRUE, CONFIRMED));
        assertEquals("", HintLabel.of(" 左上 ", "红狐狸", Boolean.TRUE, CONFIRMED));
    }

    @Test
    public void unknownPieceSentinelsProduceSilence() {
        assertEquals("", HintLabel.of("左上", "unknown", Boolean.TRUE, CONFIRMED));
        assertEquals("", HintLabel.of("左上", "未知", Boolean.TRUE, CONFIRMED));
        assertEquals("", HintLabel.of("左上", "未确认目标", Boolean.TRUE, CONFIRMED));
    }

    /** 判据 1：边界框与数字片段出现在任何输入里，整条标签作废。 */
    @Test
    public void boundingBoxFragmentInAnyInputKillsTheWholeLabel() {
        assertEquals("", HintLabel.of("左上", "红狐狸@[12,34]", Boolean.TRUE, CONFIRMED));
        assertEquals("", HintLabel.of("左上", "红狐狸[3,4]", Boolean.TRUE, CONFIRMED));
        assertEquals("", HintLabel.of("左上", "[12,34][56,78]", Boolean.TRUE, CONFIRMED));
        assertEquals("", HintLabel.of("左上", "第3列", Boolean.TRUE, CONFIRMED));
        assertEquals("", HintLabel.of("左上[0,0]", "红狐狸", Boolean.TRUE, CONFIRMED));
    }

    /** 判据 1 的正向半边：四象限 × 两动作 × 三档全部组合，逐字对照，且不含任何坐标形状。 */
    @Test
    public void everyRegionActAndTierCombinationCarriesNoCoordinateOrNumber() {
        StringBuilder produced = new StringBuilder();
        String[] regions = {"左上", "右上", "左下", "右下"};
        Boolean[] taps = {Boolean.TRUE, Boolean.FALSE};
        HintLabel.Tier[] tiers = {CONFIRMED, PROBABLE, UNSURE};
        for (String region : regions) {
            for (Boolean tap : taps) {
                for (HintLabel.Tier tier : tiers) {
                    if (produced.length() > 0) produced.append('|');
                    produced.append(HintLabel.of(region, "红狐狸", tap, tier));
                }
            }
        }
        assertEquals("左上区域，红狐狸可点|左上区域，红狐狸可点，可能|左上区域，红狐狸可点，拿不准"
                + "|左上区域，红狐狸交换|左上区域，红狐狸交换，可能|左上区域，红狐狸交换，拿不准"
                + "|右上区域，红狐狸可点|右上区域，红狐狸可点，可能|右上区域，红狐狸可点，拿不准"
                + "|右上区域，红狐狸交换|右上区域，红狐狸交换，可能|右上区域，红狐狸交换，拿不准"
                + "|左下区域，红狐狸可点|左下区域，红狐狸可点，可能|左下区域，红狐狸可点，拿不准"
                + "|左下区域，红狐狸交换|左下区域，红狐狸交换，可能|左下区域，红狐狸交换，拿不准"
                + "|右下区域，红狐狸可点|右下区域，红狐狸可点，可能|右下区域，红狐狸可点，拿不准"
                + "|右下区域，红狐狸交换|右下区域，红狐狸交换，可能|右下区域，红狐狸交换，拿不准",
                produced.toString());
        String joined = produced.toString();
        assertEquals(-1, joined.indexOf('['));
        assertEquals(-1, joined.indexOf(']'));
        assertEquals(-1, joined.indexOf('@'));
        for (char digit = '0'; digit <= '9'; digit++) assertEquals(-1, joined.indexOf(digit));
    }

    /** 判据 2：执行类措辞一律进不了标签，词表口径复用 {@link WordingGuard#BLACKLIST}。 */
    @Test
    public void guardBlacklistedWordingNeverReachesTheLabel() {
        StringBuilder offenders = new StringBuilder();
        for (String term : WordingGuard.BLACKLIST) {
            String label = HintLabel.of("左上", "红狐狸" + term, Boolean.TRUE, CONFIRMED);
            if (label.length() > 0) offenders.append(term).append('=').append(label).append(';');
        }
        assertEquals("", offenders.toString());
    }

    /** 判据 2 点名但整库口径没收的三个词：标签这一路比播报更严，这里逐个钉死。 */
    @Test
    public void promisingTheUsersActionNeverAppearsInTheLabel() {
        assertEquals("", HintLabel.of("左上", word('已', '为', '你', '点', '击'), Boolean.TRUE, CONFIRMED));
        assertEquals("", HintLabel.of("左上", word('自', '动', '执', '行'), Boolean.TRUE, CONFIRMED));
        assertEquals("", HintLabel.of("左上", word('帮', '你', '点'), Boolean.TRUE, CONFIRMED));
        assertEquals("", HintLabel.of("左上", word('替', '你', '操', '作'), Boolean.TRUE, CONFIRMED));
    }

    /** 判据 3：同一输入重复出标签，逐字节相同，且穿插别的调用也不变。 */
    @Test
    public void sameInputsAlwaysProduceTheSameLabel() {
        String first = HintLabel.of("右上", "棕色动物", Boolean.FALSE, UNSURE);
        HintLabel.of("左下", "蜂蜜覆盖", Boolean.TRUE, CONFIRMED);
        System.currentTimeMillis();
        String second = HintLabel.of("右上", "棕色动物", Boolean.FALSE, UNSURE);
        String third = HintLabel.of("右上", "棕色动物", Boolean.FALSE, UNSURE);
        assertEquals("右上区域，棕色动物交换，拿不准", first);
        assertEquals(first, second);
        assertEquals(first, third);
    }

    @Test
    public void labelNeverExceedsTwentyCharsAndDropsThePieceFirst() {
        assertEquals(20, HintLabel.MAX_LABEL_CHARS);
        String eleven = repeat('狐', 11);
        assertEquals("左上区域，" + eleven + "可点", HintLabel.of("左上", eleven, Boolean.TRUE, CONFIRMED));
        assertEquals(18, HintLabel.of("左上", eleven, Boolean.TRUE, CONFIRMED).length());
        String eighteen = repeat('狐', 18);
        assertEquals("左上区域，可点", HintLabel.of("左上", eighteen, Boolean.TRUE, CONFIRMED));
        assertEquals("左上区域，可点，拿不准", HintLabel.of("左上", eighteen, Boolean.TRUE, UNSURE));
    }

    @Test
    public void outOfRangeCellIndexOrEmptyBoardProducesSilence() {
        assertEquals("", HintLabel.ofBoard(7, 3, true, 7, 7, CONFIRMED));
        assertEquals("", HintLabel.ofBoard(-1, 0, true, 7, 7, CONFIRMED));
        assertEquals("", HintLabel.ofBoard(0, 7, true, 7, 7, CONFIRMED));
        assertEquals("", HintLabel.ofBoard(0, 0, true, 0, 0, CONFIRMED));
        assertEquals("左上区域，可点", HintLabel.ofBoard(0, 0, true, 7, 7, CONFIRMED));
        assertEquals("右下区域，交换", HintLabel.ofBoard(6, 6, false, 7, 7, CONFIRMED));
    }

    /** 接线用的入口：分区取自 {@link Match3Coach#quadrantOf}，棋子名本层拿不到所以省略。 */
    @Test
    public void hintAdapterUsesTheQuadrantAndActionTypeOfTheHintItself() {
        Match3Hint swap = new Match3Hint("s", 3, 100, geometry(7, 7), new Match3Board.Swap(4, 3, 4, 4, 1));
        Match3Hint activate = new Match3Hint("s", 3, 100, geometry(7, 7), new Match3Board.Swap(
                1, 5, 1, 5, 0, 0, 0, 0, new BitSet(), Collections.emptyList(),
                Match3Action.Type.ACTIVATE, "line_horizontal"));
        assertEquals("右下区域，交换", HintLabel.from(swap, CONFIRMED));
        assertEquals("右上区域，可点", HintLabel.from(activate, CONFIRMED));
        assertEquals("右下区域，交换，可能", HintLabel.from(swap, PROBABLE));
        assertEquals("", HintLabel.from(null, CONFIRMED));
    }

    @Test
    public void missingReadingIsReportedAsUnknownTierNotAsAnInventedGuess() {
        Match3Hint swap = new Match3Hint("s", 3, 100, geometry(7, 7), new Match3Board.Swap(4, 3, 4, 4, 1));
        assertEquals(HintLabel.Tier.UNKNOWN, HintLabel.tierOf(swap));
        assertEquals(HintLabel.Tier.UNKNOWN, HintLabel.tierOf(null));
        assertEquals("", HintLabel.forHint(swap));
        assertEquals("", HintLabel.forHint(null));
    }
}
