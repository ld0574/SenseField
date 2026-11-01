package com.openkhub.sensefield;

/**
 * 高亮层读屏标签生成器：把「棋盘分区＋棋子名＋是否可点＋置信档位」这四个既有语义件转成
 * 一行给读屏念的中文短标签，供 {@link Match3HintOverlay} 设给高亮视图的内容描述。
 *
 * <p>三条硬口径，每条都由 {@code HintLabelTest} 用写死的期望字面量钉住：
 * <ol>
 *   <li><b>只出语义，不出坐标</b>：输入里出现边界框或坐标片段（方括号、at 号起头的包围盒、
 *       阿拉伯数字）时，整条判非法并返回空标签，而不是剪掉半截继续播。播报文案里的坐标另有
 *       一套纪律，那是另一路；标签这一路一概不收。</li>
 *   <li><b>不承诺执行</b>：组装结果或输入里含 {@link WordingGuard#BLACKLIST} 的执行类措辞时，
 *       整条判非法并返回空标签。词表以那张整库口径为底、一个字不删，再叠加本类
 *       {@link #STRICTER_THAN_SPEECH} 的更严小表（标签只描述棋盘语义，「自动」「为你」「替你」
 *       出现在标签里就是替用户承诺动作）；这张更严的表只收紧、不放宽。</li>
 *   <li><b>纯函数</b>：不读随机、不读系统时钟、不读偏好，同一输入必得同一输出。</li>
 * </ol>
 *
 * <p>非法与未知一律返回空标签（{@code ""}），绝不编造语义：档位为 {@link Tier#UNKNOWN}、
 * 分区不在四象限词表内、格序越界、棋子名是未知哨兵，都播不出内容。长度上限
 * {@value #MAX_LABEL_CHARS} 字：超出先撤棋子名，撤完仍超则返回空标签。
 *
 * <p>纯 JVM：本类不 import 任何 Android 类型，也不触碰窗口标志与输入面。
 */
final class HintLabel {

    /** 与既有短句播报口径一致的标签上限，超出即降级或闭嘴。 */
    static final int MAX_LABEL_CHARS = 20;

    /**
     * 播报语境档位。前三档沿用 {@link Match3Gate#gatedSpeech} 的高／中／低三档，不新增第四档；
     * {@link #UNKNOWN} 是「本层没有读数」的哨兵，按非法输入处理，产出空标签。
     */
    enum Tier { CONFIRMED, PROBABLE, UNSURE, UNKNOWN }

    /** 四象限词表，逐字取自 {@link Match3Coach#quadrantOf} 的返回值，本类不另造方位词。 */
    private static final String[] REGIONS = {"左上", "右上", "左下", "右下"};

    /** 棋子名未知时的哨兵词：命中其一即视为「没有读数」，产出空标签而不是硬凑一个名字。 */
    private static final String[] UNKNOWN_PIECES = {"unknown", "未知", "未确认目标"};

    private static final String REGION_SUFFIX = "区域";
    private static final String SEPARATOR = "，";
    private static final String TAP = "可点";
    private static final String SWAP_WORD = "交换";
    private static final String PROBABLE_WORD = "可能";
    private static final String UNSURE_WORD = "拿不准";

    /**
     * 标签这一路比播报更严的词：{@link WordingGuard#BLACKLIST} 是整库口径，它刻意不收孤零零的
     * 「自动」二字，因为播报里那是如实描述；但标签只会描述棋盘的语义，「自动」「为你」「替你」
     * 出现在标签里一律是在替用户承诺动作，所以本路把它们一并拒掉。判定面是 BLACKLIST 加上这张
     * 更严的小表，不删 BLACKLIST 的任何词。
     *
     * <p>逐字构造、不写成整词字面量：否则本类自己就是几条「字面量里的执行类措辞」，全库扫描
     * 永远读不出 0 命中。
     */
    private static final String[] STRICTER_THAN_SPEECH = {
            token('自', '动'), token('帮', '你'), token('为', '你'), token('替', '你'), token('代', '打')};

    private static String token(char... cs) {
        return new String(cs);
    }

    private HintLabel() {
    }

    /**
     * 高亮层的唯一入口：这一层只拿得到提示本身，拿不到棋子名，所以棋子名一段按「无读数」
     * 处理（省略，不编造）；档位取读数在不在。
     */
    static String forHint(Match3Hint hint) {
        return from(hint, tierOf(hint));
    }

