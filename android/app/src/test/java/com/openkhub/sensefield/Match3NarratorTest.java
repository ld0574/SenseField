package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * 整盘解说器测试。
 *
 * <p>每条期望值都是照着词表手工写死的（左上/右上/左下/右下 ＋ 红狐狸/棕色动物/小鸡/青蛙/河马/
 * 紫猫头鹰/雪块/空位/空/非普通棋子区域/鸡蛋/未识别），不是拿 narrate 自己的输出反推的。
 * 8×8 盘的象限切分：quadrantOf 用 rows/2、cols/2 整数除法，行列 0..3 与 4..7 各自成一片，
 * 每片恰好 16 格。
 */
public class Match3NarratorTest {

    private static char[][] board(String... rows) {
        char[][] b = new char[rows.length][];
        for (int i = 0; i < rows.length; i++) b[i] = rows[i].toCharArray();
        return b;
    }

    /** 四个象限各扫一遍的 8×8 模板：左上/右上/左下/右下每片 16 格。 */
    private static char[][] fourByFour(String topLeft, String topRight,
                                       String bottomLeft, String bottomRight) {
        char[][] b = new char[8][8];
        for (int r = 0; r < 8; r++) {
            String left = r < 4 ? topLeft : bottomLeft;
            String right = r < 4 ? topRight : bottomRight;
            for (int c = 0; c < 4; c++) b[r][c] = left.charAt(c);
            for (int c = 4; c < 8; c++) b[r][c] = right.charAt(c - 4);
        }
        return b;
    }

    /** 每片内部混四种叫法，单种计数压到 4，好让「集中那一片」成为唯一最大值。 */
    private static final String MIXED = "RYGP";

    /* ---------- 1. 全空盘 ---------- */

    @Test
    public void allEmptyBoardAnnouncesEmptyCellsOnly() {
        char[][] b = board("        ", "        ", "        ", "        ",
                "        ", "        ", "        ", "        ");
        assertEquals("左上那一片主要是空 16 个。盘面都在预期里。",
                Match3Narrator.narrate(b, 8, 8));
    }

    /* ---------- 2. 全未识别 ---------- */

    @Test
    public void allUnknownBoardAnnouncesEveryCellUnread() {
        char[][] b = board("........", "........", "........", "........",
                "........", "........", "........", "........");
        assertEquals("左上那一片主要是未识别 16 个。还有 64 格没认出来。",
                Match3Narrator.narrate(b, 8, 8));
    }

    /* ---------- 3. 单片集中 ---------- */

    @Test
    public void concentratedRegionNamesTheDominantQuadrant() {
        char[][] b = fourByFour(MIXED, MIXED, MIXED, "RRRR");
        assertEquals("右下那一片主要是红狐狸 16 个。盘面都在预期里。",
                Match3Narrator.narrate(b, 8, 8));
    }

    /* ---------- 4. 四象限均衡（并列时先扫到的那片胜出，即左上） ---------- */

    @Test
    public void balancedQuadrantsBreakTieToFirstScannedRegion() {
        char[][] b = fourByFour("RRRR", "YYYY", "GGGG", "BBBB");
        assertEquals("左上那一片主要是红狐狸 16 个。盘面都在预期里。",
                Match3Narrator.narrate(b, 8, 8));
    }

    /* ---------- 5. 含雪块：走词表叫法，且雪块不算未识别 ---------- */

    @Test
    public void snowRegionUsesVocabularyWordAndIsNotCountedUnknown() {
        char[][] b = fourByFour(MIXED, MIXED, MIXED, "IIII");
        assertEquals("右下那一片主要是雪块 16 个。盘面都在预期里。",
                Match3Narrator.narrate(b, 8, 8));
    }

    /* ---------- 6. 含鸡蛋 ---------- */

    @Test
    public void eggRegionUsesVocabularyWord() {
        char[][] b = fourByFour("EEEE", MIXED, MIXED, MIXED);
        assertEquals("左上那一片主要是鸡蛋 16 个。盘面都在预期里。",
                Match3Narrator.narrate(b, 8, 8));
    }

    /* ---------- 7. 含非普通棋子区域 ---------- */

