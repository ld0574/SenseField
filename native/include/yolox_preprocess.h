#ifndef MAPASSIST_YOLOX_PREPROCESS_H
#define MAPASSIST_YOLOX_PREPROCESS_H

#include <mat.h>

namespace mapassist_yolox {

// The caller owns the RGBA plane for the duration of this synchronous read.
inline ncnn::Mat resize_roi_rgba_to_bgr(const unsigned char *rgba,
        int width, int height, int row_stride, int x0, int y0,
        int crop_width, int crop_height, int resized_width, int resized_height) {
    if (!rgba || width <= 0 || height <= 0 || x0 < 0 || y0 < 0 ||
        crop_width <= 0 || crop_height <= 0 || crop_width > width || crop_height > height ||
        x0 > width - crop_width || y0 > height - crop_height ||
        row_stride < static_cast<long long>(width) * 4 ||
        resized_width <= 0 || resized_height <= 0) return {};
    return ncnn::Mat::from_pixels_roi_resize(rgba, ncnn::Mat::PIXEL_RGBA2BGR,
            width, height, row_stride, x0, y0, crop_width, crop_height,
            resized_width, resized_height);
}

}  // namespace mapassist_yolox
#endif