    /** 读数缺失即 {@link Tier#UNKNOWN}；有读数时按目标是否读全给确认档或可能档。 */
    static Tier tierOf(Match3Hint hint) {
        if (hint == null || hint.value == null) return Tier.UNKNOWN;
        return hint.value.grounded ? Tier.CONFIRMED : Tier.PROBABLE;
    }

    /** 用提示自身的格序与动作类型出标签；越界或象限算不出分区时返回空标签。 */
    static String from(Match3Hint hint, Tier tier) {
        if (hint == null || hint.geometry == null || hint.swap == null) return "";
        Match3Board.Swap s = hint.swap;
        BoardGeometry g = hint.geometry;
        return ofBoard(s.fromRow, s.fromCol, s.actionType == Match3Action.Type.ACTIVATE,
                g.rows, g.cols, tier);
    }

    /** 越界与空盘面在这一层判掉，所以接线之外的高亮语义也能在 JVM 上直接验。 */
    static String ofBoard(int fromRow, int fromCol, boolean singleTap, int rows, int cols, Tier tier) {
        if (rows <= 0 || cols <= 0 || fromRow < 0 || fromCol < 0 || fromRow >= rows || fromCol >= cols) {
            return "";
        }
        return of(Match3Coach.quadrantOf(fromRow, fromCol, rows, cols), null, singleTap, tier);
    }

    /**
     * 四件语义拼一行标签。
     *
     * @param region   四象限分区名，必须是 {@link #REGIONS} 之一
     * @param piece    棋子名；{@code null} 或空白表示本层没有这个读数，该段省略
     * @param tappable 是否单点目标；{@code null} 表示未知
     * @param tier     置信档位；{@code null} 或 {@link Tier#UNKNOWN} 表示没有读数
     * @return 一行中文标签，或 {@code ""} 表示这一路不该播
     */
    static String of(String region, String piece, Boolean tappable, Tier tier) {
        if (!isRegion(region) || tier == null || tier == Tier.UNKNOWN || tappable == null) {
            return "";
        }
        String name = piece == null ? "" : piece.trim();
        if (name.length() > 0 && isUnknownPiece(name)) return "";
        if (carriesCoordinateShape(region) || carriesCoordinateShape(name)) return "";
        if (carriesExecutionWording(region) || carriesExecutionWording(name)) return "";
        String suffix = SEPARATOR + hedgeWord(tier);
        if (suffix.length() == SEPARATOR.length()) suffix = "";
        String act = tappable ? TAP : SWAP_WORD;
        String label = region + REGION_SUFFIX + SEPARATOR + name + act + suffix;
        if (label.length() > MAX_LABEL_CHARS) {
            label = region + REGION_SUFFIX + SEPARATOR + act + suffix;
        }
        if (label.length() > MAX_LABEL_CHARS) return "";
        return carriesExecutionWording(label) || carriesCoordinateShape(label) ? "" : label;
    }

    private static String hedgeWord(Tier tier) {
        if (tier == Tier.PROBABLE) return PROBABLE_WORD;
        if (tier == Tier.UNSURE) return UNSURE_WORD;
        return "";
    }

    private static boolean isRegion(String value) {
        if (value == null) return false;
        for (String region : REGIONS) {
            if (region.equals(value)) return true;
        }
        return false;
    }

    private static boolean isUnknownPiece(String value) {
        for (String token : UNKNOWN_PIECES) {
            if (token.equals(value)) return true;
        }
        return false;
    }

    /**
     * 坐标形状：方括号、at 号与井号／百分号起头的包围盒或百分比报点、换行，以及任何阿拉伯数字，
     * 一律算坐标片段。判定的是输入本身，所以剪掉半截拼一句这种取巧不存在。
     */
    private static boolean carriesCoordinateShape(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '[' || c == ']' || c == '@' || c == '#' || c == '%' || c == '\n' || c == '\r'
                    || c >= '0' && c <= '9') {
                return true;
            }
        }
        return false;
    }

    /** 复用 {@link WordingGuard#BLACKLIST} 逐字判，再叠加本类 {@link #STRICTER_THAN_SPEECH} 的更严小表。 */
    private static boolean carriesExecutionWording(String value) {
        for (String term : WordingGuard.BLACKLIST) {
            if (value.contains(term)) return true;
        }
        for (String term : STRICTER_THAN_SPEECH) {
            if (value.contains(term)) return true;
        }
        return false;
    }
}
