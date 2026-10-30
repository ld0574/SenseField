package com.openkhub.sensefield;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;

/**
 * 标注导出器的纯 JVM 单测：期望值全部按任务 0 的<b>实测读数</b>写死（结构、条数、阈值、四份资产的
 * 字节数与 sha256），一律不从被测类的输出反推。
 *
 * <p>覆盖：逐行可 parse、必备字段齐、单元条数（并与测试里独立点数的源结构交叉对照）、导出可复现、
 * 坐标／边界与置信／档位的搬运与「源里没有就不许凭空造」、两条硬判据（身份扫描命中 0、四份资产 sha256
 * 与基线逐字相等）、身份扫描器自身的正对照（报警器会响）、脱敏替换计数，以及未知形状输入的兜底。
 *
 * <p>本类不 import 任何 {@code android.*}，不联网；读资产只用相对路径加候选目录，绝不写死绝对路径。
 */
public class JevAnnotationExporterTest {

    /** 与既有读资产的单测同一口径：工作目录可能是 android/、android/app/ 或仓库根。 */
    private static final String[] ASSET_DIRS = {
            "src/main/assets/jev",
            "app/src/main/assets/jev",
            "android/app/src/main/assets/jev",
    };

    private static final String[] ASSET_FILES = {
            "chess.json", "gating.json", "match3.json", "menu-axtree.json",
    };

    /** 任务 0 基线：四份既存资产的字节数，「只新增不改动」的第一把锁。 */
    private static final Map<String, Integer> BASELINE_BYTES = baselineBytes();

    /** 任务 0 基线：四份既存资产的 sha256（小写十六进制），「只新增不改动」的第二把锁。 */
    private static final Map<String, String> BASELINE_SHA256 = baselineSha256();

    /** 任务 0 实测结构点出来的单元条数：菜单 1 条归属 + 6 节点；象棋 32 子 + 3 读数；消消乐 64 格 + 2 候选 + 4 读数；门控 15 个叶子。 */
    private static final Map<String, Integer> EXPECTED_UNITS = expectedUnits();

    private static final int EXPECTED_TOTAL_UNITS = 127;

    /**
     * 身份扫描面（硬判据 1 用，独立于被测类的规则表手写）：用户目录（含数字段）、网盘目录名、
     * 手机品牌机型串、聊天软件名，外加「任何盘符绝对路径」兜底。扫描面写全，不许只扫一两个词。
     */
    private static final Pattern[] IDENTITY_SCAN = {
            // 用户目录：带盘符的 Users/home 段
            Pattern.compile("(?i)[a-z]:[\\\\/]{1,2}(?:users|home)[\\\\/]"),
            // 用户目录：无盘符的 /Users/xxx、\\Users\\xxx、/home/xxx 段
            Pattern.compile("(?i)[\\\\/](?:users|home)[\\\\/][^\\\\/\"'<>|\\s]+"),
            // 用户目录数字段（个人机器上最常见的用户名形态）
            Pattern.compile("(?i)(?:users|home)[\\\\/]{1,2}[0-9]{3,}"),
            Pattern.compile("(?i)appdata"),
            // 网盘目录名
            Pattern.compile("(?i)onedrive|skydrive|dropbox|google[ _-]?drive|gdrive|icloud|"
                    + "百度网盘|腾讯微云|天翼云盘|坚果云|阿里云盘|夸克网盘"),
            // 手机品牌／机型串
            Pattern.compile("(?i)huawei|honor|xiaomi|redmi|oppo|vivo|realme|oneplus|samsung|galaxy|"
                    + "motorola|nubia|meizu|google[ _-]?pixel|mate[ ]?[0-9]+|"
                    + "华为|荣耀|小米|红米|一加|三星|魅族|努比亚"),
            // 聊天软件名
            Pattern.compile("(?i)wechat|weixin|mqq|dingtalk|feishu|\\blark\\b|telegram|whatsapp|slack|"
                    + "messenger|\\bqq\\b|微信|钉钉|飞书|企业微信"),
            // 兜底：任何盘符开头的绝对路径
            Pattern.compile("(?i)[a-z]:[\\\\/]{1,2}"),
    };

    /* ==================== 逐行可 parse ==================== */