    @Test
    public void nonSwapRegionUsesVocabularyWord() {
        char[][] b = fourByFour(MIXED, MIXED, "####", MIXED);
        assertEquals("左下那一片主要是非普通棋子区域 16 个。盘面都在预期里。",
                Match3Narrator.narrate(b, 8, 8));
    }

    /* ---------- 8. 集中那一片里点名第二多的叫法 ---------- */

    @Test
    public void dominantRegionAlsoListsItsSecondPiece() {
        // 左上（第 0..3 行、第 0..3 列）：第 0、1 行各 2 红 2 未识别，第 2、3 行各 4 红 → 红狐狸 12、未识别 4。
        // 其余三片一律 RYGP 混排，单种最高只有 4，所以集中那一片唯一。
        char[][] b = board("RR..RYGP", "RR..RYGP", "RRRRRYGP", "RRRRRYGP",
                "RYGPRYGP", "RYGPRYGP", "RYGPRYGP", "RYGPRYGP");
        assertEquals("左上那一片主要是红狐狸 12 个、未识别 4 个。还有 4 格没认出来。",
                Match3Narrator.narrate(b, 8, 8));
    }

    /** 词表里最长的名字（非普通棋子区域）与未识别并列出现，同时当长度上限的压力样本。 */
    @Test
    public void longestVocabularyNameStillFitsTheTemplate() {
        // 左下（第 4..7 行、第 0..3 列）：第 4、5 行各 2 个 # 与 2 个未识别，第 6、7 行各 4 个 #
        // → 非普通棋子区域 12、未识别 4；其余三片混排单种最高 4。
        char[][] b = board("RYGPRYGP", "RYGPRYGP", "RYGPRYGP", "RYGPRYGP",
                "##..RYGP", "##..RYGP", "####RYGP", "####RYGP");
        assertEquals("左下那一片主要是非普通棋子区域 12 个、未识别 4 个。还有 4 格没认出来。",
                Match3Narrator.narrate(b, 8, 8));
    }

    /* ---------- 9. 句数上限 ---------- */

    @Test
    public void neverMoreThanTwoSentences() {
        char[][] b = board("....RYGP", ".##.YYGP", "#H#IRYGP", " E C#PYYGP",
                "RY..####", "##..RYGP", "..##RYGP", "....RYGP");
        String out = Match3Narrator.narrate(b, 8, 8);
        assertEquals("整句必须以句号收尾：" + out, '。', out.charAt(out.length() - 1));
        int sentences = 0;
        for (int i = 0; i < out.length(); i++) {
            if (out.charAt(i) == '。') sentences++;
        }
        assertTrue("解说不得超过两句：" + out, sentences <= Match3Narrator.MAX_SENTENCES);
        assertEquals("解说必须正好两句：" + out, Match3Narrator.MAX_SENTENCES, sentences);
    }

    /* ---------- 10. 长度上限 ---------- */

    @Test
    public void outputStaysWithinLengthCap() {
        char[][] longest = board("RYGPRYGP", "RYGPRYGP", "RYGPRYGP", "RYGPRYGP",
                "##..RYGP", "##..RYGP", "####RYGP", "####RYGP");
        String out = Match3Narrator.narrate(longest, 8, 8);
        assertEquals("最长词表名那一句必须顶到接近上限：" + out.length(),
                40, out.length());
        assertTrue("解说长度必须不超过上限：" + out, out.length() <= Match3Narrator.MAX_CHARS);
    }

    /* ---------- 11. 不许出现行列坐标数字 ---------- */

    @Test
    public void outputHasNoRowColumnCoordinates() {
        char[][] b = board("....RYGP", ".##.YYGP", "#H#IRYGP", " E C#PYYGP",
                "RY..####", "##..RYGP", "..##RYGP", "....RYGP");
        String out = Match3Narrator.narrate(b, 8, 8);
        assertFalse("不许出现「第 N 行／第 N 列」式坐标：" + out,
                out.matches("(?s).*第\\s*\\d+\\s*[行列].*"));
        assertFalse("数字不许挨着行或列：" + out,
                out.matches("(?s).*\\d+\\s*[行列].*") || out.matches("(?s).*[行列]\\s*\\d+.*"));
        assertFalse("不许出现行列字样：" + out, out.indexOf('行') >= 0 || out.indexOf('列') >= 0);
    }

