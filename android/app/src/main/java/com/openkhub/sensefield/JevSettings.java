package com.openkhub.sensefield;

import android.content.Context;

import java.util.LinkedHashMap;
import java.util.Map;

/** 判定层设置（SharedPreferences：mapassist_settings，键前缀 jev_）。默认全部关闭。 */
final class JevSettings {
    static final String PREF_ENABLED = "jev_enabled";          // 总开关，默认 false
    static final String PREF_CHANNEL = "jev_channel";         // edgeone | openrouter | local
    static final String PREF_API_KEY = "jev_api_key";
    static final String PREF_LOCAL_BASE_URL = "jev_local_base_url";

    static final String CHANNEL_EDGEONE = "edgeone";
    static final String CHANNEL_OPENROUTER = "openrouter";
    static final String CHANNEL_LOCAL = "local";

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

    /** 默认五问（与创作工具/specs §10 一致；criteria 英文更稳，state 保留真实中文）。 */
    static Map<String, JevQuestion> defaultQuestions() {
        Map<String, JevQuestion> q = new LinkedHashMap<>();
        q.put("next_action", new JevQuestion("next_action", JevQuestion.TYPE_CHOICE,
                "Given the serialized game state, what should the assistant do next for the visually impaired player?",
                presetCriteria()));
        q.put("danger_level", new JevQuestion("danger_level", JevQuestion.TYPE_SCORE,
                "How dangerous is the current situation for the player (timer pressure, about to lose, piece under attack)?",
                new String[]{"Safe", "Caution: timer or obstacle approaching", "Danger: about to fail", "Emergency: act now"}));
        q.put("should_interrupt", new JevQuestion("should_interrupt", JevQuestion.TYPE_NOUL,
                "Must the assistant speak immediately, even if that interrupts the current announcement?",
                booleanCriteria("Must speak now even if it interrupts current speech",
                        "Can wait until current announcement finishes")));
        q.put("announce_rank", new JevQuestion("announce_rank", JevQuestion.TYPE_SCORE,
                "How important is it to announce the latest change right now?",
                new String[]{"Do not announce", "Optional", "Normal info", "Important", "Must announce immediately"}));
        q.put("scene_changed", new JevQuestion("scene_changed", JevQuestion.TYPE_NOUL,
                "Does the current state represent a clearly different scene or screen from the previous frame?",
                booleanCriteria("Scene differs clearly from previous frame", "Same scene continues")));
        return q;
    }

    private static Map<String, String> presetCriteria() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("announce_match", "Report the position of a possible match (or best move)");
        m.put("scan_board", "Read the board or screen row by row");
        m.put("read_menu", "Read menu or dialog items so the player can choose");
        m.put("wait", "Nothing changed or nothing actionable, keep waiting");
        return m;
    }

    private static Map<String, String> booleanCriteria(String yes, String no) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("true", yes);
        m.put("false", no);
        return m;
    }
}
