package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 弃权后的「从上往下逐条读」生成器：把 {@link SenseFieldReaderService#latestState()} 的压缩状态文本
 * 按节点行序（压缩时已是纵向从上往下遍历）转成一串短句，全程本地，不新截图、不上云。
 *
 * 压缩口径见 {@code SenseFieldReaderService} 类注释（§四）：首行是状态头（{@code app=…}），
 * 之后每行 {@code n<i> [分区] <Class>[ 可点] 状态=<E|-><1|0|-> "<label>" @[l,t][r,b]}。
 * 空行、容器空壳、无文本且无描述的节点、认不出来的脏行（半截字节被拦腰）一律跳过——念出来只是噪音。
 *
 * 播报口径：只说「第 N 条＋语义＋可点与否」，行尾的 {@code @[x,y][x,y]} 坐标整段剥掉、从不进输出
 * （坐标念出来数不了格）；既有播报文案里的坐标一个字也不动，本类只负责新句子。
 * 安全口径：{@link #readGuarded} 先过 {@link Match3Gate#sensitiveHit}，命中敏感只播一句闸门话术。
 */
final class Match3LineReader {
    private Match3LineReader() {
    }

    /** 默认条数上限：一屏听得完的量级，超出的条数在末句交代。 */
    static final int DEFAULT_MAX_ITEMS = 10;

    /** 敏感闸门命中时唯一允许播出的一句（不带任何明细）。 */
    static final String GATE_BLOCK = "本地敏感闸门命中：这一屏我不逐条读，内容不上云、不播报细节，建议手动处理。";

    /** 行尾成对的边界框 {@code @[l,t][r,b]}，解析前先整段剥掉。 */
    private static final Pattern TRAILING_BOUNDS = Pattern.compile(
            "@\\[\\s*-?\\d+\\s*,\\s*-?\\d+\\s*\\]\\[\\s*-?\\d+\\s*,\\s*-?\\d+\\s*\\]\\s*$");

    static List<String> read(String state) {
        return read(state, DEFAULT_MAX_ITEMS);
    }

    /** 闸门在前的逐条读：命中敏感即只返回一句固定话术，一条明细也不念。 */
    static List<String> readGuarded(String state) {
        return readGuarded(state, DEFAULT_MAX_ITEMS);
    }

    static List<String> readGuarded(String state, int maxItems) {
        if (Match3Gate.sensitiveHit(state) != null) {
            List<String> block = new ArrayList<String>(1);
            block.add(GATE_BLOCK);
            return block;
        }
        return read(state, maxItems);
    }

    /** 逐条读正文（含截断时的末句）。永不返回 null。 */
    static List<String> read(String state, int maxItems) {
        List<String> out = new ArrayList<String>();
        if (state == null || state.isEmpty() || maxItems <= 0) return out;
        int spoken = 0;
        int left = 0;
        for (String line : state.split("\n", -1)) {
            String saying = toSaying(line);
            if (saying == null) continue;
            if (spoken >= maxItems) {
                left++;
                continue;
            }
            spoken++;
            out.add("第 " + spoken + " 条：" + saying + "。");
        }
        if (left > 0) out.add("后面还有 " + left + " 条没念。");
        return out;
    }

    /** 单行 → 语义短句；该跳过的行返回 null。 */
    private static String toSaying(String rawLine) {
        if (rawLine == null) return null;
        String body = rawLine.trim();
        if (body.isEmpty()) return null;
        if (body.startsWith("app=")) return null;              // 状态头不是节点
        if (body.charAt(0) != 'n') return null;                // 没有节点序号＝脏行
        int cut = body.indexOf(' ');
        if (cut <= 0 || cut == body.length() - 1) return null;
        body = body.substring(cut + 1).trim();

        if (body.startsWith("[")) {                            // 分区前缀不进播报
            int zone = body.indexOf(']');
            if (zone < 0) return null;
            body = body.substring(zone + 1).trim();
        }
        if (body.isEmpty()) return null;
        body = TRAILING_BOUNDS.matcher(body).replaceAll("");   // 坐标整段剥掉

        boolean clickable = body.contains("可点");
        String label = null;
        String head = body;
        int quote = body.indexOf('"');
        if (quote >= 0) {
            int close = body.indexOf('"', quote + 1);
            if (close < 0) return null;                        // 引号被拦腰截断＝脏行
            label = body.substring(quote + 1, close);
            head = body.substring(0, quote);
        }

        String saying;
        if (label != null) {
            String text = label.trim();
            if (text.isEmpty()) return null;                   // 无文本无描述：容器空壳
            if (text.indexOf("@[") >= 0) return null;          // 标签里混进坐标片段＝宁可不说
            saying = text;
        } else {
            String code = stateCode(body);
            if (code == null) return null;                     // 既无文字也无状态位＝认不出
            String clazz = className(head);
            saying = (clazz == null ? "控件" : clazz + "控件") + code;
        }
        if (saying.isEmpty()) return null;
        return saying + "，" + (clickable ? "可点" : "不可点");
    }

    /** 引号之前的类名（分区与「可点」「状态=」尾巴都不进播报）。 */
    private static String className(String head) {
        String s = head.trim();
        int space = s.indexOf(' ');
        if (space > 0) s = s.substring(0, space);
        s = s.replace("可点", "").trim();
        return s.isEmpty() ? null : s;
    }

    /** {@code 状态=XY} 的两位状态码 → 口语文案；没有该字段返回 null。 */
    private static String stateCode(String body) {
        int at = body.indexOf("状态=");
        if (at < 0) return null;
        String tail = body.substring(at + "状态=".length());
        if (tail.length() < 2) return null;
        char enabled = tail.charAt(0);
        char checked = tail.charAt(1);
        if (enabled != 'E' && enabled != '-') return null;
        StringBuilder sb = new StringBuilder(enabled == 'E' ? "启用" : "停用");
        if (checked == '1') sb.append("且已选中");
        else if (checked == '0') sb.append("未选中");
        return sb.toString();
    }
}