    @Test
    public void everyExportedLineIsASingleParsableJsonObject() throws Exception {
        JevAnnotationExporter.Result result = exportAllAssets();
        List<String> raw = nonEmptyLines(result.jsonLines);
        assertEquals("单元总数", EXPECTED_TOTAL_UNITS, raw.size());
        for (String line : raw) {
            assertFalse("语料里不许出现空行：" + line, line.trim().isEmpty());
            assertTrue("行首必须是 {：" + line, line.startsWith("{"));
            assertTrue("行尾必须是 }：" + line, line.endsWith("}"));
            JSONTokener tokener = new JSONTokener(line);
            Object value = tokener.nextValue();
            assertTrue("这一行必须整个是一个 JSON 对象：" + line, value instanceof JSONObject);
            assertFalse("一行里不许有第二个 JSON 值（半截语料）：" + line, tokener.more());
        }
    }

    /* ==================== 字段齐 ==================== */

    @Test
    public void everyLineCarriesTheFourMandatoryAnnotationFields() throws Exception {
        List<JSONObject> units = parsedUnits(exportAllAssets());
        Set<String> sources = new LinkedHashSet<String>(Arrays.asList(ASSET_FILES));
        for (JSONObject unit : units) {
            String id = unit.optString("unit_id", "<无标识>");
            assertTrue("缺来源文件字段：" + id, unit.has("source_file"));
            assertTrue("缺单元标识：" + id, unit.has("unit_id"));
            assertTrue("缺单元种类：" + id, unit.has("unit_kind"));
            assertTrue("缺语义标签：" + id, unit.has("semantic_label"));
            assertTrue("来源文件必须是四份既存读数之一：" + unit.getString("source_file"),
                    sources.contains(unit.getString("source_file")));
            assertFalse("单元标识不许是空串：" + id, unit.getString("unit_id").trim().isEmpty());
            assertFalse("语义标签不许是空串：" + id, unit.getString("semantic_label").trim().isEmpty());
        }
    }

    /* ==================== 单元条数：与测试内独立点数的源结构交叉对照 ==================== */

    @Test
    public void unitCountsMatchStructuresCountedIndependentlyFromSource() throws Exception {
        JevAnnotationExporter.Result result = exportAllAssets();
        assertEquals("逐源单元条数必须等于任务 0 实测结构点出来的写死值",
                EXPECTED_UNITS, result.unitsBySource);
        assertEquals("总单元条数", EXPECTED_TOTAL_UNITS, result.unitCount);

        JSONObject menu = new JSONObject(assetText("menu-axtree.json")).getJSONObject("state");
        assertEquals("菜单：1 条界面归属 + 逐节点",
                1 + menu.getJSONArray("nodes").length(), count(result, "menu-axtree.json"));

        JSONObject chess = new JSONObject(assetText("chess.json")).getJSONObject("state");
        int chessCells = 0;
        JSONArray rows = chess.getJSONArray("board");
        for (int r = 0; r < rows.length(); r++) {
            JSONArray row = rows.getJSONArray(r);
            for (int c = 0; c < row.length(); c++) {
                if (!row.isNull(c)) {
                    chessCells++;
                }
            }
        }
        assertEquals("象棋：逐子 + 行棋方／着法／将军三条读数（空位与被吃子为 null 不产单元）",
                chessCells + 3, count(result, "chess.json"));

        JSONObject match3 = new JSONObject(assetText("match3.json")).getJSONObject("state");
        int cells = 0;
        JSONArray boardRows = match3.getJSONObject("board").getJSONArray("rows");
        for (int r = 0; r < boardRows.length(); r++) {
            cells += boardRows.getJSONArray(r).length();
        }
        assertEquals("消消乐：逐格 + 候选交换 + 四条 HUD 读数",
                cells + match3.getJSONArray("potential_matches").length() + 4, count(result, "match3.json"));

        assertEquals("门控：每个非元注释叶子一行",
                leafCount(new JSONObject(assetText("gating.json")), 0), count(result, "gating.json"));
    }

    /* ==================== 可复现（键序固定，语料才可 diff） ==================== */

    @Test
    public void exportIsByteIdenticalAcrossTwoRuns() throws Exception {
        String first = exportAllAssets().jsonLines;
        String second = exportAllAssets().jsonLines;
        assertEquals("同一份输入必须导出逐字节相同的语料", first.length(), second.length());
        assertEquals("同一份输入必须导出逐字节相同的语料", first, second);
    }

    /* ==================== 坐标／边界：源里有才搬，源里没有不许造 ==================== */

