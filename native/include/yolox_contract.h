#ifndef MAPASSIST_YOLOX_CONTRACT_H
#define MAPASSIST_YOLOX_CONTRACT_H

#include <cstdint>

namespace mapassist_yolox {

// Android only accepts model inputs large enough for the production 320px
// graph. Keeping the alignment here makes the native guard match the Java
// metadata guard while allowing future 32-pixel multiples such as 416.
constexpr int kMinInputSize = 320;
constexpr int kMaxInputSize = 1024;
constexpr int kInputAlignment = 32;
constexpr int kClassCountLimit = 8;
constexpr int kOutputFieldCount = 5;
constexpr int kStrides[] = {8, 16, 32};

inline bool valid_input_size(int input_size) {
    return input_size >= kMinInputSize && input_size <= kMaxInputSize &&
           input_size % kInputAlignment == 0;
}

inline int anchor_count(int input_size) {
    if (!valid_input_size(input_size)) return 0;
    std::int64_t total = 0;
    for (const int stride : kStrides) {
        const int grid = input_size / stride;
        total += static_cast<std::int64_t>(grid) * grid;
    }
    return total > INT32_MAX ? 0 : static_cast<int>(total);
}

inline bool valid_output_shape(int input_size, int class_count,
                               int output_anchors, int output_width) {
    return class_count >= 1 && class_count <= kClassCountLimit &&
           anchor_count(input_size) > 0 &&
           output_anchors == anchor_count(input_size) &&
           output_width == kOutputFieldCount + class_count;
}

}  // namespace mapassist_yolox

#endif  // MAPASSIST_YOLOX_CONTRACT_H
