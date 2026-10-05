package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;

import org.junit.Test;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 空格判定 + 特殊棋子字母分配的守卫测试。
 * 只在 java-bench 离线环境跑（依赖 android.graphics 桌面替身，真机单测里 Bitmap 取像素恒为 0）。
 */
public class Match3GuardTest {

    private static final char[] BASE_LETTERS = {'R', 'O', 'Y', 'G', 'B', 'P'};

    private static Bitmap canvas(int w, int h, int rgb) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(rgb));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return new Bitmap(img);
    }

    /** 棋盘底色（深蓝黑，v≈0.35，光靠 HSV 弃权闸门挡不住）应判为空。 */
    @Test
    public void flatBoardBackgroundIsReadAsEmpty() {
        Bitmap bg = canvas(1080, 2400, 0x1E2A58);
        int cellW = 86, cellH = 96;
        for (int[] p : new int[][]{{540, 1100}, {200, 800}, {900, 1500}}) {
            assertTrue("格心 " + p[0] + "," + p[1] + " 应判为空",
                    Match3Sampler.looksEmpty(bg, p[0], p[1], cellW, cellH));
        }
        assertTrue(Match3Sampler.centerCornerDistance(bg, 540, 1100, cellW, cellH)
                <= Match3Sampler.EMPTY_COLOR_DISTANCE);
    }

    /** 格心有对比鲜明的棋子时不许判空，否则整盘会被当成空格漏报。 */
    @Test
    public void cellWithPieceIsNotReadAsEmpty() {
        int cellW = 86, cellH = 96;
        Bitmap frame = canvas(1080, 2400, 0x1E2A58);
        BufferedImage img = frame.img;
        Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(0xD83A2C));      // 红狐狸色块
        g.fillRect(540 - cellW / 3, 1100 - cellH / 3, cellW * 2 / 3, cellH * 2 / 3);
        g.dispose();
        int distance = Match3Sampler.centerCornerDistance(frame, 540, 1100, cellW, cellH);
        assertTrue("有子格色差 " + distance + " 应远大于阈值 " + Match3Sampler.EMPTY_COLOR_DISTANCE,
                distance > Match3Sampler.EMPTY_COLOR_DISTANCE * 2);
        assertFalse(Match3Sampler.looksEmpty(frame, 540, 1100, cellW, cellH));
    }

    /** 格数自检：真机美术没格线，靠列剖面自相关；参数顺序写错过一次（getPixels stride/offset 互换）
     *  直接 AIOOBE，而仓库单测里 Bitmap 取像素恒为 0 挡不住，只能在这里锁。 */
    @Test
    public void gridCountDetectsSevenByAutocorrelation() {
        int w = 576, h = 1280;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(0x5AE0F5));          // 天空
        g.fillRect(0, 0, w, h);
        int l = 88, t = 508, r = 490, b = 918;
        g.setColor(new java.awt.Color(0x1E2A58));          // 棋盘底
        g.fillRect(l, t, r - l, b - t);
        int cw = (r - l) / 7, ch = (b - t) / 7;
        java.awt.Color[] pal = {new java.awt.Color(0xD87830), new java.awt.Color(0xF0D060),
                new java.awt.Color(0x40B040), new java.awt.Color(0x50A0E0)};
        for (int row = 0; row < 7; row++)
            for (int col = 0; col < 7; col++) {
                g.setColor(pal[(row * 3 + col) % pal.length]);
                g.fillOval(l + cw * col + 3, t + ch * row + 3, cw - 6, ch - 6);
            }
        g.dispose();
        Bitmap frame = new Bitmap(img);
        int[] auto = Match3Sampler.autoDetectBoard(frame);
        org.junit.Assert.assertNotNull("合成棋盘应能自动适配", auto);
        assertEquals(7, Match3Sampler.detectGridCount(frame, auto));
        assertEquals(7, Match3Sampler.detectGridCount(frame, new int[]{15, 39, 85, 72}));
    }

    /** 20 种特殊棋子同时学习：字母互不撞车、不占基础色字母、名字可原样还原。 */
    @Test
    public void specialPieceCodesAreUniqueAndReversible() throws Exception {
        File dir = new File("guard-files");
        File tplDir = new File(dir, "special_templates");
        if (tplDir.exists()) {
            File[] old = tplDir.listFiles();
            if (old != null) for (File f : old) f.delete();
        }
        Context ctx = new Context(dir);

        List<String> names = new ArrayList<>();
        for (int i = 0; i < 20; i++) names.add("守卫棋子" + String.format("%02d", i));
        for (String n : names) {
            Match3Sampler.saveTemplate(ctx, n, canvas(64, 64, 0x808080));
        }

        List<Match3Sampler.SpecialTemplate> loaded = Match3Sampler.loadTemplates(ctx);
        assertEquals("模板应全部落盘读回", 20, loaded.size());

        Set<Character> codes = new HashSet<>();
        for (String n : names) {
            char code = Match3Sampler.templateCode(n);
            assertNotEquals(n + " 未分配字母", Match3Sampler.UNKNOWN, code);
            for (char base : BASE_LETTERS) {
                assertNotEquals(n + " 抢占了基础色字母 " + base, base, code);
            }
            assertTrue(n + " 字母重复", codes.add(code));
            assertEquals("字母 " + code + " 名字还原错", n, Match3Sampler.nameForCode(code));
            assertEquals("播报层应念出学习的名字", n, Match3Coach.pieceName(code));
        }
        assertEquals(20, codes.size());
    }

    /** 采样矩阵必须把「没棋子」和「认不出」分开：两者旧代码同为 '.'，逐行播报就会把空格和
     *  未识别念成同一个词，特殊棋子更会被念成「空」。真机单测里 Bitmap 取像素恒为 0，只能在这跑。 */
    @Test
    public void sampleSeparatesEmptyCellFromUnknown() {
        int w = 576, h = 1280;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new java.awt.Color(0x5AE0F5));          // 天空
        g.fillRect(0, 0, w, h);
        int l = 88, t = 508, r = 490, b = 918;
        g.setColor(new java.awt.Color(0x1E2A58));          // 棋盘底
        g.fillRect(l, t, r - l, b - t);
        int cw = (r - l) / 7, ch = (b - t) / 7;
        java.awt.Color[] pal = {new java.awt.Color(0xD87830), new java.awt.Color(0xF0D060),
                new java.awt.Color(0x40B040), new java.awt.Color(0x50A0E0)};
        for (int row = 0; row < 7; row++)
            for (int col = 0; col < 7; col++) {
                if (row == 3 && col == 3) continue;        // 留一个真空位
                g.setColor(pal[(row * 3 + col) % pal.length]);
                g.fillOval(l + cw * col + 3, t + ch * row + 3, cw - 6, ch - 6);
            }
        g.dispose();
        Bitmap frame = new Bitmap(img);

        char[][] board = Match3Sampler.sample(frame, 7, 7, 15, 39, 85, 72, null);
        assertEquals("真空位应读成空格标记", Match3Sampler.EMPTY_CELL, board[3][3]);
        for (int row = 0; row < 7; row++)
            for (int col = 0; col < 7; col++)
                if (row != 3 || col != 3)
                    assertTrue("有子格 " + row + "," + col + " 读成了 " + board[row][col],
                            Match3Board.isPiece(board[row][col]));

        String line4 = Match3Board.scanSpeech(board).get(3);
        assertTrue("逐行播报要把空格念成「空」：" + line4, line4.contains("空"));
        assertFalse("不许再出现颜色词单字表：" + line4, line4.contains("橙"));
        assertEquals("空格念空、未识别念未识别", "空", Match3Coach.pieceName(Match3Sampler.EMPTY_CELL));
    }
}
