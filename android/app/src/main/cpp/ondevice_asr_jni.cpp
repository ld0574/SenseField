#include <jni.h>

#include "sherpa-onnx-c-api.h"

#include <cstdint>
#include <memory>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

constexpr int32_t kSampleRate = 16'000;
constexpr jsize kMaxSamples = 256'000;

struct AsrHandle {
  const SherpaOnnxOfflineRecognizer* recognizer = nullptr;

  ~AsrHandle() {
    if (recognizer != nullptr) {
      SherpaOnnxDestroyOfflineRecognizer(recognizer);
    }
  }
};

class UtfChars {
 public:
  UtfChars(JNIEnv* env, jstring value) : env_(env), value_(value) {
    if (value_ != nullptr) {
      chars_ = env_->GetStringUTFChars(value_, nullptr);
    }
  }

  ~UtfChars() {
    if (chars_ != nullptr) {
      env_->ReleaseStringUTFChars(value_, chars_);
    }
  }

  UtfChars(const UtfChars&) = delete;
  UtfChars& operator=(const UtfChars&) = delete;

  const char* get() const { return chars_; }

 private:
  JNIEnv* env_;
  jstring value_;
  const char* chars_ = nullptr;
};

void throwJavaException(JNIEnv* env, const char* class_name, const char* message) {
  if (env == nullptr || env->ExceptionCheck()) {
    return;
  }
  jclass exception_class = env->FindClass(class_name);
  if (exception_class != nullptr) {
    env->ThrowNew(exception_class, message);
    env->DeleteLocalRef(exception_class);
  }
}

void throwIllegalState(JNIEnv* env, const char* message) {
  throwJavaException(env, "java/lang/IllegalStateException", message);
}

void throwOutOfMemory(JNIEnv* env) {
  throwJavaException(env, "java/lang/OutOfMemoryError", "Not enough native memory for on-device ASR");
}

AsrHandle* fromHandle(jlong handle) {
  return reinterpret_cast<AsrHandle*>(static_cast<intptr_t>(handle));
}

