#ifndef MAPASSIST_EXACT_ROI_CACHE_H
#define MAPASSIST_EXACT_ROI_CACHE_H

#include <cstdint>
#include <cstring>
#include <vector>
#include "mapassist.h"

namespace mapassist {

inline void append_current_observations(const std::vector<ma_observation> &cached,
                                       int64_t timestamp_ms, std::vector<ma_observation> &output) {
    for (auto observation : cached) {
        observation.timestamp_ms = timestamp_ms;
        output.push_back(observation);
    }
}

/** Owned exact pixels, not a hash or a visual-similarity/threshold heuristic. */
class ExactRoiCache {
 public:
    struct Rect { int x, y, width, height; };
    static constexpr size_t kMaxBytes = 8u * 1024u * 1024u;

    bool matches(const uint8_t *rgba, int width, int height, int stride, Rect roi) const {
        if (!valid_ || !valid(rgba, width, height, stride, roi) || width != width_ || height != height_
                || roi.x != roi_.x || roi.y != roi_.y || roi.width != roi_.width || roi.height != roi_.height)
            return false;
        const size_t row_bytes = static_cast<size_t>(roi.width) * 4;
        for (int y = 0; y < roi.height; ++y) {
            const auto *row = rgba + static_cast<size_t>(roi.y + y) * stride + static_cast<size_t>(roi.x) * 4;
            if (std::memcmp(row, pixels_.data() + static_cast<size_t>(y) * row_bytes, row_bytes) != 0)
                return false;
        }
        return true;
    }

    bool store(const uint8_t *rgba, int width, int height, int stride, Rect roi) {
        clear();
        if (!valid(rgba, width, height, stride, roi)) return false;
        const size_t row_bytes = static_cast<size_t>(roi.width) * 4;
        pixels_.resize(row_bytes * roi.height);
        for (int y = 0; y < roi.height; ++y)
            std::memcpy(pixels_.data() + static_cast<size_t>(y) * row_bytes,
                    rgba + static_cast<size_t>(roi.y + y) * stride + static_cast<size_t>(roi.x) * 4, row_bytes);
        width_ = width; height_ = height; roi_ = roi; valid_ = true;
        return true;
    }

    void clear() { valid_ = false; pixels_.clear(); }
    size_t bytes() const { return pixels_.size(); }

 private:
    static bool valid(const uint8_t *rgba, int width, int height, int stride, Rect roi) {
        return rgba && width > 0 && height > 0 && width <= 8192 && height <= 8192
                && stride >= static_cast<int64_t>(width) * 4 && roi.x >= 0 && roi.y >= 0
                && roi.width > 0 && roi.height > 0 && static_cast<int64_t>(roi.x) + roi.width <= width
                && static_cast<int64_t>(roi.y) + roi.height <= height
                && static_cast<uint64_t>(roi.width) * roi.height * 4 <= kMaxBytes;
    }
    bool valid_ = false;
    int width_ = 0, height_ = 0;
    Rect roi_{};
    std::vector<uint8_t> pixels_;
};
}
#endif
