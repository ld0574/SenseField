package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.List;

/** 播报层字母→棋子名映射测试。特殊棋子模板名还原（需真实图片）在 research/board-recognition/java-bench 里跑。 */
public class Match3PieceNameTest {

    /** 六个基础色字母必须各自还原成动物名，不能落到「未识别」。 */
    @Test
    public void mapsBaseLettersToAnimalNames() {
        assertEquals("红狐狸", Match3Coach.pieceName('R'));
        assertEquals("棕熊", Match3Coach.pieceName('O'));
        assertEquals("小鸡", Match3Coach.pieceName('Y'));
        assertEquals("青蛙", Match3Coach.pieceName('G'));
        assertEquals("河马", Match3Coach.pieceName('B'));
        assertEquals("紫猫", Match3Coach.pieceName('P'));
    }

    @Test
    public void baseLettersAreDistinctSounds() {
        assertNotEquals(Match3Coach.pieceName('R'), Match3Coach.pieceName('O'));
    }

    /** 未注册的字母（含弃权符）必须明确说「未识别」，不许硬猜成某个动物。 */
    @Test
    public void unknownLetterAnnouncesUnknown() {
        assertEquals("未识别", Match3Coach.pieceName(Match3Sampler.UNKNOWN));
        assertEquals("未识别", Match3Coach.pieceName('Z'));
    }

    /** 名字→字母只认基础色名，特殊棋子名交给字母池分配，不占用基础色字母。 */
    @Test
    public void specialPieceNamesDoNotClaimBaseLetters() {
        assertEquals(Match3Sampler.UNKNOWN, Match3Sampler.nameToLetter("彩虹球"));
        assertEquals(Match3Sampler.UNKNOWN, Match3Sampler.nameToLetter("木箱"));
        assertEquals(Match3Sampler.UNKNOWN, Match3Sampler.nameToLetter(null));
        assertEquals('R', Match3Sampler.nameToLetter("狐狸红"));
        assertEquals('O', Match3Sampler.nameToLetter("棕熊棕"));
    }

    /** 字母表未注册时 templateCode 弃权，nameForCode 返回 null（播报层据此走「未识别」）。 */
    @Test
    public void unregisteredCodeFallsBack() {
        assertEquals(Match3Sampler.UNKNOWN, Match3Sampler.templateCode("尚未学习的棋子"));
        assertEquals(null, Match3Sampler.nameForCode('Z'));
    }

    /** 空格与未识别是两个词：没棋子 ≠ 认不出。 */
    @Test
    public void emptyAndUnknownAreDifferentWords() {
        assertEquals("空", Match3Coach.pieceName(Match3Sampler.EMPTY_CELL));
        assertEquals("未识别", Match3Coach.pieceName(Match3Sampler.UNKNOWN));
        assertNotEquals(Match3Coach.pieceName(Match3Sampler.EMPTY_CELL),
                Match3Coach.pieceName(Match3Sampler.UNKNOWN));
    }

    /** The legacy predicate means non-movable, not unknown. The two concepts stay distinct. */
    @Test
    public void unreadableCoversBothMarkers() {
        org.junit.Assert.assertTrue(Match3Sampler.isUnreadable(Match3Sampler.UNKNOWN));
        org.junit.Assert.assertTrue(Match3Sampler.isUnreadable(Match3Sampler.EMPTY_CELL));
        org.junit.Assert.assertFalse(Match3Sampler.isUnreadable('O'));
        org.junit.Assert.assertTrue(Match3Sampler.isUnreadable('1'));
        org.junit.Assert.assertFalse(Match3Sampler.isUnknown('1'));
    }

    /** 逐行扫描必须与点读/区域摘要同一套词表——旧 charName 念颜色词，玩家听到两套命名。 */
    @Test
    public void scanSpeechUsesTheSameVocabularyAsPieceName() {
        char[][] board = {
                {'O', 'Y', Match3Sampler.EMPTY_CELL},
                {'G', 'B', Match3Sampler.UNKNOWN},
        };
        List<String> lines = Match3Board.scanSpeech(board);
        assertEquals("第 1 行：棕熊、小鸡、空", lines.get(0));
        assertEquals("第 2 行：青蛙、河马、未识别", lines.get(1));
    }
}
