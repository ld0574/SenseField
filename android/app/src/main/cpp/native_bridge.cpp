// The YoloV5Focus implementation follows the Apache-2.0 YOLOX ncnn demo and
// the BSD-3-Clause ncnn examples. See THIRD_PARTY_NOTICES.md.
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>

#include "layer.h"
#include "mapassist.h"
#include "net.h"

namespace {

constexpr const char *kYoloxParamAsset = "minimap-yolox-nano-320.param";
constexpr const char *kYoloxBinAsset = "minimap-yolox-nano-320.bin";
constexpr const char *kLogTag = "MapAssistYolox";

// YOLOX Focus slices alternating pixels into four channel groups. ncnn does
// not represent Slice(step=2), so the converted graph uses this custom layer.
class YoloV5Focus final : public ncnn::Layer {
public:
    YoloV5Focus() { one_blob_only = true; }

    int forward(const ncnn::Mat &bottom_blob, ncnn::Mat &top_blob,
                const ncnn::Option &opt) const override {
        const int width = bottom_blob.w;
        const int height = bottom_blob.h;
        const int channels = bottom_blob.c;
        if (width < 2 || height < 2 || channels < 1) return -100;

        const int output_width = width / 2;
        const int output_height = height / 2;
        const int output_channels = channels * 4;
        top_blob.create(output_width, output_height, output_channels,
                        4u, 1, opt.blob_allocator);
        if (top_blob.empty()) return -100;

#pragma omp parallel for num_threads(opt.num_threads)
        for (int output_channel = 0; output_channel < output_channels; ++output_channel) {
            const int group = output_channel / channels;
            const int source_channel = output_channel % channels;
            const int y_offset = group % 2;
            const int x_offset = group / 2;
            const float *source = bottom_blob.channel(source_channel).row(y_offset) + x_offset;
            float *destination = top_blob.channel(output_channel);
            for (int y = 0; y < output_height; ++y) {
                for (int x = 0; x < output_width; ++x) {
                    *destination++ = *source;
                    source += 2;
                }
                source += width;
            }
        }
        return 0;
    }
};

DEFINE_LAYER_CREATOR(YoloV5Focus)

struct PixelRect {
    int x0;
    int y0;
    int x1;
    int y1;
};

bool has_rect(ma_rect rect) {
    return std::isfinite(rect.x) && std::isfinite(rect.y) &&
           std::isfinite(rect.w) && std::isfinite(rect.h) &&
           rect.x >= 0.0f && rect.y >= 0.0f && rect.w > 0.0f && rect.h > 0.0f &&
           rect.x + rect.w <= 1.001f && rect.y + rect.h <= 1.001f;
}

struct Detection {
    float x0;
    float y0;
    float x1;
    float y1;
    float confidence;
};

struct Session {
    ma_profile profile{};
    ma_template enemy_template{};
    ma_template ping_template{};
    std::vector<uint8_t> enemy_pixels;
    std::vector<uint8_t> ping_pixels;
    ma_engine *engine = nullptr;
    bool minimap_yolox = false;
    int yolox_input_size = 320;
    float yolox_confidence = 0.29f;
    float yolox_nms = 0.5f;
    bool yolox_runtime_error_logged = false;
    ma_minimap_locator *minimap_locator = nullptr;
    ma_player_state_matcher *player_state_matcher = nullptr;
    ma_rect player_state_roi{};
    ncnn::Net yolox;

    ~Session() {
        ma_minimap_locator_destroy(minimap_locator);
        ma_player_state_matcher_destroy(player_state_matcher);
        ma_engine_destroy(engine);
    }
};

bool copy_template(JNIEnv *env, jbyteArray input, jint width, jint height,
                   std::vector<uint8_t> &pixels, ma_template &templ) {
    if (!input || width <= 0 || height <= 0 || width > 256 || height > 256) return false;
    const jsize length = env->GetArrayLength(input);
    if (length != static_cast<jsize>(width * height * 4)) return false;
    pixels.resize(length);
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte *>(pixels.data()));
    if (env->ExceptionCheck()) return false;
    templ = {pixels.data(), width, height, width * 4};
    return true;
}

