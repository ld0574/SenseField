package com.openkhub.sensefield;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Stable game identity for the shared diagnostic screen and stored sessions. */
enum DiagnosticGame {
    HONOR("honor-of-kings", "王者荣耀"),
    MATCH3("happy-anipop", "开心消消乐"),
    UNKNOWN("", "未识别游戏");

    final String id;
    final String label;

    DiagnosticGame(String id, String label) { this.id = id; this.label = label; }

    static DiagnosticGame fromId(String id) {
        for (DiagnosticGame game : values()) if (!game.id.isEmpty() && game.id.equals(id)) return game;
        return UNKNOWN;
    }

    /** Read on the diagnostic IO worker. Never guess from the game currently selected in UI. */
    static DiagnosticGame read(File directory) {
        JSONObject metadata = readJson(new File(directory, "metadata.json"));
        if (metadata.has("game_id")) return fromId(metadata.optString("game_id"));
        for (String name : new String[]{"summary.json", "checkpoint.json"}) {
            DiagnosticGame known = inState(readJson(new File(directory, name)), 0);
            if (known != UNKNOWN) return known;
        }
        // Before game_id was stored, only Match3 used portrait-enabled recording.
        // This is a legacy recorder-mode flag, not an inference from screenshot orientation.
        Object portrait = metadata.opt("portrait_images_allowed");
        if ("sensefield.diagnostics".equals(metadata.optString("schema")) && portrait instanceof Boolean)
            return (Boolean) portrait ? MATCH3 : HONOR;
        return UNKNOWN;
    }

    private static DiagnosticGame inState(JSONObject value, int depth) {
        if (value == null || depth > 3) return UNKNOWN;
        DiagnosticGame game = fromId(value.optString("game_id"));
        if (game != UNKNOWN) return game;
        for (String key : new String[]{"last_state", "latest_state", "data"}) {
            game = inState(value.optJSONObject(key), depth + 1);
            if (game != UNKNOWN) return game;
        }
        return UNKNOWN;
    }

    private static JSONObject readJson(File file) {
        try {
            if (!file.isFile() || Files.isSymbolicLink(file.toPath()) || file.length() > 256 * 1024)
                return new JSONObject();
            return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception unavailable) { return new JSONObject(); }
    }
}