jstring emptyString(JNIEnv* env) {
  return env->NewStringUTF("");
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_openkhub_sensefield_OnDeviceAsr_nativeCreate(
    JNIEnv* env, jclass /* clazz */, jstring model_path, jstring tokens_path,
    jint threads) {
  if (model_path == nullptr || tokens_path == nullptr) {
    throwIllegalState(env, "ASR model and tokens paths are required");
    return 0;
  }

  const int32_t thread_count = threads == 0 ? 1 : static_cast<int32_t>(threads);
  if (thread_count < 1 || thread_count > 2) {
    throwIllegalState(env, "ASR threads must be between 1 and 2");
    return 0;
  }

  UtfChars model(env, model_path);
  if (env->ExceptionCheck()) {
    return 0;
  }
  UtfChars tokens(env, tokens_path);
  if (env->ExceptionCheck()) {
    return 0;
  }
  if (model.get() == nullptr || tokens.get() == nullptr || model.get()[0] == '\0'
      || tokens.get()[0] == '\0') {
    throwIllegalState(env, "ASR model and tokens paths must not be empty");
    return 0;
  }

  try {
    SherpaOnnxOfflineRecognizerConfig config{};
    config.feat_config.sample_rate = kSampleRate;
    config.feat_config.feature_dim = 80;
    config.model_config.sense_voice.model = model.get();
    config.model_config.sense_voice.language = "zh";
    config.model_config.sense_voice.use_itn = 1;
    config.model_config.tokens = tokens.get();
    config.model_config.provider = "cpu";
    config.model_config.num_threads = thread_count;
    config.decoding_method = "greedy_search";

    const SherpaOnnxOfflineRecognizer* recognizer =
        SherpaOnnxCreateOfflineRecognizer(&config);
    if (recognizer == nullptr) {
      throw std::runtime_error("ASR recognizer creation failed");
    }

    std::unique_ptr<AsrHandle> handle(new (std::nothrow) AsrHandle());
    if (!handle) {
      SherpaOnnxDestroyOfflineRecognizer(recognizer);
      throw std::bad_alloc();
    }
    handle->recognizer = recognizer;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(handle.release()));
  } catch (const std::bad_alloc&) {
    throwOutOfMemory(env);
  } catch (const std::exception&) {
    throwIllegalState(env, "Could not initialize on-device ASR");
  } catch (...) {
    throwIllegalState(env, "Could not initialize on-device ASR");
  }
  return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_openkhub_sensefield_OnDeviceAsr_nativeDecode(
    JNIEnv* env, jclass /* clazz */, jlong raw_handle, jshortArray pcm) {
  AsrHandle* handle = fromHandle(raw_handle);
  if (handle == nullptr || handle->recognizer == nullptr) {
    throwIllegalState(env, "ASR handle is not initialized");
    return nullptr;
  }
  if (pcm == nullptr) {
    throwIllegalState(env, "ASR PCM input is required");
    return nullptr;
  }

  const jsize sample_count = env->GetArrayLength(pcm);
  if (env->ExceptionCheck()) {
    return nullptr;
  }
  if (sample_count == 0) {
    return emptyString(env);
  }
  if (sample_count > kMaxSamples) {
    throwIllegalState(env, "ASR PCM may not exceed 256000 samples");
    return nullptr;
  }

  try {
    std::vector<jshort> pcm16(static_cast<size_t>(sample_count));
    env->GetShortArrayRegion(pcm, 0, sample_count, pcm16.data());
    if (env->ExceptionCheck()) {
      return nullptr;
    }

    std::vector<float> samples(static_cast<size_t>(sample_count));
    constexpr float kPcmScale = 1.0f / 32768.0f;
    for (jsize i = 0; i < sample_count; ++i) {
      samples[static_cast<size_t>(i)] = static_cast<float>(pcm16[static_cast<size_t>(i)]) * kPcmScale;
    }

    using StreamPtr = std::unique_ptr<const SherpaOnnxOfflineStream,
        decltype(&SherpaOnnxDestroyOfflineStream)>;
    StreamPtr stream(SherpaOnnxCreateOfflineStream(handle->recognizer),
                     &SherpaOnnxDestroyOfflineStream);
    if (!stream) {
      throw std::runtime_error("ASR stream creation failed");
    }
    SherpaOnnxOfflineStreamSetOption(stream.get(), "language", "zh");
    SherpaOnnxAcceptWaveformOffline(stream.get(), kSampleRate, samples.data(), sample_count);
    SherpaOnnxDecodeOfflineStream(handle->recognizer, stream.get());

    using ResultPtr = std::unique_ptr<const SherpaOnnxOfflineRecognizerResult,
        decltype(&SherpaOnnxDestroyOfflineRecognizerResult)>;
    ResultPtr result(SherpaOnnxGetOfflineStreamResult(stream.get()),
                     &SherpaOnnxDestroyOfflineRecognizerResult);
    if (!result) {
      throw std::runtime_error("ASR result is unavailable");
    }
    if (result->text == nullptr || result->text[0] == '\0') {
      return emptyString(env);
    }
    // Sherpa returns UTF-8; NewStringUTF preserves ordinary Chinese BMP text.
    return env->NewStringUTF(result->text);
  } catch (const std::bad_alloc&) {
    throwOutOfMemory(env);
  } catch (const std::exception&) {
    throwIllegalState(env, "On-device ASR decoding failed");
  } catch (...) {
    throwIllegalState(env, "On-device ASR decoding failed");
  }
  return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_openkhub_sensefield_OnDeviceAsr_nativeDestroy(
    JNIEnv* /* env */, jclass /* clazz */, jlong raw_handle) {
  delete fromHandle(raw_handle);
}
