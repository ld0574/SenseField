#include "mapassist.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <new>
#include <vector>

namespace {

struct PixelRect {
    float x;
    float y;
    float w;
    float h;
};

bool finite(float value) { return std::isfinite(value); }

bool valid_config(const ma_minimap_locator_config &config) {
    const ma_rect &base = config.base_short;
    const bool fields_valid =
           finite(base.x) && finite(base.y) && finite(base.w) && finite(base.h) &&
           base.x >= 0.0f && base.y >= 0.0f && base.w > 0.01f && base.h > 0.01f &&
           base.x < 4.0f && base.y < 1.0f && base.w < 2.0f && base.h < 2.0f &&
           base.x + base.w <= 4.000001f && base.y + base.h <= 1.000001f &&
           finite(config.search_radius_x_short) &&
           finite(config.search_radius_y_short) &&
           finite(config.position_step_short) &&
           config.search_radius_x_short >= 0.0f &&
           config.search_radius_y_short >= 0.0f &&
           config.search_radius_x_short <= 1.0f &&
           config.search_radius_y_short <= 1.0f &&
           config.position_step_short > 0.0005f &&
           config.position_step_short <= 0.25f &&
           finite(config.min_scale) && finite(config.max_scale) &&
           config.min_scale >= 0.5f && config.max_scale <= 2.0f &&
           config.min_scale <= config.max_scale &&
           config.scale_steps >= 1 && config.scale_steps <= 21 &&
           finite(config.min_aspect) && finite(config.max_aspect) &&
           config.min_aspect >= 0.5f && config.max_aspect <= 2.0f &&
           config.min_aspect <= config.max_aspect &&
           config.aspect_steps >= 1 && config.aspect_steps <= 21 &&
           config.descriptor_width >= 4 && config.descriptor_width <= 64 &&
           config.descriptor_height >= 4 && config.descriptor_height <= 64 &&
           finite(config.min_score) && config.min_score >= -1.0f &&
           config.min_score <= 1.0f &&
           config.confirm_frames >= 1 && config.confirm_frames <= 30 &&
           config.hold_frames >= 0 && config.hold_frames <= 120 &&
           config.refresh_frames >= 1 && config.refresh_frames <= 600 &&
           (config.normalize_black_bars == 0 || config.normalize_black_bars == 1) &&
           config.black_threshold >= 0 && config.black_threshold <= 64 &&
           (config.preserve_base_roi == 0 || config.preserve_base_roi == 1);
    if (!fields_valid) return false;

    // Imported profiles must not be able to combine individually valid maxima
    // into an unbounded synchronous search on the capture thread.
    const int64_t x_steps = static_cast<int64_t>(std::ceil(
            config.search_radius_x_short / config.position_step_short));
    const int64_t y_steps = static_cast<int64_t>(std::ceil(
            config.search_radius_y_short / config.position_step_short));
    const int64_t candidates = (x_steps * 2 + 1) * (y_steps * 2 + 1) *
                               config.scale_steps * config.aspect_steps;
    const int64_t descriptor_samples = candidates * config.descriptor_width *
                                       config.descriptor_height;
    return candidates <= 20000 && descriptor_samples <= 8000000;
}

float lerp_step(float minimum, float maximum, int steps, int index) {
    if (steps <= 1) return (minimum + maximum) * 0.5f;
    return minimum + (maximum - minimum) * index / static_cast<float>(steps - 1);
}

bool mostly_black_column(const uint8_t *rgba, int height, int row_stride,
                         int x, int threshold) {
    constexpr int samples = 24;
    int black = 0;
    for (int index = 0; index < samples; ++index) {
        const int y = (index * (height - 1)) / std::max(1, samples - 1);
        const uint8_t *pixel = rgba + static_cast<size_t>(y) * row_stride +
                               static_cast<size_t>(x) * 4;
        if (std::max({pixel[0], pixel[1], pixel[2]}) <= threshold) ++black;
    }
    return black >= samples - 1;
}

bool mostly_black_row(const uint8_t *rgba, int width, int row_stride,
                      int y, int threshold) {
    constexpr int samples = 24;
    int black = 0;
    for (int index = 0; index < samples; ++index) {
        const int x = (index * (width - 1)) / std::max(1, samples - 1);
        const uint8_t *pixel = rgba + static_cast<size_t>(y) * row_stride +
                               static_cast<size_t>(x) * 4;
        if (std::max({pixel[0], pixel[1], pixel[2]}) <= threshold) ++black;
    }
    return black >= samples - 1;
}

PixelRect content_rect(const ma_minimap_locator_config &config,
                       const uint8_t *rgba, int width, int height, int row_stride) {
    PixelRect result{0.0f, 0.0f, static_cast<float>(width), static_cast<float>(height)};
    if (!config.normalize_black_bars) return result;
    const int max_x = std::max(0, static_cast<int>(std::floor(width * 0.20f)));
    const int max_y = std::max(0, static_cast<int>(std::floor(height * 0.20f)));
    int left = 0;
    while (left < max_x && mostly_black_column(
            rgba, height, row_stride, left, config.black_threshold)) ++left;
    int right = width;
    while (right > width - max_x && mostly_black_column(
            rgba, height, row_stride, right - 1, config.black_threshold)) --right;
    int top = 0;
    while (top < max_y && mostly_black_row(
            rgba, width, row_stride, top, config.black_threshold)) ++top;
    int bottom = height;
    while (bottom > height - max_y && mostly_black_row(
            rgba, width, row_stride, bottom - 1, config.black_threshold)) --bottom;
    if (right - left < width / 2 || bottom - top < height / 2) return result;
    return {static_cast<float>(left), static_cast<float>(top),
            static_cast<float>(right - left), static_cast<float>(bottom - top)};
}

float rectangle_iou(const PixelRect &a, const PixelRect &b) {
    const float x0 = std::max(a.x, b.x);
    const float y0 = std::max(a.y, b.y);
    const float x1 = std::min(a.x + a.w, b.x + b.w);
    const float y1 = std::min(a.y + a.h, b.y + b.h);
    const float intersection = std::max(0.0f, x1 - x0) * std::max(0.0f, y1 - y0);
    const float union_area = a.w * a.h + b.w * b.h - intersection;
    return union_area > 0.0f ? intersection / union_area : 0.0f;
}

ma_rect normalized(const PixelRect &rect, int width, int height) {
    return {rect.x / width, rect.y / height, rect.w / width, rect.h / height};
}

PixelRect detector_rect(const ma_minimap_locator_config &config,
                        const PixelRect &base, const PixelRect &anchor,
                        const PixelRect &content) {
    if (!config.preserve_base_roi) return anchor;
    const float x0 = std::max(content.x, std::min(base.x, anchor.x));
    const float y0 = std::max(content.y, std::min(base.y, anchor.y));
    const float x1 = std::min(content.x + content.w,
                              std::max(base.x + base.w, anchor.x + anchor.w));
    const float y1 = std::min(content.y + content.h,
                              std::max(base.y + base.h, anchor.y + anchor.h));
    return {x0, y0, x1 - x0, y1 - y0};
}

float luma_at(const uint8_t *rgba, int row_stride, float x, float y) {
    const int x0 = static_cast<int>(std::floor(x));
    const int y0 = static_cast<int>(std::floor(y));
    const float fx = x - x0;
    const float fy = y - y0;
    auto luma = [&](int px, int py) {
        const uint8_t *pixel = rgba + static_cast<size_t>(py) * row_stride +
                               static_cast<size_t>(px) * 4;
        return 0.299f * pixel[0] + 0.587f * pixel[1] + 0.114f * pixel[2];
    };
    const float top = luma(x0, y0) * (1.0f - fx) + luma(x0 + 1, y0) * fx;
    const float bottom = luma(x0, y0 + 1) * (1.0f - fx) + luma(x0 + 1, y0 + 1) * fx;
    return top * (1.0f - fy) + bottom * fy;
}

float score_candidate(const uint8_t *rgba, int width, int height, int row_stride,
                      const PixelRect &candidate, const std::vector<int8_t> &descriptor,
                      int descriptor_width, int descriptor_height) {
    if (candidate.x < 0.0f || candidate.y < 0.0f || candidate.w < 2.0f ||
        candidate.h < 2.0f || candidate.x + candidate.w > width ||
        candidate.y + candidate.h > height) return -2.0f;
    const int count = descriptor_width * descriptor_height;
    double sum_sample = 0.0;
    double sum_descriptor = 0.0;
    double sum_sample_squared = 0.0;
    double sum_descriptor_squared = 0.0;
    double sum_product = 0.0;
    for (int gy = 0, index = 0; gy < descriptor_height; ++gy) {
        const float source_y = std::clamp(
                candidate.y + (gy + 0.5f) * candidate.h / descriptor_height,
                0.0f, static_cast<float>(height - 1) - 1e-3f);
        for (int gx = 0; gx < descriptor_width; ++gx, ++index) {
            const float source_x = std::clamp(
                    candidate.x + (gx + 0.5f) * candidate.w / descriptor_width,
                    0.0f, static_cast<float>(width - 1) - 1e-3f);
            const double sample = luma_at(rgba, row_stride, source_x, source_y);
            const double expected = descriptor[index];
            sum_sample += sample;
            sum_descriptor += expected;
            sum_sample_squared += sample * sample;
            sum_descriptor_squared += expected * expected;
            sum_product += sample * expected;
        }
    }
    const double numerator = count * sum_product - sum_sample * sum_descriptor;
    const double sample_variance = count * sum_sample_squared - sum_sample * sum_sample;
    const double descriptor_variance =
            count * sum_descriptor_squared - sum_descriptor * sum_descriptor;
    const double denominator = std::sqrt(std::max(0.0, sample_variance) *
                                         std::max(0.0, descriptor_variance));
    return denominator > 1e-9 ? static_cast<float>(numerator / denominator) : -2.0f;
}

}  // namespace

