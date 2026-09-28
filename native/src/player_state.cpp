#include "mapassist.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <new>
#include <vector>

namespace {

struct Features {
    uint64_t dhash = 0;
    std::array<uint8_t, 64> luma{};
    std::array<uint8_t, 32> chroma{};
};

bool valid_rect(ma_rect rect) {
    return std::isfinite(rect.x) && std::isfinite(rect.y) &&
           std::isfinite(rect.w) && std::isfinite(rect.h) &&
           rect.x >= 0 && rect.y >= 0 && rect.w > 0 && rect.h > 0 &&
           rect.x + rect.w <= 1.001f && rect.y + rect.h <= 1.001f;
}

uint8_t luma(const uint8_t *pixel) {
    return static_cast<uint8_t>((77 * pixel[0] + 150 * pixel[1] + 29 * pixel[2]) >> 8);
}

const uint8_t *sample(const uint8_t *rgba, int width, int height, int stride,
                      ma_rect roi, int gx, int gy, int grid_w, int grid_h) {
    const int x0 = std::clamp(static_cast<int>(std::floor(roi.x * width)), 0, width - 1);
    const int y0 = std::clamp(static_cast<int>(std::floor(roi.y * height)), 0, height - 1);
    const int x1 = std::clamp(static_cast<int>(std::ceil((roi.x + roi.w) * width)), x0 + 1, width);
    const int y1 = std::clamp(static_cast<int>(std::ceil((roi.y + roi.h) * height)), y0 + 1, height);
    const int x = std::clamp(x0 + (2 * gx + 1) * (x1 - x0) / (2 * grid_w), x0, x1 - 1);
    const int y = std::clamp(y0 + (2 * gy + 1) * (y1 - y0) / (2 * grid_h), y0, y1 - 1);
    return rgba + static_cast<size_t>(y) * stride + static_cast<size_t>(x) * 4;
}

Features extract(const uint8_t *rgba, int width, int height, int stride, ma_rect roi) {
    Features result;
    std::array<int, 64> raw_luma{};
    int mean = 0;
    for (int y = 0; y < 8; ++y) {
        for (int x = 0; x < 8; ++x) {
            int value = luma(sample(rgba, width, height, stride, roi, x, y, 8, 8));
            raw_luma[static_cast<size_t>(y) * 8 + x] = value;
            mean += value;
        }
    }
    mean /= 64;
    for (size_t i = 0; i < raw_luma.size(); ++i) {
        result.luma[i] = static_cast<uint8_t>(std::clamp(raw_luma[i] - mean + 128, 0, 255));
    }
    for (int y = 0; y < 8; ++y) {
        for (int x = 0; x < 8; ++x) {
            int left = luma(sample(rgba, width, height, stride, roi, x, y, 9, 8));
            int right = luma(sample(rgba, width, height, stride, roi, x + 1, y, 9, 8));
            if (left < right) result.dhash |= uint64_t{1} << (y * 8 + x);
        }
    }
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) {
            const uint8_t *pixel = sample(rgba, width, height, stride, roi, x, y, 4, 4);
            const int r = pixel[0], g = pixel[1], b = pixel[2];
            const int cb = std::clamp(128 + ((-43 * r - 85 * g + 128 * b) >> 8), 0, 255);
            const int cr = std::clamp(128 + ((128 * r - 107 * g - 21 * b) >> 8), 0, 255);
            size_t index = static_cast<size_t>(y * 4 + x) * 2;
            result.chroma[index] = static_cast<uint8_t>(cb);
            result.chroma[index + 1] = static_cast<uint8_t>(cr);
        }
    }
    return result;
}

float mae(const uint8_t *a, const uint8_t *b, int count) {
    int total = 0;
    for (int i = 0; i < count; ++i) total += std::abs(int(a[i]) - int(b[i]));
    return static_cast<float>(total) / (count * 255.0f);
}

}  // namespace

struct ma_player_state_matcher {
    ma_player_state_matcher_config config{};
    std::vector<ma_player_state_signature> signatures;
};

