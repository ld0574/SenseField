package com.openkhub.sensefield;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * 界面读数 → 社区标注语料（JSON Lines）的导出器：<b>输入是文本，输出也是文本</b>，纯 JVM 实现，
 * 不 import 任何 {@code android.*} 类、不起服务、不读写文件、不联网、不写死任何绝对路径，也不新增依赖
 * （只用工程既有测试类路径上已存在的 org.json 与 JDK 标准库）。
 *
 * <p>为什么要它：仓库里 {@code assets/jev/} 的四份读数（菜单无障碍树、消消乐局面、象棋局面、判定门控配置）
 * 已经是「一屏一个界面语义」的成品标注，但格式是 app 内部读的结构化状态，社区接不动。本类把它们逐条摊平成
 * <b>一行一个可标注单元</b>的 JSON Lines，攒语料不训模型（本机无 GPU／CUDA／NPU，训练路线已裁掉）。
 *
 * <p>规格来源是<b>实测结构</b>（任务 0 的读数），不是猜的字段名：
 * <ul>
 *   <li>{@code menu-axtree.json}：{@code state.nodes[]}，每个节点带 {@code id/text/content_desc/clickable/bounds[4]}；</li>
 *   <li>{@code match3.json}：{@code state.board.rows[][]} 字符前缀、{@code state.potential_matches[]} 带
 *       {@code from/to[2]} 与 {@code run/reward}，另有 {@code scene/moves_left/score/time_limit_sec} 读数；</li>
 *   <li>{@code chess.json}：{@code state.board[][]}（10 行 × 9 列，前缀 b=黑方 r=红方，null=空位），
 *       另有 {@code turn/last_move/in_check/captured_recently}；</li>
 *   <li>{@code gating.json}：顶层就是门控配置，每个键下是若干阈值／开关／文案叶子。</li>
 * </ul>
 * 四类之外的输入走通用叶子遍历（键路径当单元标识），新增读数不会静默丢掉。
 *
 * <p>导出面要对外，所以<b>身份字段一律不落进语料</b>：用户目录（含盘符的 Users／home／AppData 段）、
 * 网盘目录名、手机品牌机型串、聊天软件名，在写出一行之前就被替换成占位符（＜用户目录＞／＜仓库根＞／
 * ＜网盘目录＞／＜机型＞／＜聊天软件＞），替换次数按类别累计并从 {@link Result} 读出，成为可测读数。
 * 每个字段值先脱敏一次，整行序列化后再脱敏一次（防转义把路径切开漏掉尾巴），所以只要语料里还有一个身份
 * 串没被吃掉，计数就一定不是零。
 *
 * <p>{@code _note} 这类元注释键<b>不进语料</b>：它写的是「模拟样例，非真实抓取」，属于来源说明而不是
 * 可标注单元； provenance 信息由 {@code source_file} 字段承担。
 */
public final class JevAnnotationExporter {

    /** 占位符（全角尖括号，本身不含任何身份字面量，也不含引号／反斜杠，不会破坏 JSON 结构）。 */
    public static final String PLACEHOLDER_USER_DIR = "＜用户目录＞";
    public static final String PLACEHOLDER_REPO_ROOT = "＜仓库根＞";
    public static final String PLACEHOLDER_CLOUD_DRIVE = "＜网盘目录＞";
    public static final String PLACEHOLDER_DEVICE = "＜机型＞";
    public static final String PLACEHOLDER_CHAT_APP = "＜聊天软件＞";

    /** 替换类别名，测试按这些字面量核对分类计数。 */
    public static final String CATEGORY_USER_DIR = "user_dir";
    public static final String CATEGORY_REPO_ROOT = "repo_root";
    public static final String CATEGORY_CLOUD_DRIVE = "cloud_drive";
    public static final String CATEGORY_DEVICE = "device_brand";
    public static final String CATEGORY_CHAT_APP = "chat_app";

