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
        /* 160×100 网格中，棋盘占 28%-72% 高、0-100% 宽（模拟全屏棋盘图） */
        boolean[][] m = mask(100, 160, 28, 72, 0, 159);
        int[] box = Match3Sampler.detectBoundsFromMask(m);
        assertNotNull(box);
        assertEquals(0, box[0]);
        assertEquals(28, box[1]);
        assertEquals(100, box[2]);
        assertEquals(73, box[3]);   // (72+1)*100/100 向上取整格
    }

    @Test
    public void detectsLetterboxedBoard() {
        /* 棋盘只占中间 40% 宽（两侧留边），行 30-70 */
        boolean[][] m = mask(100, 160, 30, 70, 48, 111);
        int[] box = Match3Sampler.detectBoundsFromMask(m);
        assertNotNull(box);
        assertEquals(30, box[0]);   // 48*100/160=30
        assertEquals(30, box[1]);
        assertEquals(70, box[2]);   // 112*100/160=70
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
    public void ignoresSparseNoiseRows() {
        /* 稀疏噪声（每行只有 10% 像素）不应构成棋盘带 */
        boolean[][] m = new boolean[100][160];
        for (int r = 20; r <= 80; r++)
            for (int c = 0; c < 16; c++) m[r][c] = true;
        assertNull(Match3Sampler.detectBoundsFromMask(m));
    }
}
