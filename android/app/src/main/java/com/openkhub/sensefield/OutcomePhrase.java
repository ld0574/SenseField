package com.openkhub.sensefield;

/**
 * 「这一步点下去会怎样」这句结果语义的生成器（纯 JVM：不 import 任何 Android 类，不读随机、
 * 不读时钟、不读偏好，同一输入必得同一输出）。
 *
 * <p>背景：{@link Match3Board.Swap} 上一直带着四个已经由盘面推出来的结果量
 * （{@code runsFormed}／{@code matchedCells}／{@code longestRun}／{@code adjacentIce}），
 * {@link Match3MoveRanker} 拿它们排序，但四个字段名在整个主源集的出文案类里 0 命中——玩家听到的是
 * 「哪一步」，听不到「这一步会消掉几子、成几组、能不能破冰」。本类只补这一句人话，
 * 一个判定也不改：不重排、不算概率、不承诺效率。
 *
 * <p>三条硬口径，逐条由 {@code OutcomePhraseTest} 用写死的期望字面量钉住：
 * <ol>
 *   <li><b>只陈述盘面已推出的结构结果</b>：句子里只出现数量（几个、几组、几连、几块冰），
 *       不出现「最优／必过／稳赢／更快／上分」这类承诺，也不出现「好像／拿不准」这类 hedge——
 *       「拿不准」那一档在生产里没有产生点（{@link HintLabel.Tier#UNSURE} 无上游赋值），
 *       领导待裁之前本类不接那一档。</li>
 *   <li><b>没读数就闭嘴</b>：四个量全为 0、任一量为负或超出可播报范围、{@code swap} 本身缺失、
 *       动作类型不是普通交换、规则串不是本类认识的口径，一律返回<b>空串</b>，绝不硬凑一句。
 *       空串是本类唯一的「无话可说」出口，调用方把它拼在既有文案之后即可保证文案逐字不变。</li>
 *   <li><b>不出坐标形状</b>：计数一律写成中文数字（五、十二、一百四十四），句内不出现阿拉伯数字、
 *       方括号、{@code @}／{@code #}／{@code %} 与换行——与 {@code HintLabel} 里那条既有判据
 *       （带数字方位的串判死）同一口径。本类不共用、也不修改 {@code HintLabel} 的判定面与
 *       {@code HintLabel.MAX_LABEL_CHARS} 那 20 字标签预算。</li>
 * </ol>
 *
 * <p>长度上限 {@link #MAX_PHRASE_CHARS} 字是本类自定的显式预算（不复用任何既有常量）：
 * 超限宁可整句不播，也不截半句出去念成残句。
 *
 * <p>证据边界：本机无 adb、无声学设备，本类与它的测试只证 JVM 代码路径与字符串形状，
 * 证不了这一句在真机上念得完念不完、玩家听不听得懂。
 */
final class OutcomePhrase {

    /** 整句长度上限（字符数）。本类自定并显式成常量，不共用 {@code HintLabel} 的 20 字标签预算。 */
    static final int MAX_PHRASE_CHARS = 40;

    /** 可播报的最大计数；任一出数超出这一口径即视为读数不可信，整句不造。 */
    static final int MAX_COUNT = 999;

    /** 只有普通交换带这四个结构读数；其余动作类型在 {@code Match3Action.special} 里四个量恒为 0。 */
    private static final Match3Action.Type SPEAKABLE_TYPE = Match3Action.Type.SWAP;

    /** 本类认识的结算口径，逐字取自 {@code Match3Board} 给普通交换写死的默认规则串。 */
    private static final String SPEAKABLE_RULE = "ordinary_uncovered_animal";

    private static final String STEP_HEAD = "这一步";
    private static final String CLEAR_HEAD = "会消掉";
    private static final String CLEAR_TAIL = "个";
    private static final String RUNS_HEAD = "成";
    private static final String RUNS_TAIL = "组";
    private static final String LONGEST_HEAD = "最长";
    private static final String LONGEST_TAIL = "连";
    private static final String ICE_HEAD = "旁边有";
    private static final String ICE_TAIL = "块冰可破";
    private static final String SEPARATOR = "、";
    private static final String FULL_STOP = "。";

