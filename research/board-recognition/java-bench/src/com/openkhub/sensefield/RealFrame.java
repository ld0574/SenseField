package com.openkhub.sensefield;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

/**
 * 真机截图离线复现：把一帧真实画面喂给仓库里的 Match3Sampler，看它读出什么矩阵、会播报什么。
 * 用法：java RealFrame <png> [rows cols l t r b] [7x7真值49字符]
 * 不带标定参数时依次试：默认手工标定 → autoDetectBoard → 真实几何。
 */
public final class RealFrame {

    static void dump(String label, char[][] b, String gt, int rows, int cols) {
        System.out.println("--- " + label + " ---");
        for (int r = 0; r < rows; r++) {
            StringBuilder sb = new StringBuilder("    ");
            for (int c = 0; c < cols; c++) sb.append(b[r][c]).append(' ');
            if (gt != null) {
                int diff = 0;
                for (int c = 0; c < cols; c++) if (b[r][c] != gt.charAt(r * cols + c)) diff++;
                sb.append("  | 真值行 ").append(r + 1).append(" 错").append(diff);
            }
            System.out.println(sb);
        }
        if (gt != null) {
            int ok = 0, abstain = 0;
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < cols; c++) {
                    char a = b[r][c], e = gt.charAt(r * cols + c);
                    if (a == e) ok++;
                    else if (a == Match3Sampler.UNKNOWN) abstain++;
                }
            System.out.println("    命中 " + ok + "/" + (rows * cols)
                    + "  弃权 " + abstain + "  错判成别的颜色 " + (rows * cols - ok - abstain));
        }
        for (String s : Match3Board.scanSpeech(b)) System.out.println("    会播报: " + s);
    }

    /** 逐格诊断：格心平均色 + HSV + 空格判定色差 + 分类结果。 */
    static void diagnose(Bitmap f, int rows, int cols, int l, int t, int r, int b, String gt) {
        int cellW = (r - l) / cols, cellH = (b - t) / rows;
        System.out.println("--- 逐格诊断（格心平均色 / HSV / 心-角色差 / 结果）---");
        for (int row = 0; row < rows; row++) {
            StringBuilder sb = new StringBuilder("    r" + (row + 1) + ": ");
            for (int col = 0; col < cols; col++) {
                int cx = l + cellW * col + cellW / 2;
                int cy = t + cellH * row + cellH / 2;
                int half = Math.max(3, Math.min(cellW, cellH) / 8);
                long sr = 0, sg = 0, sbl = 0, n = 0;
                for (int y = cy - half; y <= cy + half; y++)
                    for (int x = cx - half; x <= cx + half; x++) {
                        int px = f.getPixel(x, y);
                        sr += Color.red(px); sg += Color.green(px); sbl += Color.blue(px); n++;
                    }
                int rgb = Color.rgb((int) (sr / n), (int) (sg / n), (int) (sbl / n));
                float[] hsv = new float[3];
                Color.colorToHSV(rgb, hsv);
                char got = Match3Sampler.classifyCell(f, cx, cy, half, null);
                int dist = Match3Sampler.centerCornerDistance(f, cx, cy, cellW, cellH);
                boolean empty = Match3Sampler.looksEmpty(f, cx, cy, cellW, cellH);
                char exp = gt != null ? gt.charAt(row * cols + col) : '?';
                sb.append(String.format("%c/%c h%3.0f s%.2f v%.2f d%-3d %c%s | ",
                        exp, got, hsv[0], hsv[1], hsv[2], dist, got, empty ? "(判空)" : ""));
            }
            System.out.println(sb);
        }
    }

    public static void main(String[] args) {
        Bitmap f = BitmapFactory.decodeFile(args[0]);
        if (f == null) { System.out.println("读不到图: " + args[0]); return; }
        System.out.println("画面 " + f.getWidth() + "x" + f.getHeight());
        String gt = null;
        for (int i = 1; i < args.length; i++)
            if (args[i].length() == 49 || args[i].length() == 64) gt = args[i].toUpperCase();

        if (args.length >= 7) {
            int rows = Integer.parseInt(args[1]), cols = Integer.parseInt(args[2]);
            int l = Integer.parseInt(args[3]), t = Integer.parseInt(args[4]);
            int r = Integer.parseInt(args[5]), b = Integer.parseInt(args[6]);
            char[][] board = Match3Sampler.sample(f, rows, cols, l, t, r, b, null);
            dump(rows + "x" + cols + " 标定 " + l + "/" + t + "/" + r + "/" + b, board, gt, rows, cols);
            if (args.length >= 8) diagnose(f, rows, cols,
                    f.getWidth() * l / 100, f.getHeight() * t / 100,
                    f.getWidth() * r / 100, f.getHeight() * b / 100, gt);
            return;
        }

        int[][] cfg = {{8, 8, 4, 18, 96, 82}};
        for (int[] c : cfg) {
            char[][] board = Match3Sampler.sample(f, c[0], c[1], c[2], c[3], c[4], c[5], null);
            dump("默认手工标定 " + c[0] + "x" + c[1] + " " + c[2] + "/" + c[3] + "/" + c[4] + "/" + c[5],
                    board, null, c[0], c[1]);
            try {
                System.out.println("detectGridCount(默认框) = "
                        + Match3Sampler.detectGridCount(f, new int[]{c[2], c[3], c[4], c[5]}));
            } catch (RuntimeException e) {
                System.out.println("detectGridCount(默认框) 抛异常: " + e);
            }
        }
        int[] auto = Match3Sampler.autoDetectBoard(f);
        if (auto == null) {
            System.out.println("autoDetectBoard: 检测不到（返回 null）");
        } else {
            System.out.println("autoDetectBoard: " + auto[0] + "/" + auto[1] + "/" + auto[2] + "/" + auto[3]);
            int n;
            try {
                n = Match3Sampler.detectGridCount(f, auto);
            } catch (RuntimeException e) {
                System.out.println("detectGridCount 抛异常: " + e);
                n = -1;
            }
            System.out.println("detectGridCount(auto 框) = " + n);
            for (int rc : new int[]{n > 0 ? n : 8, 7}) {
                char[][] board = Match3Sampler.sample(f, rc, rc, auto[0], auto[1], auto[2], auto[3], null);
                dump("auto 框 + " + rc + "x" + rc, board, rc == 7 ? gt : null, rc, rc);
            }
            if (gt != null) diagnose(f, 7, 7,
                    f.getWidth() * auto[0] / 100, f.getHeight() * auto[1] / 100,
                    f.getWidth() * auto[2] / 100, f.getHeight() * auto[3] / 100, gt);
        }
    }
}