    /**
     * 脱敏规则表，<b>顺序敏感</b>：先具体（用户目录、AppData）、后泛化（任意盘符绝对路径）、再品牌与应用名。
     * 尾部字符类不放引号与尖括号，保证替换永远落在单个字符串值内部，不越出 JSON 结构。
     */
    private static final Rule[] RULES = {
            // 盘符 + Users/home + 一段用户名（用户名常见为纯数字段）
            new Rule(CATEGORY_USER_DIR, Pattern.compile(
                    "(?i)[a-z]:[\\\\/]{1,2}(?:users|home)[\\\\/]{1,2}[^\"'<>|\\s]*")),
            // 无盘符的 \\Users\\xxx、/home/xxx、/Users/xxx 片段
            new Rule(CATEGORY_USER_DIR, Pattern.compile(
                    "(?i)[\\\\/]{1,2}(?:users|home)[\\\\/]{1,2}[^\"'<>|\\s]*")),
            new Rule(CATEGORY_USER_DIR, Pattern.compile("(?i)(?:appdata|漫游应用数据|本地应用数据)[\\\\/]{1,2}[^\"'<>|\\s]*")),
            // 反向验证暴露的漏网形状：值直接从 users／home 开头（前面没有分隔符或盘符）还带一段数字用户名
            new Rule(CATEGORY_USER_DIR, Pattern.compile(
                    "(?i)\\b(?:users|home)[\\\\/]{1,2}[0-9]{3,}[^\"'<>|\\s]*")),
            // 兜底：任何还剩盘符的绝对路径
            new Rule(CATEGORY_REPO_ROOT, Pattern.compile("(?i)[a-z]:[\\\\/]{1,2}[^\"'<>|\\s]*")),
            new Rule(CATEGORY_REPO_ROOT, Pattern.compile("(?i)~(?=[\\\\/])")),
            // 网盘目录名
            new Rule(CATEGORY_CLOUD_DRIVE, Pattern.compile(
                    "(?i)onedrive|onedriveforbusiness|skydrive|dropbox|google[ _-]?drive|gdrive|icloud|"
                            + "百度网盘|腾讯微云|天翼云盘|坚果云|阿里云盘|夸克网盘")),
            // 手机品牌／机型串（覆盖面宽，宁可多判不可漏判）
            new Rule(CATEGORY_DEVICE, Pattern.compile(
                    "(?i)huawei|honor|xiaomi|redmi|mi\\s?(?:11|12|13|14)|oppo|vivo|realme|oneplus|"
                            + "samsung|galaxy|motorola|nubia|meizu|google[ _-]?pixel|mate\\s?\\d+|"
                            + "华为|荣耀|小米|红米|一加|三星|魅族|努比亚")),
            // 聊天软件名
            new Rule(CATEGORY_CHAT_APP, Pattern.compile(
                    "(?i)wechat|weixin|\\bqq\\b|mqq|dingtalk|feishu|\\blark\\b|telegram|whatsapp|slack|"
                            + "messenger|微信|钉钉|飞书|企业微信|QQ")),
    };

    /** 象棋棋子前缀 → 阵营（实测 chess.json 的 _note：b=黑方 r=红方）。 */
    private static final Map<String, String> CHESS_SIDE = chessSide();
    /** 消消乐字符前缀 → 颜色（实测 match3.json 的 _note：R红/Y黄/B蓝/G绿/O橙/P紫）。 */
    private static final Map<String, String> MATCH3_COLOR = match3Color();
    /** 门控配置键路径 → 中文语义标签（键名实测自 gating.json）。 */
    private static final Map<String, String> GATING_LABEL = gatingLabel();

    private JevAnnotationExporter() {
    }

    /* ==================== 对外入口 ==================== */

    /** 一份待导出的源读数：文件名（只做标识用，不做路径用）＋ 文本内容。 */
    public static final class Source {
        public final String name;
        public final String text;

        public Source(String name, String text) {
            this.name = name == null ? "" : name;
            this.text = text == null ? "" : text;
        }
    }

