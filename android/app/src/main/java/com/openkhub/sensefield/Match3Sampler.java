package com.openkhub.sensefield;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 消消乐识别采样器：Bitmap＋标定 → 颜色矩阵；含特殊棋子模板匹配。
 *  Activity（截图式）与 Match3LiveService（实时式）共用，保证两条链路行为一致。 */
final class Match3Sampler {
    static final char UNKNOWN = '.';

    /** 特殊棋子模板：一张 32×32 裁剪图＋名字。 */
    static final class SpecialTemplate {
        final String name;
        final Bitmap thumb;

        SpecialTemplate(String name, Bitmap thumb) {
            this.name = name;
            this.thumb = thumb;
        }
    }

    /* ---------- 实例封装：Activity/Service 持有一个实例，模板只加载一次 ---------- */

    private final android.content.Context context;
    private final int rows;
    private final int cols;
    private final int lPct;
    private final int tPct;
    private final int rPct;
    private final int bPct;
    private final List<SpecialTemplate> templates;

    Match3Sampler(android.content.Context context, int rows, int cols,
                  int lPct, int tPct, int rPct, int bPct) {
        this.context = context;
        this.rows = rows;
        this.cols = cols;
        this.lPct = lPct;
        this.tPct = tPct;
        this.rPct = rPct;
        this.bPct = bPct;
        this.templates = loadTemplates(context);
    }

    /** 按实例标定采样整盘（含特殊棋子模板匹配）。 */
    char[][] sample(Bitmap bitmap) {
        return sample(bitmap, rows, cols, lPct, tPct, rPct, bPct, templates);
    }

    /** 按标定采样整个棋盘。templates 可为 null/空。 */
    /** 单点报点：对截图中任意一点做颜色分类（预览点击报点用）。 */
    static char classifyPoint(Bitmap bitmap, int cx, int cy) {
        int half = Math.max(6, bitmap.getWidth() / 130);
        return classifyCell(bitmap, cx, cy, half, null);
    }

    /** 横向均匀取 n 个槽位的颜色（道具栏播报用）。 */
    static char[] classifyStrip(Bitmap bitmap, int yPctFrom, int yPctTo, int slots) {
        char[] out = new char[slots];
        int y = bitmap.getHeight() * (yPctFrom + yPctTo) / 200;
        int slotW = bitmap.getWidth() / slots;
        for (int i = 0; i < slots; i++) {
            out[i] = classifyCell(bitmap, slotW * i + slotW / 2, y, slotW / 6, null);
        }
        return out;
    }