extern "C" ma_player_state_matcher *ma_player_state_matcher_create(
        const ma_player_state_matcher_config *config,
        const ma_player_state_signature *signatures, int signature_count) {
    if (!config || !signatures || signature_count < 6 || signature_count > 64 ||
        !valid_rect(config->roi) || config->max_dhash_distance < 0 ||
        config->max_dhash_distance > 64 || !std::isfinite(config->max_luma_mae) ||
        !std::isfinite(config->max_chroma_mae) || !std::isfinite(config->min_state_margin) ||
        config->max_luma_mae < 0 || config->max_luma_mae > 1 ||
        config->max_chroma_mae < 0 || config->max_chroma_mae > 1 ||
        config->min_state_margin < 0 || config->min_state_margin > 1) return nullptr;
    int dead = 0, alive = 0;
    for (int i = 0; i < signature_count; ++i) {
        if (signatures[i].state == MA_PLAYER_STATE_DEAD) ++dead;
        else if (signatures[i].state == MA_PLAYER_STATE_ALIVE) ++alive;
        else return nullptr;
    }
    if (dead < 3 || alive < 3) return nullptr;
    auto *matcher = new (std::nothrow) ma_player_state_matcher;
    if (!matcher) return nullptr;
    matcher->config = *config;
    matcher->signatures.assign(signatures, signatures + signature_count);
    return matcher;
}

extern "C" void ma_player_state_matcher_destroy(ma_player_state_matcher *matcher) {
    delete matcher;
}

extern "C" int ma_player_state_match_rgba(
        const ma_player_state_matcher *matcher,
        const uint8_t *rgba, int width, int height, int row_stride,
        float *out_confidence) {
    if (out_confidence) *out_confidence = 0;
    if (!matcher || !rgba || width < 1 || height < 1 || width > 8192 || height > 8192 ||
        row_stride < width * 4) return MA_PLAYER_STATE_UNKNOWN;
    Features value = extract(rgba, width, height, row_stride, matcher->config.roi);
    float dead_cost = std::numeric_limits<float>::infinity();
    float alive_cost = std::numeric_limits<float>::infinity();
    bool dead_ok = false, alive_ok = false;
    for (const ma_player_state_signature &signature : matcher->signatures) {
        int hamming = __builtin_popcountll(value.dhash ^ signature.dhash);
        float luma_error = mae(value.luma.data(), signature.luma, 64);
        float chroma_error = mae(value.chroma.data(), signature.chroma, 32);
        bool accepted = hamming <= matcher->config.max_dhash_distance &&
                luma_error <= matcher->config.max_luma_mae &&
                chroma_error <= matcher->config.max_chroma_mae;
        // A signature that fails any evidence gate is not a valid reference
        // for its class.  Keeping its distance in the class minimum would
        // let an out-of-gate sample suppress the other class through the
        // margin comparison, diverging from the calibrated matcher policy.
        if (!accepted) continue;
        float cost = (static_cast<float>(hamming) / 64.0f + luma_error + chroma_error) / 3.0f;
        if (signature.state == MA_PLAYER_STATE_DEAD) {
            dead_cost = std::min(dead_cost, cost);
            dead_ok = true;
        } else {
            alive_cost = std::min(alive_cost, cost);
            alive_ok = true;
        }
    }
    int state = MA_PLAYER_STATE_UNKNOWN;
    float best = 1;
    if (dead_ok && dead_cost + matcher->config.min_state_margin <= alive_cost) {
        state = MA_PLAYER_STATE_DEAD;
        best = dead_cost;
    } else if (alive_ok && alive_cost + matcher->config.min_state_margin <= dead_cost) {
        state = MA_PLAYER_STATE_ALIVE;
        best = alive_cost;
    }
    if (out_confidence && state != MA_PLAYER_STATE_UNKNOWN)
        *out_confidence = std::clamp(1.0f - best, 0.0f, 1.0f);
    return state;
}
