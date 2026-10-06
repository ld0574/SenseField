package com.openkhub.sensefield;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.util.Arrays;

/**
 * 复现 Match3LiveService 的「采纳前试采」闸门：对每帧跑 autoDetectBoard → detectGridCount →
 * sample → 数 unreadable 占比，看这道 40% 闸门在真机帧上到底拦住了什么。
 * 只关心占比，不关心逐格字母，所以不学任何特殊棋子（templates 为空 = 新装首启）。
 *
 * 用法：java ... GuardProbe <无模板的 filesDir> <帧目录1> [帧目录2 ...] > guard.csv
 */
public class GuardProbe {

    public static void main(String[] args) throws Exception {
        Context ctx = new Context(new File(args[0]));
        System.out.println("frame,gridN,box,empty,unknown,cells,unreadable_pct,adopted_at_40");
        for (int i = 1; i < args.length; i++) {
            File dir = new File(args[i]);
            File[] files = dir.listFiles((d, n) -> n.endsWith(".png"));
            if (files == null) continue;
            Arrays.sort(files);
            for (File f : files) {
                Bitmap frame = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (frame == null) continue;
                int[] box = Match3Sampler.autoDetectBoard(frame);
                if (box == null) {
                    System.out.println(dir.getName() + "/" + f.getName() + ",-1,none,-,-,-,-,no");
                    continue;
                }
                int n;
                try {
                    n = Match3Sampler.detectGridCount(frame, box);
                } catch (RuntimeException e) {
                    System.out.println(dir.getName() + "/" + f.getName()
                            + ",throw," + box[0] + "/" + box[1] + "/" + box[2] + "/" + box[3]
                            + ",-,-,-,-,no");
                    continue;
                }
                if (n < 0) {
                    System.out.println(dir.getName() + "/" + f.getName() + ",-1,"
                            + box[0] + "/" + box[1] + "/" + box[2] + "/" + box[3] + ",-,-,-,-,no");
                    continue;
                }
                char[][] m = new Match3Sampler(ctx, n, n, box[0], box[1], box[2], box[3])
                        .sample(frame);
                int empty = 0, unknown = 0;
                for (char[] row : m) {
                    for (char c : row) {
                        if (c == Match3Sampler.EMPTY_CELL) empty++;
                        else if (c == Match3Sampler.UNKNOWN) unknown++;
                    }
                }
                int cells = m.length * m[0].length;
                int unreadable = empty + unknown;
                int pct = unreadable * 100 / cells;
                // 与 Match3LiveService 同一个表达式，不另算一套（整数除法在边界上会差一格）
                boolean rejected = unreadable * 100 > cells * 40;
                System.out.println(dir.getName() + "/" + f.getName() + "," + n + ","
                        + box[0] + "/" + box[1] + "/" + box[2] + "/" + box[3] + ","
                        + empty + "," + unknown + "," + cells + "," + pct + ","
                        + (rejected ? "no" : "YES"));
            }
        }
    }
}