    /** 中文数字：一个阿拉伯数字也不进句子，句子才过得了既有那条坐标形状判据。 */
    private static final String DIGITS = "零一二三四五六七八九";
    private static final String TWO = "两";
    private static final String HUNDRED = "百";
    private static final String TEN = "十";
    private static final String ZERO_LINK = "零";

    private OutcomePhrase() {
    }

    /**
     * 生产入口：从一条走法的四个结果量与它自身的类型／规则出句。
     * {@code swap} 为 {@code null}（本层没有读数）时返回空串。
     */
    static String forSwap(Match3Board.Swap swap) {
        if (swap == null) return "";
        return of(swap.runsFormed, swap.matchedCells, swap.longestRun, swap.adjacentIce,
                swap.actionType, swap.ruleId);
    }

    /** 只给四个量、不带类型与规则时的口径（两者按缺省＝不额外设卡）。 */
    static String of(int runsFormed, int matchedCells, int longestRun, int adjacentIce) {
        return of(runsFormed, matchedCells, longestRun, adjacentIce, null, null);
    }

    /**
     * 四个结果量 → 一句中文。
     *
     * @param runsFormed   这次交换新增的连组数
     * @param matchedCells 这次交换消掉的棋子格数
     * @param longestRun   最长一条连的长度
     * @param adjacentIce  连组旁可破的冰块数
     * @param type         动作类型；{@code null} 按缺省放行，非普通交换返回空串
     * @param rule         规则串；{@code null}／空白按缺省放行，本类不认识的口径返回空串
     * @return 一句中文结果语义，或 {@code ""} 表示这一路不该播
     */
    static String of(int runsFormed, int matchedCells, int longestRun, int adjacentIce,
            Match3Action.Type type, String rule) {
        if (type != null && type != SPEAKABLE_TYPE) return "";
        if (!isSpeakableRule(rule)) return "";
        int[] counts = {runsFormed, matchedCells, longestRun, adjacentIce};
        for (int count : counts) {
            if (count < 0 || count > MAX_COUNT) return "";
        }
        StringBuilder phrase = new StringBuilder(STEP_HEAD);
        appendClause(phrase, matchedCells, CLEAR_HEAD, CLEAR_TAIL);
        appendClause(phrase, runsFormed, RUNS_HEAD, RUNS_TAIL);
        appendClause(phrase, longestRun, LONGEST_HEAD, LONGEST_TAIL);
        appendClause(phrase, adjacentIce, ICE_HEAD, ICE_TAIL);
        if (phrase.length() == STEP_HEAD.length()) return "";
        phrase.append(FULL_STOP);
        return phrase.length() > MAX_PHRASE_CHARS ? "" : phrase.toString();
    }

    /** 计数为 0 的段整段省略：没有读数就不写进句子。 */
    private static void appendClause(StringBuilder phrase, int count, String head, String tail) {
        if (count <= 0) return;
        if (phrase.length() > STEP_HEAD.length()) phrase.append(SEPARATOR);
        phrase.append(head).append(chineseCount(count)).append(tail);
    }

    private static boolean isSpeakableRule(String rule) {
        if (rule == null) return true;
        String trimmed = rule.trim();
        return trimmed.isEmpty() || SPEAKABLE_RULE.equals(trimmed);
    }

    /** 1..999 的中文写法；2 单独成词时用「两」，其余按位拼。越界由调用方先判掉。 */
    private static String chineseCount(int value) {
        if (value == 2) return TWO;
        int hundreds = value / 100;
        int rest = value % 100;
        StringBuilder sb = new StringBuilder();
        if (hundreds > 0) sb.append(digit(hundreds)).append(HUNDRED);
        if (rest == 0) return sb.toString();
        if (hundreds > 0 && rest < 10) return sb.append(ZERO_LINK).append(digit(rest)).toString();
        int tens = rest / 10;
        int ones = rest % 10;
        if (tens == 1) sb.append(TEN);
        else if (tens > 1) sb.append(digit(tens)).append(TEN);
        if (ones > 0) sb.append(digit(ones));
        return sb.toString();
    }

    private static char digit(int value) {
        return DIGITS.charAt(value);
    }
}
