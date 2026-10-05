package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

/** 棋盘自动适配（包围盒检测）纯逻辑测试。 */
public class Match3AutoDetectTest {

    private static boolean[][] mask(int rows, int cols, int top, int bottom, int left, int right) {
        boolean[][] m = new boolean[rows][cols];
        for (int r = top; r <= bottom; r++)
            for (int c = left; c <= right; c++)
                m[r][c] = true;
        return m;
    }

    @Test
    public void detectsCenteredBoard() {
        /* 160×100 网格中，方形棋盘占行 28-72、列 0-44（v3 方形约束：边长=行带高，
         * 滑窗密度并列时取最左） */
        boolean[][] m = mask(100, 160, 28, 72, 0, 44);
        int[] box = Match3Sampler.detectBoundsFromMask(m);
        assertNotNull(box);
        assertEquals(0, box[0]);
        assertEquals(28, box[1]);
        assertEquals(28, box[2]);   // (0+45)*100/160=28
        assertEquals(73, box[3]);   // (28+45)*100/100 向上取整格
    }

    @Test
    public void detectsLetterboxedBoard() {
        /* 棋盘只占中间部分：行 30-70（41 行）、列 48-111（64 列）→ 方形约束取 41×41 */
        boolean[][] m = mask(100, 160, 30, 70, 48, 111);
        int[] box = Match3Sampler.detectBoundsFromMask(m);
        assertNotNull(box);
        assertEquals(30, box[0]);   // 48*100/160=30
        assertEquals(30, box[1]);
        assertEquals(55, box[2]);   // (48+41)*100/160=55.6
        assertEquals(71, box[3]);
    }

    @Test
    public void prefersLargestBandWhenSkyAlsoDark() {
        /* 天空带（行 5-8 全宽暗）+ 棋盘带（行 40-90 全宽暗）→ 应选更长的棋盘带 */
        boolean[][] m = new boolean[100][160];
        for (int r = 5; r <= 8; r++)
            for (int c = 0; c < 160; c++) m[r][c] = true;
        for (int r = 40; r <= 90; r++)
            for (int c = 0; c < 160; c++) m[r][c] = true;
        int[] box = Match3Sampler.detectBoundsFromMask(m);
        assertNotNull(box);
        assertEquals(40, box[1]);
        assertEquals(91, box[3]);
    }

    @Test
    public void returnsNullWhenNoBoard() {
        assertNull(Match3Sampler.detectBoundsFromMask(new boolean[100][160]));
    }
    @Test
    public void detectsSquareBoardWithSparseGrid() {
        /* 真机场景（REAL_VIDEO_FINDINGS.md）：棋子几乎填满格子，暗底只在格缝/边框露出。
         * 240×160 网格里放一块精确 7×7 方形棋盘（行 100-219、列 26-145，格距 17），
         * 只有格线行列是暗的（棋子行每行仅 8 条竖线，恰好达 cols/20 阈值）。
         * v3 应完整检出并按方形约束锚定到格线区域。 */
        boolean[][] m = new boolean[240][160];
        for (int r = 100; r <= 219; r++)
            for (int c = 26; c <= 145; c++)
                if ((r - 100) % 17 == 0 || (c - 26) % 17 == 0)
                    m[r][c] = true;
        int[] box = Match3Sampler.detectBoundsFromMask(m);
        assertNotNull(box);
        assertEquals(16, box[0]);   // 26*100/160
        assertEquals(41, box[1]);   // 100*100/240
        assertEquals(91, box[2]);   // (26+120)*100/160=91.25
        assertEquals(91, box[3]);   // (100+120)*100/240=91.67
    }

    @Test
    public void rejectsWindowWithHardlyAnyGridPixels() {
        /* 暗像素太少（噪声带）→ 密度门限判不可信 */
        boolean[][] m = new boolean[100][160];
        for (int r = 30; r <= 70; r++)
            for (int c = 40; c <= 80; c++)
                if ((r + c) % 25 == 0) m[r][c] = true;   // ~4% 撒点，低于 5% 密度门限
        int[] box = Match3Sampler.detectBoundsFromMask(m);
        assertNull(box);
    }

    @Test
    public void ignoresSparseNoiseRows() {
        /* 稀疏噪声（每行只有 10% 像素）不应构成棋盘带 */
        boolean[][] m = new boolean[100][160];
        for (int r = 20; r <= 80; r++)
            for (int c = 0; c < 16; c++) m[r][c] = true;
        assertNull(Match3Sampler.detectBoundsFromMask(m));
    }
}
