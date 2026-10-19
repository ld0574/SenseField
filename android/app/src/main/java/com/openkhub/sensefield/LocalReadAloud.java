package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 「从上往下逐条读」的生成器（纯 JVM，不 import 任何 Android 类，全程本地、不联网、不新截图）。
 *
 * <p>输入是 {@link SenseFieldReaderService#latestState()} 那种压缩状态文本，每行形如
 * {@code n3 [主内容] Button 可点 状态=E- "搜索" @[12,940][96,984]}（见 {@code SenseFieldReaderService:151-155}），
 * 首行是 {@code app=… activity=… dark=…} 状态头。输出是「第 N 条：&lt;语义&gt;，可点／不可点〔，状态段〕」的短句列表，
 * 顺序即压缩状态里的 {@code n<i>} 顺序（顶栏 → 主内容 → 底栏，等于人眼从上往下）。
 * 段内顺序固定为语义 → 可不可点 → 状态；状态段只在 {@link #statePhrase(String)} 判定该加长时才出现。
 *
 * <p>三条口径：
 * <ul>
 *   <li>跳过状态头、空行、容器空壳（无文字描述且不可点的行）与不认识的脏行（半截字节被拦腰也只跳过，不抛）。</li>
 *   <li><b>不念坐标</b>：行尾的 {@code @[x,y][x,y]} 一律不进输出，标签里混进来的坐标片段也剥掉；
 *       旧播报里的坐标文案原样保留，本类只负责新句子。</li>
 *   <li>状态码<b>原文</b>（{@code E1}／{@code -0} 这类字母数字）绝不进输出：念出来是「E 一」这种噪声。
 *       但码里那两个布尔量本身是要传达的语义，所以由 {@link #statePhrase(String)} 翻成人话后
 *       <b>追加</b>在「可点／不可点」之后：{@code -} 开头加「当前不可用」，{@code E} 开头<b>不加</b>任何废话
 *       （可用是常态，念出来才是噪声——原注释里对的那一半），第二位 {@code 1} 加「已选中」、{@code 0} 加「未选中」、
 *       {@code -}（不可选）<b>不加</b>；六个码之外的输入一律不加。<b>只作附加</b>：
 *       「可点／不可点」四个字一字不改，clickable 与 enabled 是两个独立属性，不许互相替换。</li>
 * </ul>
 *
 * <p>条数上限是参数（默认 {@link #DEFAULT_LIMIT}），超限时末句用同一种「第 N 条往后」的编号说清还剩几条没念。
 *
 * <p>安全闸门优先于功能：{@link #sentences} 先过 {@link Match3Gate#sensitiveHit}，命中敏感就返回空列表，
 * 调用方因此不可能把敏感页的明细念出来——这条由 {@code LocalReadAloudTest#sensitivePageIsNotReadItemAtAll} 钉住。
 */
public final class LocalReadAloud {

    /** 默认上限：一屏能被听完的条数量级（压缩状态本身最多 60 个节点）。 */
    public static final int DEFAULT_LIMIT = 12;

    /** 行尾/串内的坐标片段：{@code [12,940]} 与 {@code [96,984]} 这类纯数字方括号。 */
    private static final Pattern COORD_FRAGMENT = Pattern.compile("\\[\\s*-?\\d+\\s*,\\s*-?\\d+\\s*\\]");

    /** 节点行的 ID 段，如 {@code n3 }；没有它就不是节点行（容器空壳、状态头、脏行都归到这里判）。 */
    private static final Pattern NODE_ID = Pattern.compile("^n\\d+\\s");

    private LocalReadAloud() {
    }

    /** 用默认上限逐条读。 */
    public static List<String> sentences(String state) {
        return sentences(state, DEFAULT_LIMIT);
    }

    /**
     * 逐条读：输入压缩状态文本，输出短句列表；文本为空或 null 返回空列表，绝不返回 null。
     * {@code limit <= 0} 视为用默认上限（不给「一条也不读」留口子）。
     */
    public static List<String> sentences(String state, int limit) {
        if (Match3Gate.sensitiveHit(state) != null) {
            return Collections.emptyList();     // 本地敏感闸门命中：一条也不念，调用方只播既有的拦截句
        }
        List<String> body = collect(state);
        int cap = limit <= 0 ? DEFAULT_LIMIT : limit;
        if (body.isEmpty()) {
            return Collections.emptyList();
        }
        if (body.size() <= cap) {
            return body;
        }
        List<String> out = new ArrayList<>(body.subList(0, cap));
        int left = body.size() - cap;
        out.add("第 " + (cap + 1) + " 条往后：还有 " + left + " 条没念，想听完请再说一次。");
        return out;
    }

    /** 把一屏压成一句可播的话；不逐条读时（空列表）返回空串，调用方据此不播。 */
    public static String speech(String state, int limit) {
        List<String> lines = sentences(state, limit);
        if (lines.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /* ---------- 解析 ---------- */

    private static List<String> collect(String state) {
        List<String> out = new ArrayList<>();
        if (state == null || state.isEmpty()) {
            return out;
        }
        int n = 0;
        for (String raw : state.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("app=") || !NODE_ID.matcher(line).find()) {
                continue;                       // 空行 / 状态头 / 不是节点行的脏行
            }
            String what = describe(line);
            if (what == null) {
                continue;                       // 容器空壳：无文字描述且不可点
            }
            n++;
            boolean clickable = line.contains(" 可点");
            String sentence = "第 " + n + " 条：" + what + "，" + (clickable ? "可点" : "不可点");
            // 状态段只作追加，位置固定在「可点／不可点」之后；取不到码就是空串，句子一字不变。
            String tail = statePhrase(stateCodeOf(line));
            out.add(tail.isEmpty() ? sentence : sentence + "，" + tail);
        }
        return out;
    }

    /**
     * 取一行的语义：有标签念标签，没标签但可点念「没有文字的控件」，
     * 既没标签又不可点则返回 null（跳过）。标签里的坐标片段一律剥掉。
     */
    private static String describe(String line) {
        int q1 = line.indexOf('"');
        int q2 = line.lastIndexOf('"');
        String label = (q1 >= 0 && q2 > q1) ? clean(line.substring(q1 + 1, q2)) : "";
        if (!label.isEmpty()) {
            return label;
        }
        return line.contains(" 可点") ? "没有文字的控件" : null;
    }

    /* ---------- 状态码 → 人话（纯 JVM，fail-closed） ---------- */

    /** 采集侧把状态码写进行里时用的标记，见 {@code SenseFieldReaderService} 里 {@code state2} 的拼装。 */
    private static final String STATE_MARKER = " 状态=";

    /**
     * 从一行里取出状态码那一段：标记之后、到下一个空格为止。
     * 行里没有这个标记就返回空串——空串不在六个码之内，于是这一个字也不加。
     */
    private static String stateCodeOf(String line) {
        int at = line.indexOf(STATE_MARKER);
        if (at < 0) {
            return "";
        }
        int start = at + STATE_MARKER.length();
        int end = line.indexOf(' ', start);
        return end < 0 ? line.substring(start) : line.substring(start, end);
    }

    /**
     * 状态码 → 该追加进句子的那段人话；返回空串就表示「什么都不加」。
     *
     * <p>取值域只有六个（{@code isEnabled} 与 {@code isCheckable}／{@code isChecked} 三个布尔量能拼出的全部）：
     * 「只加已选中」「只加未选中」「什么都不加」「当前不可用、已选中」「当前不可用、未选中」「只说当前不可用」，
     * 一一对应见 {@code StatePhraseContractTest}。可用是常态、不可选也不等于未选中，所以这两样都不念。
     *
     * <p>fail-closed：null、空串、半截、长度不是 2、大小写不同、含任何其它字符，一律返回空串且<b>绝不抛</b>——
     * 解析不了也要把这一行照常念出来，不许丢行。返回值里不含任何原始字母码，念不出「E 一」那种噪声。
     */
    static String statePhrase(String code) {
        if (code == null || code.length() != 2) {
            return "";
        }
        char enabled = code.charAt(0);
        char checked = code.charAt(1);
        if (enabled != 'E' && enabled != '-') {
            return "";                          // 首位不是那两个取值：这个码不认识
        }
        if (checked != '1' && checked != '0' && checked != '-') {
            return "";                          // 第二位同理
        }
        StringBuilder phrase = new StringBuilder();
        if (enabled == '-') {
            phrase.append("当前不可用");          // E 开头不加「可用」：可用是常态，念出来才是噪声
        }
        if (checked == '1') {
            appendClause(phrase, "已选中");
        } else if (checked == '0') {
            appendClause(phrase, "未选中");
        }
        return phrase.toString();               // 不可选、又确实可用时，这里就是空串
    }

    private static void appendClause(StringBuilder phrase, String clause) {
        if (phrase.length() > 0) {
            phrase.append('、');
        }
        phrase.append(clause);
    }

    private static String clean(String raw) {
        String s = COORD_FRAGMENT.matcher(raw).replaceAll("");
        int at = s.indexOf('@');
        if (at >= 0) {
            s = s.substring(0, at);
        }
        return s.replace('"', ' ').trim();
    }
}
