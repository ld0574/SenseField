package com.openkhub.sensefield;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 从 EmptyCalib 产出的 CSV 里按 d 区间挑格，把真机帧上的那一格裁成小图并拼成带序号的联络表，
 * 供人工目视标注（「这格到底是没棋子，还是有棋子被判成空」）。几何完全复用仓库代码，不另算一套。
 *
 * <p>挑样按 d 升序等间隔抽，避免全部落在第一个视频／最窄的 d 上。
 *
 * 用法：java ... EmptyCrops <帧根目录> <cells.csv> <outDir> <dMin> <dMax> <maxCount>
 */
public class EmptyCrops {

    private static final int ZOOM = 3;
    private static final int COLS = 6;
    private static final int ROWS_PER_SHEET = 8;

    public static void main(String[] args) throws Exception {
        File framesRoot = new File(args[0]);
        File outDir = new File(args[2]);
        outDir.mkdirs();
        int dMin = Integer.parseInt(args[3]), dMax = Integer.parseInt(args[4]);
        int maxCount = Integer.parseInt(args[5]);

        List<String[]> candidates = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(args[1]))) {
            if (line.startsWith("frame,") || line.isEmpty()) continue;
            String[] p = line.split(",");
            int d = Integer.parseInt(p[3]);
            if (d >= dMin && d <= dMax) candidates.add(p);
        }
        candidates.sort(Comparator.comparingInt((String[] p) -> Integer.parseInt(p[3]))
                .thenComparing(p -> p[0])
                .thenComparingInt(p -> Integer.parseInt(p[1]) * 100 + Integer.parseInt(p[2])));

        List<String[]> picked = new ArrayList<>();
        int stride = Math.max(1, candidates.size() / maxCount);
        for (int i = 0; i < candidates.size() && picked.size() < maxCount; i += stride) {
            picked.add(candidates.get(i));
        }

        PrintWriter manifest = new PrintWriter(new File(outDir, "manifest.txt"), "UTF-8");
        List<BufferedImage> tiles = new ArrayList<>();
        for (int i = 0; i < picked.size(); i++) {
            String[] p = picked.get(i);
            Bitmap frame = BitmapFactory.decodeFile(new File(args[0], p[0]).getAbsolutePath());
            if (frame == null) continue;
            int[] box = Match3Sampler.autoDetectBoard(frame);
            int gridN = box == null ? -1 : Match3Sampler.detectGridCount(frame, box);
            if (box == null || gridN < 0) continue;
            int w = frame.getWidth(), h = frame.getHeight();
            int l = w * box[0] / 100, t = h * box[1] / 100;
            int r = w * box[2] / 100, b = h * box[3] / 100;
            int cellW = (r - l) / gridN, cellH = (b - t) / gridN;
            int row = Integer.parseInt(p[1]), col = Integer.parseInt(p[2]);
            Bitmap crop = Bitmap.createBitmap(frame, l + cellW * col, t + cellH * row, cellW, cellH);
            BufferedImage tile = label(upscale(crop.img, cellW * ZOOM, cellH * ZOOM),
                    String.valueOf(i));
            tiles.add(tile);
            manifest.println(i + "\t" + p[0] + " r" + row + " c" + col + " d=" + p[3]
                    + " grid=" + gridN + " box=" + box[0] + "/" + box[1] + "/" + box[2] + "/" + box[3]);
        }
        manifest.close();
        int sheets = sheet(tiles, outDir);
        System.out.println("候选 " + candidates.size() + "，采样 " + tiles.size()
                + "，联络表 " + sheets + " 张 → " + outDir);
    }

    private static BufferedImage upscale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** 左上角画序号白字黑描边，序号即 manifest 行首，目视结论能逐格回填。 */
    private static BufferedImage label(BufferedImage img, String text) {
        Graphics2D g = img.createGraphics();
        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.setColor(Color.BLACK);
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                g.drawString(text, 6 + dx, 24 + dy);
        g.setColor(Color.WHITE);
        g.drawString(text, 6, 24);
        g.dispose();
        return img;
    }

    private static int sheet(List<BufferedImage> tiles, File outDir) throws Exception {
        int tw = tiles.isEmpty() ? 0 : tiles.get(0).getWidth();
        int th = tiles.isEmpty() ? 0 : tiles.get(0).getHeight();
        int count = 0;
        for (int s = 0; s * COLS * ROWS_PER_SHEET < tiles.size(); s++) {
            int from = s * COLS * ROWS_PER_SHEET;
            int to = Math.min(tiles.size(), from + COLS * ROWS_PER_SHEET);
            int n = to - from;
            int rows = (n + COLS - 1) / COLS;
            BufferedImage sheet = new BufferedImage(COLS * tw, rows * th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = sheet.createGraphics();
            g.setColor(Color.DARK_GRAY);
            for (int i = 0; i < n; i++) {
                g.drawImage(tiles.get(from + i), (i % COLS) * tw, (i / COLS) * th, null);
                g.drawRect((i % COLS) * tw, (i / COLS) * th, tw, th);
            }
            g.dispose();
            javax.imageio.ImageIO.write(sheet, "png", new File(outDir, "sheet" + count++ + ".png"));
        }
        return count;
    }
}