    static char[][] sample(Bitmap bitmap, int rows, int cols,
                           int lPct, int tPct, int rPct, int bPct,
                           List<SpecialTemplate> templates) {
        int l = bitmap.getWidth() * lPct / 100;
        int t = bitmap.getHeight() * tPct / 100;
        int r = bitmap.getWidth() * rPct / 100;
        int b = bitmap.getHeight() * bPct / 100;
        char[][] board = new char[rows][cols];
        int cellW = (r - l) / cols, cellH = (b - t) / rows;
        int half = Math.max(3, Math.min(cellW, cellH) / 8);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int cx = l + cellW * col + cellW / 2;
                int cy = t + cellH * row + cellH / 2;
                board[row][col] = classifyCell(bitmap, cx, cy, half, templates);
            }
        }
        return board;
    }

    static char classifyCell(Bitmap bitmap, int cx, int cy, int half,
                             List<SpecialTemplate> templates) {
        long sumR = 0, sumG = 0, sumB = 0, n = 0;
        for (int y = Math.max(0, cy - half); y <= Math.min(bitmap.getHeight() - 1, cy + half); y++) {
            for (int x = Math.max(0, cx - half); x <= Math.min(bitmap.getWidth() - 1, cx + half); x++) {
                int px = bitmap.getPixel(x, y);
                sumR += Color.red(px);
                sumG += Color.green(px);
                sumB += Color.blue(px);
                n++;
            }
        }
        if (n == 0) return UNKNOWN;
        int rgb = Color.rgb((int) (sumR / n), (int) (sumG / n), (int) (sumB / n));
        float[] hsv = new float[3];
        Color.colorToHSV(rgb, hsv);
        if (hsv[1] < 0.18f || hsv[2] < 0.15f) {
            return matchTemplate(bitmap, cx, cy, half, templates);
        }
        float h = hsv[0];
        if (h >= 345 || h < 14) return 'R';
        if (h < 38) return 'O';
        if (h < 68) return 'Y';
        if (h < 165) return 'G';
        if (h < 262) return 'B';
        return 'P';
    }

    /** 颜色判不出的格子 → 与特殊棋子模板比对（16×16 缩放后平均绝对差），阈值内取最像的。 */
    private static char matchTemplate(Bitmap bitmap, int cx, int cy, int half,
                                      List<SpecialTemplate> templates) {
        if (templates == null || templates.isEmpty()) return UNKNOWN;
        Bitmap cell = cropSquare(bitmap, cx, cy, half * 4);
        if (cell == null) return UNKNOWN;
        Bitmap small = Bitmap.createScaledBitmap(cell, 16, 16, true);
        char code = UNKNOWN;
        float best = Float.MAX_VALUE;
        for (SpecialTemplate t : templates) {
            float diff = meanAbsDiff(small, t.thumb);
            if (diff < best) {
                best = diff;
                code = templateCode(t.name);
            }
        }
        if (best > 30f) return UNKNOWN;   // 都不像 → 未识别，不硬猜
        return code;
    }

    /** 模板名 → 矩阵字母（'1'..'9' 供扩展矩阵用；名字在播报层还原）。 */
    static char templateCode(String name) {
        int idx = Math.abs(name.hashCode()) % 9;
        return (char) ('1' + idx);
    }

    private static Bitmap cropSquare(Bitmap bitmap, int cx, int cy, int halfSide) {
        int side = Math.max(8, halfSide);
        int l = Math.max(0, cx - side), t = Math.max(0, cy - side);
        int r = Math.min(bitmap.getWidth(), cx + side), b = Math.min(bitmap.getHeight(), cy + side);
        if (r - l < 8 || b - t < 8) return null;
        return Bitmap.createBitmap(bitmap, l, t, r - l, b - t);
    }

    private static float meanAbsDiff(Bitmap a, Bitmap b) {
        Bitmap bb = (b.getWidth() != 16 || b.getHeight() != 16)
                ? Bitmap.createScaledBitmap(b, 16, 16, true) : b;
        long diff = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int pa = a.getPixel(x, y), pb = bb.getPixel(x, y);
                diff += Math.abs(Color.red(pa) - Color.red(pb))
                        + Math.abs(Color.green(pa) - Color.green(pb))
                        + Math.abs(Color.blue(pa) - Color.blue(pb));
            }
        }
        return diff / (16f * 16f * 3f);
    }

    /* ---------- 棋盘自动适配：检测深色棋盘格区域的包围盒 ---------- */

    /**
     * 纯逻辑：给定逐像素「是棋盘格」掩码（降采样网格），返回棋盘包围盒（百分比）。
     * 算法：行剖面找最长的密集行带（≥25% 像素是格），带内列剖面（≥30%）定左右。
     * 返回 {l,t,r,b}（百分比，0-100）；检测不到返回 null。
     */
    static int[] detectBoundsFromMask(boolean[][] mask) {
        int rows = mask.length, cols = mask[0].length;
        int[] rowCount = new int[rows];
        for (int r = 0; r < rows; r++) {
            int n = 0;
            for (int c = 0; c < cols; c++) if (mask[r][c]) n++;
            rowCount[r] = n;
        }
        int minRowCount = cols / 4;
        int bestTop = -1, bestBottom = -1, bestLen = 0;
        int top = -1;
        for (int r = 0; r <= rows; r++) {
            boolean dense = r < rows && rowCount[r] >= minRowCount;
            if (dense && top < 0) top = r;
            if ((!dense || r == rows) && top >= 0) {
                int len = r - top;
                if (len > bestLen) { bestLen = len; bestTop = top; bestBottom = r - 1; }
                top = -1;
            }
        }
        if (bestTop < 0 || bestLen < rows / 10) return null;
        int[] colCount = new int[cols];
        for (int r = bestTop; r <= bestBottom; r++) {
            for (int c = 0; c < cols; c++) if (mask[r][c]) colCount[c]++;
        }
        int bandRows = bestBottom - bestTop + 1;
        int minColCount = bandRows * 3 / 10;
        int left = -1, right = -1;
        for (int c = 0; c < cols; c++) {
            if (colCount[c] >= minColCount) { if (left < 0) left = c; right = c; }
        }
        if (left < 0 || right - left < cols / 10) return null;
        return new int[]{
                left * 100 / cols, bestTop * 100 / rows,
                (right + 1) * 100 / cols, (bestBottom + 1) * 100 / rows
        };
    }

    /**
     * 对一帧做自动适配：降采样 → 逐像素判「深色棋盘格」（开心消消乐棋盘底为深蓝黑）→ 包围盒。
     * 检测不到返回 null（保持手动标定）。
     */
    static int[] autoDetectBoard(Bitmap frame) {
        int step = Math.max(1, frame.getWidth() / 160);
        int cols = frame.getWidth() / step, rows = frame.getHeight() / step;
        boolean[][] mask = new boolean[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int x = Math.min(frame.getWidth() - 1, c * step + step / 2);
                int y = Math.min(frame.getHeight() - 1, r * step + step / 2);
                int px = frame.getPixel(x, y);
                float[] hsv = new float[3];
                Color.colorToHSV(px, hsv);
                /* 棋盘底：暗（v<0.45）、偏冷色（hue 170-300）、饱和度不限（冰格也偏暗） */
                mask[r][c] = hsv[2] < 0.45f && hsv[0] >= 170f && hsv[0] <= 300f;
            }
        }
        return detectBoundsFromMask(mask);
    }

    /* ---------- 特殊棋子模板存取（app 私有目录 special_templates/） ---------- */

    static File templateDir(android.content.Context context) {
        File dir = new File(context.getFilesDir(), "special_templates");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static List<SpecialTemplate> loadTemplates(android.content.Context context) {
        List<SpecialTemplate> out = new ArrayList<>();
        File dir = templateDir(context);
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (!f.getName().endsWith(".png")) continue;
            String name = f.getName().substring(0, f.getName().length() - 4);
            Bitmap bmp = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bmp != null) out.add(new SpecialTemplate(name, bmp));
        }
        return out;
    }

    static void saveTemplate(android.content.Context context, String name, Bitmap cell) throws IOException {
        Bitmap thumb = Bitmap.createScaledBitmap(cell, 32, 32, true);
        File out = new File(templateDir(context), name + ".png");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            thumb.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
    }
}
