#include <jni.h>

#include <chrono>
#include <cstdint>
#include <memory>
#include <vector>

#include "mapassist.h"

namespace {

struct Session {
    ma_profile profile{};
    ma_template enemy_template{};
    ma_template ping_template{};
    std::vector<uint8_t> enemy_pixels;
    std::vector<uint8_t> ping_pixels;
    ma_engine *engine = nullptr;

    ~Session() { ma_engine_destroy(engine); }
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

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_openrd_mapassist_NativeBridge_nativeCreate(
        JNIEnv *env, jclass, jfloatArray rois, jintArray flags,
        jfloatArray tuning, jintArray event_ints, jfloat min_confidence,
        jbyteArray enemy_rgba, jint enemy_width, jint enemy_height,
        jbyteArray ping_rgba, jint ping_width, jint ping_height) {
    if (!rois || !flags || !tuning || !event_ints ||
        env->GetArrayLength(rois) != 12 || env->GetArrayLength(flags) != 5 ||
        env->GetArrayLength(tuning) != 5 || env->GetArrayLength(event_ints) != 5)
        return 0;
    jfloat r[12], t[5];
    jint f[5], e[5];
    env->GetFloatArrayRegion(rois, 0, 12, r);
    env->GetIntArrayRegion(flags, 0, 5, f);
    env->GetFloatArrayRegion(tuning, 0, 5, t);
    env->GetIntArrayRegion(event_ints, 0, 5, e);
    if (env->ExceptionCheck()) return 0;

    std::unique_ptr<Session> session(new Session);
    session->profile.minimap = {r[0], r[1], r[2], r[3]};
    session->profile.ping_area = {r[4], r[5], r[6], r[7]};
    session->profile.center_mask = {r[8], r[9], r[10], r[11]};
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
    jint result[5] = {0, 0, 0, 0, 0};
    auto *session = reinterpret_cast<Session *>(handle);
    auto *rgba = static_cast<uint8_t *>(env->GetDirectBufferAddress(frame));
    const jlong capacity = frame ? env->GetDirectBufferCapacity(frame) : -1;
    const int64_t required = static_cast<int64_t>(height - 1) * row_stride +
                             static_cast<int64_t>(width) * 4;
    if (!session || !rgba || width <= 0 || height <= 0 ||
        width > 8192 || height > 8192 || row_stride < width * 4 ||
        capacity < required) {
        result[3] = -1;
    } else {
        const auto start = std::chrono::steady_clock::now();
        ma_observation observations[64];
        const int count = ma_detect_rgba(
                rgba, width, height, row_stride, frame_timestamp_ms, &session->profile,
                session->profile.enable_minimap_template ? &session->enemy_template : nullptr,
                session->profile.enable_ping_template ? &session->ping_template : nullptr,
                observations, 64);
        const auto detected = std::chrono::steady_clock::now();
        const int64_t processing_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                detected - start).count();
        ma_cue cue{};
        const int cue_count = ma_engine_step(session->engine, observations, count,
                                             processing_now_ms + processing_ms, &cue, 1);
        if (cue_count) {
            result[0] = cue.kind;
            result[1] = cue.direction;
            result[2] = cue.priority;
        }
        result[3] = count;
        result[4] = static_cast<jint>(std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - start).count());
    }
    jintArray output = env->NewIntArray(5);
    if (output) env->SetIntArrayRegion(output, 0, 5, result);
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_org_openrd_mapassist_NativeBridge_nativeReset(JNIEnv *, jclass, jlong handle) {
    auto *session = reinterpret_cast<Session *>(handle);
    if (session) ma_engine_reset(session->engine);
}

extern "C" JNIEXPORT void JNICALL
Java_org_openrd_mapassist_NativeBridge_nativeDestroy(JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<Session *>(handle);
}
