package com.openkhub.sensefield;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 整盘解说器：把散点报点拼成不超过两句的人话。
 *
 * <p>叫法一律调 {@link Match3Coach#pieceName(char)} 取、方位一律调 {@link Match3Coach#quadrantOf}
 * 取，本类不出现任何棋子名字面量，也不出现任何方位名字面量——全项目播报词表仍只有 Match3Coach
 * 一处来源，这里只是把它拼成整盘口径。不新造方位名，不写裸颜色。
 *
 * <p>设计上只供「没找到可推荐」那一支追加使用（不参与正常推荐路径），因此输出必须短：
 * 第一句讲哪一片大概有什么、几个，第二句讲未识别几格；没有未识别就播「盘面都在预期里」。
 * 输出不含行列坐标数字（计数不是坐标）、不含包名与机型名。
 *
 * <p>纯 JVM 逻辑，不碰 Android API。
 */
final class Match3Narrator {

    /** 解说句数上限。 */
    static final int MAX_SENTENCES = 2;

    /**
     * 解说长度上限（字符数）。这个数值是执行者拍的（猜的）：按词表里最长的名字
     * 「非普通棋子区域」与象限名拼满两句后留出的余量定，只为兜住「不超过两句」的口径。
     */
    static final int MAX_CHARS = 48;

    private Match3Narrator() {
    }

    /**
     * 整盘解说。
     *
     * @param board 棋盘字符矩阵（与采样器同一套字母：基础色、雪块 I、空位 H、空格、# 非普通棋子区域、
     *              C 银币、E 鸡蛋、. 未识别）
     * @param rows  播报口径用的行数，交给 {@code Match3Coach.quadrantOf} 切象限
     * @param cols  播报口径用的列数
     * @return 不超过两句的整盘解说
     */
    static String narrate(char[][] board, int rows, int cols) {
        // 象限键与叫法键都只由 quadrantOf / pieceName 产生。LinkedHashMap 保留行主序首次出现的顺序，
        // 并列时「先扫描到的」胜出，即左上优先——不写死任何方位名也能得到确定的优先次序。
        LinkedHashMap<String, LinkedHashMap<String, Integer>> byQuadrant = new LinkedHashMap<>();
        int unknown = 0;
        if (board != null && rows > 0 && cols > 0) {
            for (int r = 0; r < board.length && r < rows; r++) {
                char[] line = board[r];
                if (line == null) continue;
                for (int c = 0; c < line.length && c < cols; c++) {
                    char piece = line[c];
                    String quadrant = Match3Coach.quadrantOf(r, c, rows, cols);
                    String name = Match3Coach.pieceName(piece);
                    LinkedHashMap<String, Integer> counts = byQuadrant.get(quadrant);
                    if (counts == null) {
                        counts = new LinkedHashMap<>();
                        byQuadrant.put(quadrant, counts);
                    }
                    Integer previous = counts.get(name);
                    counts.put(name, previous == null ? 1 : previous + 1);
                    if (Match3Sampler.isUnknown(piece)) unknown++;
                }
            }
        }
        return firstSentence(byQuadrant) + secondSentence(unknown);
    }

    /** 第一句：哪一片大概有什么、几个。取「片内最高计数」最大的那一片，并列时行主序先出现的片胜出。 */
    private static String firstSentence(
            LinkedHashMap<String, LinkedHashMap<String, Integer>> byQuadrant) {
        String bestQuadrant = null;
        LinkedHashMap<String, Integer> bestCounts = null;
        int bestTopCount = 0;
        for (Map.Entry<String, LinkedHashMap<String, Integer>> q : byQuadrant.entrySet()) {
            int top = 0;
            for (Integer count : q.getValue().values()) {
                if (count > top) top = count;
            }
            if (top > bestTopCount) {
                bestTopCount = top;
                bestQuadrant = q.getKey();
                bestCounts = q.getValue();
            }
        }
        if (bestQuadrant == null) return "这一盘暂时读不出棋子分布。";

        // 该片内计数最高与次高的两个叫法；并列按该片内先出现的叫法，次高必须与最高不同名。
        String firstName = null;
        String secondName = null;
        int firstCount = 0;
        int secondCount = 0;
        for (Map.Entry<String, Integer> n : bestCounts.entrySet()) {
            int count = n.getValue();
            if (count > firstCount) {
                secondName = firstName;
                secondCount = firstCount;
                firstName = n.getKey();
                firstCount = count;
            } else if (count > secondCount) {
                secondName = n.getKey();
                secondCount = count;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(bestQuadrant).append("那一片主要是").append(firstName).append(' ')
          .append(firstCount).append(" 个");
        if (secondName != null) {
            sb.append("、").append(secondName).append(' ').append(secondCount).append(" 个");
        }
        return sb.append("。").toString();
    }

    /** 第二句：未识别几格；一格都没有就播「盘面都在预期里」。 */
    private static String secondSentence(int unknown) {
        if (unknown > 0) return "还有 " + unknown + " 格没认出来。";
        return "盘面都在预期里。";
    }
}
