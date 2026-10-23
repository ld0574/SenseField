package com.openkhub.sensefield;

import android.content.Context;
import android.graphics.Bitmap;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** 离线精度基准：被测对象是 Match3Sampler.java 原文件（未改动，sha256 与仓库一致）。 */
public final class Bench {
    static final int W = 1080, H = 2400;
    static final int ROWS = 8, COLS = 8;
    static final int LP = 18, TP = 30, RP = 82, BP = 62;

    static final class Kind {
        final String name, truth;
        Kind(String name, String truth) { this.name = name; this.truth = truth; }
    }

    static final Kind[] KINDS = {
        new Kind("狐狸红", "R"), new Kind("棕熊棕", "O"), new Kind("小鸡黄", "Y"),
        new Kind("青蛙绿", "G"), new Kind("河马蓝", "B"), new Kind("紫猫紫", "P"),
        new Kind("彩虹球", "C"), new Kind("魔法棒", "S"), new Kind("木箱", "X"),
    };

    static int boardL() { return W * LP / 100; }
    static int boardT() { return H * TP / 100; }
    static int cellW() { return (W * RP / 100 - boardL()) / COLS; }
    static int cellH() { return (H * BP / 100 - boardT()) / ROWS; }

    static BufferedImage render(Kind[][] truth, double bright, int blur, double alpha, long seed) {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(scaled(new Color(28, 20, 44), bright));
        g.fillRect(0, 0, W, H);

        int l = boardL(), t = boardT(), cw = cellW(), ch = cellH();
        g.setColor(scaled(new Color(22, 30, 58), bright));
        g.fillRoundRect(l - 8, t - 8, cw * COLS + 16, ch * ROWS + 16, 24, 24);

        Random rng = new Random(seed);
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int cx = l + cw * c + cw / 2, cy = t + ch * r + ch / 2;
                g.setColor(scaled(new Color(16, 22, 46), bright));
                g.fillOval(cx - cw / 2 + 3, cy - ch / 2 + 3, cw - 6, ch - 6);
                Kind k = truth[r][c];
                if (k == null) continue;
                int ox = blur > 0 ? rng.nextInt(blur * 2 + 1) - blur : 0;
                Graphics2D pg = (Graphics2D) g.create();
                pg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) alpha));
                drawPiece(pg, cx + ox, cy, (int) (Math.min(cw, ch) * 0.42), k.name, bright);
                pg.dispose();
            }
        }
        g.dispose();
        return img;
    }

    static void drawPiece(Graphics2D g, int cx, int cy, int rad, String name, double bright) {
        if ("彩虹球".equals(name)) {
            int[] hues = {0, 60, 120, 180, 240, 300};
            for (int i = 0; i < 6; i++) {
                g.setColor(scaled(hsb(hues[i], 0.85f, 0.9f), bright));
                g.fillArc(cx - rad, cy - rad, rad * 2, rad * 2, i * 60, 61);
            }
            g.setColor(scaled(new Color(240, 240, 245), bright));
            g.fillOval(cx - rad / 3, cy - rad / 3, rad * 2 / 3, rad * 2 / 3);
            return;
        }
        if ("魔法棒".equals(name)) {
            g.setStroke(new BasicStroke(rad * 0.34f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(scaled(new Color(150, 150, 160), bright));
            g.drawLine(cx - rad, cy + rad, cx + rad / 2, cy - rad / 2);
            g.setColor(scaled(new Color(250, 245, 200), bright));
            g.fillOval(cx + rad / 2 - rad / 3, cy - rad / 2 - rad / 3, rad * 2 / 3, rad * 2 / 3);
            return;
        }
        if ("木箱".equals(name)) {
            g.setColor(scaled(new Color(122, 78, 38), bright));
            g.fillRect(cx - rad, cy - rad, rad * 2, rad * 2);
            g.setColor(scaled(new Color(84, 52, 24), bright));
            g.fillRect(cx - rad, cy - rad / 4, rad * 2, rad / 2);
            g.setStroke(new BasicStroke(4f));
            g.drawRoundRect(cx - rad, cy - rad, rad * 2, rad * 2, 8, 8);
            return;
        }
        Color body;
        switch (name) {
            case "狐狸红": body = hsb(4, 0.86f, 0.86f); break;
            case "棕熊棕": body = hsb(26, 0.55f, 0.55f); break;
            case "小鸡黄": body = hsb(52, 0.85f, 0.95f); break;
            case "青蛙绿": body = hsb(108, 0.78f, 0.72f); break;
            case "河马蓝": body = hsb(206, 0.72f, 0.88f); break;
            default:      body = hsb(286, 0.66f, 0.80f); break;
        }
        body = scaled(body, bright);
        Color dark = scaled(body.darker().darker(), bright);
        int d = rad * 2;
        g.setColor(body);
        g.fillOval(cx - rad, cy - rad, d, d);
        g.setColor(dark);
        g.setStroke(new BasicStroke(Math.max(2f, rad * 0.12f)));
        g.drawOval(cx - rad, cy - rad, d, d);
        g.setColor(Color.WHITE);
        int er = Math.max(3, rad / 4);
        g.fillOval(cx - rad / 2 - er, cy - rad / 3 - er, er * 2, er * 2);
        g.fillOval(cx + rad / 2 - er, cy - rad / 3 - er, er * 2, er * 2);
        g.setColor(new Color(20, 20, 20));
        int pr = Math.max(1, er / 2);
        g.fillOval(cx - rad / 2 - pr, cy - rad / 3 - pr, pr * 2, pr * 2);
        g.fillOval(cx + rad / 2 - pr, cy - rad / 3 - pr, pr * 2, pr * 2);
    }

    static Color hsb(int hue, float s, float v) { return Color.getHSBColor(hue / 360f, s, v); }

    static Color scaled(Color c, double k) {
        return new Color(Math.min(255, (int) Math.round(c.getRed() * k)),
                Math.min(255, (int) Math.round(c.getGreen() * k)),
                Math.min(255, (int) Math.round(c.getBlue() * k)));
    }

    static Kind[][] randomBoard(Random rng, double emptyRate) {
        Kind[][] b = new Kind[ROWS][COLS];
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                b[r][c] = rng.nextDouble() < emptyRate ? null : KINDS[rng.nextInt(KINDS.length)];
            }
        }
        return b;
    }

    /** 特殊棋子的期望字母：基准侧独立按同一规则预计算，不读被测代码的运行时注册表——
     *  否则「无模板」场景下期望会跟着变成 '.'，把指标写成自证。 */
    static final Map<String, Character> EXPECTED_SPECIAL = buildExpectedSpecial();

    static Map<String, Character> buildExpectedSpecial() {
        List<String> specials = new ArrayList<>();
        for (Kind k : KINDS) {
            if (Match3Sampler.nameToLetter(k.name) == Match3Sampler.UNKNOWN) specials.add(k.name);
        }
        Collections.sort(specials);
        char[] pool = "123456789abcdefghijklmnopqrstuvwxyz".toCharArray();
        Map<String, Character> m = new LinkedHashMap<>();
        for (int i = 0; i < specials.size() && i < pool.length; i++) m.put(specials.get(i), pool[i]);
        return m;
    }

    static char expectedChar(Kind k) {
        if (k == null) return Match3Sampler.UNKNOWN;
        char letter = Match3Sampler.nameToLetter(k.name);
        return letter != Match3Sampler.UNKNOWN ? letter : EXPECTED_SPECIAL.get(k.name);
    }

    /** 模拟玩家在 UI 里逐格点「学习」：每类各存一张模板。 */
    static int learnTemplates(Context ctx, BufferedImage frame, Kind[][] truth) throws Exception {
        int l = boardL(), t = boardT(), cw = cellW(), ch = cellH();
        boolean[] done = new boolean[KINDS.length];
        int learned = 0;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                Kind k = truth[r][c];
                if (k == null) continue;
                int idx = idxOf(k);
                if (done[idx]) continue;
                int cx = l + cw * c + cw / 2, cy = t + ch * r + ch / 2;
                int side = Math.min(cw, ch) / 2;
                Bitmap cell = new Bitmap(frame.getSubimage(cx - side, cy - side, side * 2, side * 2));
                Match3Sampler.saveTemplate(ctx, k.name, cell);
                done[idx] = true;
                learned++;
            }
        }
        return learned;
    }

    static int idxOf(Kind k) {
        for (int i = 0; i < KINDS.length; i++) if (KINDS[i] == k) return i;
        return -1;
    }

    static final class Score {
        int cells, correct, correctOccupied, wrong, unknown, emptyCells, emptyLeak, confidentOccupied;
        final Map<String, Integer> confusion = new LinkedHashMap<>();

        void add(Score s) {
            cells += s.cells; correct += s.correct; wrong += s.wrong; unknown += s.unknown;
            emptyCells += s.emptyCells; emptyLeak += s.emptyLeak;
            correctOccupied += s.correctOccupied; confidentOccupied += s.confidentOccupied;
            s.confusion.forEach((k, v) -> confusion.merge(k, v, Integer::sum));
        }
    }

    static Score run(BufferedImage frame, Kind[][] truth, List<Match3Sampler.SpecialTemplate> tpl) {
        Score s = new Score();
        char[][] got = Match3Sampler.sample(new Bitmap(frame), ROWS, COLS, LP, TP, RP, BP, tpl);
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                s.cells++;
                char exp = expectedChar(truth[r][c]);
                char act = got[r][c];
                if (truth[r][c] == null) {
                    s.emptyCells++;
                    if (act != exp) s.emptyLeak++;
                } else {
                    if (act != Match3Sampler.UNKNOWN) s.confidentOccupied++;
                    if (act == exp) s.correctOccupied++;
                }
                if (act == exp) s.correct++;
                else if (act == Match3Sampler.UNKNOWN) s.unknown++;
                else {
                    s.wrong++;
                    s.confusion.merge(exp + "->" + act, 1, Integer::sum);
                }
            }
        }
        return s;
    }

    static String pct(int a, int b) {
        return b == 0 ? "n/a" : String.format("%.1f%%", 100.0 * a / b);
    }

    static void report(String label, Score s) {
        int occupied = s.cells - s.emptyCells;
        System.out.printf("%-30s 格%5d 对%5d 错%4d 未识别%4d | 全格%7s 有子格%7s 报点命中率%7s 空格误报%d/%d%n",
                label, s.cells, s.correct, s.wrong, s.unknown,
                pct(s.correct, s.cells), pct(s.correctOccupied, occupied),
                pct(s.correctOccupied, s.confidentOccupied), s.emptyLeak, s.emptyCells);
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : "bench-files");
        File tplDir = new File(dir, "special_templates");
        if (tplDir.exists()) {
            File[] old = tplDir.listFiles();
            if (old != null) for (File f : old) f.delete();
        }
        Context ctx = new Context(dir);

        System.out.println("=== 消消乐棋子识别基准 · 被测：Match3Sampler.java 原文件 ===");
        System.out.println("图像 " + W + "x" + H + "  棋盘 " + ROWS + "x" + COLS
                + "  标定 l/t/r/b=" + LP + "/" + TP + "/" + RP + "/" + BP
                + "  单格 " + cellW() + "x" + cellH() + "px  类别 " + KINDS.length + " 类 +空格");

        Random rng = new Random(20261004L);
        int N = 30;
        Kind[][][] truths = new Kind[N][][];
        for (int i = 0; i < N; i++) truths[i] = randomBoard(rng, 0.10);

        Score a = new Score();
        for (int i = 0; i < N; i++) a.add(run(render(truths[i], 1.0, 0, 1.0, i), truths[i], List.of()));
        report("A 冷启动·无模板(仅HSV桶)", a);

        int learned = learnTemplates(ctx, render(truths[0], 1.0, 0, 1.0, 0), truths[0]);
        List<Match3Sampler.SpecialTemplate> tpl = Match3Sampler.loadTemplates(ctx);
        System.out.println("    （玩家逐格学习：尝试 " + KINDS.length + " 类，首帧实际学到 " + learned
                + " 类，模板落盘再读回 " + tpl.size() + " 张）");

        Score b = new Score();
        for (int i = 0; i < N; i++) b.add(run(render(truths[i], 1.0, 0, 1.0, i), truths[i], tpl));
        report("B 每类学1张·同源帧", b);

        Score c = new Score();
        double[] lights = {0.88, 0.94, 1.06, 1.12};
        for (int i = 0; i < N; i++) {
            c.add(run(render(truths[i], lights[i % lights.length], 0, 1.0, i + 100), truths[i], tpl));
        }
        report("C 模板跨亮度泛化(±12%)", c);

        Score d = new Score();
        for (int i = 0; i < N; i++) d.add(run(render(truths[i], 1.0, 6, 0.55, i + 200), truths[i], tpl));
        report("D 动画中间态(模糊+半透明)", d);

        System.out.println();
        System.out.println("--- 混淆明细 A（冷启动，自信但判错的前 8 项）---");
        printConfusions(a.confusion, 8);
        System.out.println("--- 混淆明细 C（跨亮度，前 8 项）---");
        printConfusions(c.confusion, 8);

        printEmptyMargin(truths[0]);
        verifyNames(tpl);

        System.out.println();
        System.out.println("--- templateCode 哈希撞车检查（特殊棋子名 → 矩阵字母）---");
        Map<String, List<String>> codeMap = new LinkedHashMap<>();
        for (Kind k : KINDS) {
            if (Match3Sampler.nameToLetter(k.name) != Match3Sampler.UNKNOWN) continue;
            codeMap.computeIfAbsent(String.valueOf(Match3Sampler.templateCode(k.name)), x -> new ArrayList<>())
                    .add(k.name);
        }
        codeMap.forEach((code, names) -> System.out.println("    字母 '" + code + "' <- " + names
                + (names.size() > 1 ? "   ★ 撞车：两种棋子播报时会混" : "")));
    }

    /** 空格判定的实测余量：格心与格四角的色差，空格应远小于阈值、有子应远大于。 */
    static void printEmptyMargin(Kind[][] truth) {
        BufferedImage f = render(truth, 1.0, 0, 1.0, 7L);
        int l = boardL(), t = boardT(), cw = cellW(), ch = cellH();
        Bitmap b = new Bitmap(f);
        int emptyMax = -1, occupiedMin = Integer.MAX_VALUE;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                int d = Match3Sampler.centerCornerDistance(b,
                        l + cw * c + cw / 2, t + ch * r + ch / 2, cw, ch);
                if (truth[r][c] == null) emptyMax = Math.max(emptyMax, d);
                else occupiedMin = Math.min(occupiedMin, d);
            }
        }
        System.out.println();
        System.out.println("--- 空格判定余量（阈值 EMPTY_COLOR_DISTANCE = "
                + Match3Sampler.EMPTY_COLOR_DISTANCE + "）---");
        System.out.println("    空格实测最大色差: " + emptyMax + "   有子实测最小色差: " + occupiedMin);
        System.out.println("    判定为空格的数量: " + countEmpty(b, truth));
    }

    static int countEmpty(Bitmap b, Kind[][] truth) {
        int l = boardL(), t = boardT(), cw = cellW(), ch = cellH(), n = 0;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (Match3Sampler.looksEmpty(b, l + cw * c + cw / 2, t + ch * r + ch / 2, cw, ch)) n++;
            }
        }
        return n;
    }

    /** 播报层名字还原：学过的特殊棋子必须播成真名，不再一律「未识别」。 */
    static void verifyNames(List<Match3Sampler.SpecialTemplate> tpl) {
        System.out.println();
        System.out.println("--- 播报层名字还原（缺口 1 的验收）---");
        int ok = 0, bad = 0;
        for (Match3Sampler.SpecialTemplate t : tpl) {
            char letter = Match3Sampler.nameToLetter(t.name);
            char code = letter != Match3Sampler.UNKNOWN ? letter : Match3Sampler.templateCode(t.name);
            String spoken = Match3Coach.pieceName(code);
            boolean restored = letter == Match3Sampler.UNKNOWN ? t.name.equals(spoken) : !"未识别".equals(spoken);
            if (restored) ok++; else bad++;
            System.out.println("    " + t.name + " -> 字母 '" + code + "' -> 播报「" + spoken + "」"
                    + (restored ? "" : "   ★ 没还原"));
        }
        System.out.println("    还原 " + ok + " / " + tpl.size() + "，失败 " + bad);
    }

    static void printConfusions(Map<String, Integer> m, int n) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((x, y) -> y.getValue() - x.getValue());
        if (l.isEmpty()) { System.out.println("    （无）"); return; }
        for (Map.Entry<String, Integer> e : l.subList(0, Math.min(n, l.size()))) {
            System.out.println("    " + e.getKey() + "  " + e.getValue());
        }
    }
}