PixelRect to_pixels(ma_rect rect, int width, int height) {
    const int x0 = std::clamp(static_cast<int>(std::floor(rect.x * width)), 0, width);
    const int y0 = std::clamp(static_cast<int>(std::floor(rect.y * height)), 0, height);
    const int x1 = std::clamp(
            static_cast<int>(std::ceil((rect.x + rect.w) * width)), x0, width);
    const int y1 = std::clamp(
            static_cast<int>(std::ceil((rect.y + rect.h) * height)), y0, height);
    return {x0, y0, x1, y1};
}

PixelRect to_direction_reference_pixels(ma_rect rect, int width, int height) {
    const int x0 = std::clamp(static_cast<int>(std::lround(rect.x * width)), 0, width);
    const int y0 = std::clamp(static_cast<int>(std::lround(rect.y * height)), 0, height);
    const int x1 = std::clamp(
            static_cast<int>(std::lround((rect.x + rect.w) * width)), x0, width);
    const int y1 = std::clamp(
            static_cast<int>(std::lround((rect.y + rect.h) * height)), y0, height);
    return {x0, y0, x1, y1};
}

int direction_for(float x, float y, const PixelRect &reference) {
    const float center_x = (reference.x0 + reference.x1) * 0.5f;
    const float center_y = (reference.y0 + reference.y1) * 0.5f;
    const float dx = (x - center_x) /
                     std::max(1.0f, (reference.x1 - reference.x0) * 0.5f);
    const float dy = (y - center_y) /
                     std::max(1.0f, (reference.y1 - reference.y0) * 0.5f);
    if (std::abs(dx) < 0.15f && std::abs(dy) < 0.15f) return MA_DIR_NONE;
    if (std::abs(dx) >= std::abs(dy)) return dx < 0 ? MA_DIR_LEFT : MA_DIR_RIGHT;
    return dy < 0 ? MA_DIR_UP : MA_DIR_DOWN;
}

float intersection_over_union(const Detection &a, const Detection &b) {
    const float intersection_width =
            std::max(0.0f, std::min(a.x1, b.x1) - std::max(a.x0, b.x0));
    const float intersection_height =
            std::max(0.0f, std::min(a.y1, b.y1) - std::max(a.y0, b.y0));
    const float intersection = intersection_width * intersection_height;
    const float area_a = std::max(0.0f, a.x1 - a.x0) * std::max(0.0f, a.y1 - a.y0);
    const float area_b = std::max(0.0f, b.x1 - b.x0) * std::max(0.0f, b.y1 - b.y0);
    const float union_area = area_a + area_b - intersection;
    return union_area > 0.0f ? intersection / union_area : 0.0f;
}

void non_maximum_suppression(std::vector<Detection> &detections, float threshold) {
    std::sort(detections.begin(), detections.end(),
              [](const Detection &a, const Detection &b) {
                  return a.confidence > b.confidence;
              });
    std::vector<Detection> kept;
    kept.reserve(detections.size());
    for (const Detection &candidate : detections) {
        bool overlaps = false;
        for (const Detection &existing : kept) {
            if (intersection_over_union(candidate, existing) > threshold) {
                overlaps = true;
                break;
            }
        }
        if (!overlaps) kept.push_back(candidate);
    }
    detections.swap(kept);
}

bool load_yolox(AAssetManager *assets, Session &session) {
    if (!assets) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "AssetManager is unavailable");
        return false;
    }
    session.yolox.opt.lightmode = true;
    session.yolox.opt.num_threads = 2;
    session.yolox.opt.use_packing_layout = true;
    session.yolox.opt.use_fp16_packed = false;
    session.yolox.opt.use_fp16_storage = false;
    session.yolox.opt.use_fp16_arithmetic = false;
    session.yolox.opt.use_vulkan_compute = false;
    session.yolox.register_custom_layer("YoloV5Focus", YoloV5Focus_layer_creator);
    const int param_status = session.yolox.load_param(assets, kYoloxParamAsset);
    if (param_status != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "Could not load %s (status %d)",
                            kYoloxParamAsset, param_status);
        return false;
    }
    const int model_status = session.yolox.load_model(assets, kYoloxBinAsset);
    if (model_status != 0) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "Could not load %s (status %d)",
                            kYoloxBinAsset, model_status);
        return false;
    }
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "Loaded YOLOX Nano minimap model (%d px)",
                        session.yolox_input_size);
    return true;
}

