#include "diagnostic_pixels.h"

#include <cassert>
#include <cmath>
#include <cstdint>
#include <limits>
#include <random>
#include <vector>

namespace {

diagnostic_pixels::OutputSize old_output_size(uint64_t crop_width,
                                               uint64_t crop_height,
                                               uint64_t max_edge) {
    const double scale = std::min(
            1.0, static_cast<double>(std::min<uint64_t>(max_edge, 1280)) /
                         static_cast<double>(std::max(crop_width, crop_height)));
    return {
        std::max<uint64_t>(1, static_cast<uint64_t>(std::floor(crop_width * scale + 0.5))),
        std::max<uint64_t>(1, static_cast<uint64_t>(std::floor(crop_height * scale + 0.5))),
    };
}

std::vector<uint32_t> old_java_oracle(const std::vector<uint8_t> &rgba,
                                     uint64_t width, uint64_t height,
                                     uint64_t row_stride,
                                     uint64_t x0, uint64_t y0,
                                     uint64_t crop_width, uint64_t crop_height,
                                     uint64_t output_width, uint64_t output_height) {
    assert(x0 + crop_width <= width && y0 + crop_height <= height);
    std::vector<uint32_t> result(output_width * output_height);
    for (uint64_t y = 0; y < output_height; ++y) {
        const uint64_t source_y = y0 + y * crop_height / output_height;
        for (uint64_t x = 0; x < output_width; ++x) {
            const uint64_t source_x = x0 + x * crop_width / output_width;
            const uint64_t at = source_y * row_stride + source_x * 4;
            // This is the value produced by ByteBuffer.getInt(at) in BIG_ENDIAN
            // order followed by the previous Java channel conversion.
            const uint32_t rgba_pixel =
                    (static_cast<uint32_t>(rgba[at]) << 24) |
                    (static_cast<uint32_t>(rgba[at + 1]) << 16) |
                    (static_cast<uint32_t>(rgba[at + 2]) << 8) |
                    static_cast<uint32_t>(rgba[at + 3]);
            result[y * output_width + x] = 0xff000000u |
                    ((rgba_pixel >> 24) << 16) |
                    (((rgba_pixel >> 16) & 255u) << 8) |
                    ((rgba_pixel >> 8) & 255u);
        }
    }
    return result;
}

void check_copy_matches_old_algorithm(uint64_t width, uint64_t height,
                                      uint64_t row_stride, uint64_t x0, uint64_t y0,
                                      uint64_t crop_width, uint64_t crop_height,
                                      uint64_t max_edge, std::mt19937 &random) {
    assert(width > 0 && height > 0 && row_stride >= width * 4);
    const uint64_t required = (height - 1) * row_stride + width * 4;
    const uint64_t capacity = row_stride * height + 13;
    const uint64_t limit = required + (capacity - required) / 2;
    std::vector<uint8_t> source(static_cast<size_t>(capacity));
    for (uint8_t &byte : source) byte = static_cast<uint8_t>(random());

    const auto size = old_output_size(crop_width, crop_height, max_edge);
    std::vector<uint32_t> actual(size.width * size.height, 0x12121212u);
    const auto expected = old_java_oracle(source, width, height, row_stride,
                                          x0, y0, crop_width, crop_height,
                                          size.width, size.height);
    assert(diagnostic_pixels::copy_rgba_to_argb(
            source.data(), capacity, limit, width, height, row_stride,
            x0, y0, crop_width, crop_height, size.width, size.height,
            actual.size(), actual.data()));
    assert(actual == expected);
}

void test_invalid_bounds_and_overflow() {
    uint8_t bytes[80] = {};
    uint32_t output[6] = {};

    // The full padded image through its last pixel must fit both the direct
    // buffer capacity and the explicit ByteBuffer limit.
    assert(!diagnostic_pixels::copy_rgba_to_argb(
            bytes, sizeof(bytes), 31, 2, 3, 12, 0, 0, 2, 3, 2, 3, 6, output));
    assert(!diagnostic_pixels::copy_rgba_to_argb(
            bytes, 31, 32, 2, 3, 12, 0, 0, 2, 3, 2, 3, 6, output));
    assert(diagnostic_pixels::copy_rgba_to_argb(
            bytes, sizeof(bytes), 32, 2, 3, 12, 0, 0, 2, 3, 2, 3, 6, output));

    assert(!diagnostic_pixels::copy_rgba_to_argb(
            bytes, sizeof(bytes), sizeof(bytes), 2, 3, 12, 1, 0, 2, 1,
            2, 1, 2, output));  // crop extends past the source width
    assert(!diagnostic_pixels::copy_rgba_to_argb(
            bytes, sizeof(bytes), sizeof(bytes), 2, 3, 12, 0, 0, 0, 1,
            1, 1, 1, output));  // empty crop
    assert(!diagnostic_pixels::copy_rgba_to_argb(
            bytes, sizeof(bytes), sizeof(bytes), 2, 3, 7, 0, 0, 2, 3,
            2, 3, 6, output));  // short row stride
    assert(!diagnostic_pixels::copy_rgba_to_argb(
            bytes, sizeof(bytes), sizeof(bytes), 2, 3, 12, 0, 0, 2, 3,
            2, 2, 4, output));  // incorrect output geometry
    assert(!diagnostic_pixels::copy_rgba_to_argb(
            bytes, sizeof(bytes), sizeof(bytes), 2, 3, 12, 0, 0, 2, 3,
            2, 3, 5, output));  // incorrect output array length
    assert(!diagnostic_pixels::copy_rgba_to_argb(
            bytes, sizeof(bytes), sizeof(bytes), 2, 3, 12, 0, 0, 2, 3,
            1281, 1, 1281, output));  // out of supported thumbnail bounds

    const uint64_t max = std::numeric_limits<uint64_t>::max();
    assert(!diagnostic_pixels::copy_rgba_to_argb(
            bytes, max, max, max, 1, max, 0, 0, 1, 1, 1, 1, 1, output));
    assert(!diagnostic_pixels::copy_rgba_to_argb(
            bytes, max, max, 1, max, 4, 0, 0, 1, 1, 1, 1, 1, output));
    assert(!diagnostic_pixels::copy_rgba_to_argb(
            bytes, sizeof(bytes), sizeof(bytes), 2, 3, 12, max, 0, 1, 1,
            1, 1, 1, output));  // crop coordinate addition overflow
}

void test_edges_and_padded_rows(std::mt19937 &random) {
    check_copy_matches_old_algorithm(4, 3, 20, 1, 1, 3, 2, 1, random);
    check_copy_matches_old_algorithm(5, 2, 24, 0, 0, 5, 2, 3, random);
    check_copy_matches_old_algorithm(1281, 1, 5132, 0, 0, 1281, 1, 9000, random);
    check_copy_matches_old_algorithm(17, 13, 84, 2, 3, 12, 8, 11, random);
}

void test_randomized_parity() {
    std::mt19937 random(0x5eed1234u);
    std::uniform_int_distribution<int> dimensions(1, 240);
    std::uniform_int_distribution<int> edge(1, 1600);
    for (int iteration = 0; iteration < 5000; ++iteration) {
        const uint64_t width = static_cast<uint64_t>(dimensions(random));
        const uint64_t height = static_cast<uint64_t>(dimensions(random));
        const uint64_t row_stride = width * 4 + static_cast<uint64_t>(random() % 37);
        const uint64_t x0 = static_cast<uint64_t>(random() % width);
        const uint64_t y0 = static_cast<uint64_t>(random() % height);
        const uint64_t crop_width = 1 + static_cast<uint64_t>(random() % (width - x0));
        const uint64_t crop_height = 1 + static_cast<uint64_t>(random() % (height - y0));
        check_copy_matches_old_algorithm(width, height, row_stride, x0, y0,
                crop_width, crop_height, static_cast<uint64_t>(edge(random)), random);
    }
}

}  // namespace

int main() {
    test_invalid_bounds_and_overflow();
    std::mt19937 random(0xc0ffeeu);
    test_edges_and_padded_rows(random);
    test_randomized_parity();
    return 0;
}
