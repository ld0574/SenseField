package org.openrd.mapassist;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class GameProfile {
    static final String PREFS = "mapassist_settings";
    static final String IMPORTED_FILE = "game_profile.json";

    static final class TemplateData {
        final byte[] rgba;
        final int width;
        final int height;

        TemplateData(byte[] rgba, int width, int height) {
            this.rgba = rgba;
            this.width = width;
            this.height = height;
        }
    }

    final String name;
    final String version;
    final boolean verified;
    final float[] rois;
    final int[] flags;
    final float[] tuning;
    final int[] eventInts;
    final float minConfidence;
    final TemplateData enemyTemplate;
    final TemplateData pingTemplate;

    private GameProfile(String name, String version, boolean verified, float[] rois, int[] flags,
                        float[] tuning, int[] eventInts, float minConfidence,
                        TemplateData enemyTemplate, TemplateData pingTemplate) {
        this.name = name;
        this.version = version;
        this.verified = verified;
        this.rois = rois;
        this.flags = flags;
        this.tuning = tuning;
        this.eventInts = eventInts;
        this.minConfidence = minConfidence;
        this.enemyTemplate = enemyTemplate;
        this.pingTemplate = pingTemplate;
    }

    static SharedPreferences settings(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static GameProfile load(Context context) throws IOException, JSONException {
        File imported = new File(context.getFilesDir(), IMPORTED_FILE);
        try (InputStream stream = imported.exists()
                ? new FileInputStream(imported)
                : context.getAssets().open("profile.json")) {
            return parse(readText(stream), settings(context));
        }
    }

    static GameProfile parse(String text, SharedPreferences preferences) throws JSONException {
        JSONObject data = new JSONObject(text);
        if (data.getInt("schema_version") != 1) {
            throw new JSONException("Unsupported GameProfile schema");
        }
        JSONObject areas = data.getJSONObject("rois");
        float[] rois = new float[12];
        fillRect(rois, 0, areas.getJSONArray("minimap"));
        fillRect(rois, 4, areas.getJSONArray("ping_area"));
        fillRect(rois, 8, areas.getJSONArray("center_mask"));
        if (preferences.contains("center_percent")) {
            int centerPercent = preferences.getInt("center_percent", 30);
            float center = Math.max(0.10f, Math.min(0.60f, centerPercent / 100f));
            rois[8] = (1f - center) / 2f;
            rois[9] = (1f - center) / 2f;
            rois[10] = center;
            rois[11] = center;
        }

        boolean verified = data.optBoolean("verified", false);
        boolean enabled = verified || preferences.getBoolean("allow_experimental", false);
        JSONObject detectors = data.getJSONObject("detectors");
        JSONObject thresholds = data.getJSONObject("thresholds");
        int[] flags = new int[] {
            enabled && detectors.getBoolean("main_red_bar") ? 1 : 0,
            enabled && detectors.getBoolean("minimap_template") ? 1 : 0,
            enabled && detectors.optBoolean("minimap_red_ring", false) ? 1 : 0,
            enabled && detectors.getBoolean("danger_ping_template") ? 1 : 0,
            thresholds.getInt("red_min"),
        };
        float[] tuning = new float[] {
            (float) thresholds.getDouble("red_dominance"),
            (float) thresholds.getDouble("main_min_width_ratio"),
            (float) thresholds.getDouble("main_max_height_ratio"),
            (float) thresholds.getDouble("main_min_aspect"),
            (float) thresholds.getDouble("template_match"),
        };
        JSONObject events = data.getJSONObject("events");
        int[] eventInts = new int[] {
            events.getInt("max_observation_age_ms"),
            preferences.getInt("cue_gap_ms", events.getInt("min_global_gap_ms")),
            events.optInt("minimap_min_gap_ms", 5000),
            events.getInt("min_hits_in_three_frames"),
            events.getInt("reset_after_missing_frames"),
        };
        float minConfidence = (float) events.getDouble("min_confidence");
        if (flags[4] < 0 || flags[4] > 255 ||
                !finiteRange(tuning[0], 1f, 10f) ||
                !finiteRange(tuning[1], 0f, 1f) ||
                !finiteRange(tuning[2], 0f, 1f) ||
                !finiteRange(tuning[3], 1f, 100f) ||
                !finiteRange(tuning[4], 0f, 1f) ||
                !finiteRange(minConfidence, 0f, 1f) ||
                eventInts[0] < 0 || eventInts[0] > 5000 ||
                eventInts[1] < 0 || eventInts[1] > 60000 ||
                eventInts[2] < 0 || eventInts[2] > 60000 ||
                eventInts[3] < 1 || eventInts[3] > 3 ||
                eventInts[4] < 1 || eventInts[4] > 120) {
            throw new JSONException("Invalid detector threshold or event rule");
        }
        JSONObject templates = data.optJSONObject("templates_b64");
        TemplateData enemy = decodeTemplate(templates, "minimap_enemy");
        TemplateData ping = decodeTemplate(templates, "danger_ping");
        if (detectors.getBoolean("minimap_template") && enemy == null) {
            throw new JSONException("Enabled minimap detector needs embedded minimap_enemy PNG");
        }
        if (detectors.getBoolean("danger_ping_template") && ping == null) {
            throw new JSONException("Enabled ping detector needs embedded danger_ping PNG");
        }
        return new GameProfile(data.optString("name", "unnamed"),
                data.optString("profile_version", "unversioned"), verified, rois, flags,
                tuning, eventInts, minConfidence, enemy, ping);
    }

    private static boolean finiteRange(float value, float min, float max) {
        return Float.isFinite(value) && value >= min && value <= max;
    }

    private static void fillRect(float[] target, int offset, JSONArray values) throws JSONException {
        if (values.length() != 4) throw new JSONException("ROI needs four normalized numbers");
        for (int i = 0; i < 4; i++) {
            target[offset + i] = (float) values.getDouble(i);
            if (!Float.isFinite(target[offset + i]) ||
                    target[offset + i] < 0 || target[offset + i] > 1) {
                throw new JSONException("ROI outside normalized frame");
            }
        }
        if (target[offset] + target[offset + 2] > 1.001f ||
                target[offset + 1] + target[offset + 3] > 1.001f) {
            throw new JSONException("ROI outside frame");
        }
    }

    private static TemplateData decodeTemplate(JSONObject templates, String key) throws JSONException {
        if (templates == null || templates.isNull(key)) return null;
        String encoded = templates.optString(key, "");
        if (encoded.isEmpty() || encoded.length() > 2_000_000) return null;
        byte[] png;
        try {
            png = Base64.decode(encoded, Base64.DEFAULT);
        } catch (IllegalArgumentException error) {
            throw new JSONException("Invalid base64 PNG for " + key);
        }
        Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
        if (bitmap == null || bitmap.getWidth() > 256 || bitmap.getHeight() > 256) {
            throw new JSONException("Invalid or oversized PNG for " + key);
        }
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] argb = new int[width * height];
        bitmap.getPixels(argb, 0, width, 0, 0, width, height);
        bitmap.recycle();
        byte[] rgba = new byte[width * height * 4];
        for (int i = 0; i < argb.length; i++) {
            int pixel = argb[i];
            rgba[i * 4] = (byte) ((pixel >> 16) & 255);
            rgba[i * 4 + 1] = (byte) ((pixel >> 8) & 255);
            rgba[i * 4 + 2] = (byte) (pixel & 255);
            rgba[i * 4 + 3] = (byte) ((pixel >> 24) & 255);
        }
        return new TemplateData(rgba, width, height);
    }

    static String readText(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