    @Test
    public void boundsAndGridCoordsAreCarriedExactlyFromSource() throws Exception {
        Map<String, JSONObject> byId = byUnitId(parsedUnits(exportAllAssets()));

        JSONArray nodes = new JSONObject(assetText("menu-axtree.json"))
                .getJSONObject("state").getJSONArray("nodes");
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            JSONObject unit = byId.get("menu-axtree.json#state.nodes[" + i + "]." + node.getString("id"));
            assertTrue("无障碍节点必须带 bounds：" + node.getString("id"), unit.has("bounds"));
            JSONArray expected = node.getJSONArray("bounds");
            JSONArray actual = unit.getJSONArray("bounds");
            assertEquals("bounds 长度", expected.length(), actual.length());
            for (int k = 0; k < expected.length(); k++) {
                assertEquals("bounds[" + k + "] 必须与源逐值相等", expected.getInt(k), actual.getInt(k));
            }
        }

        JSONObject chessCell = byId.get("chess.json#state.board[0][0]");
        assertEquals("象棋左上角那子的坐标", Arrays.asList(0, 0), intsOf(chessCell.getJSONArray("coords")));
        assertEquals("象棋左上角那子的值", "b車", chessCell.getString("value"));
        JSONObject chessPao = byId.get("chess.json#state.board[2][1]");
        assertEquals("象棋炮位坐标", Arrays.asList(2, 1), intsOf(chessPao.getJSONArray("coords")));

        JSONObject move = byId.get("match3.json#state.potential_matches[0]");
        assertEquals("候选交换要同时带 from 与 to 四个数",
                Arrays.asList(2, 3, 3, 3), intsOf(move.getJSONArray("coords")));