struct ma_minimap_locator {
    ma_minimap_locator_config config{};
    std::vector<int8_t> descriptor;
    PixelRect current{};
    PixelRect pending{};
    bool has_current = false;
    bool has_pending = false;
    int pending_hits = 0;
    int misses = 0;
    int frames_since_search = 0;
    int frame_width = 0;
    int frame_height = 0;
    float score = -2.0f;
};

extern "C" ma_minimap_locator *ma_minimap_locator_create(
        const ma_minimap_locator_config *config,
        const int8_t *descriptor, int descriptor_length) {
    if (!config || !descriptor || !valid_config(*config)) return nullptr;
    const int expected = config->descriptor_width * config->descriptor_height;
    if (descriptor_length != expected) return nullptr;
    bool varied = false;
    for (int index = 1; index < descriptor_length; ++index) {
        if (descriptor[index] != descriptor[0]) {
            varied = true;
            break;
        }
    }
    if (!varied) return nullptr;
    auto *locator = new (std::nothrow) ma_minimap_locator;
    if (!locator) return nullptr;
    locator->config = *config;
    try {
        locator->descriptor.assign(descriptor, descriptor + descriptor_length);
    } catch (const std::bad_alloc &) {
        delete locator;
        return nullptr;
    }
    return locator;
}

