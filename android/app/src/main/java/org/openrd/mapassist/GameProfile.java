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
import java.util.HashSet;
import java.util.Set;

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

    static final class PlayerLifeData {
        final float[] roiAndThresholds;
        final int maxDhashDistance;
        final long[] hashes;
        final byte[] states;
        final byte[] luma;
        final byte[] chroma;

        PlayerLifeData(float[] roiAndThresholds, int maxDhashDistance, long[] hashes,
                       byte[] states, byte[] luma, byte[] chroma) {
            this.roiAndThresholds = roiAndThresholds;
            this.maxDhashDistance = maxDhashDistance;
            this.hashes = hashes;
            this.states = states;
            this.luma = luma;
            this.chroma = chroma;
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
    final String minimapYoloxBinSha256;
    /** Optional screen-layout calibration passed to the native minimap locator. */
    final boolean minimapLocatorEnabled;
    final float[] minimapLocatorFloats;
    final int[] minimapLocatorInts;
    final byte[] minimapLocatorDescriptor;
    final TemplateData enemyTemplate;
    final TemplateData pingTemplate;
    final PlayerLifeData playerLife;

    private GameProfile(String name, String version, boolean verified, float[] rois, int[] flags,
                        float[] tuning, int[] eventInts, float minConfidence,
                        boolean minimapYolox, int yoloxInputSize,
                        float yoloxConfidence, float yoloxNms,
                        String minimapYoloxBinSha256,
                        boolean minimapLocatorEnabled, float[] minimapLocatorFloats,
                        int[] minimapLocatorInts, byte[] minimapLocatorDescriptor,
                        TemplateData enemyTemplate, TemplateData pingTemplate,
                        PlayerLifeData playerLife) {
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
        this.minimapYoloxBinSha256 = minimapYoloxBinSha256;
        this.minimapLocatorEnabled = minimapLocatorEnabled;
        this.minimapLocatorFloats = minimapLocatorFloats;
        this.minimapLocatorInts = minimapLocatorInts;
        this.minimapLocatorDescriptor = minimapLocatorDescriptor;
        this.enemyTemplate = enemyTemplate;
        this.pingTemplate = pingTemplate;
        this.playerLife = playerLife;
    }

    static SharedPreferences settings(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static GameProfile load(Context context) throws IOException, JSONException {
        File imported = new File(context.getFilesDir(), IMPORTED_FILE);
        try (InputStream stream = imported.exists()
                ? new FileInputStream(imported)
                : context.getAssets().open("profile.json")) {
            GameProfile profile = parse(readText(stream), settings(context));
            if (profile.minimapYolox) {
                requireAsset(context, "minimap-yolox-nano-320.param");
                requireAsset(context, "minimap-yolox-nano-320.bin");
                verifyMinimapYoloxModelBinding(context, profile.minimapYoloxBinSha256,
                        profile.verified);
            }
            return profile;
        }
    }

    private static void requireAsset(Context context, String name) throws IOException {
        try (InputStream ignored = context.getAssets().open(name)) {
            // A local imported profile can enable the experimental recognizer
            // only when its private model assets were included in the APK.
        } catch (IOException missing) {
            throw new IOException(
                    "当前配置启用了实验小地图识别，但 APK 未包含本地模型文件 "
                            + "minimap-yolox-nano-320.param 和 minimap-yolox-nano-320.bin。"
                            + "请按团队本地运行文档放置模型后重新构建。",
                    missing);
        }
    }

    private static void verifyMinimapYoloxModelBinding(Context context, String profileSha256,
                                                       boolean requireVerifiedModel)
            throws IOException {
        if (profileSha256 == null || !profileSha256.matches("[0-9a-f]{64}")) {
            throw new IOException("启用 minimap_yolox 的 GameProfile 必须在 "
                    + "models.minimap_yolox_bin_sha256 中绑定 64 位小写 SHA-256。");
        }

        final String metadataText;
        try (InputStream stream = context.getAssets().open(
                "minimap-yolox-nano-320.metadata.json")) {
            metadataText = readText(stream);
        } catch (IOException missing) {
            throw new IOException("APK 缺少 minimap-yolox-nano-320.metadata.json，"
                    + "无法核对启用的 YOLOX 权重。", missing);
        }

        final JSONObject metadata;
        final String runtimeSha256;
        try {
            metadata = new JSONObject(metadataText);
            JSONObject runtime = metadata.optJSONObject("runtime");
            Object value = runtime == null ? null : runtime.opt("bin_sha256");
            if (!(value instanceof String)) {
                throw new JSONException("runtime.bin_sha256 is missing or is not a string");
            }
            runtimeSha256 = (String) value;
        } catch (JSONException malformed) {
            throw new IOException("APK 模型 metadata 格式无效，必须包含 "
                    + "runtime.bin_sha256。", malformed);
        }
        if (!runtimeSha256.matches("[0-9a-f]{64}")) {
            throw new IOException("APK 模型 metadata 的 runtime.bin_sha256 格式无效，"
                    + "必须是 64 位小写 SHA-256。");
        }
        if (requireVerifiedModel && !metadataAllowsVerifiedYolox(metadata)) {
            throw new IOException("GameProfile 将 YOLOX 标记为已验证，但 APK 模型 metadata "
                    + "尚未同时标记 verified=true 和 candidate.release_ready=true。"
                    + "请将该 profile 保持为实验配置，直到模型验证完成。");
        }
        if (!profileSha256.equals(runtimeSha256)) {
            throw new IOException("GameProfile 绑定的 ncnn bin SHA-256 与 APK 模型不一致；"
                    + "请导入与当前 APK 权重匹配的 profile。");
        }
    }

    private static boolean metadataAllowsVerifiedYolox(JSONObject metadata) {
        JSONObject candidate = metadata.optJSONObject("candidate");
        return metadataAllowsVerifiedYolox(metadata.optInt("schema_version", -1),
                metadata.optBoolean("verified", false), candidate != null,
                candidate != null && candidate.optBoolean("release_ready", false));
    }

    static boolean metadataAllowsVerifiedYolox(int schemaVersion, boolean metadataVerified,
                                               boolean hasCandidate, boolean releaseReady) {
        return schemaVersion == 1 && metadataVerified && hasCandidate && releaseReady;
    }

    static GameProfile parse(String text, SharedPreferences preferences) throws JSONException {
        JSONObject data = new JSONObject(text);
        if (data.getInt("schema_version") != 1) {
            throw new JSONException("Unsupported GameProfile schema");
        }
        JSONObject areas = data.getJSONObject("rois");
        // The final rectangle is optional so older profiles keep following the
        // detector crop, including a dynamically resolved locator crop.
        float[] rois = new float[16];
        fillRect(rois, 0, areas.getJSONArray("minimap"));
        fillRect(rois, 4, areas.getJSONArray("ping_area"));
        fillRect(rois, 8, areas.getJSONArray("center_mask"));
        if (areas.has("minimap_direction") && !areas.isNull("minimap_direction")) {
            fillRect(rois, 12, areas.getJSONArray("minimap_direction"));
            if (rois[14] <= 0 || rois[15] <= 0) {
                throw new JSONException("minimap_direction must have positive dimensions");
            }
        }
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
        JSONObject models = data.optJSONObject("models");
        String minimapYoloxBinSha256 = models == null ? null
                : models.optString("minimap_yolox_bin_sha256", null);
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
        PlayerLifeData playerLife = parsePlayerLife(data.optJSONObject("state_recognition"),
                enabled);
        return new GameProfile(data.optString("name", "unnamed"),
                data.optString("profile_version", "unversioned"), verified, rois, flags,
                tuning, eventInts, minConfidence, minimapYolox, yoloxInputSize,
                yoloxConfidence, yoloxNms, minimapYoloxBinSha256,
                useMinimapLocator,
                locator == null ? new float[0] : locator.floats,
                locator == null ? new int[0] : locator.ints,
                locator == null ? null : locator.descriptor,
                enemy, ping, playerLife);
    }

    private static PlayerLifeData parsePlayerLife(JSONObject stateRecognition, boolean allowed)
            throws JSONException {
        if (stateRecognition == null) return null;
        JSONObject data = stateRecognition.optJSONObject("player_life");
        if (data == null) return null;
        Object enabledValue = data.has("enabled") ? data.get("enabled") : Boolean.FALSE;
        if (!playerLifeEnabled(enabledValue, allowed)) return null;
        if (!"mapassist.player_life_signatures".equals(data.getString("schema")) ||
                data.getInt("schema_version") != 1) {
            throw new JSONException("Unsupported player-life signature schema");
        }
        float[] values = new float[7];
        JSONArray roi = data.getJSONArray("roi");
        if (roi.length() != 4) throw new JSONException("player_life.roi needs four numbers");
        for (int i = 0; i < 4; i++) values[i] = jsonFloat(roi, i, 0f, 1f);
        if (values[2] <= 0 || values[3] <= 0 || values[0] + values[2] > 1.001f ||
                values[1] + values[3] > 1.001f)
            throw new JSONException("Invalid player-life ROI");
        JSONObject thresholds = data.getJSONObject("thresholds");
        int maxDhash = requiredInt(thresholds, "max_dhash_distance", 0, 64);
        values[4] = requiredFloat(thresholds, "max_luma_mae", 0f, 1f);
        values[5] = requiredFloat(thresholds, "max_chroma_mae", 0f, 1f);
        values[6] = requiredFloat(thresholds, "min_state_margin", 0f, 1f);
        JSONArray dead = data.getJSONArray("dead");
        JSONArray alive = data.getJSONArray("alive");
        validatePlayerLifeCounts(dead.length(), alive.length());
        int count = dead.length() + alive.length();
        long[] hashes = new long[count];
        byte[] states = new byte[count];
        byte[] luma = new byte[count * 64];
        byte[] chroma = new byte[count * 32];
        Set<String> cropHashes = new HashSet<>();
        int offset = 0;
        for (int state = 1; state <= 2; state++) {
            JSONArray entries = state == 1 ? dead : alive;
            for (int i = 0; i < entries.length(); i++, offset++) {
                JSONObject signature = entries.getJSONObject(i);
                String hash = signature.getString("dhash64");
                if (!hash.matches("[0-9a-f]{16}"))
                    throw new JSONException("dhash64 must be 16 lowercase hexadecimal characters");
                try {
                    hashes[offset] = Long.parseUnsignedLong(hash, 16);
                } catch (NumberFormatException error) {
                    throw new JSONException("Invalid dhash64");
                }
                states[offset] = (byte) state;
                byte[] lumaEntry = decodeFeature(signature, "luma8x8_b64", 64);
                byte[] chromaEntry = decodeFeature(signature, "chroma4x4_b64", 32);
                System.arraycopy(lumaEntry, 0, luma, offset * 64, 64);
                System.arraycopy(chromaEntry, 0, chroma, offset * 32, 32);
                String cropSha = signature.getString("crop_sha256");
                validatePlayerLifeCropHash(cropHashes, cropSha);
            }
        }
        return new PlayerLifeData(values, maxDhash, hashes, states, luma, chroma);
    }

    /** Keep the explicit enabled flag strict while preserving the disabled lightweight form. */
    static boolean playerLifeEnabled(Object enabledValue, boolean allowed) throws JSONException {
        if (!(enabledValue instanceof Boolean))
            throw new JSONException("player_life.enabled must be true or false");
        return (Boolean) enabledValue && allowed;
    }

    static void validatePlayerLifeCounts(int deadCount, int aliveCount) throws JSONException {
        int total = deadCount + aliveCount;
        if (total < 6 || total > 64)
            throw new JSONException("player-life signatures need 6 to 64 entries");
        if (deadCount < 3 || aliveCount < 3)
            throw new JSONException("player-life dead and alive each need 3 signatures");
    }

    static void validatePlayerLifeCropHash(Set<String> cropHashes, String cropSha)
            throws JSONException {
        if (cropSha == null || !cropSha.matches("[0-9a-f]{64}"))
            throw new JSONException("crop_sha256 must be lowercase SHA-256");
        if (!cropHashes.add(cropSha))
            throw new JSONException("player-life signatures must have unique crop_sha256 values");
    }

    private static byte[] decodeFeature(JSONObject data, String key, int expected)
            throws JSONException {
        final byte[] decoded;
        try {
            decoded = Base64.decode(data.getString(key), Base64.NO_WRAP);
        } catch (IllegalArgumentException error) {
            throw new JSONException("Invalid base64 feature " + key);
        }
        if (decoded.length != expected)
            throw new JSONException(key + " must decode to " + expected + " bytes");
        return decoded;
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