void append_yolox_observations(Session &session, const uint8_t *rgba,
                               int width, int height, int row_stride,
                               int64_t timestamp_ms, ma_rect minimap_roi,
                               bool minimap_ready,
                               std::vector<ma_observation> &observations) {
    if (!session.minimap_yolox || !minimap_ready) return;
    const PixelRect area = to_pixels(minimap_roi, width, height);
    const PixelRect direction_reference = has_rect(session.profile.minimap_direction)
            ? to_direction_reference_pixels(
                    session.profile.minimap_direction, width, height) : area;
    const int crop_width = area.x1 - area.x0;
    const int crop_height = area.y1 - area.y0;
    if (crop_width < 2 || crop_height < 2) return;

    std::vector<uint8_t> crop(static_cast<size_t>(crop_width) * crop_height * 4);
    for (int y = 0; y < crop_height; ++y) {
        const uint8_t *source = rgba + static_cast<size_t>(area.y0 + y) * row_stride +
                                static_cast<size_t>(area.x0) * 4;
        std::memcpy(crop.data() + static_cast<size_t>(y) * crop_width * 4,
                    source, static_cast<size_t>(crop_width) * 4);
    }

    const int target = session.yolox_input_size;
    const float scale = std::min(static_cast<float>(target) / crop_width,
                                 static_cast<float>(target) / crop_height);
    const int resized_width = std::max(1, static_cast<int>(crop_width * scale));
    const int resized_height = std::max(1, static_cast<int>(crop_height * scale));
    ncnn::Mat resized = ncnn::Mat::from_pixels_resize(
            crop.data(), ncnn::Mat::PIXEL_RGBA2BGR,
            crop_width, crop_height, resized_width, resized_height);
    if (resized.empty()) return;
    ncnn::Mat input;
    ncnn::copy_make_border(resized, input, 0, target - resized_height,
                           0, target - resized_width,
                           ncnn::BORDER_CONSTANT, 114.0f);
    if (input.empty()) return;

    ncnn::Extractor extractor = session.yolox.create_extractor();
    extractor.set_light_mode(true);
    if (extractor.input("in0", input) != 0) {
        if (!session.yolox_runtime_error_logged) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                                "ncnn rejected YOLOX input tensor");
            session.yolox_runtime_error_logged = true;
        }
        return;
    }
    ncnn::Mat output;
    constexpr int expected_anchors = 40 * 40 + 20 * 20 + 10 * 10;
    const int extract_status = extractor.extract("out0", output);
    if (extract_status != 0 || output.empty() || output.dims != 2 ||
        output.w != 6 || output.h != expected_anchors || output.elempack != 1) {
        if (!session.yolox_runtime_error_logged) {
            __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                                "Invalid YOLOX output status=%d dims=%d w=%d h=%d pack=%d",
                                extract_status, output.dims, output.w, output.h,
                                output.elempack);
            session.yolox_runtime_error_logged = true;
        }
        return;
    }

    std::vector<Detection> proposals;
    proposals.reserve(32);
    int anchor = 0;
    constexpr int strides[] = {8, 16, 32};
    for (const int stride : strides) {
        const int grid = target / stride;
        for (int grid_y = 0; grid_y < grid; ++grid_y) {
            for (int grid_x = 0; grid_x < grid; ++grid_x, ++anchor) {
                if (anchor >= output.h) break;
                const float *row = output.row(anchor);
                const float confidence = row[4] * row[5];
                if (!std::isfinite(confidence) || confidence < session.yolox_confidence)
                    continue;
                const float center_x = (row[0] + grid_x) * stride;
                const float center_y = (row[1] + grid_y) * stride;
                const float box_width = std::exp(std::clamp(row[2], -10.0f, 10.0f)) * stride;
                const float box_height = std::exp(std::clamp(row[3], -10.0f, 10.0f)) * stride;
                Detection detection{
                        center_x - box_width * 0.5f,
                        center_y - box_height * 0.5f,
                        center_x + box_width * 0.5f,
                        center_y + box_height * 0.5f,
                        confidence,
                };
                detection.x0 = std::clamp(detection.x0 / scale, 0.0f,
                                           static_cast<float>(crop_width));
                detection.y0 = std::clamp(detection.y0 / scale, 0.0f,
                                           static_cast<float>(crop_height));
                detection.x1 = std::clamp(detection.x1 / scale, 0.0f,
                                           static_cast<float>(crop_width));
                detection.y1 = std::clamp(detection.y1 / scale, 0.0f,
                                           static_cast<float>(crop_height));
                if (detection.x1 - detection.x0 >= 1.0f &&
                    detection.y1 - detection.y0 >= 1.0f) {
                    proposals.push_back(detection);
                }
            }
        }
    }
    non_maximum_suppression(proposals, session.yolox_nms);

    for (const Detection &detection : proposals) {
        const float x0 = area.x0 + detection.x0;
        const float y0 = area.y0 + detection.y0;
        const float x1 = area.x0 + detection.x1;
        const float y1 = area.y0 + detection.y1;
        observations.push_back({
                MA_MINIMAP_ENEMY,
                direction_for((x0 + x1) * 0.5f, (y0 + y1) * 0.5f,
                              direction_reference),
                {x0 / width, y0 / height, (x1 - x0) / width, (y1 - y0) / height},
                detection.confidence,
                timestamp_ms,
        });
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_openrd_mapassist_NativeBridge_nativeCreate(
        JNIEnv *env, jclass, jobject asset_manager, jfloatArray rois, jintArray flags,
        jfloatArray tuning, jintArray event_ints, jfloat min_confidence,
        jbyteArray enemy_rgba, jint enemy_width, jint enemy_height,
        jbyteArray ping_rgba, jint ping_width, jint ping_height,
        jboolean minimap_yolox, jint yolox_input_size,
        jfloat yolox_confidence, jfloat yolox_nms,
        jboolean minimap_locator_enabled, jfloatArray minimap_locator_floats,
        jintArray minimap_locator_ints, jbyteArray minimap_locator_descriptor,
        jboolean player_life_enabled, jfloatArray player_life_values,
        jint player_life_max_dhash, jlongArray player_life_hashes,
        jbyteArray player_life_states, jbyteArray player_life_luma,
        jbyteArray player_life_chroma) {
    if (!rois || !flags || !tuning || !event_ints ||
        env->GetArrayLength(rois) != 16 || env->GetArrayLength(flags) != 5 ||
        env->GetArrayLength(tuning) != 5 || env->GetArrayLength(event_ints) != 5)
        return 0;
    if (minimap_yolox &&
        (yolox_input_size != 320 ||
         !std::isfinite(yolox_confidence) || yolox_confidence < 0.0f || yolox_confidence > 1.0f ||
         !std::isfinite(yolox_nms) || yolox_nms < 0.0f || yolox_nms > 1.0f))
        return 0;
    if (minimap_locator_enabled &&
        (!minimap_locator_floats || !minimap_locator_ints ||
         !minimap_locator_descriptor ||
         env->GetArrayLength(minimap_locator_floats) != 12 ||
         env->GetArrayLength(minimap_locator_ints) != 10)) return 0;
    if (player_life_enabled &&
        (!player_life_values || !player_life_hashes || !player_life_states ||
         !player_life_luma || !player_life_chroma ||
         env->GetArrayLength(player_life_values) != 7)) return 0;

    jfloat r[16], t[5];
    jint f[5], e[5];
    env->GetFloatArrayRegion(rois, 0, 16, r);
    env->GetIntArrayRegion(flags, 0, 5, f);
    env->GetFloatArrayRegion(tuning, 0, 5, t);
    env->GetIntArrayRegion(event_ints, 0, 5, e);
    if (env->ExceptionCheck()) return 0;

    std::unique_ptr<Session> session(new Session);
    session->profile.minimap = {r[0], r[1], r[2], r[3]};
    session->profile.ping_area = {r[4], r[5], r[6], r[7]};
    session->profile.center_mask = {r[8], r[9], r[10], r[11]};
    session->profile.minimap_direction = {r[12], r[13], r[14], r[15]};
    session->profile.enable_main_bar = f[0];
    session->profile.enable_minimap_template = f[1];
    session->profile.enable_minimap_red_ring = f[2];
    session->profile.enable_ping_template = f[3];
    session->profile.red_min = f[4];
    session->profile.red_dominance = t[0];
    session->profile.main_min_width_ratio = t[1];
    session->profile.main_max_height_ratio = t[2];
    session->profile.main_min_aspect = t[3];
    session->profile.template_threshold = t[4];

    if (f[1] && !copy_template(env, enemy_rgba, enemy_width, enemy_height,
                                session->enemy_pixels, session->enemy_template)) return 0;
    if (f[3] && !copy_template(env, ping_rgba, ping_width, ping_height,
                                session->ping_pixels, session->ping_template)) return 0;
    session->minimap_yolox = minimap_yolox;
    session->yolox_input_size = yolox_input_size;
    session->yolox_confidence = yolox_confidence;
    session->yolox_nms = yolox_nms;
    if (minimap_locator_enabled) {
        jfloat locator_floats[12];
        jint locator_ints[10];
        env->GetFloatArrayRegion(
                minimap_locator_floats, 0, 12, locator_floats);
        env->GetIntArrayRegion(
                minimap_locator_ints, 0, 10, locator_ints);
        if (env->ExceptionCheck()) return 0;
        const int descriptor_length = env->GetArrayLength(minimap_locator_descriptor);
        const int64_t expected_descriptor_length =
                static_cast<int64_t>(locator_ints[2]) * locator_ints[3];
        if (expected_descriptor_length <= 0 || expected_descriptor_length > 4096 ||
            descriptor_length != expected_descriptor_length) return 0;
        std::vector<int8_t> descriptor(static_cast<size_t>(descriptor_length));
        env->GetByteArrayRegion(
                minimap_locator_descriptor, 0, descriptor_length,
                reinterpret_cast<jbyte *>(descriptor.data()));
        if (env->ExceptionCheck()) return 0;
        const ma_minimap_locator_config locator_config{
            {locator_floats[0], locator_floats[1],
             locator_floats[2], locator_floats[3]},
            locator_floats[4], locator_floats[5], locator_floats[6],
            locator_floats[7], locator_floats[8], locator_ints[0],
            locator_floats[9], locator_floats[10], locator_ints[1],
            locator_ints[2], locator_ints[3], locator_floats[11],
            locator_ints[4], locator_ints[5], locator_ints[6],
            locator_ints[7], locator_ints[8], locator_ints[9],
        };
        session->minimap_locator = ma_minimap_locator_create(
                &locator_config, descriptor.data(), descriptor_length);
        if (!session->minimap_locator) return 0;
    }
    if (session->minimap_yolox) {
        AAssetManager *assets = AAssetManager_fromJava(env, asset_manager);
        if (!load_yolox(assets, *session)) return 0;
    }
    if (player_life_enabled) {
        const int count = env->GetArrayLength(player_life_hashes);
        if (count < 6 || count > 64 || env->GetArrayLength(player_life_states) != count ||
            env->GetArrayLength(player_life_luma) != count * 64 ||
            env->GetArrayLength(player_life_chroma) != count * 32) return 0;
        jfloat values[7];
        std::vector<jlong> hashes(static_cast<size_t>(count));
        std::vector<jbyte> states(static_cast<size_t>(count));
        std::vector<jbyte> luma(static_cast<size_t>(count) * 64);
        std::vector<jbyte> chroma(static_cast<size_t>(count) * 32);
        env->GetFloatArrayRegion(player_life_values, 0, 7, values);
        env->GetLongArrayRegion(player_life_hashes, 0, count, hashes.data());
        env->GetByteArrayRegion(player_life_states, 0, count, states.data());
        env->GetByteArrayRegion(player_life_luma, 0, count * 64, luma.data());
        env->GetByteArrayRegion(player_life_chroma, 0, count * 32, chroma.data());
        if (env->ExceptionCheck()) return 0;
        std::vector<ma_player_state_signature> signatures(static_cast<size_t>(count));
        for (int i = 0; i < count; ++i) {
            signatures[i].state = states[i];
            signatures[i].dhash = static_cast<uint64_t>(hashes[i]);
            std::memcpy(signatures[i].luma, luma.data() + static_cast<size_t>(i) * 64, 64);
            std::memcpy(signatures[i].chroma, chroma.data() + static_cast<size_t>(i) * 32, 32);
        }
        const ma_player_state_matcher_config player_config{
            {values[0], values[1], values[2], values[3]},
            player_life_max_dhash, values[4], values[5], values[6],
        };
        session->player_state_roi = player_config.roi;
        session->player_state_matcher = ma_player_state_matcher_create(
                &player_config, signatures.data(), count);
        if (!session->player_state_matcher) return 0;
    }

    const ma_engine_config config{min_confidence, e[0], e[1], e[2], e[3], e[4]};
    session->engine = ma_engine_create(&config);
    if (!session->engine) return 0;
    return reinterpret_cast<jlong>(session.release());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_org_openrd_mapassist_NativeBridge_nativeProcess(
        JNIEnv *env, jclass, jlong handle, jobject frame,
        jint width, jint height, jint row_stride,
        jlong frame_timestamp_ms, jlong processing_now_ms) {
    // kind, direction, priority, observation count, processing micros,
    // locator state/score, normalized minimap ROI in parts per million,
    // marker count, then marker records of:
    // state, movement direction, x/y/w/h ppm, age ms, transition event, track id.
    constexpr int marker_offset = 12;
    constexpr int marker_stride = 9;
    constexpr int marker_capacity = 8;
    constexpr int result_size = marker_offset + marker_stride * marker_capacity;
    jint result[result_size] = {};
    result[5] = -1;
    auto *session = reinterpret_cast<Session *>(handle);
    auto *rgba = frame ? static_cast<uint8_t *>(env->GetDirectBufferAddress(frame)) : nullptr;
    const jlong capacity = frame ? env->GetDirectBufferCapacity(frame) : -1;
    const int64_t required = static_cast<int64_t>(height - 1) * row_stride +
                             static_cast<int64_t>(width) * 4;
    if (!session || !rgba || width <= 0 || height <= 0 ||
        width > 8192 || height > 8192 || row_stride < width * 4 ||
        capacity < required) {
        result[3] = -1;
    } else {
        const auto start = std::chrono::steady_clock::now();
        ma_profile frame_profile = session->profile;
        bool minimap_ready = true;
        if (session->minimap_locator) {
            ma_rect located_minimap{};
            ma_rect content{};
            float locator_score = -2.0f;
            const int locator_state = ma_minimap_locator_update(
                    session->minimap_locator, rgba, width, height, row_stride,
                    &located_minimap, &content, &locator_score);
            result[5] = locator_state;
            result[6] = static_cast<jint>(std::lround(locator_score * 1000.0f));
            minimap_ready = locator_state != MA_LOCATOR_SEARCHING;
            if (!minimap_ready) {
                // A lost/unknown minimap ROI is not evidence that tracked
                // enemies disappeared. Drop those tracks silently until the
                // locator confirms a usable region again.
                ma_engine_clear_minimap_tracks(session->engine);
            }
            if (minimap_ready) {
                frame_profile.minimap = located_minimap;
                result[7] = static_cast<jint>(std::lround(located_minimap.x * 1000000.0f));
                result[8] = static_cast<jint>(std::lround(located_minimap.y * 1000000.0f));
                result[9] = static_cast<jint>(std::lround(located_minimap.w * 1000000.0f));
                result[10] = static_cast<jint>(std::lround(located_minimap.h * 1000000.0f));
            } else {
                frame_profile.enable_minimap_template = 0;
                frame_profile.enable_minimap_red_ring = 0;
            }
        } else {
            result[7] = static_cast<jint>(std::lround(frame_profile.minimap.x * 1000000.0f));
            result[8] = static_cast<jint>(std::lround(frame_profile.minimap.y * 1000000.0f));
            result[9] = static_cast<jint>(std::lround(frame_profile.minimap.w * 1000000.0f));
            result[10] = static_cast<jint>(std::lround(frame_profile.minimap.h * 1000000.0f));
        }
        ma_observation native_observations[64];
        const int native_count = ma_detect_rgba(
                rgba, width, height, row_stride, frame_timestamp_ms, &frame_profile,
                frame_profile.enable_minimap_template ? &session->enemy_template : nullptr,
                frame_profile.enable_ping_template ? &session->ping_template : nullptr,
                native_observations, 64);
        std::vector<ma_observation> observations(
                native_observations, native_observations + native_count);
        append_yolox_observations(*session, rgba, width, height, row_stride,
                                  frame_timestamp_ms, frame_profile.minimap,
                                  minimap_ready, observations);
        if (session->player_state_matcher) {
            float confidence = 0.0f;
            const int state = ma_player_state_match_rgba(
                    session->player_state_matcher, rgba, width, height, row_stride,
                    &confidence);
            if (state == MA_PLAYER_STATE_DEAD || state == MA_PLAYER_STATE_ALIVE) {
                observations.push_back({
                    state == MA_PLAYER_STATE_DEAD ? MA_PLAYER_DEAD : MA_PLAYER_ALIVE,
                    MA_DIR_NONE, session->player_state_roi, confidence, frame_timestamp_ms,
                });
            }
        }
        std::sort(observations.begin(), observations.end(),
                  [](const ma_observation &a, const ma_observation &b) {
                      return a.confidence > b.confidence;
                  });
        if (observations.size() > 64) observations.resize(64);

        const auto detected = std::chrono::steady_clock::now();
        const int64_t processing_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                detected - start).count();
        ma_cue cue{};
        const int cue_count = ma_engine_step(
                session->engine, observations.data(), static_cast<int>(observations.size()),
                processing_now_ms + processing_ms, &cue, 1);
        if (cue_count) {
            result[0] = cue.kind;
            result[1] = cue.direction;
            result[2] = cue.priority;
        }
        ma_minimap_marker markers[marker_capacity];
        const int marker_count = std::clamp(
                ma_engine_read_minimap_markers(
                        session->engine, markers, marker_capacity),
                0, marker_capacity);
        result[11] = marker_count;
        for (int index = 0; index < marker_count; ++index) {
            const ma_minimap_marker &marker = markers[index];
            const int base = marker_offset + index * marker_stride;
            result[base] = marker.state;
            result[base + 1] = marker.movement_direction;
            result[base + 2] = static_cast<jint>(std::lround(marker.bbox.x * 1000000.0f));
            result[base + 3] = static_cast<jint>(std::lround(marker.bbox.y * 1000000.0f));
            result[base + 4] = static_cast<jint>(std::lround(marker.bbox.w * 1000000.0f));
            result[base + 5] = static_cast<jint>(std::lround(marker.bbox.h * 1000000.0f));
            result[base + 6] = marker.age_ms;
            result[base + 7] = marker.event;
            result[base + 8] = marker.track_id;
        }
        result[3] = static_cast<jint>(observations.size());
        result[4] = static_cast<jint>(std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - start).count());
    }
    jintArray output = env->NewIntArray(result_size);
    if (output) env->SetIntArrayRegion(output, 0, result_size, result);
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_org_openrd_mapassist_NativeBridge_nativeReset(JNIEnv *, jclass, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session) {
        ma_engine_reset(session->engine);
        ma_minimap_locator_reset(session->minimap_locator);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_org_openrd_mapassist_NativeBridge_nativeDestroy(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<Session *>(handle);
}