    /** 导出结果：语料全文 + 单元数 + 脱敏替换计数（分类别）。 */
    public static final class Result {
        public final String jsonLines;
        public final int unitCount;
        public final int replacementCount;
        public final Map<String, Integer> replacementsByCategory;
        public final Map<String, Integer> unitsBySource;

        Result(String jsonLines, int unitCount, int replacementCount,
               Map<String, Integer> replacementsByCategory, Map<String, Integer> unitsBySource) {
            this.jsonLines = jsonLines;
            this.unitCount = unitCount;
            this.replacementCount = replacementCount;
            this.replacementsByCategory = replacementsByCategory;
            this.unitsBySource = unitsBySource;
        }

        /** 某个类别的替换次数，没有该类记录时返回 0。 */
        public int replacementCount(String category) {
            Integer value = replacementsByCategory.get(category);
            return value == null ? 0 : value;
        }

        /** "match3.json=70" 这样的逐源单元计数，便于留痕与断言。 */
        public List<String> unitCountReadout() {
            List<String> out = new ArrayList<String>();
            for (Map.Entry<String, Integer> entry : unitsBySource.entrySet()) {
                out.add(entry.getKey() + "=" + entry.getValue());
            }
            return out;
        }
    }

    /** 单份导出。 */
    public static Result export(Source source) {
        List<Source> single = new ArrayList<Source>();
        single.add(source);
        return export(single);
    }

