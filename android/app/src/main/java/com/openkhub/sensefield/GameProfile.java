package com.openkhub.sensefield;

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
    static final String PREF_ALLOW_EXPERIMENTAL = "allow_experimental";
    static final boolean DEFAULT_ALLOW_EXPERIMENTAL = true;
    static final String PREF_VISION_MEMORY = "vision_memory";
    static final boolean DEFAULT_VISION_MEMORY = true;

    // Keep these names stable for the bundled 320px model. Imported profiles
    // may select another metadata/model pair, but only after its metadata has
    // passed the same tensor contract and the asset names have been checked.
    static final String DEFAULT_YOLOX_METADATA_ASSET =
            "minimap-yolox-nano-320.metadata.json";
    static final String DEFAULT_YOLOX_PARAM_ASSET = "minimap-yolox-nano-320.param";
    static final String DEFAULT_YOLOX_BIN_ASSET = "minimap-yolox-nano-320.bin";
    static final int YOLOX_MIN_INPUT_SIZE = 320;
    // Bound imported profiles so a malformed or experimental model cannot
    // request a multi-gigapixel tensor on a phone. Current candidates are
    // 320, 416 and 512; 1024 still leaves ample room for later experiments.
    static final int YOLOX_MAX_INPUT_SIZE = 1024;
    static final int YOLOX_INPUT_ALIGNMENT = 32;
    static final int[] YOLOX_STRIDES = {8, 16, 32};

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

    /** Runtime output contract read from the model metadata bundled with the APK. */
    static final class YoloxModelData {
        final int[] classKinds;
        final float[] classThresholds;

        YoloxModelData(int[] classKinds, float[] classThresholds) {
            this.classKinds = classKinds.clone();
            this.classThresholds = classThresholds.clone();
        }
    }

    private static final class YoloxBinding {
        final YoloxModelData model;
        final String paramAsset;
        final String binAsset;

        YoloxBinding(YoloxModelData model, String paramAsset, String binAsset) {
            this.model = model;
            this.paramAsset = paramAsset;
            this.binAsset = binAsset;
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
    final String yoloxMetadataAsset;
    final String yoloxParamAsset;
    final String yoloxBinAsset;
    /** Optional profile thresholds keyed by the metadata class name. */
    final JSONObject yoloxProfileClassThresholds;
    final int[] yoloxClassKinds;
    final float[] yoloxClassThresholds;
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
                        String minimapYoloxBinSha256, String yoloxMetadataAsset,
                        String yoloxParamAsset, String yoloxBinAsset,
                        JSONObject yoloxProfileClassThresholds,
                        int[] yoloxClassKinds,
                        float[] yoloxClassThresholds,
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
        this.yoloxMetadataAsset = yoloxMetadataAsset;
        this.yoloxParamAsset = yoloxParamAsset;
        this.yoloxBinAsset = yoloxBinAsset;
        this.yoloxProfileClassThresholds = yoloxProfileClassThresholds;
        this.yoloxClassKinds = yoloxClassKinds.clone();
        this.yoloxClassThresholds = yoloxClassThresholds.clone();
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
                YoloxBinding binding = verifyMinimapYoloxModelBinding(
                        context, profile.minimapYoloxBinSha256, profile.verified,
                        profile.yoloxInputSize, profile.yoloxMetadataAsset,
                        profile.yoloxParamAsset, profile.yoloxBinAsset,
                        profile.yoloxConfidence, profile.yoloxProfileClassThresholds);
                profile = profile.withYoloxModel(binding);
            }
            return profile;
        }
    }

    private GameProfile withYoloxModel(YoloxBinding binding) {
        return new GameProfile(name, version, verified, rois, flags, tuning, eventInts,
                minConfidence, minimapYolox, yoloxInputSize, yoloxConfidence, yoloxNms,
                minimapYoloxBinSha256, yoloxMetadataAsset, binding.paramAsset, binding.binAsset,
                yoloxProfileClassThresholds, binding.model.classKinds, binding.model.classThresholds,
                minimapLocatorEnabled, minimapLocatorFloats, minimapLocatorInts,
                minimapLocatorDescriptor, enemyTemplate, pingTemplate, playerLife);
    }

    private static void requireAsset(Context context, String name) throws IOException {
        try (InputStream ignored = context.getAssets().open(name)) {
            // A local imported profile can enable the experimental recognizer
            // only when its private model assets were included in the APK.
        } catch (IOException missing) {
            throw new IOException(
                    "当前配置启用了实验小地图识别，但 APK 未包含本地模型文件 " + name + "。"
                            + "请按团队本地运行文档放置模型后重新构建。",
                    missing);
        }
    }

    private static YoloxBinding verifyMinimapYoloxModelBinding(
            Context context, String profileSha256, boolean requireVerifiedModel,
            int profileInputSize, String metadataAsset, String profileParamAsset,
            String profileBinAsset, float fallbackConfidence, JSONObject profileClassThresholds)
            throws IOException {
        if (profileSha256 == null || !profileSha256.matches("[0-9a-f]{64}")) {
            throw new IOException("启用 minimap_yolox 的 GameProfile 必须在 "
                    + "models.minimap_yolox_bin_sha256 中绑定 64 位小写 SHA-256。");
        }

        final String metadataText;
        try (InputStream stream = context.getAssets().open(metadataAsset)) {
            metadataText = readText(stream);
        } catch (IOException missing) {
            throw new IOException("APK 缺少 " + metadataAsset + "，"
                    + "无法核对启用的 YOLOX 权重。", missing);
        }

        final JSONObject metadata;
        final String runtimeParamSha256;
        final String runtimeSha256;
        final String metadataParamAsset;
        final String metadataBinAsset;
        final int metadataInputSize;
        try {
            metadata = new JSONObject(metadataText);
            JSONObject runtime = metadata.optJSONObject("runtime");
            Object paramHash = runtime == null ? null : runtime.opt("param_sha256");
            Object value = runtime == null ? null : runtime.opt("bin_sha256");
            if (!(paramHash instanceof String) || !(value instanceof String)) {
                throw new JSONException(
                        "runtime.param_sha256/bin_sha256 are missing or are not strings");
            }
            runtimeParamSha256 = (String) paramHash;
            runtimeSha256 = (String) value;
            metadataParamAsset = modelAssetName(runtime, "param_asset", DEFAULT_YOLOX_PARAM_ASSET);
            metadataBinAsset = modelAssetName(runtime, "bin_asset", DEFAULT_YOLOX_BIN_ASSET);
            int[] input = jsonShape(metadata.getJSONArray("input"), "input");
            int[] output = jsonShape(metadata.getJSONArray("output"), "output");
            JSONArray classes = metadata.getJSONArray("classes");
            validateYoloxTensorContract(input, output, classes.length());
            validateYoloxStrides(metadata);
            metadataInputSize = input[2];
        } catch (JSONException malformed) {
            throw new IOException("APK 模型 metadata 格式无效，必须包含 "
                    + "runtime.param_sha256/bin_sha256、合法的 input/output tensor contract。",
                    malformed);
        }
        if (!runtimeParamSha256.matches("[0-9a-f]{64}") ||
                !runtimeSha256.matches("[0-9a-f]{64}")) {
            throw new IOException("APK 模型 metadata 的 runtime.param_sha256/bin_sha256 "
                    + "格式无效，必须是 64 位小写 SHA-256。");
        }
        if (metadataInputSize != profileInputSize) {
            throw new IOException("GameProfile 的 minimap_yolox_input_size 与模型 metadata input 不一致；"
                    + "请导入与当前模型匹配的 profile。" );
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
        try {
            String paramAsset = profileParamAsset == null ? metadataParamAsset : profileParamAsset;
            String binAsset = profileBinAsset == null ? metadataBinAsset : profileBinAsset;
            requireAsset(context, paramAsset);
            requireAsset(context, binAsset);
            if (!runtimeParamSha256.equals(sha256Asset(context, paramAsset)) ||
                    !runtimeSha256.equals(sha256Asset(context, binAsset))) {
                throw new IOException("APK 中的 YOLOX param/bin 文件与所选 metadata 的 "
                        + "SHA-256 不一致。");
            }
            return new YoloxBinding(
                    parseYoloxModelData(metadata, fallbackConfidence, profileClassThresholds),
                    paramAsset, binAsset);
        } catch (JSONException malformed) {
            throw new IOException("APK 模型 metadata 的 classes 或分类阈值无效。", malformed);
        }
    }

    /**
     * Convert metadata class names into the stable native entity kinds. The optional
     * postprocess.confidence_by_class object overrides the profile threshold per class.
     */
    static YoloxModelData parseYoloxModelData(JSONObject metadata, float fallbackConfidence)
            throws JSONException {
        return parseYoloxModelData(metadata, fallbackConfidence, null);
    }

    /** Parse metadata while applying a profile's optional per-class fallback. */
    static YoloxModelData parseYoloxModelData(JSONObject metadata, float fallbackConfidence,
                                              JSONObject profileClassThresholds)
            throws JSONException {
        if (!finiteRange(fallbackConfidence, 0f, 1f))
            throw new JSONException("Invalid fallback confidence");
        JSONArray classes = metadata.getJSONArray("classes");
        if (classes.length() < 1 || classes.length() > 8)
            throw new JSONException("classes must contain 1 to 8 entries");
        validateYoloxTensorContract(
                jsonShape(metadata.getJSONArray("input"), "input"),
                jsonShape(metadata.getJSONArray("output"), "output"),
                classes.length());
        validateYoloxStrides(metadata);
        JSONObject postprocess = null;
        if (metadata.has("postprocess") && !metadata.isNull("postprocess")) {
            Object rawPostprocess = metadata.get("postprocess");
            if (!(rawPostprocess instanceof JSONObject)) {
                throw new JSONException("postprocess must be an object");
            }
            postprocess = (JSONObject) rawPostprocess;
        }
        JSONObject perClass = null;
        Object scalarThreshold = null;
        if (postprocess != null) {
            // Accept the early local spelling while all produced metadata uses
            // confidence_by_class. A present but non-object value is malformed;
            // silently falling back would change a class threshold at runtime.
            String thresholdKey = postprocess.has("confidence_by_class")
                    ? "confidence_by_class"
                    : postprocess.has("class_confidence") ? "class_confidence" : null;
            if (thresholdKey != null) {
                Object value = postprocess.get(thresholdKey);
                if (!(value instanceof JSONObject)) {
                    throw new JSONException(thresholdKey + " must be an object");
                }
                perClass = (JSONObject) value;
            } else if (postprocess.has("confidence") &&
                    !postprocess.isNull("confidence")) {
                scalarThreshold = postprocess.get("confidence");
            }
        }
        // A few early sidecars wrote these fields at the metadata root. Keep
        // the same precedence as the training/replay tools for imported
        // profiles: postprocess fields, then root fields, then profile.
        if (perClass == null && scalarThreshold == null &&
                metadata.has("confidence_by_class") &&
                !metadata.isNull("confidence_by_class")) {
            Object value = metadata.get("confidence_by_class");
            if (!(value instanceof JSONObject)) {
                throw new JSONException("confidence_by_class must be an object");
            }
            perClass = (JSONObject) value;
        }
        if (perClass == null && scalarThreshold == null && metadata.has("confidence") &&
                !metadata.isNull("confidence")) {
            scalarThreshold = metadata.get("confidence");
        }
        String[] names = new String[classes.length()];
        float[] metadataClassThresholds = perClass == null
                ? null : new float[classes.length()];
        float[] profileThresholds = profileClassThresholds == null
                ? null : new float[classes.length()];
        Set<String> classNames = new HashSet<>();
        for (int index = 0; index < classes.length(); index++) {
            String name = classes.getString(index);
            names[index] = name;
            classNames.add(name);
            if (perClass != null) {
                metadataClassThresholds[index] = yoloxThreshold(perClass.get(name), name);
            }
            if (profileClassThresholds != null) {
                profileThresholds[index] = yoloxThreshold(
                        profileClassThresholds.get(name), name);
            }
        }
        if (perClass != null) {
            Set<String> thresholdNames = new HashSet<>();
            java.util.Iterator<String> keys = perClass.keys();
            while (keys.hasNext()) thresholdNames.add(keys.next());
            if (!thresholdNames.equals(classNames))
                throw new JSONException(
                        "confidence_by_class keys must exactly match classes"
                );
        }
        if (profileClassThresholds != null) {
            Set<String> profileNames = new HashSet<>();
            java.util.Iterator<String> keys = profileClassThresholds.keys();
            while (keys.hasNext()) profileNames.add(keys.next());
            if (!profileNames.equals(classNames))
                throw new JSONException(
                        "minimap_yolox_confidence_by_class keys must exactly match classes"
                );
        }
        Float metadataScalarThreshold = scalarThreshold == null
                ? null : yoloxThreshold(scalarThreshold, "confidence");
        float[] thresholds = selectYoloxThresholds(
                classes.length(), fallbackConfidence, metadataScalarThreshold,
                metadataClassThresholds, profileThresholds);
        return yoloxModelData(names, thresholds);
    }

    /** Pure Java precedence rule shared by the JSON parser and JVM tests. */
    static float[] selectYoloxThresholds(int classCount, float fallbackConfidence,
                                         Float metadataScalarThreshold,
                                         float[] metadataClassThresholds,
                                         float[] profileClassThresholds)
            throws JSONException {
        if (classCount < 1 || classCount > 8 ||
                !finiteRange(fallbackConfidence, 0f, 1f)) {
            throw new JSONException("Invalid YOLOX threshold contract");
        }
        if (metadataClassThresholds != null &&
                metadataClassThresholds.length != classCount) {
            throw new JSONException("Metadata class thresholds do not match classes");
        }
        if (profileClassThresholds != null &&
                profileClassThresholds.length != classCount) {
            throw new JSONException("Profile class thresholds do not match classes");
        }
        if (metadataScalarThreshold != null &&
                !finiteRange(metadataScalarThreshold, 0f, 1f)) {
            throw new JSONException("Invalid metadata confidence");
        }
        float[] selected = new float[classCount];
        for (int index = 0; index < classCount; index++) {
            float value = metadataClassThresholds != null
                    ? metadataClassThresholds[index]
                    : metadataScalarThreshold != null
                    ? metadataScalarThreshold
                    : profileClassThresholds != null
                    ? profileClassThresholds[index] : fallbackConfidence;
            if (!finiteRange(value, 0f, 1f)) {
                throw new JSONException("Invalid YOLOX class confidence");
            }
            selected[index] = value;
        }
        return selected;
    }

    /** Parse one JSON-compatible numeric threshold without Android framework state. */
    static float yoloxThreshold(Object value, String name) throws JSONException {
        if (!(value instanceof Number))
            throw new JSONException("Invalid YOLOX threshold for " + name);
        double threshold = ((Number) value).doubleValue();
        if (!Double.isFinite(threshold) || threshold < 0.0 || threshold > 1.0)
            throw new JSONException("Invalid YOLOX threshold for " + name);
        return (float) threshold;
    }

    private static int[] jsonShape(JSONArray shape, String label) throws JSONException {
        int[] result = new int[shape.length()];
        for (int index = 0; index < shape.length(); index++) {
            Object value = shape.get(index);
            if (!(value instanceof Number))
                throw new JSONException(label + " shape must contain integers");
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number) || number != Math.rint(number) ||
                    number < 0 || number > Integer.MAX_VALUE)
                throw new JSONException(label + " shape must contain integers");
            result[index] = (int) number;
        }
        return result;
    }

    private static String modelAssetName(JSONObject object, String key, String fallback)
            throws JSONException {
        if (object == null || !object.has(key) || object.isNull(key)) return fallback;
        Object value = object.get(key);
        if (!(value instanceof String))
            throw new JSONException(key + " must be a string");
        String name = (String) value;
        if (!validModelAssetName(name))
            throw new JSONException(key + " is not a safe APK asset name");
        return name;
    }

    private static String optionalModelAssetName(JSONObject object, String key)
            throws JSONException {
        if (object == null || !object.has(key) || object.isNull(key)) return null;
        return modelAssetName(object, key, null);
    }

    /** Keep model paths inside the APK asset namespace; no filesystem paths or traversal. */
    static boolean validModelAssetName(String name) {
        if (name == null || name.isEmpty() || name.length() > 240 ||
                name.charAt(0) == '/' || name.charAt(name.length() - 1) == '/') return false;
        String[] parts = name.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) return false;
            for (int index = 0; index < part.length(); index++) {
                char character = part.charAt(index);
                if (!(character >= 'a' && character <= 'z') &&
                        !(character >= 'A' && character <= 'Z') &&
                        !(character >= '0' && character <= '9') &&
                        character != '.' && character != '_' && character != '-') {
                    return false;
                }
            }
        }
        return true;
    }

    private static void validateYoloxStrides(JSONObject metadata) throws JSONException {
        JSONObject postprocess = metadata.optJSONObject("postprocess");
        if (postprocess == null || !postprocess.has("strides") ||
                postprocess.isNull("strides")) return;
        JSONArray strides = postprocess.getJSONArray("strides");
        int[] values = new int[strides.length()];
        for (int index = 0; index < strides.length(); index++) {
            Object value = strides.get(index);
            if (!(value instanceof Number) ||
                    ((Number) value).doubleValue() != ((Number) value).intValue()) {
                throw new JSONException("YOLOX postprocess.strides must be [8,16,32]");
            }
            values[index] = ((Number) value).intValue();
        }
        validateYoloxStrides(values);
    }

    /** Pure Java stride-contract guard for local JVM tests. */
    static void validateYoloxStrides(int[] strides) throws JSONException {
        if (!java.util.Arrays.equals(strides, YOLOX_STRIDES))
            throw new JSONException("YOLOX postprocess.strides must be [8,16,32]");
    }

    /** Pure Java tensor-contract guard for unit tests and the JSON parser. */
    static void validateYoloxTensorContract(int[] input, int[] output, int classCount)
            throws JSONException {
        if (input == null || output == null || classCount < 1 || classCount > 8)
            throw new JSONException("Invalid YOLOX tensor contract");
        if (input.length != 4 || input[0] != 1 || input[1] != 3 ||
                input[2] != input[3] || !validYoloxInputSize(input[2])) {
            throw new JSONException("YOLOX input shape must be [1,3,S,S], where S is a "
                    + "square 32-pixel multiple at least 320");
        }
        int expectedAnchors = yoloxAnchorCount(input[2]);
        if (output.length != 3 || output[0] != 1 || output[1] != expectedAnchors ||
                output[2] != 5 + classCount) {
            throw new JSONException("YOLOX output shape must be [1," + expectedAnchors
                    + ",5+C]");
        }
    }

    static boolean validYoloxInputSize(int inputSize) {
        return inputSize >= YOLOX_MIN_INPUT_SIZE && inputSize <= YOLOX_MAX_INPUT_SIZE &&
                inputSize % YOLOX_INPUT_ALIGNMENT == 0;
    }

    static int yoloxAnchorCount(int inputSize) throws JSONException {
        if (!validYoloxInputSize(inputSize))
            throw new JSONException("Invalid YOLOX input size");
        long anchors = 0;
        for (int stride : YOLOX_STRIDES) {
            int grid = inputSize / stride;
            anchors += (long) grid * grid;
        }
        if (anchors > Integer.MAX_VALUE)
            throw new JSONException("YOLOX anchor count is too large");
        return (int) anchors;
    }

    /** Pure-Java portion of the metadata contract, kept directly unit-testable. */
    static YoloxModelData yoloxModelData(String[] classes, float[] thresholds)
            throws JSONException {
        if (classes == null || thresholds == null || classes.length < 1 ||
                classes.length > 8 || thresholds.length != classes.length)
            throw new JSONException("Invalid YOLOX class arrays");
        int[] kinds = new int[classes.length];
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < classes.length; index++) {
            String name = classes[index];
            if (name == null || !seen.add(name))
                throw new JSONException("Duplicate or missing YOLOX class " + name);
            if ("minimap_enemy".equals(name)) kinds[index] = 2;
            else if ("minimap_player".equals(name)) kinds[index] = 6;
            else throw new JSONException("Unsupported YOLOX class " + name);
            if (!finiteRange(thresholds[index], 0f, 1f))
                throw new JSONException("Invalid YOLOX threshold for " + name);
        }
        return new YoloxModelData(kinds, thresholds);
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
        boolean enabled = verified || preferences.getBoolean(
                PREF_ALLOW_EXPERIMENTAL, DEFAULT_ALLOW_EXPERIMENTAL);
        JSONObject detectors = data.getJSONObject("detectors");
        JSONObject models = data.optJSONObject("models");
        String minimapYoloxBinSha256 = models == null ? null
                : models.optString("minimap_yolox_bin_sha256", null);
        String yoloxMetadataAsset = modelAssetName(
                models, "minimap_yolox_metadata_asset", DEFAULT_YOLOX_METADATA_ASSET);
        String yoloxParamAsset = optionalModelAssetName(
                models, "minimap_yolox_param_asset");
        String yoloxBinAsset = optionalModelAssetName(
                models, "minimap_yolox_bin_asset");
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
                !validYoloxInputSize(yoloxInputSize) ||
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
                yoloxConfidence, yoloxNms, minimapYoloxBinSha256, yoloxMetadataAsset,
                yoloxParamAsset, yoloxBinAsset,
                parseYoloxProfileClassThresholds(thresholds),
                new int[] {2}, new float[] {yoloxConfidence},
                useMinimapLocator,
                locator == null ? new float[0] : locator.floats,
                locator == null ? new int[0] : locator.ints,
                locator == null ? null : locator.descriptor,
                enemy, ping, playerLife);
    }

    /** Validate the optional profile-side per-class confidence mapping. */
    private static JSONObject parseYoloxProfileClassThresholds(JSONObject thresholds)
            throws JSONException {
        final String key = "minimap_yolox_confidence_by_class";
        if (!thresholds.has(key) || thresholds.isNull(key)) return null;
        Object value = thresholds.get(key);
        if (!(value instanceof JSONObject)) {
            throw new JSONException(key + " must be an object");
        }
        JSONObject result = (JSONObject) value;
        java.util.Iterator<String> keys = result.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            yoloxThreshold(result.get(name), name);
        }
        return result;
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

    private static String sha256Asset(Context context, String assetName) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
        try (InputStream stream = context.getAssets().open(assetName)) {
            byte[] buffer = new byte[64 * 1024];
            for (int count; (count = stream.read(buffer)) != -1; ) {
                digest.update(buffer, 0, count);
            }
        }
        byte[] value = digest.digest();
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] encoded = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            int item = value[i] & 255;
            encoded[i * 2] = alphabet[item >>> 4];
            encoded[i * 2 + 1] = alphabet[item & 15];
        }
        return new String(encoded);
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
