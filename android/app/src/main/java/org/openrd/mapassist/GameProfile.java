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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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
    final boolean minimapYolox;
    final int yoloxInputSize;
    final float yoloxConfidence;
    final float yoloxNms;
    /** Optional screen-layout calibration passed to the native minimap locator. */
    final boolean minimapLocatorEnabled;
    final float[] minimapLocatorFloats;
    final int[] minimapLocatorInts;
    final byte[] minimapLocatorDescriptor;
    final TemplateData enemyTemplate;
    final TemplateData pingTemplate;

    private GameProfile(String name, String version, boolean verified, float[] rois, int[] flags,
                        float[] tuning, int[] eventInts, float minConfidence,
                        boolean minimapYolox, int yoloxInputSize,
                        float yoloxConfidence, float yoloxNms,
                        boolean minimapLocatorEnabled, float[] minimapLocatorFloats,
                        int[] minimapLocatorInts, byte[] minimapLocatorDescriptor,
                        TemplateData enemyTemplate, TemplateData pingTemplate) {
        this.name = name;
        this.version = version;
        this.verified = verified;
        this.rois = rois;
        this.flags = flags;
        this.tuning = tuning;
        this.eventInts = eventInts;
        this.minConfidence = minConfidence;
        this.minimapYolox = minimapYolox;
        this.yoloxInputSize = yoloxInputSize;
        this.yoloxConfidence = yoloxConfidence;
        this.yoloxNms = yoloxNms;
        this.minimapLocatorEnabled = minimapLocatorEnabled;
        this.minimapLocatorFloats = minimapLocatorFloats;
        this.minimapLocatorInts = minimapLocatorInts;
        this.minimapLocatorDescriptor = minimapLocatorDescriptor;
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
        boolean minimapYolox = enabled && detectors.optBoolean("minimap_yolox", false);
        double yoloxInputValue = thresholds.optDouble("minimap_yolox_input_size", 320);
        int yoloxInputSize = (int) yoloxInputValue;
        float yoloxConfidence = (float) thresholds.optDouble(
                "minimap_yolox_confidence", 0.29);
        float yoloxNms = (float) thresholds.optDouble("minimap_yolox_nms", 0.5);
        JSONObject layout = null;
        if (data.has("layout") && !data.isNull("layout")) {
            layout = data.getJSONObject("layout");
        }
        MinimapLocatorData locator = parseMinimapLocator(layout);
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
                !Double.isFinite(yoloxInputValue) || yoloxInputValue != yoloxInputSize ||
                // The packaged pnnx graph has fixed 320x320 reshape dimensions.
                yoloxInputSize != 320 ||
                !finiteRange(yoloxConfidence, 0f, 1f) ||
                !finiteRange(yoloxNms, 0f, 1f) ||
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
        boolean useMinimapLocator = enabled && locator != null &&
                (minimapYolox || flags[1] != 0 || flags[2] != 0);
        return new GameProfile(data.optString("name", "unnamed"),
                data.optString("profile_version", "unversioned"), verified, rois, flags,
                tuning, eventInts, minConfidence, minimapYolox, yoloxInputSize,
                yoloxConfidence, yoloxNms,
                useMinimapLocator,
                locator == null ? new float[0] : locator.floats,
                locator == null ? new int[0] : locator.ints,
                locator == null ? null : locator.descriptor,
                enemy, ping);
    }

    private static final class MinimapLocatorData {
        final float[] floats;
        final int[] ints;
        final byte[] descriptor;

        MinimapLocatorData(float[] floats, int[] ints, byte[] descriptor) {
            this.floats = floats;
            this.ints = ints;
            this.descriptor = descriptor;
        }
    }

    /**
     * Parse the calibration emitted by calibrate_minimap_anchor.py.  The
     * locator is deliberately optional so old schema_version 1 profiles keep
     * using their fixed ROI.  Once the object is present, however, its
     * coordinate space and binary descriptor are checked before native code
     * sees them.
     */
    private static MinimapLocatorData parseMinimapLocator(JSONObject layout)
            throws JSONException {
        if (layout == null || !layout.has("minimap_locator") ||
                layout.isNull("minimap_locator")) return null;
        JSONObject locator = layout.optJSONObject("minimap_locator");
        if (locator == null) throw new JSONException("layout.minimap_locator must be an object");
        if (!"mapassist.minimap_locator".equals(locator.getString("schema")) ||
                locator.getInt("schema_version") != 1 || locator.getInt("version") != 1 ||
                !"short_edge".equals(locator.getString("coordinate_space"))) {
            throw new JSONException("Unsupported minimap locator schema");
        }

        JSONArray base = locator.getJSONArray("base_rect_short");
        if (base.length() != 4) throw new JSONException("base_rect_short needs four numbers");
        float[] values = new float[12];
        for (int i = 0; i < 4; i++) values[i] = jsonFloat(base, i, -Float.MAX_VALUE, Float.MAX_VALUE);
        if (!finiteRange(values[0], 0f, 4f) || values[0] >= 4f ||
                !finiteRange(values[1], 0f, 1f) || values[1] >= 1f ||
                !finiteRange(values[2], 0f, 2f) || values[2] <= 0.01f ||
                !finiteRange(values[3], 0f, 2f) || values[3] <= 0.01f ||
                values[0] + values[2] > 4.000001f ||
                values[1] + values[3] > 1.000001f) {
            throw new JSONException("Invalid minimap locator base rectangle");
        }
        values[4] = requiredFloat(locator, "search_radius_x_short", 0f, 1f);
        values[5] = requiredFloat(locator, "search_radius_y_short", 0f, 1f);
        values[6] = requiredFloat(locator, "position_step_short", 0.0005f, 0.25f);
        if (values[6] <= 0.0005f) throw new JSONException("Invalid locator position_step_short");
        values[7] = requiredFloat(locator, "min_scale", 0.5f, 2f);
        values[8] = requiredFloat(locator, "max_scale", 0.5f, 2f);
        if (values[7] > values[8]) throw new JSONException("min_scale exceeds max_scale");
        values[9] = requiredFloat(locator, "min_aspect", 0.5f, 2f);
        values[10] = requiredFloat(locator, "max_aspect", 0.5f, 2f);
        if (values[9] > values[10]) throw new JSONException("min_aspect exceeds max_aspect");
        values[11] = requiredFloat(locator, "min_score", -1f, 1f);

        int gridWidth = requiredInt(locator, "grid_width", 4, 64);
        int gridHeight = requiredInt(locator, "grid_height", 4, 64);
        int[] ints = new int[] {
                requiredInt(locator, "scale_steps", 1, 21),
                requiredInt(locator, "aspect_steps", 1, 21),
                gridWidth,
                gridHeight,
                requiredInt(locator, "confirm_frames", 1, 30),
                requiredInt(locator, "hold_frames", 0, 120),
                requiredInt(locator, "refresh_frames", 1, 600),
                locator.getBoolean("normalize_black_bars") ? 1 : 0,
                requiredInt(locator, "black_threshold", 0, 64),
                locator.optBoolean("preserve_base_roi", false) ? 1 : 0,
        };
        long xSteps = (long) Math.ceil(values[4] / values[6]);
        long ySteps = (long) Math.ceil(values[5] / values[6]);
        long candidates = (xSteps * 2 + 1) * (ySteps * 2 + 1) * ints[0] * ints[1];
        long descriptorSamples = candidates * gridWidth * gridHeight;
        if (candidates > 20_000 || descriptorSamples > 8_000_000) {
            throw new JSONException("Minimap locator search budget is too large");
        }
        String encoded = locator.getString("descriptor_b64");
        if (encoded.length() > 2_000_000) throw new JSONException("Locator descriptor is oversized");
        final byte[] descriptor;
        try {
            descriptor = Base64.decode(encoded, Base64.DEFAULT);
        } catch (IllegalArgumentException error) {
            throw new JSONException("Invalid minimap locator descriptor base64");
        }
        if (descriptor.length != gridWidth * gridHeight) {
            throw new JSONException("Locator descriptor length does not match grid");
        }
        JSONObject quantization = locator.getJSONObject("quantization");
        if (!"int8".equals(quantization.getString("dtype")) ||
                requiredFloat(quantization, "scale", 32f, 32f) != 32f ||
                requiredInt(quantization, "zero_point", 0, 0) != 0) {
            throw new JSONException("Unsupported locator descriptor quantization");
        }
        String expectedSha256 = locator.getString("descriptor_sha256");
        if (!expectedSha256.matches("[0-9a-fA-F]{64}") ||
                !expectedSha256.equalsIgnoreCase(sha256(descriptor))) {
            throw new JSONException("Locator descriptor SHA-256 mismatch");
        }
        boolean varied = false;
        for (int i = 1; i < descriptor.length; i++) {
            if (descriptor[i] != descriptor[0]) { varied = true; break; }
        }
        if (!varied) throw new JSONException("Locator descriptor must contain variation");
        return new MinimapLocatorData(values, ints, descriptor);
    }

    private static String sha256(byte[] value) throws JSONException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            char[] alphabet = "0123456789abcdef".toCharArray();
            char[] encoded = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int item = digest[i] & 255;
                encoded[i * 2] = alphabet[item >>> 4];
                encoded[i * 2 + 1] = alphabet[item & 15];
            }
            return new String(encoded);
        } catch (NoSuchAlgorithmException error) {
            throw new JSONException("SHA-256 is unavailable");
        }
    }

    private static float jsonFloat(JSONArray values, int index, float min, float max)
            throws JSONException {
        double value = values.getDouble(index);
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new JSONException("Invalid locator number");
        }
        return (float) value;
    }

    private static float requiredFloat(JSONObject object, String key, float min, float max)
            throws JSONException {
        double value = object.getDouble(key);
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new JSONException("Invalid locator " + key);
        }
        return (float) value;
    }

    private static int requiredInt(JSONObject object, String key, int min, int max)
            throws JSONException {
        double raw = object.getDouble(key);
        if (!Double.isFinite(raw) || raw != Math.rint(raw) || raw < min || raw > max) {
            throw new JSONException("Invalid locator " + key);
        }
        return (int) raw;
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