    /**
     * 全量导出：按传入顺序逐份摊平，输出以换行结尾的 JSON Lines（每行一个单元，行间无空行）。
     *
     * @throws IllegalArgumentException 某份输入不是合法 JSON 对象时抛出，绝不静默产出半截语料
     */
    public static Result export(List<Source> sources) {
        Counter counter = new Counter();
        Map<String, Integer> unitsBySource = new LinkedHashMap<String, Integer>();
        List<String> lines = new ArrayList<String>();
        for (Source source : sources) {
            JSONObject root = parseObject(source);
            List<String> fileLines = new ArrayList<String>();
            exportDocument(source, root, fileLines, counter);
            if (fileLines.size() == 0) {
                throw new IllegalArgumentException("source " + source.name + " produced no annotation unit");
            }
            lines.addAll(fileLines);
            unitsBySource.put(source.name, fileLines.size());
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        return new Result(sb.toString(), lines.size(), counter.total, counter.byCategory, unitsBySource);
    }

    /** 单值脱敏，不关心计数时用它。 */
    public static String desensitize(String raw) {
        return desensitize(raw, null);
    }

    /**
     * 单值脱敏：把一份原始文本里所有身份串换成占位符；{@code sink} 非空时按规则类别累加替换次数。
     * 供测试与调用方复用，语义与导出内部完全一致。
     */
    public static String desensitize(String raw, Map<String, Integer> sink) {
        String out = raw == null ? "" : raw;
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern.matcher(out);
            StringBuffer buffer = new StringBuffer();
            int hits = 0;
            while (matcher.find()) {
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(rule.placeholder));
                hits++;
            }
            matcher.appendTail(buffer);
            out = buffer.toString();
            if (hits > 0 && sink != null) {
                Integer previous = sink.get(rule.category);
                sink.put(rule.category, Integer.valueOf(previous == null ? hits : previous + hits));
            }
        }
        return out;
    }

    /* ==================== 结构化摊平 ==================== */

    private static void exportDocument(Source source, JSONObject root, List<String> lines, Counter counter) {
        JSONObject state = root.optJSONObject("state");
        if (state != null) {
            if (state.has("nodes")) {
                exportAccessibilityTree(source, state, lines, counter);
                return;
            }
            if (state.has("potential_matches")) {
                exportMatch3(source, state, lines, counter);
                return;
            }
            if (state.optJSONArray("board") != null) {
                exportChess(source, state, lines, counter);
                return;
            }
            exportLeaves(source, state, "state", lines, counter);
            return;
        }
        exportLeaves(source, root, "", lines, counter);
    }

    /** 菜单无障碍树：一个节点一行，bounds 原样带上。 */
    private static void exportAccessibilityTree(Source source, JSONObject state, List<String> lines,
                                               Counter counter) {
        String packageValue = state.optString("package", "");
        String activityValue = state.optString("activity", "");
        UnitLine scope = new UnitLine()
                .put("source_file", source.name)
                .put("unit_id", qualified(source.name, "state.scope"))
                .put("unit_kind", "screen_scope")
                .put("semantic_label", "界面归属")
                .put("value", packageValue + " / " + activityValue);
        emit(scope, lines, counter);
        JSONArray nodes = state.optJSONArray("nodes");
        if (nodes == null) {
            return;
        }
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) {
                continue;
            }
            String id = node.optString("id", "node_" + i);
            String text = node.optString("text", "");
            String description = node.isNull("content_desc") ? "" : node.optString("content_desc", "");
            boolean clickable = node.optBoolean("clickable", false);
            String label = (clickable ? "可操作控件：" : "只读文本：")
                    + (description.isEmpty() ? text : description);
            UnitLine line = new UnitLine()
                    .put("source_file", source.name)
                    .put("unit_id", qualified(source.name, "state.nodes[" + i + "]." + id))
                    .put("unit_kind", "accessibility_node")
                    .put("semantic_label", label)
                    .put("value", text)
                    .put("node_id", id)
                    .put("content_desc", description)
                    .put("interactive", clickable);
            int[] bounds = intsOf(node.optJSONArray("bounds"));
            if (bounds != null) {
                line.put("bounds", bounds);
            }
            emit(line, lines, counter);
        }
    }

    /** 消消乐局面：逐格棋子 + 候选交换 + HUD 读数。 */
    private static void exportMatch3(Source source, JSONObject state, List<String> lines, Counter counter) {
        JSONObject board = state.optJSONObject("board");
        JSONArray rows = board == null ? null : board.optJSONArray("rows");
        int[] size = board == null ? null : intsOf(board.optJSONArray("size"));
        if (rows != null) {
            for (int r = 0; r < rows.length(); r++) {
                JSONArray row = rows.optJSONArray(r);
                if (row == null) {
                    continue;
                }
                for (int c = 0; c < row.length(); c++) {
                    String piece = row.optString(c, "");
                    if (piece.isEmpty()) {
                        continue;
                    }
                    String color = MATCH3_COLOR.get(piece);
                    String label = (color == null ? "未知棋子 " + piece : color + "棋子 " + piece);
                    emit(new UnitLine()
                            .put("source_file", source.name)
                            .put("unit_id", qualified(source.name, "state.board.rows[" + r + "][" + c + "]"))
                            .put("unit_kind", "board_cell")
                            .put("semantic_label", label)
                            .put("value", piece)
                            .put("coords", new int[]{r, c}), lines, counter);
                }
            }
        }
        JSONArray matches = state.optJSONArray("potential_matches");
        if (matches != null) {
            for (int i = 0; i < matches.length(); i++) {
                JSONObject move = matches.optJSONObject(i);
                if (move == null) {
                    continue;
                }
                int[] from = intsOf(move.optJSONArray("from"));
                int[] to = intsOf(move.optJSONArray("to"));
                int run = move.optInt("run", -1);
                String reward = move.optString("reward", "");
                List<Integer> span = new ArrayList<Integer>();
                if (from != null) {
                    span.add(from[0]);
                    span.add(from[1]);
                }
                if (to != null) {
                    span.add(to[0]);
                    span.add(to[1]);
                }
                UnitLine line = new UnitLine()
                        .put("source_file", source.name)
                        .put("unit_id", qualified(source.name, "state.potential_matches[" + i + "]"))
                        .put("unit_kind", "candidate_move")
                        .put("semantic_label", "候选交换 三连长度 " + run + " 收益档 " + reward)
                        .put("run_length", run)
                        .put("tier", reward);
                if (!span.isEmpty()) {
                    int[] array = new int[span.size()];
                    for (int i2 = 0; i2 < array.length; i2++) {
                        array[i2] = span.get(i2);
                    }
                    line.put("coords", array);
                }
                emit(line, lines, counter);
            }
        }
        for (String key : new String[]{"scene", "moves_left", "score", "time_limit_sec"}) {
            if (!state.has(key) || state.isNull(key)) {
                continue;
            }
            emit(new UnitLine()
                    .put("source_file", source.name)
                    .put("unit_id", qualified(source.name, "state." + key))
                    .put("unit_kind", "state_reading")
                    .put("semantic_label", MATCH3_STATE_LABEL.get(key))
                    .put("value", typed(state.opt(key)))
                    .put("grid_size", size == null ? null : size), lines, counter);
        }
    }

    /** 象棋局面：逐子一行（空位不产单元）+ 行棋方／着法／将军读数。 */
    private static void exportChess(Source source, JSONObject state, List<String> lines, Counter counter) {
        JSONArray board = state.optJSONArray("board");
        if (board != null) {
            for (int r = 0; r < board.length(); r++) {
                JSONArray row = board.optJSONArray(r);
                if (row == null) {
                    continue;
                }
                for (int c = 0; c < row.length(); c++) {
                    String piece = row.isNull(c) ? "" : row.optString(c, "");
                    if (piece.isEmpty()) {
                        continue;
                    }
                    String side = CHESS_SIDE.get(piece.substring(0, 1));
                    String body = piece.substring(1);
                    emit(new UnitLine()
                            .put("source_file", source.name)
                            .put("unit_id", qualified(source.name, "state.board[" + r + "][" + c + "]"))
                            .put("unit_kind", "board_cell")
                            .put("semantic_label", (side == null ? "未知阵营" : side) + "棋子 " + body)
                            .put("value", piece)
                            .put("coords", new int[]{r, c}), lines, counter);
                }
            }
        }
        for (String key : new String[]{"turn", "last_move", "in_check", "captured_recently"}) {
            if (!state.has(key) || state.isNull(key)) {
                continue;
            }
            emit(new UnitLine()
                    .put("source_file", source.name)
                    .put("unit_id", qualified(source.name, "state." + key))
                    .put("unit_kind", "state_reading")
                    .put("semantic_label", CHESS_STATE_LABEL.get(key))
                    .put("value", typed(state.opt(key))), lines, counter);
        }
    }

    /** 通用叶子遍历：门控配置与未知形状都走这里，一个叶子一行。 */
    private static void exportLeaves(Source source, JSONObject node, String path, List<String> lines,
                                     Counter counter) {
        // 只用 android.jar 的 org.json 与测试类路径上的 org.json jar 两边都有的 API：
        // keys() 返回 Iterator。keySet() 与静态 getNames() 在 android.jar 里都不存在，编译期就报找不到符号。
        Iterator<String> keys = node.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("_")) {
                continue;
            }
            String childPath = path.isEmpty() ? key : path + "." + key;
            Object value = node.opt(key);
            if (value instanceof JSONObject) {
                exportLeaves(source, (JSONObject) value, childPath, lines, counter);
                continue;
            }
            UnitLine line = new UnitLine()
                    .put("source_file", source.name)
                    .put("unit_id", qualified(source.name, childPath))
                    .put("unit_kind", source.name.toLowerCase(Locale.ROOT).contains("gating")
                            ? "gate_rule" : "raw_reading")
                    .put("semantic_label", labelFor(childPath, key));
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                int[] ints = intsOf(array);
                if (ints != null) {
                    line.put("coords", ints);
                    line.put("value", Arrays.toString(ints));
                } else {
                    StringBuilder joined = new StringBuilder();
                    for (int i = 0; i < array.length(); i++) {
                        if (i > 0) {
                            joined.append(" | ");
                        }
                        joined.append(scalar(array.opt(i)));
                    }
                    line.put("value", joined.toString());
                }
            } else if (value instanceof Number) {
                double numeric = ((Number) value).doubleValue();
                if (childPath.endsWith("high") || childPath.endsWith("mid") || childPath.endsWith("gate")
                        || childPath.endsWith("threshold") || childPath.endsWith("lowConfidence")) {
                    line.put("confidence", numeric);
                    line.put("value", Double.valueOf(numeric));
                } else {
                    line.put("value", Double.valueOf(numeric));
                }
            } else {
                line.put("value", typed(value));
            }
            emit(line, lines, counter);
        }
    }

    /* ==================== 行落地（脱敏 + 序列化） ==================== */

    private static void emit(UnitLine line, List<String> lines, Counter counter) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        Map<String, Object> sanitized = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> entry : line.fields.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                sanitized.put(entry.getKey(), desensitize((String) value, counts));
            } else {
                sanitized.put(entry.getKey(), value);
            }
        }
        String serialized = serialize(sanitized);
        serialized = desensitize(serialized, counts);
        int replacements = 0;
        for (Integer hit : counts.values()) {
            replacements += hit.intValue();
        }
        if (replacements > 0) {
            counter.add(replacements, counts);
            Map<String, Object> withCount = new LinkedHashMap<String, Object>(sanitized);
            withCount.put("desens_replacements", Integer.valueOf(replacements));
            withCount.put("desens_categories", counts.keySet().toString());
            serialized = serialize(withCount);
        }
        lines.add(serialized);
    }

    private static String serialize(Map<String, Object> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(jsonQuote(entry.getKey())).append(':');
            Object value = entry.getValue();
            if (value instanceof String) {
                sb.append(jsonQuote((String) value));
            } else if (value instanceof int[]) {
                sb.append('[');
                int[] ints = (int[]) value;
                for (int i = 0; i < ints.length; i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(ints[i]);
                }
                sb.append(']');
            } else if (value instanceof Number || value instanceof Boolean) {
                sb.append(value.toString());
            } else {
                sb.append(jsonQuote(String.valueOf(value)));
            }
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * 自带 JSON 字符串转义：不依赖 {@code JSONObject.quote}（Android 的 org.json 与测试类路径上的
     * org.json jar 在这个静态方法上并不可移植），转义规则就是 RFC 8259 那一套——引号、反斜杠、
     * 控制字符，行分隔符按反斜杠加 u 的四位十六进制写，保证一行就是一条记录。
     */
    private static String jsonQuote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '/':
                    sb.append("\\/");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\u2028':
                case '\u2029':
                    sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** 单元标识一律带源文件名前缀，跨文件不撞车。 */
    private static String qualified(String sourceName, String path) {
        return sourceName + "#" + path;
    }

    private static JSONObject parseObject(Source source) {
        if (source.text.trim().isEmpty()) {
            throw new IllegalArgumentException("source " + source.name + " is empty text");
        }
        try {
            return new JSONObject(source.text);
        } catch (JSONException notAnObject) {
            throw new IllegalArgumentException("source " + source.name + " is not a JSON object");
        }
    }

    private static String scalar(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) {
            return "";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return String.valueOf(value);
    }

    /** 保留 JSON 原生类型：数字写成数字、布尔写成布尔，其余按字符串（脱敏只作用在字符串上）。 */
    private static Object typed(Object value) {
        if (value == null || JSONObject.NULL.equals(value)) {
            return "";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return String.valueOf(value);
    }

    /** 只认「纯整数数组」，长度 1 到 8；其余返回 null 交给调用方按列表处理。 */
    private static int[] intsOf(JSONArray array) {
        if (array == null || array.length() == 0 || array.length() > 8) {
            return null;
        }
        int[] out = new int[array.length()];
        for (int i = 0; i < array.length(); i++) {
            Object value = array.opt(i);
            if (!(value instanceof Number)) {
                return null;
            }
            double numeric = ((Number) value).doubleValue();
            if (Double.compare(numeric, Math.floor(numeric)) != 0) {
                return null;
            }
            out[i] = (int) numeric;
        }
        return out;
    }

    private static String labelFor(String path, String key) {
        String gloss = GATING_LABEL.get(path);
        return gloss == null ? path : gloss;
    }

    private static Map<String, String> chessSide() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("b", "黑方");
        map.put("r", "红方");
        return map;
    }

    private static Map<String, String> match3Color() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("R", "红色");
        map.put("Y", "黄色");
        map.put("B", "蓝色");
        map.put("G", "绿色");
        map.put("O", "橙色");
        map.put("P", "紫色");
        return map;
    }

    private static final Map<String, String> CHESS_STATE_LABEL = chessStateLabel();
    private static final Map<String, String> MATCH3_STATE_LABEL = match3StateLabel();

    private static Map<String, String> chessStateLabel() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("turn", "行棋方");
        map.put("last_move", "上一步着法");
        map.put("in_check", "是否被将军");
        map.put("captured_recently", "最近被吃的子");
        return map;
    }

    private static Map<String, String> match3StateLabel() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("scene", "场景");
        map.put("moves_left", "剩余步数");
        map.put("score", "当前得分");
        map.put("time_limit_sec", "限时秒数");
        return map;
    }

    private static Map<String, String> gatingLabel() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("screen_type.high", "屏幕类型判定·高置信线");
        map.put("screen_type.mid", "屏幕类型判定·中置信线");
        map.put("has_modal.gate", "弹窗存在判定·门槛");
        map.put("sensitive.gate", "敏感界面·门槛");
        map.put("sensitive.localRuleOnly", "敏感界面·只走本地规则");
        map.put("icon_disambiguation.high", "图标消歧·高置信线");
        map.put("icon_disambiguation.note", "图标消歧·说明");
        map.put("high_risk.threshold", "高风险界面·确认门槛");
        map.put("high_risk.needConfirm", "高风险界面·需要确认");
        map.put("high_risk.scenes", "高风险界面·场景清单");
        map.put("abstain.alwaysInCandidates", "弃权·恒列候选");
        map.put("abstain.speech", "弃权·播报文案");
        map.put("escalate.lowConfidence", "升级·低置信线");
        map.put("escalate.toCloudLLM", "升级·是否转云端大模型");
        map.put("escalate.note", "升级·说明");
        return map;
    }

    /** 一条脱敏规则：类别 + 正则 + 占位符。 */
    private static final class Rule {
        final String category;
        final Pattern pattern;
        final String placeholder;

        Rule(String category, Pattern pattern) {
            this.category = category;
            this.pattern = pattern;
            this.placeholder = placeholderFor(category);
        }

        private static String placeholderFor(String category) {
            if (CATEGORY_USER_DIR.equals(category)) {
                return PLACEHOLDER_USER_DIR;
            }
            if (CATEGORY_REPO_ROOT.equals(category)) {
                return PLACEHOLDER_REPO_ROOT;
            }
            if (CATEGORY_CLOUD_DRIVE.equals(category)) {
                return PLACEHOLDER_CLOUD_DRIVE;
            }
            if (CATEGORY_DEVICE.equals(category)) {
                return PLACEHOLDER_DEVICE;
            }
            return PLACEHOLDER_CHAT_APP;
        }
    }

    /** 有序字段袋（保持写出顺序，导出结果才可复现）。 */
    private static final class UnitLine {
        final Map<String, Object> fields = new LinkedHashMap<String, Object>();

        UnitLine put(String key, Object value) {
            if (value != null) {
                fields.put(key, value);
            }
            return this;
        }
    }

    /**
     * 替换计数袋：总数 + 按规则类别（user_dir／repo_root／cloud_drive／device_brand／chat_app）的分项计数。
     * 一条语料可能同时踩多条规则，所以分项按类别累加，总数按次数累加。
     */
    private static final class Counter {
        int total;
        final Map<String, Integer> byCategory = new LinkedHashMap<String, Integer>();

        void add(int replacements, Map<String, Integer> counts) {
            total += replacements;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                Integer previous = byCategory.get(entry.getKey());
                byCategory.put(entry.getKey(), Integer.valueOf(
                        previous == null ? entry.getValue().intValue() : previous.intValue() + entry.getValue().intValue()));
            }
        }
    }
}
