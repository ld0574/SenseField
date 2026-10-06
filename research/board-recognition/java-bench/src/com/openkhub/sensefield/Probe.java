package com.openkhub.sensefield;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 两项回归探针：空格底色误报是否被局部对比挡住、特殊棋子字母是否还撞车。 */
public final class Probe {

    /** 复用 Bench 跑完后落盘的模板（bench-files/special_templates/）。 */
    static final List<Match3Sampler.SpecialTemplate> TPL =
            Match3Sampler.loadTemplates(new Context(new File("bench-files")));

    static Bitmap flat(int rgb, int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) img.setRGB(x, y, rgb);
        return new Bitmap(img);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 探针 1：空格（棋盘底色）误报是否被空格判定挡住 ===");
        System.out.println("载入模板数: " + TPL.size() + "（0 表示「有模板」列无效，请先跑 Bench）");
        System.out.printf("%-22s %-6s | %-14s %-14s %-10s%n",
                "底色 RGB", "V", "无模板旧行为", "有模板旧行为", "空格判定");
        int[][] bgs = {
            {16, 22, 46}, {14, 19, 40}, {12, 16, 34}, {30, 42, 88}, {46, 60, 110},
            {60, 74, 132}, {20, 20, 20}, {0, 0, 0},
        };
        int stillLeak = 0;
        for (int[] c : bgs) {
            int rgb = Color.rgb(c[0], c[1], c[2]);
            float[] hsv = new float[3];
            Color.colorToHSV(rgb, hsv);
            Bitmap b = flat(rgb, 200);
            char noTpl = Match3Sampler.classifyCell(b, 100, 100, 12, List.of());
            char withTpl = Match3Sampler.classifyCell(b, 100, 100, 12, TPL);
            boolean empty = Match3Sampler.looksEmpty(b, 100, 100, 86, 96);
            if (!empty && (noTpl != '.' || withTpl != '.')) stillLeak++;
            System.out.printf("%-22s %-6.2f | %-14s %-14s %-10s%n",
                    "rgb(" + c[0] + "," + c[1] + "," + c[2] + ")", hsv[2],
                    noTpl == '.' ? "未识别" : "判成 '" + noTpl + "'",
                    withTpl == '.' ? "未识别" : "判成 '" + withTpl + "'",
                    empty ? "判为空 拦住" : "判为有子 ★");
        }
        System.out.println("    仍会漏的底色档: " + stillLeak + " / " + bgs.length
                + "（旧行为下这些格会被报成棋子；空格判定生效时全部应为「判为空 拦住」）");

        System.out.println();
        System.out.println("=== 探针 2：特殊棋子字母分配是否无撞车 ===");
        Map<Character, List<String>> buckets = new LinkedHashMap<>();
        for (Match3Sampler.SpecialTemplate t : TPL) {
            if (Match3Sampler.nameToLetter(t.name) != Match3Sampler.UNKNOWN) continue;
            buckets.computeIfAbsent(Match3Sampler.templateCode(t.name), k -> new ArrayList<>()).add(t.name);
        }
        buckets.forEach((code, names) -> System.out.println("    '" + code + "' <- " + names
                + (names.size() > 1 ? "   ★ 撞车" : "")));
        System.out.println("    本轮 " + buckets.size() + " 个特殊棋子 → " + buckets.size() + " 个字母");

        System.out.println();
        System.out.println("=== 探针 3：字母池容量（20 种特殊棋子名同时学）===");
        File dir = new File("probe-files");
        File tplDir = new File(dir, "special_templates");
        if (tplDir.exists()) {
            File[] old = tplDir.listFiles();
            if (old != null) for (File f : old) f.delete();
        }
        Context ctx = new Context(dir);
        String[] many = {
            "彩虹球", "魔法棒", "木箱", "雪块", "冰块", "藤蔓", "气球", "蜂巢",
            "牛奶瓶", "鼓", "窗帘", "糖果盒", "岩石", "传送门", "魔法帽", "冰柱",
            "巧克力", "灯笼", "贝壳", "水母",
        };
        Bitmap dummy = flat(Color.rgb(120, 90, 60), 64);
        for (String n : many) Match3Sampler.saveTemplate(ctx, n, dummy);
        List<Match3Sampler.SpecialTemplate> loaded = Match3Sampler.loadTemplates(ctx);
        Map<Character, List<String>> wide = new LinkedHashMap<>();
        List<String> unassigned = new ArrayList<>();
        for (Match3Sampler.SpecialTemplate t : loaded) {
            char code = Match3Sampler.templateCode(t.name);
            if (code == Match3Sampler.UNKNOWN) { unassigned.add(t.name); continue; }
            wide.computeIfAbsent(code, k -> new ArrayList<>()).add(t.name);
        }
        int collided = 0;
        for (Map.Entry<Character, List<String>> e : wide.entrySet()) {
            if (e.getValue().size() > 1) collided += e.getValue().size();
        }
        System.out.println("    " + loaded.size() + " 种特殊棋子 → " + wide.size()
                + " 个不同字母，撞车 " + collided + " 个，未分配 " + unassigned.size() + " 个");
        System.out.println("    名字还原抽查: " + loaded.get(0).name + " -> '"
                + Match3Sampler.templateCode(loaded.get(0).name) + "' -> 「"
                + Match3Coach.pieceName(Match3Sampler.templateCode(loaded.get(0).name)) + "」");
    }
}
