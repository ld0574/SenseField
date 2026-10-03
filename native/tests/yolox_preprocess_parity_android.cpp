// Standalone Android test of the pinned ncnn preprocessing API; no model or game data.
#include "yolox_preprocess.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <random>
#include <vector>

static bool identical(const ncnn::Mat &left, const ncnn::Mat &right) {
    if (left.empty() || right.empty() || left.dims != right.dims || left.w != right.w ||
        left.h != right.h || left.c != right.c || left.elempack != right.elempack ||
        left.elemsize != right.elemsize) return false;
    // Ignore alignment padding outside the valid channel pixels.
    for (int channel = 0; channel < left.c; ++channel) {
        if (std::memcmp(left.channel(channel), right.channel(channel),
                        static_cast<size_t>(left.w) * left.h * left.elemsize) != 0) return false;
    }
    return true;
}

int main() {
    std::mt19937 random(7307);
    constexpr int cases = 96;
    ncnn::Option inference_options;
    inference_options.num_threads = 2;
    inference_options.openmp_blocktime = 0;
    inference_options.use_packing_layout = true;
    inference_options.use_fp16_packed = false;
    inference_options.use_fp16_storage = false;
    inference_options.use_fp16_arithmetic = false;
    inference_options.use_vulkan_compute = false;
    for (int trial = 0; trial < cases; ++trial) {
        const int width = 40 + random() % 661, height = 32 + random() % 389;
        const int stride = width * 4 + (trial % 3 == 0 ? 0 : 4 * (1 + random() % 32));
        const int x0 = random() % (width - 1), y0 = random() % (height - 1);
        const int crop_width = 2 + random() % (width - x0 - 1);
        const int crop_height = 2 + random() % (height - y0 - 1);
        const int target = trial % 2 == 0 ? 320 : 512;
        std::vector<unsigned char> pixels(static_cast<size_t>(height - 1) * stride + width * 4);
        for (auto &pixel : pixels) pixel = static_cast<unsigned char>(random());
        std::vector<unsigned char> crop(static_cast<size_t>(crop_width) * crop_height * 4);
        for (int y = 0; y < crop_height; ++y)
            std::memcpy(crop.data() + static_cast<size_t>(y) * crop_width * 4,
                        pixels.data() + static_cast<size_t>(y0 + y) * stride + x0 * 4,
                        static_cast<size_t>(crop_width) * 4);
        const float scale = std::min(static_cast<float>(target) / crop_width,
                                     static_cast<float>(target) / crop_height);
        const int resized_width = std::max(1, static_cast<int>(crop_width * scale));
        const int resized_height = std::max(1, static_cast<int>(crop_height * scale));
        const ncnn::Mat legacy = ncnn::Mat::from_pixels_resize(crop.data(), ncnn::Mat::PIXEL_RGBA2BGR,
                crop_width, crop_height, resized_width, resized_height);
        const ncnn::Mat candidate = mapassist_yolox::resize_roi_rgba_to_bgr(pixels.data(),
                width, height, stride, x0, y0,
                crop_width, crop_height, resized_width, resized_height);
        ncnn::Mat legacy_padded;
        ncnn::copy_make_border(legacy, legacy_padded, 0, target - resized_height,
                0, target - resized_width, ncnn::BORDER_CONSTANT, 114.0f);
        const ncnn::Mat candidate_padded = mapassist_yolox::pad_resized_to_square(
                candidate, target, inference_options);
        if (!identical(legacy, candidate) || !identical(legacy_padded, candidate_padded)) {
            std::fprintf(stderr, "Preprocess parity mismatch in synthetic case %d\n", trial);
            return 1;
        }
    }
    if (!mapassist_yolox::pad_resized_to_square(ncnn::Mat(), 512, inference_options).empty())
        return 1;
    const ncnn::Mat too_large(513, 512, 3);
    if (!mapassist_yolox::pad_resized_to_square(too_large, 512, inference_options).empty())
        return 1;
    std::printf("YOLOX_PREPROCESS_PARITY cases=%d bit_identical=true synthetic=true explicit_load_options=true\n", cases);
    return 0;
}
