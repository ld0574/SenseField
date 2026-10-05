package com.openkhub.sensefield;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.util.Arrays;

/**
 * 空位阈值标定：把一批真机帧逐格跑仓库里的 centerCornerDistance，输出 CSV
 * （frame,row,col,d,cellW,cellH,当前阈值下是否判空）。
 * 阈值 12 目前只有合成集标定，这个工具用来在真机美术上取分布，再由人工目视标注对拍。
 *
 * 用法：java ... EmptyCalib <帧目录1> [帧目录2 ...] > cells.csv
 */
public class EmptyCalib {

    public static void main(String[] args) throws Exception {
        System.out.println("frame,row,col,d,cellW,cellH,looks_empty");
        for (String dirName : args) {
            File dir = new File(dirName);
            File[] files = dir.listFiles((d, n) -> n.endsWith(".png"));
            if (files == null) { System.err.println("读不到目录: " + dirName); continue; }
            Arrays.sort(files);
            for (File f : files) {
                Bitmap frame = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (frame == null) continue;
                int[] box = Match3Sampler.autoDetectBoard(frame);
                if (box == null) continue;
                int n;
                try {
                    n = Match3Sampler.detectGridCount(frame, box);
                } catch (RuntimeException e) {
                    System.err.println(f.getName() + " detectGridCount 抛异常: " + e);
                    continue;
                }
                if (n < 0) continue;

                int w = frame.getWidth(), h = frame.getHeight();
                int l = w * box[0] / 100, t = h * box[1] / 100;
                int r = w * box[2] / 100, b = h * box[3] / 100;
                int cellW = (r - l) / n, cellH = (b - t) / n;
                if (cellW < 6 || cellH < 6) continue;
                String tag = dir.getName() + "/" + f.getName();
                for (int row = 0; row < n; row++) {
                    for (int col = 0; col < n; col++) {
                        int cx = l + cellW * col + cellW / 2;
                        int cy = t + cellH * row + cellH / 2;
                        int d = Match3Sampler.centerCornerDistance(frame, cx, cy, cellW, cellH);
                        System.out.println(tag + "," + row + "," + col + "," + d + ","
                                + cellW + "," + cellH + ","
                                + Match3Sampler.looksEmpty(frame, cx, cy, cellW, cellH));
                    }
                }
            }
        }
    }
}