extern "C" void ma_minimap_locator_destroy(ma_minimap_locator *locator) {
    delete locator;
}

extern "C" void ma_minimap_locator_reset(ma_minimap_locator *locator) {
    if (!locator) return;
    locator->current = {};
    locator->pending = {};
    locator->has_current = false;
    locator->has_pending = false;
    locator->pending_hits = 0;
    locator->misses = 0;
    locator->frames_since_search = 0;
    locator->frame_width = 0;
    locator->frame_height = 0;
    locator->score = -2.0f;
}

extern "C" int ma_minimap_locator_update(
        ma_minimap_locator *locator,
        const uint8_t *rgba, int width, int height, int row_stride,
        ma_rect *out_minimap, ma_rect *out_content, float *out_score) {
    // Leave every supplied output deterministic even when the caller passes
    // invalid frame metadata. This also prevents a stale locked ROI from being
    // reused after a rejected update.
    if (out_minimap) *out_minimap = {};
    if (out_content) *out_content = {};
    if (out_score) *out_score = -2.0f;
    if (!locator || !rgba || width < 2 || height < 2 || width > 8192 || height > 8192 ||
        row_stride < width * 4 || !out_minimap || !out_content || !out_score) {
        return MA_LOCATOR_SEARCHING;
    }
    if (locator->frame_width != width || locator->frame_height != height) {
        ma_minimap_locator_reset(locator);
        locator->frame_width = width;
        locator->frame_height = height;
    }
    const auto &config = locator->config;
    const PixelRect content = content_rect(config, rgba, width, height, row_stride);
    *out_content = normalized(content, width, height);
    const float short_edge = std::min(content.w, content.h);
    const PixelRect base{
        content.x + config.base_short.x * short_edge,
        content.y + config.base_short.y * short_edge,
        config.base_short.w * short_edge,
        config.base_short.h * short_edge,
    };
    if (locator->has_current &&
        locator->frames_since_search < config.refresh_frames) {
        const float tracked_score = score_candidate(
                rgba, width, height, row_stride, locator->current,
                locator->descriptor, config.descriptor_width,
                config.descriptor_height);
        locator->score = tracked_score;
        *out_score = tracked_score;
        if (tracked_score >= config.min_score) {
            locator->misses = 0;
            locator->has_pending = false;
            locator->pending_hits = 0;
            locator->pending = {};
            ++locator->frames_since_search;
            *out_minimap = normalized(
                    detector_rect(config, base, locator->current, content), width, height);
            return MA_LOCATOR_LOCKED;
        }
    }
    const float radius_x = config.search_radius_x_short * short_edge;
    const float radius_y = config.search_radius_y_short * short_edge;
    const float position_step = std::max(1.0f, config.position_step_short * short_edge);
    const int x_steps = std::max(0, static_cast<int>(std::ceil(radius_x / position_step)));
    const int y_steps = std::max(0, static_cast<int>(std::ceil(radius_y / position_step)));
    PixelRect best{};
    float best_score = -2.0f;
    for (int scale_index = 0; scale_index < config.scale_steps; ++scale_index) {
        const float scale = lerp_step(
                config.min_scale, config.max_scale, config.scale_steps, scale_index);
        for (int aspect_index = 0; aspect_index < config.aspect_steps; ++aspect_index) {
            const float aspect = lerp_step(
                    config.min_aspect, config.max_aspect,
                    config.aspect_steps, aspect_index);
            const float candidate_width = base.w * scale * aspect;
            const float candidate_height = base.h * scale;
            for (int y_step = -y_steps; y_step <= y_steps; ++y_step) {
                const float candidate_y = base.y + y_step * position_step;
                for (int x_step = -x_steps; x_step <= x_steps; ++x_step) {
                    const PixelRect candidate{
                        base.x + x_step * position_step,
                        candidate_y,
                        candidate_width,
                        candidate_height,
                    };
                    if (candidate.x < content.x || candidate.y < content.y ||
                        candidate.x + candidate.w > content.x + content.w ||
                        candidate.y + candidate.h > content.y + content.h) continue;
                    const float score = score_candidate(
                            rgba, width, height, row_stride, candidate,
                            locator->descriptor, config.descriptor_width,
                            config.descriptor_height);
                    if (score > best_score) {
                        best_score = score;
                        best = candidate;
                    }
                }
            }
        }
    }
    locator->score = best_score;
    locator->frames_since_search = 0;
    *out_score = best_score;
    if (best_score >= config.min_score) {
        if (locator->has_current && rectangle_iou(locator->current, best) >= 0.50f) {
            constexpr float smoothing = 0.35f;
            locator->current.x += (best.x - locator->current.x) * smoothing;
            locator->current.y += (best.y - locator->current.y) * smoothing;
            locator->current.w += (best.w - locator->current.w) * smoothing;
            locator->current.h += (best.h - locator->current.h) * smoothing;
            locator->has_pending = false;
            locator->pending_hits = 0;
            locator->misses = 0;
        } else {
            if (locator->has_pending && rectangle_iou(locator->pending, best) >= 0.75f) {
                ++locator->pending_hits;
                locator->pending = best;
            } else {
                locator->pending = best;
                locator->has_pending = true;
                locator->pending_hits = 1;
            }
            if (locator->pending_hits >= config.confirm_frames) {
                locator->current = locator->pending;
                locator->has_current = true;
                locator->has_pending = false;
                locator->pending_hits = 0;
                locator->misses = 0;
            }
        }
        // A different candidate must complete confirmation before it can be
        // called locked. Until then the old ROI is only a bounded hold; calling
        // it locked would run detection on a location that failed validation
        // and could keep that stale location alive indefinitely.
        if (locator->has_current &&
            rectangle_iou(locator->current, best) >= 0.50f) {
            *out_minimap = normalized(
                    detector_rect(config, base, locator->current, content), width, height);
            return MA_LOCATOR_LOCKED;
        }
        if (locator->has_current && locator->misses < config.hold_frames) {
            ++locator->misses;
            *out_minimap = normalized(
                    detector_rect(config, base, locator->current, content), width, height);
            return MA_LOCATOR_HELD;
        }
        locator->has_current = false;
        locator->current = {};
    } else {
        locator->has_pending = false;
        locator->pending_hits = 0;
        if (locator->has_current && locator->misses < config.hold_frames) {
            ++locator->misses;
            *out_minimap = normalized(
                    detector_rect(config, base, locator->current, content), width, height);
            return MA_LOCATOR_HELD;
        }
        locator->has_current = false;
        locator->current = {};
    }
    return MA_LOCATOR_SEARCHING;
}
