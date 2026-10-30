package com.openkhub.sensefield;

import android.content.Context;

import java.util.LinkedHashMap;
import java.util.Map;

/** 判定层设置（SharedPreferences：mapassist_settings，键前缀 jev_）。默认全部关闭。
 *  问题语言：默认简体中文（领导 2026-10-03 改判，覆盖此前「criteria 默认英文」的拍板），
 *  保留英文模式（jev_lang=en），便于与英文主训练语言的实测对照。 */
final class JevSettings {
    static final String PREF_ENABLED = "jev_enabled";          // 总开关，默认 false
    static final String PREF_CHANNEL = "jev_channel";         // edgeone | openrouter | local
    static final String PREF_API_KEY = "jev_api_key";
    static final String PREF_LOCAL_BASE_URL = "jev_local_base_url";
    static final String PREF_LANG = "jev_lang";               // zh（默认）| en

    static final String CHANNEL_EDGEONE = "edgeone";
    static final String CHANNEL_OPENROUTER = "openrouter";
    static final String CHANNEL_LOCAL = "local";
    static final String LANG_ZH = "zh";
    static final String LANG_EN = "en";

    private JevSettings() {
    }

    static boolean enabled(Context context) {
        return GameProfile.settings(context).getBoolean(PREF_ENABLED, false);
    }

    static void setEnabled(Context context, boolean value) {
        GameProfile.settings(context).edit().putBoolean(PREF_ENABLED, value).apply();
    }

    static String channel(Context context) {
        return GameProfile.settings(context).getString(PREF_CHANNEL, CHANNEL_OPENROUTER);
    }

    static void setChannel(Context context, String channel) {
        GameProfile.settings(context).edit().putString(PREF_CHANNEL, channel).apply();
    }

    static String apiKey(Context context) {
        return GameProfile.settings(context).getString(PREF_API_KEY, "");
    }

    static void setApiKey(Context context, String value) {
        GameProfile.settings(context).edit().putString(PREF_API_KEY, value == null ? "" : value.trim()).apply();
    }

    static String localBaseUrl(Context context) {
        return GameProfile.settings(context).getString(PREF_LOCAL_BASE_URL, "");
    }

    static void setLocalBaseUrl(Context context, String value) {
        GameProfile.settings(context).edit().putString(PREF_LOCAL_BASE_URL, value == null ? "" : value.trim()).apply();
    }

    static String lang(Context context) {
        return GameProfile.settings(context).getString(PREF_LANG, LANG_ZH);
    }

    static void setLang(Context context, String value) {
        GameProfile.settings(context).edit().putString(PREF_LANG, LANG_EN.equals(value) ? LANG_EN : LANG_ZH).apply();
    }

    /** 按当前设置构造 Client；设置不完整时返回 null（由调用方出声提示，不静默）。 */
    static JevClient clientOrNull(Context context) {
        if (!enabled(context)) return null;
        String channel = channel(context);
        String key = apiKey(context);
        if (!CHANNEL_LOCAL.equals(channel) && (key == null || key.trim().isEmpty())) return null;
        try {
            return JevClients.create(channel, key, localBaseUrl(context));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 默认五问（中文为默认；英文保留作对照模式）。 */
    static Map<String, JevQuestion> defaultQuestions(Context context) {
        return defaultQuestions(lang(context));
    }

    static Map<String, JevQuestion> defaultQuestions(String lang) {
        return LANG_EN.equals(lang) ? englishQuestions() : chineseQuestions();
    }

    /* ---------- 简体中文（默认） ---------- */
    private static Map<String, JevQuestion> chineseQuestions() {
        Map<String, JevQuestion> q = new LinkedHashMap<>();
        q.put("next_action", new JevQuestion("next_action", JevQuestion.TYPE_CHOICE,
                "根据序列化后的游戏状态，接下来应该为视障玩家做什么？",
                zhMap("announce_match", "播报可消除组合（或最佳走法）的位置",
                        "scan_board", "逐行读取棋盘或屏幕内容",
                        "read_menu", "朗读菜单或对话框选项供玩家选择",
                        "wait", "局面无变化或无可执行操作，继续等待")));
        q.put("danger_level", new JevQuestion("danger_level", JevQuestion.TYPE_SCORE,
                "当前局面对玩家的危险程度如何（计时压力、即将失败、棋子被攻击）？",
                new String[]{"安全", "注意：计时或障碍逼近", "危险：即将失败", "紧急：立即行动"}));
        q.put("should_interrupt", new JevQuestion("should_interrupt", JevQuestion.TYPE_NOUL,
                "是否必须立即播报，即使会打断当前正在进行的播报？",
                zhMap("true", "必须立即说，可以打断当前播报",
                        "false", "可以等当前播报结束后再说")));
        q.put("announce_rank", new JevQuestion("announce_rank", JevQuestion.TYPE_SCORE,
                "最新的变化现在有多重要、必须马上播报吗？",
                new String[]{"不必播报", "可说可不说", "普通信息", "重要信息", "必须立即播报"}));
        q.put("scene_changed", new JevQuestion("scene_changed", JevQuestion.TYPE_NOUL,
                "当前 state 相比上一帧是否明显切换了场景或界面？",
                zhMap("true", "与上一帧明显不同（新场景或新界面）",
                        "false", "同一场景延续")));
        return q;
    }

    /* ---------- English（保留模式，与英文主训练语言对照实测用） ---------- */
    private static Map<String, JevQuestion> englishQuestions() {
        Map<String, JevQuestion> q = new LinkedHashMap<>();
        q.put("next_action", new JevQuestion("next_action", JevQuestion.TYPE_CHOICE,
                "Given the serialized game state, what should the assistant do next for the visually impaired player?",
                zhMap("announce_match", "Report the position of a possible match (or best move)",
                        "scan_board", "Read the board or screen row by row",
                        "read_menu", "Read menu or dialog items so the player can choose",
                        "wait", "Nothing changed or nothing actionable, keep waiting")));
        q.put("danger_level", new JevQuestion("danger_level", JevQuestion.TYPE_SCORE,
                "How dangerous is the current situation for the player (timer pressure, about to lose, piece under attack)?",
                new String[]{"Safe", "Caution: timer or obstacle approaching", "Danger: about to fail", "Emergency: act now"}));
        q.put("should_interrupt", new JevQuestion("should_interrupt", JevQuestion.TYPE_NOUL,
                "Must the assistant speak immediately, even if that interrupts the current announcement?",
                zhMap("true", "Must speak now even if it interrupts current speech",
                        "false", "Can wait until current announcement finishes")));
        q.put("announce_rank", new JevQuestion("announce_rank", JevQuestion.TYPE_SCORE,
                "How important is it to announce the latest change right now?",
                new String[]{"Do not announce", "Optional", "Normal info", "Important", "Must announce immediately"}));
        q.put("scene_changed", new JevQuestion("scene_changed", JevQuestion.TYPE_NOUL,
                "Does the current state represent a clearly different scene or screen from the previous frame?",
                zhMap("true", "Scene differs clearly from previous frame", "false", "Same scene continues")));
        return q;
    }

    private static Map<String, String> zhMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }
}