        for (JSONObject unit : parsedUnits(exportAllAssets())) {
            if ("gate_rule".equals(unit.optString("unit_kind"))) {
                assertFalse("门控配置源里没有几何信息，不许造 bounds：" + unit.getString("unit_id"),
                        unit.has("bounds"));
                assertFalse("门控配置源里没有几何信息，不许造 coords：" + unit.getString("unit_id"),
                        unit.has("coords"));
            }
        }
    }

    /* ==================== 置信／档位 ==================== */

    @Test
    public void confidenceAndTierComeOnlyFromSourcesThatHaveThem() throws Exception {
        Map<String, JSONObject> byId = byUnitId(parsedUnits(exportAllAssets()));
        assertEquals("屏幕类型高线", 0.85d, byId.get("gating.json#screen_type.high").getDouble("confidence"), 0d);
        assertEquals("屏幕类型中线", 0.60d, byId.get("gating.json#screen_type.mid").getDouble("confidence"), 0d);
        assertEquals("弹窗门槛", 0.70d, byId.get("gating.json#has_modal.gate").getDouble("confidence"), 0d);
        assertEquals("敏感界面门槛", 0.60d, byId.get("gating.json#sensitive.gate").getDouble("confidence"), 0d);
        assertEquals("图标消歧高线", 0.60d,
                byId.get("gating.json#icon_disambiguation.high").getDouble("confidence"), 0d);
        assertEquals("高风险确认门槛", 0.95d,
                byId.get("gating.json#high_risk.threshold").getDouble("confidence"), 0d);
        assertEquals("升级低置信线", 0.50d,
                byId.get("gating.json#escalate.lowConfidence").getDouble("confidence"), 0d);
        assertEquals("布尔开关不许被当成置信度",
                false, byId.get("gating.json#sensitive.localRuleOnly").has("confidence"));

        assertEquals("候选交换收益档", "low",
                byId.get("match3.json#state.potential_matches[0]").getString("tier"));
        assertEquals("候选交换收益档", "medium",
                byId.get("match3.json#state.potential_matches[1]").getString("tier"));

        int confidenceUnits = 0;
        for (JSONObject unit : parsedUnits(exportAllAssets())) {
            if (unit.has("confidence")) {
                assertEquals("只有门控规则的数值叶子才带置信字段：" + unit.getString("unit_id"),
                        "gate_rule", unit.getString("unit_kind"));
                confidenceUnits++;
            }
            if ("accessibility_node".equals(unit.optString("unit_kind"))
                    || "board_cell".equals(unit.optString("unit_kind"))) {
                assertFalse("节点／棋子源里没有置信，不许造：" + unit.getString("unit_id"),
                        unit.has("confidence"));
                assertFalse("节点／棋子源里没有档位，不许造：" + unit.getString("unit_id"),
                        unit.has("tier"));
            }
        }
        assertEquals("带置信的单元条数（门控七个数值阈值叶子）", 7, confidenceUnits);
    }

    /* ==================== 硬判据 1：脱敏断言 ==================== */

    @Test
    public void identityScanOverTheWholeExportFindsZeroHits() throws Exception {
        JevAnnotationExporter.Result result = exportAllAssets();
        String corpus = result.jsonLines;
        assertEquals("干净语料不许触发任何一次脱敏替换", 0, result.replacementCount);
        assertEquals("干净语料的替换分类表必须是空表", 0, result.replacementsByCategory.size());
        assertFalse("干净语料不许出现 desens_replacements 字段", corpus.contains("desens_replacements"));
        assertEquals("身份扫描命中总数必须为 0（八个扫描面：带盘符用户目录、无盘符用户目录段、"
                        + "用户目录数字段、AppData、网盘目录名、手机品牌机型串、聊天软件名、盘符绝对路径）",
                0, identityHits(corpus));
        assertFalse("扫描面不许是空表", IDENTITY_SCAN.length == 0);
        assertEquals("扫描面条目数", 8, IDENTITY_SCAN.length);
    }

    /** 正对照：脱敏那条断言的报警器必须会响——每个扫描面各塞一个样本，命中数必须大于 0。 */
    @Test
    public void identityScanRingsOnEveryPlantedTokenCategory() {
        String[] planted = {
                "C:\\Users\\987654\\AppData\\Local\\Temp\\x",
                "/home/424242/work/repo",
                "OneDrive/Desktop/report.pptx",
                "HUAWEI Mate 60 Pro 1080x2400@420dpi",
                "小米 14 Ultra",
                "微信 / 钉钉 / QQ / DingTalk / Feishu",
                "D:\\work\\repo",
                "\\Users\\876543\\notes",
        };
        for (String text : planted) {
            assertTrue("报警器必须对这一类身份串响：" + text, identityHits(text) > 0);
        }
        assertEquals("干净样例不许误报", 0, identityHits("第 12 关 | 金币 1280 | 炮二平五 | 0.85"));
    }

    /* ==================== 替换计数成为可测读数 ==================== */

    @Test
    public void desensitizerReplacesEveryIdentityTokenAndCountsItByCategory() throws Exception {
        JSONObject leaves = new JSONObject();
        leaves.put("u1", "C:\\Users\\987654\\AppData\\Local\\Temp");
        leaves.put("u2", "/home/424242/projects");
        leaves.put("u3", "C:/Users/777777");
        leaves.put("u4", "users/987654/leak");
        leaves.put("c1", "OneDrive/Desktop");
        leaves.put("d1", "HUAWEI Mate 60 Pro");
        leaves.put("m1", "微信");
        leaves.put("m2", "钉钉");
        leaves.put("m3", "QQ");
        leaves.put("m4", "DingTalk");
        leaves.put("r1", "D:\\work\\SenseField");
        JSONObject fixture = new JSONObject();
        fixture.put("trace", leaves);

        JevAnnotationExporter.Result result =
                JevAnnotationExporter.export(new JevAnnotationExporter.Source("planted.json", fixture.toString()));

        assertEquals("十一个被污染的叶子各出一行", 11, result.unitCount);
        assertEquals("用户目录类替换次数（含没有前导分隔符的 users/home 数字段形状）", 4,
                result.replacementCount(
                        JevAnnotationExporter.CATEGORY_USER_DIR));
        assertEquals("网盘目录类替换次数", 1, result.replacementCount(
                JevAnnotationExporter.CATEGORY_CLOUD_DRIVE));
        assertEquals("手机品牌类替换次数（品牌名与机型号各一次）", 2, result.replacementCount(
                JevAnnotationExporter.CATEGORY_DEVICE));
        assertEquals("聊天软件类替换次数", 4, result.replacementCount(
                JevAnnotationExporter.CATEGORY_CHAT_APP));
        assertEquals("绝对路径类替换次数", 1, result.replacementCount(
                JevAnnotationExporter.CATEGORY_REPO_ROOT));
        assertEquals("替换总次数", 12, result.replacementCount);

        assertEquals("被替换后仍不许有任何身份串残留", 0, identityHits(result.jsonLines));
        assertTrue("必须落到占位符＜用户目录＞", result.jsonLines.contains(
                JevAnnotationExporter.PLACEHOLDER_USER_DIR));
        assertTrue("必须落到占位符＜网盘目录＞", result.jsonLines.contains(
                JevAnnotationExporter.PLACEHOLDER_CLOUD_DRIVE));
        assertTrue("必须落到占位符＜机型＞", result.jsonLines.contains(
                JevAnnotationExporter.PLACEHOLDER_DEVICE));
        assertTrue("必须落到占位符＜聊天软件＞", result.jsonLines.contains(
                JevAnnotationExporter.PLACEHOLDER_CHAT_APP));
        assertTrue("必须落到占位符＜仓库根＞", result.jsonLines.contains(
                JevAnnotationExporter.PLACEHOLDER_REPO_ROOT));

        int summedPerLine = 0;
        for (JSONObject unit : parsedUnits(result)) {
            if (unit.has("desens_replacements")) {
                summedPerLine += unit.getInt("desens_replacements");
            }
        }
        assertEquals("逐行 desens_replacements 之和必须等于总数", result.replacementCount, summedPerLine);
    }

    /* ==================== 硬判据 2：既存资产不许变 ==================== */

    @Test
    public void packagedJevAssetsStillMatchTaskZeroBaseline() throws Exception {
        for (String name : ASSET_FILES) {
            byte[] bytes = assetBytes(name);
            assertEquals(name + " 字节数必须与任务 0 基线相等",
                    BASELINE_BYTES.get(name).intValue(), bytes.length);
            assertEquals(name + " sha256 必须与任务 0 基线逐字相等",
                    BASELINE_SHA256.get(name), sha256Hex(bytes));
        }
    }

    /* ==================== 门控覆盖完整 ==================== */

    @Test
    public void gatingExportCoversEveryMeasuredLeafPathAndSkipsMetaNotes() throws Exception {
        List<JSONObject> units = new ArrayList<JSONObject>();
        for (JSONObject unit : parsedUnits(exportAllAssets())) {
            if ("gating.json".equals(unit.getString("source_file"))) {
                units.add(unit);
            }
        }
        Set<String> ids = new LinkedHashSet<String>();
        for (JSONObject unit : units) {
            ids.add(unit.getString("unit_id"));
        }
        String[] expected = {
                "gating.json#screen_type.high", "gating.json#screen_type.mid",
                "gating.json#has_modal.gate", "gating.json#sensitive.gate",
                "gating.json#sensitive.localRuleOnly", "gating.json#icon_disambiguation.high",
                "gating.json#icon_disambiguation.note", "gating.json#high_risk.threshold",
                "gating.json#high_risk.needConfirm", "gating.json#high_risk.scenes",
                "gating.json#abstain.alwaysInCandidates", "gating.json#abstain.speech",
                "gating.json#escalate.lowConfidence", "gating.json#escalate.toCloudLLM",
                "gating.json#escalate.note",
        };
        assertEquals("门控叶子条数", expected.length, ids.size());
        for (String path : expected) {
            assertTrue("门控叶子缺失：" + path, ids.contains(path));
        }
        for (String id : ids) {
            assertFalse("元注释键不许进语料：" + id, id.contains("_note"));
        }
    }

    /* ==================== 未知形状兜底 + 坏输入拒绝 ==================== */

    @Test
    public void unknownShapeFallsBackToLeafWalkAndBadInputIsRejected() throws Exception {
        JevAnnotationExporter.Result result = JevAnnotationExporter.export(
                new JevAnnotationExporter.Source("unknown.json", "{\"alpha\":{\"beta\":2,\"gamma\":\"delta\"}}"));
        assertEquals("未知形状按叶子摊平", 2, result.unitCount);
        Map<String, JSONObject> byId = byUnitId(parsedUnits(result));
        assertEquals("叶子路径要进单元标识", "raw_reading", byId.get("unknown.json#alpha.beta").getString("unit_kind"));
        assertEquals("数值叶子保留原生类型", 2, byId.get("unknown.json#alpha.beta").getInt("value"));
        assertEquals("字符串叶子原样搬运", "delta", byId.get("unknown.json#alpha.gamma").getString("value"));

        try {
            JevAnnotationExporter.export(new JevAnnotationExporter.Source("array.json", "[1,2,3]"));
            fail("非对象输入必须被拒绝，不许静默产出半截语料");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("array.json"));
        }
        try {
            JevAnnotationExporter.export(new JevAnnotationExporter.Source("blank.json", "   "));
            fail("空文本输入必须被拒绝");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("blank.json"));
        }
    }

    /* ==================== 辅助 ==================== */

    private static JevAnnotationExporter.Result exportAllAssets() throws Exception {
        List<JevAnnotationExporter.Source> sources = new ArrayList<JevAnnotationExporter.Source>();
        for (String name : ASSET_FILES) {
            sources.add(new JevAnnotationExporter.Source(name, assetText(name)));
        }
        return JevAnnotationExporter.export(sources);
    }

    private static List<JSONObject> parsedUnits(JevAnnotationExporter.Result result) throws Exception {
        List<JSONObject> out = new ArrayList<JSONObject>();
        for (String line : nonEmptyLines(result.jsonLines)) {
            out.add(new JSONObject(line));
        }
        return out;
    }

    private static Map<String, JSONObject> byUnitId(List<JSONObject> units) throws Exception {
        Map<String, JSONObject> map = new LinkedHashMap<String, JSONObject>();
        for (JSONObject unit : units) {
            String id = unit.getString("unit_id");
            assertFalse("单元标识不许重复：" + id, map.containsKey(id));
            map.put(id, unit);
        }
        return map;
    }

    private static int count(JevAnnotationExporter.Result result, String sourceFile) {
        Integer value = result.unitsBySource.get(sourceFile);
        return value == null ? -1 : value.intValue();
    }

    /** 逐元素取整数，避免依赖 JSONArray.toString() 的空格写法（不同 org.json 实现写法不一致）。 */
    private static List<Integer> intsOf(JSONArray array) throws Exception {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < array.length(); i++) {
            out.add(Integer.valueOf(array.getInt(i)));
        }
        return out;
    }

    private static List<String> nonEmptyLines(String jsonLines) {
        List<String> out = new ArrayList<String>();
        for (String line : jsonLines.split("\n")) {
            if (!line.trim().isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    /** 测试自己的身份扫描：逐条扫描面数命中次数，返回总数。 */
    private static int identityHits(String text) {
        int hits = 0;
        for (Pattern pattern : IDENTITY_SCAN) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                hits++;
            }
        }
        return hits;
    }

    /** 独立点数：非元注释的叶子条数（对象递归一层层数，数组算一个叶子）。 */
    private static int leafCount(JSONObject node, int ignored) {
        int total = 0;
        Iterator<String> keys = node.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.startsWith("_")) {
                continue;
            }
            Object value = node.opt(key);
            if (value instanceof JSONObject) {
                total += leafCount((JSONObject) value, 0);
            } else {
                total++;
            }
        }
        return total;
    }

    private static Path assetPath(String name) {
        for (String dir : ASSET_DIRS) {
            Path candidate = Path.of(dir, name);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return Path.of(ASSET_DIRS[0], name);
    }

    private static String assetText(String name) throws Exception {
        return new String(assetBytes(name), StandardCharsets.UTF_8);
    }

    private static byte[] assetBytes(String name) throws Exception {
        Path path = assetPath(name);
        assertTrue("读不到既存资产，工作目录异常：" + name, Files.isRegularFile(path));
        return Files.readAllBytes(path);
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    private static Map<String, Integer> baselineBytes() {
        Map<String, Integer> map = new LinkedHashMap<String, Integer>();
        map.put("chess.json", Integer.valueOf(1808));
        map.put("gating.json", Integer.valueOf(746));
        map.put("match3.json", Integer.valueOf(1809));
        map.put("menu-axtree.json", Integer.valueOf(1699));
        return map;
    }

    private static Map<String, String> baselineSha256() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("chess.json", "47ceee76ebac8694ee94b803efc2fdb78c5b467d9cbb9e5926b65112aa5059fa");
        map.put("gating.json", "8d39d9decbd0a4d2c5518f99d3d6087be72d56dce3303ae45188a13436124ed4");
        map.put("match3.json", "574d34163a618f2770ee957a289bcb281b5d4accf6d20d06045efda2a04a6a82");
        map.put("menu-axtree.json", "73cffa2fff3f91b7673bac853d8398b1f8840a25788bcf45ad06a75224a644f3");
        return map;
    }

    private static Map<String, Integer> expectedUnits() {
        Map<String, Integer> map = new LinkedHashMap<String, Integer>();
        map.put("chess.json", Integer.valueOf(35));
        map.put("gating.json", Integer.valueOf(15));
        map.put("match3.json", Integer.valueOf(70));
        map.put("menu-axtree.json", Integer.valueOf(7));
        return map;
    }
}