    /* ---------- 12. 方位词必须逐字来自 quadrantOf，不许自造 ---------- */

    @Test
    public void directionWordsComeOnlyFromQuadrantOf() {
        char[][][] samples = {
                board("        ", "        ", "        ", "        ",
                        "        ", "        ", "        ", "        "),
                board("........", "........", "........", "........",
                        "........", "........", "........", "........"),
                fourByFour(MIXED, MIXED, MIXED, "RRRR"),
                fourByFour("RRRR", "YYYY", "GGGG", "BBBB"),
        };
        String[] invented = {"左上角", "右上角", "左下角", "右下角", "上角", "下角", "中部",
                "中央", "正中", "偏上", "偏下", "偏左", "偏右", "上方", "下方", "左边", "右边",
                "靠上", "靠下", "对角"};
        for (char[][] b : samples) {
            String out = Match3Narrator.narrate(b, 8, 8);
            for (String word : invented) {
                assertFalse("解说里不许出现词表外的自造方位词「" + word + "」：" + out,
                        out.contains(word));
            }
            Set<String> legal = new HashSet<>();
            for (int r = 0; r < 8; r++) {
                for (int c = 0; c < 8; c++) legal.add(Match3Coach.quadrantOf(r, c, 8, 8));
            }
            boolean usedLegal = false;
            for (String q : legal) {
                if (out.contains(q)) usedLegal = true;
            }
            assertTrue("解说的方位必须来自 quadrantOf 的返回集合：" + out, usedLegal);
        }
    }

    /* ---------- 13. 不许写裸颜色（词表内的形象名除外） ---------- */

    @Test
    public void noBareColorWordOutsideVocabulary() {
        char[][] b = fourByFour(MIXED, MIXED, "OOOO", "PPPP");
        String out = Match3Narrator.narrate(b, 8, 8);
        assertEquals("左下那一片主要是棕色动物 16 个。盘面都在预期里。", out);
        String residue = out.replace("左上", "").replace("右上", "")
                .replace("左下", "").replace("右下", "")
                .replace(Match3Coach.pieceName('O'), "")
                .replace(Match3Coach.pieceName('P'), "")
                .replace(Match3Coach.pieceName('R'), "")
                .replace(Match3Coach.pieceName('Y'), "")
                .replace(Match3Coach.pieceName('G'), "")
                .replace(Match3Coach.pieceName('B'), "");
        assertFalse("剥掉词表名后不许残留裸颜色：" + residue, residue.indexOf('色') >= 0);
    }

    /* ---------- 14. 不许出现包名／机型名 ---------- */

    @Test
    public void noPackageOrDeviceNameInOutput() {
        String out = Match3Narrator.narrate(fourByFour(MIXED, MIXED, MIXED, "IIII"), 8, 8);
        assertFalse(out.contains("com."));
        assertFalse(out.contains("openkhub"));
        assertFalse(out.contains("sensefield"));
        assertFalse(out.contains("SenseField"));
    }

    /* ---------- 15. 退化输入不许编造分布 ---------- */

    @Test
    public void degenerateInputDoesNotInventADistribution() {
        assertEquals("这一盘暂时读不出棋子分布。盘面都在预期里。",
                Match3Narrator.narrate(new char[0][0], 0, 0));
        assertEquals("这一盘暂时读不出棋子分布。盘面都在预期里。",
                Match3Narrator.narrate(new char[][]{null}, 1, 4));
        assertEquals("这一盘暂时读不出棋子分布。盘面都在预期里。",
                Match3Narrator.narrate(board("RRRR"), 1, 0));
    }

    /** 反面对照：1 行 4 列不是退化输入。quadrantOf 用整数除法，rows/2=0 使该行整体落在下半片。 */
    @Test
    public void singleRowBoardIsStillReadAndLandsInBottomHalves() {
        // 第 0 行：col<cols/2=2 落左下 2 格，其余落右下 2 格；行主序先扫到左下，并列时左下胜出。
        assertEquals("左下那一片主要是红狐狸 2 个。盘面都在预期里。",
                Match3Narrator.narrate(board("RRRR"), 1, 4));
    }
}
