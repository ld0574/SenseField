package com.openkhub.sensefield;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 消消乐识别采样器：Bitmap＋标定 → 颜色矩阵；含特殊棋子模板匹配。
 *  Activity（截图式）与 Match3LiveService（实时式）共用，保证两条链路行为一致。 */
final class Match3Sampler {
    static final char UNKNOWN = '.';
    /** 确认的空格（格心与四角同色）。与 UNKNOWN 分开，否则「没棋子」和「认不出」在播报里同一个词。 */
    static final char EMPTY_CELL = ' ';

    /** 该格读不出棋子（真空位或认不出）——自我修复检测按这个口径数。 */
    static boolean isUnreadable(char c) {
        return c == UNKNOWN || c == EMPTY_CELL;
    }

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
                board[row][col] = looksEmpty(bitmap, cx, cy, cellW, cellH)
                        ? EMPTY_CELL
                        : classifyCell(bitmap, cx, cy, half, templates);
            }
        }
        return board;
    }

    int rowCount() { return rows; }

    int colCount() { return cols; }

    /** 触屏点读：把屏幕坐标映射到格子并分类该格（模板优先）。返回 {row,col,piece}，null=点在棋盘外。 */
    int[] touchRead(Bitmap frame, int px, int py) {
        int l = frame.getWidth() * lPct / 100;
        int t = frame.getHeight() * tPct / 100;
        int r = frame.getWidth() * rPct / 100;
        int b = frame.getHeight() * bPct / 100;
        if (px < l || px >= r || py < t || py >= b) return null;
        int col = (px - l) * cols / (r - l);
        int row = (py - t) * rows / (b - t);
        if (row < 0 || row >= rows || col < 0 || col >= cols) return null;
        int cellW = (r - l) / cols, cellH = (b - t) / rows;
        int cx = l + cellW * col + cellW / 2, cy = t + cellH * row + cellH / 2;
        int half = Math.max(3, Math.min(cellW, cellH) / 8);
        char piece = looksEmpty(frame, cx, cy, cellW, cellH)
                ? EMPTY_CELL
                : classifyCell(frame, cx, cy, half, templates);
        return new int[]{row, col, piece};
    }

    static char classifyCell(Bitmap bitmap, int cx, int cy, int half,
                             List<SpecialTemplate> templates) {
        /* 模板优先：玩家对真实画面学习过的棋子（基础动物＋特殊棋子）最可信，
         * 先比模板（对狐狸红与棕熊棕这类相近色相远比 HSV 桶可靠），HSV 桶只做兜底。 */
        if (templates != null && !templates.isEmpty()) {
            char byTemplate = matchTemplate(bitmap, cx, cy, half, templates);
            if (byTemplate != UNKNOWN) return byTemplate;
        }
        int rgb = avgColor(bitmap, cx, cy, half);
        if (rgb == NO_PIXELS) return UNKNOWN;
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

    /** 以 (cx,cy) 为中心、half 为半径取平均色，返回 packed RGB；区域越界无像素时返回 NO_PIXELS。 */
    private static int avgColor(Bitmap bitmap, int cx, int cy, int half) {
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
        if (n == 0) return NO_PIXELS;
        return Color.rgb((int) (sumR / n), (int) (sumG / n), (int) (sumB / n));
    }

    /** Color.rgb 打包后恒为负数（alpha 占高位），所以「取不到像素」必须用落在有效色域外的哨兵。 */
    private static final int NO_PIXELS = Integer.MIN_VALUE;

    /** 空格判定阈值：中心与四角的逐通道平均色差上限。棋子会盖住中心、盖不住四角，实测余量见基准。 */
    static final int EMPTY_COLOR_DISTANCE = 12;

    /** 格心与格四角（棋子覆盖不到的位置）的逐通道平均色差。空格≈0，有子时远大于此。 */
    static int centerCornerDistance(Bitmap bitmap, int cx, int cy, int cellW, int cellH) {
        int centre = avgColor(bitmap, cx, cy, Math.max(3, Math.min(cellW, cellH) / 8));
        if (centre == NO_PIXELS) return Integer.MAX_VALUE;
        int ox = cellW * 42 / 100, oy = cellH * 42 / 100;
        int probe = Math.max(2, Math.min(cellW, cellH) / 16);
        int[][] corners = {
                {cx - ox, cy - oy}, {cx + ox, cy - oy}, {cx - ox, cy + oy}, {cx + ox, cy + oy}
        };
        long total = 0;
        int counted = 0;
        for (int[] p : corners) {
            int c = avgColor(bitmap, p[0], p[1], probe);
            if (c == NO_PIXELS) continue;
            total += Math.abs(Color.red(centre) - Color.red(c))
                    + Math.abs(Color.green(centre) - Color.green(c))
                    + Math.abs(Color.blue(centre) - Color.blue(c));
            counted++;
        }
        return counted == 0 ? Integer.MAX_VALUE : (int) (total / (counted * 3L));
    }

    /** 棋盘底色常落在 HSV 弃权闸门（v<0.15）之上，光靠颜色阈值挡不住空格，改用格心与格角的局部对比。 */
    static boolean looksEmpty(Bitmap bitmap, int cx, int cy, int cellW, int cellH) {
        return centerCornerDistance(bitmap, cx, cy, cellW, cellH) <= EMPTY_COLOR_DISTANCE;
    }

    /** 颜色判不出的格子 → 与特殊棋子模板比对（16×16 缩放后平均绝对差），阈值内取最像的。 */
    private static char matchTemplate(Bitmap bitmap, int cx, int cy, int half,
                                      List<SpecialTemplate> templates) {
        if (templates == null || templates.isEmpty()) return UNKNOWN;
        Bitmap cell = cropSquare(bitmap, cx, cy, half * 4);
        if (cell == null) return UNKNOWN;
        Bitmap small = Bitmap.createScaledBitmap(cell, 16, 16, true);
        String bestName = null;
        float best = Float.MAX_VALUE;
        for (SpecialTemplate t : templates) {
            float diff = meanAbsDiff(small, t.thumb);
            if (diff < best) {
                best = diff;
                bestName = t.name;
            }
        }
        if (best > 30f) return UNKNOWN;   // 都不像 → 未识别，不硬猜
        char letter = nameToLetter(bestName);
        return letter != UNKNOWN ? letter : templateCode(bestName);
    }

    /** 学习到的动物名 → 矩阵字母（基础棋子模板用固定字母，与播报名一致）。 */
    static char nameToLetter(String name) {
        if (name == null) return UNKNOWN;
        if (name.contains("狐狸") || name.contains("红")) return 'R';
        if (name.contains("小鸡") || name.contains("黄")) return 'Y';
        if (name.contains("青蛙") || name.contains("绿")) return 'G';
        if (name.contains("河马") || name.contains("蓝")) return 'B';
        if (name.contains("棕熊") || name.contains("熊")) return 'O';
        if (name.contains("紫猫") || name.contains("紫")) return 'P';
        return UNKNOWN;
    }

    /** 模板名 → 矩阵字母。字母由本轮载入的模板集合按名字排序稳定分配，不撞车。 */
    static char templateCode(String name) {
        Character code = NAME_TO_CODE.get(name);
        return code != null ? code : UNKNOWN;
    }

    /** 矩阵字母 → 玩家学的棋子名（播报层用）。非特殊棋子字母返回 null。 */
    static String nameForCode(char code) {
        return CODE_TO_NAME.get(code);
    }

    /** 按模板集合重建字母分配表。字母池用满后多余的模板返回 UNKNOWN 而非挤占同一字母。 */
    private static void assignCodes(List<SpecialTemplate> templates) {
        CODE_TO_NAME.clear();
        NAME_TO_CODE.clear();
        List<String> names = new ArrayList<>();
        for (SpecialTemplate t : templates) {
            if (nameToLetter(t.name) == UNKNOWN) names.add(t.name);
        }
        Collections.sort(names);
        for (int i = 0; i < names.size() && i < CODE_POOL.length; i++) {
            CODE_TO_NAME.put(CODE_POOL[i], names.get(i));
            NAME_TO_CODE.put(names.get(i), CODE_POOL[i]);
        }
    }

    /** 数字 9 个 + 小写字母 26 个：避开 R/O/Y/G/B/P 六个基础色字母与 '.'。 */
    private static final char[] CODE_POOL = buildCodePool();

    private static char[] buildCodePool() {
        char[] pool = new char[9 + 26];
        int i = 0;
        for (char c = '1'; c <= '9'; c++) pool[i++] = c;
        for (char c = 'a'; c <= 'z'; c++) pool[i++] = c;
        return pool;
    }

    private static final Map<Character, String> CODE_TO_NAME = new LinkedHashMap<>();
    private static final Map<String, Character> NAME_TO_CODE = new LinkedHashMap<>();

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
     * v3（真机视频对拍后重设计，见 research/board-recognition/REAL_VIDEO_FINDINGS.md）：
     * 行阈值 cols/20（真机棋子几乎填满格子，暗底只在格缝露，cols/4 会把行带切碎→检测失败）；
     * 带内列阈值 15% 定左右；方形约束取边长；带内滑窗取暗底密度最高处锚定
     * （侧边栏/底部导航等暗色 UI 污染列剖面时不跑偏）；窗口密度 <5% 判不可信返回 null。
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
        int minRowCount = Math.max(2, cols / 20);
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
        if (bestTop < 0 || bestLen < rows / 8) return null;
        int[] colCount = new int[cols];
        for (int r = bestTop; r <= bestBottom; r++) {
            for (int c = 0; c < cols; c++) if (mask[r][c]) colCount[c]++;
        }
        int bandRows = bestBottom - bestTop + 1;
        int minColCount = Math.max(2, bandRows * 15 / 100);
        int left = -1, right = -1;
        for (int c = 0; c < cols; c++) {
            if (colCount[c] >= minColCount) { if (left < 0) left = c; right = c; }
        }
        if (left < 0 || right - left < cols / 10) return null;
        /* 方形约束 + 密度锚定：棋盘是正方形，在行带×列带范围内滑动 side×side 窗口，
         * 取暗底密度最高的位置作为棋盘左上角（降采样网格两个轴 step 相同，方格即正方形） */
        int side = Math.min(bandRows, right - left + 1);
        if (side * 10 < Math.min(rows, cols) * 3) return null;   // 边长不足短边 30%
        long[][] integral = new long[rows + 1][cols + 1];
        for (int r = 0; r < rows; r++) {
            long rowSum = 0;
            for (int c = 0; c < cols; c++) {
                rowSum += mask[r][c] ? 1 : 0;
                integral[r + 1][c + 1] = integral[r][c + 1] + rowSum;
            }
        }
        long bestSum = -1;
        int anchorTop = bestTop, anchorLeft = left;
        int maxRowStart = Math.min(bestBottom - side + 1, bestTop + 40);
        int maxColStart = Math.min(right - side + 1, left + 40);
        for (int rt = bestTop; rt <= maxRowStart; rt++) {
            for (int cl = left; cl <= maxColStart; cl++) {
                long s = integral[rt + side][cl + side] - integral[rt][cl + side]
                        - integral[rt + side][cl] + integral[rt][cl];
                if (s > bestSum) { bestSum = s; anchorTop = rt; anchorLeft = cl; }
            }
        }
        if (bestSum * 20 < (long) side * side) return null;      // 窗口内暗底 <5%，不可信
        return new int[]{
                Math.min(100, anchorLeft * 100 / cols), Math.min(100, anchorTop * 100 / rows),
                Math.min(100, (anchorLeft + side) * 100 / cols),
                Math.min(100, (anchorTop + side) * 100 / rows)
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

    /**
     * 格数自检：棋盘裁剪区 V 通道列均值剖面的自相关周期 ≈ 格宽
     * （棋子以格宽为周期重复；开心消消乐真机美术没有可见格线，暗线计数不可行，
     * 自相关在真机 7×7 抽帧上逐帧命中 7，合成 7×7 有守卫测试锁住；
     * 但只在按棋盘裁剪后的框内可信——喂进含天空／道具栏的宽框会自信地数成 9，
     * 调用方须先拿到 autoDetectBoard 的框。实测数据见 research/board-recognition/REAL_VIDEO_FINDINGS.md）。
     * 返回 6..9；不可信返回 -1（调用方回退到已存格数）。
     */
    static int detectGridCount(Bitmap frame, int[] boundsPct) {
        int w = frame.getWidth(), h = frame.getHeight();
        int x0 = w * boundsPct[0] / 100, y0 = h * boundsPct[1] / 100;
        int x1 = Math.min(w, w * boundsPct[2] / 100), y1 = Math.min(h, h * boundsPct[3] / 100);
        int cw = x1 - x0, ch = y1 - y0;
        if (cw < 60 || ch < 60) return -1;
        int stride = Math.max(1, cw / 240);
        int nCols = cw / stride;
        if (nCols < 30) return -1;
        double[] prof = new double[nCols];
        int nRows = 0;
        int[] rowBuf = new int[cw];
        float[] hsv = new float[3];
        for (int y = y0; y < y1; y += 2) {
            frame.getPixels(rowBuf, cw, 0, x0, y, cw, 1);
            nRows++;
            for (int c = 0; c < nCols; c++) {
                Color.colorToHSV(rowBuf[Math.min(cw - 1, c * stride)], hsv);
                prof[c] += hsv[2];
            }
        }
        if (nRows == 0) return -1;
        double mean = 0;
        for (int c = 0; c < nCols; c++) prof[c] /= nRows;
        for (double v : prof) mean += v;
        mean /= nCols;
        double var = 0;
        for (int c = 0; c < nCols; c++) {
            prof[c] -= mean;
            var += prof[c] * prof[c];
        }
        var /= nCols;
        if (var < 1e-6) return -1;
        int dMin = Math.max(2, nCols / 10), dMax = Math.min(nCols / 5, nCols - 1);
        int bestD = -1;
        double bestV = -2;
        for (int d = dMin; d <= dMax; d++) {
            double s = 0;
            for (int c = 0; c + d < nCols; c++) s += prof[c] * prof[c + d];
            s /= (nCols - d) * var;
            if (s > bestV) { bestV = s; bestD = d; }
        }
        if (bestD < 0) return -1;
        int n = (int) Math.round((double) nCols / bestD);
        return (n >= 6 && n <= 9) ? n : -1;
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
        assignCodes(out);
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
