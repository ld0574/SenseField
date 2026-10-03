#ifndef MAPASSIST_DIAGNOSTIC_PIXELS_H
#define MAPASSIST_DIAGNOSTIC_PIXELS_H

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace diagnostic_pixels {

constexpr uint64_t kMaximumEdge = 1280;

struct OutputSize {
    uint64_t width;
    uint64_t height;
};

inline bool checked_multiply(uint64_t left, uint64_t right, uint64_t *result) {
    if (!result || (right != 0 && left > std::numeric_limits<uint64_t>::max() / right))
        return false;
    *result = left * right;
    return true;
}

inline bool checked_add(uint64_t left, uint64_t right, uint64_t *result) {
    if (!result || left > std::numeric_limits<uint64_t>::max() - right) return false;
    *result = left + right;
    return true;
}

// Java computes this with Math.round, which for nonnegative inputs is
// floor(value + 0.5). The output edge is inferred from the supplied output
// size by copy_rgba_to_argb; on the dominant axis it is exact for every
// thumbnail created by DiagnosticPixels.copy.
inline bool compute_output_size(uint64_t crop_width, uint64_t crop_height,
                                uint64_t max_edge, OutputSize *result) {
    if (!result || crop_width == 0 || crop_height == 0 || max_edge == 0) return false;
    const uint64_t edge = std::min(max_edge, kMaximumEdge);
    const uint64_t longest = std::max(crop_width, crop_height);
    const double scale = std::min(1.0, static_cast<double>(edge) /
                                          static_cast<double>(longest));
    const double scaled_width = static_cast<double>(crop_width) * scale;
    const double scaled_height = static_cast<double>(crop_height) * scale;
    const double rounded_width = std::floor(scaled_width + 0.5);
    const double rounded_height = std::floor(scaled_height + 0.5);
    if (!std::isfinite(rounded_width) || !std::isfinite(rounded_height) ||
        rounded_width > static_cast<double>(kMaximumEdge) ||
        rounded_height > static_cast<double>(kMaximumEdge)) return false;
    result->width = std::max<uint64_t>(1, static_cast<uint64_t>(rounded_width));
    result->height = std::max<uint64_t>(1, static_cast<uint64_t>(rounded_height));
    return true;
}

// Copy RGBA bytes into opaque ARGB words. All bounds are validated before the
// first output write; source pixels are read by byte to avoid alignment and
// host-endianness assumptions.
inline bool copy_rgba_to_argb(const uint8_t *source,
                              uint64_t source_capacity,
                              uint64_t source_limit,
                              uint64_t width,
                              uint64_t height,
                              uint64_t row_stride,
                              uint64_t crop_x,
                              uint64_t crop_y,
                              uint64_t crop_width,
                              uint64_t crop_height,
                              uint64_t output_width,
                              uint64_t output_height,
                              uint64_t output_length,
                              uint32_t *output) {
    if (!source || !output || width == 0 || height == 0 || row_stride == 0 ||
        source_limit > source_capacity || crop_width == 0 || crop_height == 0 ||
        output_width == 0 || output_height == 0 ||
        output_width > kMaximumEdge || output_height > kMaximumEdge) return false;

    uint64_t row_bytes = 0;
    uint64_t preceding_rows = 0;
    uint64_t required_bytes = 0;
    if (!checked_multiply(width, 4, &row_bytes) || row_stride < row_bytes ||
        !checked_multiply(height - 1, row_stride, &preceding_rows) ||
        !checked_add(preceding_rows, row_bytes, &required_bytes) ||
        required_bytes > source_capacity || required_bytes > source_limit) return false;

    uint64_t crop_right = 0;
    uint64_t crop_bottom = 0;
    if (!checked_add(crop_x, crop_width, &crop_right) ||
        !checked_add(crop_y, crop_height, &crop_bottom) ||
        crop_right > width || crop_bottom > height) return false;

    const uint64_t inferred_edge = std::max(output_width, output_height);
    OutputSize expected{};
    if (!compute_output_size(crop_width, crop_height, inferred_edge, &expected) ||
        expected.width != output_width || expected.height != output_height) return false;

    uint64_t expected_length = 0;
    if (!checked_multiply(output_width, output_height, &expected_length) ||
        expected_length != output_length) return false;

    // The nearest-neighbor coordinate products below must also be proven safe
    // before writing any destination pixels.
    uint64_t ignored = 0;
    if ((output_width > 1 && !checked_multiply(output_width - 1, crop_width, &ignored)) ||
        (output_height > 1 && !checked_multiply(output_height - 1, crop_height, &ignored)))
        return false;

    for (uint64_t y = 0; y < output_height; ++y) {
        const uint64_t source_y = crop_y + (y * crop_height / output_height);
        const uint64_t row_offset = source_y * row_stride;
        for (uint64_t x = 0; x < output_width; ++x) {
            const uint64_t source_x = crop_x + (x * crop_width / output_width);
            const uint64_t at = row_offset + source_x * 4;
            output[y * output_width + x] = 0xff000000u |
                    (static_cast<uint32_t>(source[at]) << 16) |
                    (static_cast<uint32_t>(source[at + 1]) << 8) |
                    static_cast<uint32_t>(source[at + 2]);
        }
    }
    return true;
}

}  // namespace diagnostic_pixels

#endif  // MAPASSIST_DIAGNOSTIC_PIXELS_H
