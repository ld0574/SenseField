#include <jni.h>

#include <array>
#include <cstdint>
#include <mutex>
#include <new>
#include <vector>

#include "speex/speex_echo.h"
#include "speex/speex_resampler.h"
#include "webrtc/common_audio/vad/include/webrtc_vad.h"

namespace {

constexpr spx_uint32_t kSampleRate = 16000;
constexpr spx_uint32_t kFrameSamples = 160;  // 10 ms at 16 kHz.
constexpr int kEchoTailSamples = 8000;       // 500 ms adaptive filter.

struct EchoHandle {
    SpeexEchoState* echo = nullptr;
    std::mutex lock;
};

struct SpeechHandle {
    VadInst* vad = nullptr;
    std::mutex lock;
};

EchoHandle* handleFrom(jlong value) {
    return reinterpret_cast<EchoHandle*>(static_cast<intptr_t>(value));
}

SpeechHandle* speechHandleFrom(jlong value) {
    return reinterpret_cast<SpeechHandle*>(static_cast<intptr_t>(value));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_openkhub_sensefield_VoiceEchoProcessor_nativeCreate(JNIEnv*, jclass) {
    auto* state = new (std::nothrow) EchoHandle();
    if (state == nullptr) return 0;
    state->echo = speex_echo_state_init(kFrameSamples, kEchoTailSamples);
    if (state->echo == nullptr) {
        delete state;
        return 0;
    }
    int sampleRate = static_cast<int>(kSampleRate);
    speex_echo_ctl(state->echo, SPEEX_ECHO_SET_SAMPLING_RATE, &sampleRate);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(state));
}

extern "C" JNIEXPORT void JNICALL
Java_com_openkhub_sensefield_VoiceEchoProcessor_nativeFeedRender(
        JNIEnv* env, jclass, jlong handle, jshortArray reference) {
    EchoHandle* state = handleFrom(handle);
    if (state == nullptr || reference == nullptr
            || env->GetArrayLength(reference) != static_cast<jsize>(kFrameSamples)) return;
    std::array<spx_int16_t, kFrameSamples> frame{};
    env->GetShortArrayRegion(reference, 0, static_cast<jsize>(kFrameSamples), frame.data());
    if (env->ExceptionCheck()) return;
    std::lock_guard<std::mutex> guard(state->lock);
    if (state->echo != nullptr) speex_echo_playback(state->echo, frame.data());
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_openkhub_sensefield_VoiceEchoProcessor_nativeProcess(
        JNIEnv* env, jclass, jlong handle, jshortArray microphone) {
    EchoHandle* state = handleFrom(handle);
    if (state == nullptr || microphone == nullptr
            || env->GetArrayLength(microphone) != static_cast<jsize>(kFrameSamples)) return nullptr;
    std::array<spx_int16_t, kFrameSamples> captured{};
    std::array<spx_int16_t, kFrameSamples> cleaned{};
    env->GetShortArrayRegion(microphone, 0, static_cast<jsize>(kFrameSamples), captured.data());
    if (env->ExceptionCheck()) return nullptr;
    {
        std::lock_guard<std::mutex> guard(state->lock);
        if (state->echo == nullptr) return nullptr;
        speex_echo_capture(state->echo, captured.data(), cleaned.data());
    }
    jshortArray output = env->NewShortArray(static_cast<jsize>(kFrameSamples));
    if (output != nullptr)
        env->SetShortArrayRegion(output, 0, static_cast<jsize>(kFrameSamples), cleaned.data());
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_com_openkhub_sensefield_VoiceEchoProcessor_nativeReset(
        JNIEnv*, jclass, jlong handle) {
    EchoHandle* state = handleFrom(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> guard(state->lock);
    if (state->echo != nullptr) speex_echo_state_reset(state->echo);
}

extern "C" JNIEXPORT void JNICALL
Java_com_openkhub_sensefield_VoiceEchoProcessor_nativeClose(
        JNIEnv*, jclass, jlong handle) {
    EchoHandle* state = handleFrom(handle);
    if (state == nullptr) return;
    {
        std::lock_guard<std::mutex> guard(state->lock);
        if (state->echo != nullptr) {
            speex_echo_state_destroy(state->echo);
            state->echo = nullptr;
        }
    }
    delete state;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_openkhub_sensefield_VoiceEchoProcessor_nativeResampleTo16k(
        JNIEnv* env, jclass, jshortArray input, jint inputRate) {
    if (input == nullptr || inputRate < 8000 || inputRate > 96000) return nullptr;
    const jsize inputCount = env->GetArrayLength(input);
    if (inputCount == 0) return env->NewShortArray(0);
    std::vector<spx_int16_t> source(static_cast<size_t>(inputCount));
    env->GetShortArrayRegion(input, 0, inputCount, source.data());
    if (env->ExceptionCheck()) return nullptr;
    int error = RESAMPLER_ERR_SUCCESS;
    SpeexResamplerState* resampler = speex_resampler_init(
            1, static_cast<spx_uint32_t>(inputRate), kSampleRate,
            SPEEX_RESAMPLER_QUALITY_DEFAULT, &error);
    if (resampler == nullptr || error != RESAMPLER_ERR_SUCCESS) {
        if (resampler != nullptr) speex_resampler_destroy(resampler);
        return nullptr;
    }
    const auto estimate = static_cast<spx_uint32_t>(
            (static_cast<uint64_t>(inputCount) * kSampleRate + inputRate - 1) / inputRate + 64);
    std::vector<spx_int16_t> converted(estimate);
    spx_uint32_t consumed = static_cast<spx_uint32_t>(inputCount);
    spx_uint32_t produced = estimate;
    error = speex_resampler_process_int(resampler, 0, source.data(), &consumed,
                                       converted.data(), &produced);
    speex_resampler_destroy(resampler);
    if (error != RESAMPLER_ERR_SUCCESS || consumed != static_cast<spx_uint32_t>(inputCount))
        return nullptr;
    jshortArray output = env->NewShortArray(static_cast<jsize>(produced));
    if (output != nullptr)
        env->SetShortArrayRegion(output, 0, static_cast<jsize>(produced), converted.data());
    return output;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_openkhub_sensefield_VoiceSpeechDetector_nativeCreate(
        JNIEnv*, jclass, jint mode) {
    if (mode < 0 || mode > 3) return 0;
    auto* state = new (std::nothrow) SpeechHandle();
    if (state == nullptr) return 0;
    if (WebRtcVad_Create(&state->vad) != 0 || state->vad == nullptr
            || WebRtcVad_Init(state->vad) != 0
            || WebRtcVad_set_mode(state->vad, mode) != 0) {
        if (state->vad != nullptr) WebRtcVad_Free(state->vad);
        delete state;
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(state));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_openkhub_sensefield_VoiceSpeechDetector_nativeIsSpeech(
        JNIEnv* env, jclass, jlong handle, jshortArray input) {
    SpeechHandle* state = speechHandleFrom(handle);
    if (state == nullptr || input == nullptr
            || env->GetArrayLength(input) != static_cast<jsize>(kFrameSamples))
        return JNI_FALSE;
    std::array<int16_t, kFrameSamples> frame{};
    static_assert(sizeof(int16_t) == sizeof(jshort), "PCM sample types must match");
    env->GetShortArrayRegion(input, 0, static_cast<jsize>(kFrameSamples),
                             reinterpret_cast<jshort*>(frame.data()));
    if (env->ExceptionCheck()) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(state->lock);
    if (state->vad == nullptr) return JNI_FALSE;
    const int result = WebRtcVad_Process(state->vad, static_cast<int>(kSampleRate),
                                         frame.data(), static_cast<int>(kFrameSamples));
    return result == 1 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_openkhub_sensefield_VoiceSpeechDetector_nativeClose(
        JNIEnv*, jclass, jlong handle) {
    SpeechHandle* state = speechHandleFrom(handle);
    if (state == nullptr) return;
    {
        std::lock_guard<std::mutex> guard(state->lock);
        if (state->vad != nullptr) {
            WebRtcVad_Free(state->vad);
            state->vad = nullptr;
        }
    }
    delete state;
}
