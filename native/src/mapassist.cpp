#include "mapassist.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <new>
#include <vector>

namespace {

struct PixelRect {
    int x0;
    int y0;
    int x1;
    int y1;
};

PixelRect pixels(ma_rect r, int width, int height) {
    const int x0 = std::clamp(static_cast<int>(std::floor(r.x * width)), 0, width);
    const int y0 = std::clamp(static_cast<int>(std::floor(r.y * height)), 0, height);
    const int x1 = std::clamp(static_cast<int>(std::ceil((r.x + r.w) * width)), 0, width);
    const int y1 = std::clamp(static_cast<int>(std::ceil((r.y + r.h) * height)), 0, height);
    return {x0, y0, std::max(x0, x1), std::max(y0, y1)};
}

PixelRect reference_pixels(ma_rect r, int width, int height) {
    const int x0 = std::clamp(static_cast<int>(std::lround(r.x * width)), 0, width);
    const int y0 = std::clamp(static_cast<int>(std::lround(r.y * height)), 0, height);
    const int x1 = std::clamp(
            static_cast<int>(std::lround((r.x + r.w) * width)), x0, width);
    const int y1 = std::clamp(
            static_cast<int>(std::lround((r.y + r.h) * height)), y0, height);
    return {x0, y0, x1, y1};
}

bool has_rect(ma_rect r) {
    return std::isfinite(r.x) && std::isfinite(r.y) &&
           std::isfinite(r.w) && std::isfinite(r.h) &&
           r.x >= 0.0f && r.y >= 0.0f && r.w > 0.0f && r.h > 0.0f &&
           r.x + r.w <= 1.001f && r.y + r.h <= 1.001f;
}

PixelRect direction_reference(const ma_profile &profile, PixelRect fallback,
                              int width, int height) {
    return has_rect(profile.minimap_direction)
            ? reference_pixels(profile.minimap_direction, width, height) : fallback;
}

bool contains(PixelRect r, int x, int y) {
    return x >= r.x0 && x < r.x1 && y >= r.y0 && y < r.y1;
}

bool intersects(PixelRect a, PixelRect b) {
    return a.x0 < b.x1 && a.x1 > b.x0 && a.y0 < b.y1 && a.y1 > b.y0;
}

ma_rect normalized(PixelRect r, int width, int height) {
    return {static_cast<float>(r.x0) / width, static_cast<float>(r.y0) / height,
            static_cast<float>(r.x1 - r.x0) / width,
            static_cast<float>(r.y1 - r.y0) / height};
}

int direction_for(int x, int y, PixelRect reference) {
    const float cx = (reference.x0 + reference.x1) * 0.5f;
    const float cy = (reference.y0 + reference.y1) * 0.5f;
    const float dx = (x - cx) / std::max(1.0f, (reference.x1 - reference.x0) * 0.5f);
    const float dy = (y - cy) / std::max(1.0f, (reference.y1 - reference.y0) * 0.5f);
    // An icon near the reference centre has no reliable cardinal direction.
    if (std::abs(dx) < 0.15f && std::abs(dy) < 0.15f) return MA_DIR_NONE;
    if (std::abs(dx) >= std::abs(dy)) return dx < 0 ? MA_DIR_LEFT : MA_DIR_RIGHT;
    return dy < 0 ? MA_DIR_UP : MA_DIR_DOWN;
}

bool red_pixel(const uint8_t *p, const ma_profile &profile) {
    return p[0] >= profile.red_min &&
           p[0] >= p[1] * profile.red_dominance &&
           p[0] >= p[2] * profile.red_dominance;
}

float template_score(const uint8_t *frame, int frame_stride, int x, int y,
                     const ma_template &templ, float stop_below) {
    // Transparent template pixels are ignored. A sparse 2-pixel sampling grid
    // keeps this deterministic and cheap on mobile CPUs.
    int64_t error = 0;
    int samples = 0;
    const int total_possible = ((templ.width + 1) / 2) * ((templ.height + 1) / 2);
    for (int ty = 0; ty < templ.height; ty += 2) {
        const uint8_t *tp = templ.rgba + ty * templ.row_stride;
        const uint8_t *fp = frame + (y + ty) * frame_stride + x * 4;
        for (int tx = 0; tx < templ.width; tx += 2) {
            const uint8_t *t = tp + tx * 4;
            if (t[3] < 128) continue;
            const uint8_t *f = fp + tx * 4;
            error += std::abs(static_cast<int>(f[0]) - t[0]);
            error += std::abs(static_cast<int>(f[1]) - t[1]);
            error += std::abs(static_cast<int>(f[2]) - t[2]);
            ++samples;
            if (stop_below > 0.0f && samples > 8) {
                const int64_t max_error = static_cast<int64_t>(
                    (1.0f - stop_below) * 255.0f * 3.0f * total_possible);
                if (error > max_error) return 0.0f;
            }
        }
    }
    return samples ? std::clamp(1.0f - static_cast<float>(error) / (samples * 3 * 255), 0.0f, 1.0f)
                   : 0.0f;
}

struct Match {
    PixelRect box;
    float score;
};

struct Anchor {
    int x;
    int y;
    uint8_t r;
    uint8_t g;
    uint8_t b;
};

std::array<Anchor, 4> template_anchors(const ma_template &templ, int &count) {
    int opaque = 0;
    for (int y = 0; y < templ.height; y += 2) {
        const uint8_t *row = templ.rgba + y * templ.row_stride;
        for (int x = 0; x < templ.width; x += 2) {
            if (row[x * 4 + 3] >= 128) ++opaque;
        }
    }
    count = std::min(4, opaque);
    std::array<Anchor, 4> anchors{};
    if (!count) return anchors;
    int seen = 0;
    int selected = 0;
    for (int y = 0; y < templ.height && selected < count; y += 2) {
        const uint8_t *row = templ.rgba + y * templ.row_stride;
        for (int x = 0; x < templ.width && selected < count; x += 2) {
            const uint8_t *pixel = row + x * 4;
            if (pixel[3] < 128) continue;
            if (seen == selected * (opaque - 1) / std::max(1, count - 1)) {
                anchors[selected++] = {x, y, pixel[0], pixel[1], pixel[2]};
            }
            ++seen;
        }
    }
    return anchors;
}

bool plausible_anchor(const uint8_t *rgba, int row_stride, int x, int y,
                      const std::array<Anchor, 4> &anchors, int count,
                      int allowed_error) {
    for (int i = 0; i < count; ++i) {
        const Anchor &anchor = anchors[i];
        const uint8_t *pixel = rgba + (y + anchor.y) * row_stride + (x + anchor.x) * 4;
        const int error = std::abs(static_cast<int>(pixel[0]) - anchor.r) +
                          std::abs(static_cast<int>(pixel[1]) - anchor.g) +
                          std::abs(static_cast<int>(pixel[2]) - anchor.b);
        if (error <= allowed_error) return true;
    }
    return false;
}

void find_template(const uint8_t *rgba, int row_stride,
                   PixelRect area, const ma_template *templ, float threshold,
                   int max_matches, std::vector<Match> &matches) {
    if (!templ || !templ->rgba || templ->width <= 0 || templ->height <= 0 ||
        templ->row_stride < templ->width * 4 || area.x1 - area.x0 < templ->width ||
        area.y1 - area.y0 < templ->height) return;

    int anchor_count = 0;
    const auto anchors = template_anchors(*templ, anchor_count);
    if (!anchor_count) return;
    // A lenient colour check avoids full comparisons on obvious background.
    // Final matches still use the complete alpha-masked template score.
    const int allowed_anchor_error = std::max(
            120, static_cast<int>((1.0f - threshold) * 765.0f));

    // Keep the strongest local maxima instead of emitting a match at every
    // neighbouring pixel of the same icon.
    for (int y = area.y0; y <= area.y1 - templ->height; ++y) {
        for (int x = area.x0; x <= area.x1 - templ->width; ++x) {
            if (!plausible_anchor(rgba, row_stride, x, y, anchors, anchor_count,
                                  allowed_anchor_error)) continue;
            const float score = template_score(rgba, row_stride, x, y, *templ, threshold);
            if (score < threshold) continue;
            Match candidate{{x, y, x + templ->width, y + templ->height}, score};
            bool merged = false;
            for (Match &existing : matches) {
                const int dx = std::abs(existing.box.x0 - x);
                const int dy = std::abs(existing.box.y0 - y);
                if (dx < templ->width / 2 && dy < templ->height / 2) {
                    if (score > existing.score) existing = candidate;
                    merged = true;
                    break;
                }
            }
            if (!merged) matches.push_back(candidate);
        }
    }
    std::sort(matches.begin(), matches.end(), [](const Match &a, const Match &b) {
        return a.score > b.score;
    });
    if (static_cast<int>(matches.size()) > max_matches) matches.resize(max_matches);
}

void detect_main_red_bars(const uint8_t *rgba, int width, int height, int row_stride,
                          int64_t timestamp_ms, const ma_profile &profile,
                          std::vector<ma_observation> &out) {
    if (!profile.enable_main_bar) return;
    constexpr int scale = 2;
    const int grid_w = (width + scale - 1) / scale;
    const int grid_h = (height + scale - 1) / scale;
    std::vector<uint8_t> red(static_cast<size_t>(grid_w) * grid_h, 0);
    std::vector<uint8_t> visited(red.size(), 0);
    const PixelRect center = pixels(profile.center_mask, width, height);
    const PixelRect minimap = pixels(profile.minimap, width, height);
    const PixelRect ping = pixels(profile.ping_area, width, height);

    for (int gy = 0; gy < grid_h; ++gy) {
        const int y = gy * scale;
        const uint8_t *row = rgba + y * row_stride;
        for (int gx = 0; gx < grid_w; ++gx) {
            const int x = gx * scale;
            if (contains(center, x, y) || contains(minimap, x, y) || contains(ping, x, y)) continue;
            red[static_cast<size_t>(gy) * grid_w + gx] = red_pixel(row + x * 4, profile) ? 1 : 0;
        }
    }

    std::vector<int> queue;
    queue.reserve(1024);
    for (size_t start = 0; start < red.size(); ++start) {
        if (!red[start] || visited[start]) continue;
        queue.clear();
        queue.push_back(static_cast<int>(start));
        visited[start] = 1;
        int min_x = grid_w, max_x = 0, min_y = grid_h, max_y = 0;
        size_t head = 0;
        while (head < queue.size()) {
            const int index = queue[head++];
            const int gx = index % grid_w;
            const int gy = index / grid_w;
            min_x = std::min(min_x, gx); max_x = std::max(max_x, gx);
            min_y = std::min(min_y, gy); max_y = std::max(max_y, gy);
            const int nx[4] = {gx - 1, gx + 1, gx, gx};
            const int ny[4] = {gy, gy, gy - 1, gy + 1};
            for (int k = 0; k < 4; ++k) {
                if (nx[k] < 0 || nx[k] >= grid_w || ny[k] < 0 || ny[k] >= grid_h) continue;
                const int neighbour = ny[k] * grid_w + nx[k];
                if (red[neighbour] && !visited[neighbour]) {
                    visited[neighbour] = 1;
                    queue.push_back(neighbour);
                }
            }
        }
        PixelRect box{min_x * scale, min_y * scale,
                      std::min(width, (max_x + 1) * scale),
                      std::min(height, (max_y + 1) * scale)};
        const float bw = static_cast<float>(box.x1 - box.x0);
        const float bh = static_cast<float>(box.y1 - box.y0);
        const float aspect = bw / std::max(1.0f, bh);
        if (bw < width * profile.main_min_width_ratio ||
            bh > height * profile.main_max_height_ratio ||
            aspect < profile.main_min_aspect ||
            intersects(box, center)) continue;
        const float fill = static_cast<float>(queue.size()) /
                           ((max_x - min_x + 1) * (max_y - min_y + 1));
        const float confidence = std::clamp(0.55f + 0.025f * aspect + 0.18f * fill,
                                            0.0f, 0.98f);
        out.push_back({MA_MAIN_ENEMY,
                       direction_for((box.x0 + box.x1) / 2, (box.y0 + box.y1) / 2,
                                     {0, 0, width, height}),
                       normalized(box, width, height), confidence, timestamp_ms});
    }
}

void detect_minimap_red_rings(const uint8_t *rgba, int width, int height, int row_stride,
                              int64_t timestamp_ms, const ma_profile &profile,
                              std::vector<ma_observation> &out) {
    if (!profile.enable_minimap_red_ring) return;
    const PixelRect area = pixels(profile.minimap, width, height);
    const PixelRect reference = direction_reference(profile, area, width, height);
    const int area_w = area.x1 - area.x0;
    const int area_h = area.y1 - area.y0;
    const int short_side = std::min(area_w, area_h);
    if (short_side < 24) return;

    const size_t pixel_count = static_cast<size_t>(area_w) * area_h;
    std::vector<uint8_t> raw(pixel_count, 0);
    std::vector<uint8_t> expanded(pixel_count, 0);
    std::vector<uint8_t> visited(pixel_count, 0);
    const int dilation = std::clamp(static_cast<int>(std::lround(short_side / 108.0f)), 1, 6);
    int cyan_map_pixels = 0;
    int dark_map_pixels = 0;
    constexpr int presence_grid = 4;
    int cyan_cells[presence_grid * presence_grid] = {};
    int dark_cells[presence_grid * presence_grid] = {};
    int cell_pixels[presence_grid * presence_grid] = {};
    for (int local_y = 0; local_y < area_h; ++local_y) {
        const uint8_t *row = rgba + (area.y0 + local_y) * row_stride + area.x0 * 4;
        for (int local_x = 0; local_x < area_w; ++local_x) {
            const uint8_t *pixel = row + local_x * 4;
            const int cell_x = std::min(presence_grid - 1,
                                        local_x * presence_grid / area_w);
            const int cell_y = std::min(presence_grid - 1,
                                        local_y * presence_grid / area_h);
            const int cell = cell_y * presence_grid + cell_x;
            ++cell_pixels[cell];
            if (std::max({pixel[0], pixel[1], pixel[2]}) < 65) {
                ++dark_map_pixels;
                ++dark_cells[cell];
            }
            if (pixel[1] >= 70 && pixel[2] >= 70 &&
                pixel[1] >= pixel[0] * 1.25f && pixel[2] >= pixel[0] * 1.25f) {
                ++cyan_map_pixels;
                ++cyan_cells[cell];
            }
            if (!red_pixel(pixel, profile)) continue;
            raw[static_cast<size_t>(local_y) * area_w + local_x] = 1;
            for (int dy = -dilation; dy <= dilation; ++dy) {
                const int y = local_y + dy;
                if (y < 0 || y >= area_h) continue;
                for (int dx = -dilation; dx <= dilation; ++dx) {
                    const int x = local_x + dx;
                    if (x >= 0 && x < area_w)
                        expanded[static_cast<size_t>(y) * area_w + x] = 1;
                }
            }
        }
    }

    // Scoreboards, hero cards, and menus can cover the minimap while retaining
    // red and cyan UI. A visible map has dark terrain plus cyan lane/base marks
    // spread across it; card borders tend to be bright or concentrated in only
    // a few cells. Stay silent unless both pieces of evidence are present.
    int cyan_occupied_cells = 0;
    int dark_occupied_cells = 0;
    for (int cell = 0; cell < presence_grid * presence_grid; ++cell) {
        if (cyan_cells[cell] >= std::max(1, static_cast<int>(
                std::ceil(cell_pixels[cell] * 0.008f)))) ++cyan_occupied_cells;
        if (dark_cells[cell] >= std::max(1, static_cast<int>(
                std::ceil(cell_pixels[cell] * 0.05f)))) ++dark_occupied_cells;
    }
    if (cyan_map_pixels < static_cast<int>(short_side * short_side * 0.008f) ||
        cyan_occupied_cells < 12 ||
        dark_occupied_cells < 11 ||
        dark_map_pixels < static_cast<int>(short_side * short_side * 0.06f)) return;

    // Rectangle sums over the unexpanded red mask let us inspect large
    // components without rescanning their pixels. This matters when two
    // neighbouring portrait rings become connected by the one-pixel dilation.
    const int integral_stride = area_w + 1;
    std::vector<int> red_integral(static_cast<size_t>(integral_stride) * (area_h + 1), 0);
    for (int y = 0; y < area_h; ++y) {
        int row_sum = 0;
        for (int x = 0; x < area_w; ++x) {
            row_sum += raw[static_cast<size_t>(y) * area_w + x];
            red_integral[static_cast<size_t>(y + 1) * integral_stride + x + 1] =
                    red_integral[static_cast<size_t>(y) * integral_stride + x + 1] +
                    row_sum;
        }
    }
    const auto red_sum = [&](int x0, int y0, int x1, int y1) {
        return red_integral[static_cast<size_t>(y1) * integral_stride + x1] -
               red_integral[static_cast<size_t>(y0) * integral_stride + x1] -
               red_integral[static_cast<size_t>(y1) * integral_stride + x0] +
               red_integral[static_cast<size_t>(y0) * integral_stride + x0];
    };

    const int minimum_size = std::max(4, static_cast<int>(std::ceil(short_side * 0.105f)));
    const int maximum_size = std::max(minimum_size, static_cast<int>(std::ceil(short_side * 0.45f)));
    const int minimum_red = std::max(3, static_cast<int>(std::ceil(short_side * short_side * 0.0035f)));
    const int minimum_component_area = std::max(
            1, static_cast<int>(std::ceil(short_side * short_side * 0.017f)));
    const int portrait_width = std::max(6, static_cast<int>(std::lround(short_side * 0.15f)));
    const int portrait_height = std::max(7, static_cast<int>(std::lround(short_side * 0.17f)));
    const int ring_band = std::max(2, static_cast<int>(std::lround(short_side * 0.032f)));
    const int split_score_minimum = std::max(20, static_cast<int>(std::lround(
            150.0f * short_side * short_side / (108.0f * 108.0f))));
    struct RingWindow {
        int score;
        int x;
        int y;
    };
    std::vector<int> queue;
    queue.reserve(2048);
    for (size_t start = 0; start < expanded.size(); ++start) {
        if (!expanded[start] || visited[start]) continue;
        queue.clear();
        queue.push_back(static_cast<int>(start));
        visited[start] = 1;
        int min_x = area_w, max_x = 0, min_y = area_h, max_y = 0;
        int red_count = 0;
        size_t head = 0;
        while (head < queue.size()) {
            const int index = queue[head++];
            const int x = index % area_w;
            const int y = index / area_w;
            min_x = std::min(min_x, x); max_x = std::max(max_x, x);
            min_y = std::min(min_y, y); max_y = std::max(max_y, y);
            red_count += raw[static_cast<size_t>(index)];
            const int nx[4] = {x - 1, x + 1, x, x};
            const int ny[4] = {y, y, y - 1, y + 1};
            for (int k = 0; k < 4; ++k) {
                if (nx[k] < 0 || nx[k] >= area_w || ny[k] < 0 || ny[k] >= area_h) continue;
                const int neighbour = ny[k] * area_w + nx[k];
                if (expanded[neighbour] && !visited[neighbour]) {
                    visited[neighbour] = 1;
                    queue.push_back(neighbour);
                }
            }
        }
        const int box_w = max_x - min_x + 1;
        const int box_h = max_y - min_y + 1;
        const float aspect = static_cast<float>(box_w) / std::max(1, box_h);
        if (box_w < minimum_size || box_h < minimum_size ||
            box_w > maximum_size || box_h > maximum_size ||
            aspect < 0.5f || aspect > 2.0f || red_count < minimum_red) continue;

        const int component_area = box_w * box_h;
        if (component_area < minimum_component_area ||
            red_count < static_cast<int>(std::ceil(component_area * 0.22f))) continue;

        const PixelRect box{area.x0 + min_x, area.y0 + min_y,
                            area.x0 + max_x + 1, area.y0 + max_y + 1};
        const float squareness = static_cast<float>(std::min(box_w, box_h)) /
                                 std::max(box_w, box_h);
        const float relative_size = std::clamp(
                static_cast<float>(std::min(box_w, box_h)) / (short_side * 0.16f),
                0.0f, 1.0f);
        const float red_strength = std::clamp(
                static_cast<float>(red_count) / (short_side * short_side * 0.01f),
                0.0f, 1.0f);
        const float confidence = std::clamp(
                0.70f + 0.12f * squareness + 0.10f * relative_size +
                0.08f * red_strength, 0.0f, 0.98f);

        // A single portrait is about 15% x 17% of the minimap short side.
        // Search only oversized connected components for two or more compact
        // ring-shaped windows. Requiring red support on three sides rejects
        // thin towers and skill-effect streaks while separating overlapping
        // portraits that the dilation joined.
        const float portrait_area = static_cast<float>(portrait_width * portrait_height);
        const float component_ratio = component_area / std::max(1.0f, portrait_area);
        if (short_side >= 96 && component_ratio >= 1.65f && portrait_width <= area_w &&
            portrait_height <= area_h) {
            std::vector<RingWindow> candidates;
            const int first_x = std::max(0, min_x - portrait_width / 2);
            const int last_x = std::min(area_w - portrait_width,
                                        min_x + box_w - portrait_width / 2);
            const int first_y = std::max(0, min_y - portrait_height / 2);
            const int last_y = std::min(area_h - portrait_height,
                                        min_y + box_h - portrait_height / 2);
            for (int y = first_y; y <= last_y; ++y) {
                for (int x = first_x; x <= last_x; ++x) {
                    const int right = x + portrait_width;
                    const int bottom = y + portrait_height;
                    const int total = red_sum(x, y, right, bottom);
                    const int left_red = red_sum(x, y,
                                                 std::min(right, x + ring_band), bottom);
                    const int right_red = red_sum(std::max(x, right - ring_band), y,
                                                  right, bottom);
                    const int top_red = red_sum(x, y, right,
                                                std::min(bottom, y + ring_band));
                    const int bottom_red = red_sum(x, std::max(y, bottom - ring_band),
                                                   right, bottom);
                    const int supported_sides = (left_red >= 3) + (right_red >= 3) +
                                                (top_red >= 3) + (bottom_red >= 3);
                    if (supported_sides < 3 ||
                        !((left_red >= 3 && right_red >= 3) ||
                          (top_red >= 3 && bottom_red >= 3))) continue;
                    const int score = total + 2 * std::min(left_red, right_red) +
                                      2 * std::min(top_red, bottom_red) +
                                      3 * supported_sides;
                    if (score >= split_score_minimum) candidates.push_back({score, x, y});
                }
            }
            std::sort(candidates.begin(), candidates.end(),
                      [](const RingWindow &a, const RingWindow &b) {
                          if (a.score != b.score) return a.score > b.score;
                          if (a.y != b.y) return a.y < b.y;
                          return a.x < b.x;
                      });
            std::vector<RingWindow> windows;
            const int window_limit = std::min(
                    5, std::max(2, static_cast<int>(std::lround(component_ratio * 0.65f))));
            for (const RingWindow &candidate : candidates) {
                bool overlaps = false;
                for (const RingWindow &kept : windows) {
                    const float dx = static_cast<float>(candidate.x - kept.x) /
                                     (portrait_width * 0.45f);
                    const float dy = static_cast<float>(candidate.y - kept.y) /
                                     (portrait_height * 0.45f);
                    if (dx * dx + dy * dy < 1.0f) {
                        overlaps = true;
                        break;
                    }
                }
                if (!overlaps) windows.push_back(candidate);
                if (static_cast<int>(windows.size()) >= window_limit) break;
            }
            if (windows.size() >= 2 && windows[1].score >= windows[0].score * 0.70f) {
                for (const RingWindow &window : windows) {
                    const PixelRect split_box{area.x0 + window.x, area.y0 + window.y,
                                              area.x0 + window.x + portrait_width,
                                              area.y0 + window.y + portrait_height};
                    out.push_back({MA_MINIMAP_ENEMY,
                                   direction_for((split_box.x0 + split_box.x1) / 2,
                                                 (split_box.y0 + split_box.y1) / 2,
                                                 reference),
                                   normalized(split_box, width, height), confidence,
                                   timestamp_ms});
                }
                continue;
            }
        }
        out.push_back({MA_MINIMAP_ENEMY,
                       direction_for((box.x0 + box.x1) / 2,
                                     (box.y0 + box.y1) / 2, reference),
                       normalized(box, width, height), confidence, timestamp_ms});
    }
}

struct Track {
    uint8_t recent = 0;
    int missing = 0;
    bool announced = false;
};

struct SpatialTrack {
    bool active = false;
    bool matched = false;
    float x = 0.0f;
    float y = 0.0f;
    float w = 0.0f;
    float h = 0.0f;
    float velocity_x = 0.0f;
    float velocity_y = 0.0f;
    uint8_t recent = 0;
    int missing = 0;
    bool announced = false;
    bool confirmed = false;
    int direction = MA_DIR_NONE;
    int event = MA_VISION_EVENT_NONE;
    int64_t last_seen_ms = 0;
    int64_t disappeared_at_ms = 0;
    int track_id = 0;
};

int track_index(int kind, int direction) {
    return (kind - 1) * 5 + direction;
}

int hit_count(uint8_t recent) {
    return (recent & 1) + ((recent >> 1) & 1) + ((recent >> 2) & 1);
}

int movement_direction(float velocity_x, float velocity_y) {
    // Ignore sub-pixel detector jitter. Coordinates are normalized to the
    // full frame; 0.0015 is roughly 3.6 px on a 2400 px-wide capture.
    constexpr float minimum_motion = 0.0015f;
    if (std::hypot(velocity_x, velocity_y) < minimum_motion)
        return MA_DIR_NONE;
    if (std::abs(velocity_x) >= std::abs(velocity_y))
        return velocity_x < 0.0f ? MA_DIR_LEFT : MA_DIR_RIGHT;
    return velocity_y < 0.0f ? MA_DIR_UP : MA_DIR_DOWN;
}

int priority(int kind) {
    // Native priority is an ordering rank. Platform output policy maps it to
    // the user-facing 40/60/80/100 scale without breaking the stable C ABI.
    if (kind == MA_PLAYER_DEAD) return 5;
    if (kind == MA_PLAYER_ALIVE) return 4;
    if (kind == MA_DANGER_PING) return 3;
    if (kind == MA_MAIN_ENEMY) return 2;
    return 1;
}

}  // namespace

struct ma_engine {
    ma_engine_config config;
    Track tracks[15];
    SpatialTrack minimap_tracks[8];
    int64_t last_emitted_ms = std::numeric_limits<int64_t>::min() / 2;
    int64_t last_minimap_emitted_ms = std::numeric_limits<int64_t>::min() / 2;
    int64_t last_step_ms = std::numeric_limits<int64_t>::min() / 2;
    int last_emitted_priority = 0;
    uint8_t player_dead_recent = 0;
    uint8_t player_alive_recent = 0;
    int player_state = MA_PLAYER_STATE_UNKNOWN;
    int next_minimap_track_id = 1;
};

extern "C" int ma_detect_rgba(const uint8_t *rgba, int width, int height,
                               int row_stride, int64_t timestamp_ms,
                               const ma_profile *profile,
                               const ma_template *minimap_enemy_template,
                               const ma_template *danger_ping_template,
                               ma_observation *out, int capacity) {
    if (!rgba || !profile || !out || width <= 0 || height <= 0 ||
        width > 8192 || height > 8192 ||
        row_stride < width * 4 || capacity <= 0) return 0;
    std::vector<ma_observation> found;
    detect_main_red_bars(rgba, width, height, row_stride, timestamp_ms, *profile, found);
    detect_minimap_red_rings(rgba, width, height, row_stride, timestamp_ms, *profile, found);
    if (profile->enable_minimap_template) {
        std::vector<Match> matches;
        const PixelRect area = pixels(profile->minimap, width, height);
        const PixelRect reference = direction_reference(*profile, area, width, height);
        find_template(rgba, row_stride, area, minimap_enemy_template,
                      profile->template_threshold, 5, matches);
        for (const Match &match : matches) {
            found.push_back({MA_MINIMAP_ENEMY,
                             direction_for((match.box.x0 + match.box.x1) / 2,
                                           (match.box.y0 + match.box.y1) / 2, reference),
                             normalized(match.box, width, height), match.score,
                             timestamp_ms});
        }
    }
    if (profile->enable_ping_template) {
        std::vector<Match> matches;
        find_template(rgba, row_stride,
                      pixels(profile->ping_area, width, height), danger_ping_template,
                      profile->template_threshold, 1, matches);
        for (const Match &match : matches) {
            found.push_back({MA_DANGER_PING, MA_DIR_NONE,
                             normalized(match.box, width, height), match.score,
                             timestamp_ms});
        }
    }
    std::sort(found.begin(), found.end(), [](const ma_observation &a,
                                             const ma_observation &b) {
        return a.confidence > b.confidence;
    });
    const int count = std::min(capacity, static_cast<int>(found.size()));
    for (int i = 0; i < count; ++i) out[i] = found[i];
    return count;
}

extern "C" ma_engine *ma_engine_create(const ma_engine_config *config) {
    if (!config || !std::isfinite(config->min_confidence) ||
        config->min_confidence < 0.0f || config->min_confidence > 1.0f ||
        config->max_observation_age_ms < 0 || config->max_observation_age_ms > 5000 ||
        config->min_global_gap_ms < 0 || config->min_global_gap_ms > 60000 ||
        config->minimap_min_gap_ms < 0 || config->minimap_min_gap_ms > 60000 ||
        config->min_hits_in_three_frames < 1 || config->min_hits_in_three_frames > 3 ||
        config->reset_after_missing_frames < 1 ||
        config->reset_after_missing_frames > 120) return nullptr;
    ma_engine *engine = new (std::nothrow) ma_engine;
    if (!engine) return nullptr;
    engine->config = *config;
    return engine;
}

extern "C" void ma_engine_destroy(ma_engine *engine) { delete engine; }

extern "C" void ma_engine_reset(ma_engine *engine) {
    if (!engine) return;
    for (Track &track : engine->tracks) track = Track{};
    for (SpatialTrack &track : engine->minimap_tracks) track = SpatialTrack{};
    engine->last_emitted_ms = std::numeric_limits<int64_t>::min() / 2;
    engine->last_minimap_emitted_ms = std::numeric_limits<int64_t>::min() / 2;
    engine->last_step_ms = std::numeric_limits<int64_t>::min() / 2;
    engine->last_emitted_priority = 0;
    engine->player_dead_recent = 0;
    engine->player_alive_recent = 0;
    engine->player_state = MA_PLAYER_STATE_UNKNOWN;
    // Track IDs belong to the native session, so reset clears observations
    // without rewinding the allocator.  This keeps a post-reset marker from
    // being mistaken for the marker that was cleared.
}

extern "C" int ma_engine_step(ma_engine *engine,
                               const ma_observation *observations,
                               int observation_count, int64_t now_ms,
                               ma_cue *out, int capacity) {
    if (!engine || !out || capacity <= 0 || observation_count < 0 ||
        (observation_count && !observations)) return 0;
    if (now_ms < engine->last_step_ms) {
        engine->last_emitted_ms = std::numeric_limits<int64_t>::min() / 2;
        engine->last_minimap_emitted_ms = std::numeric_limits<int64_t>::min() / 2;
        engine->last_emitted_priority = 0;
    }
    if (now_ms < engine->last_step_ms ||
        now_ms - engine->last_step_ms > std::max<int64_t>(500, engine->config.max_observation_age_ms * 3)) {
        for (Track &track : engine->tracks) track = Track{};
        for (SpatialTrack &track : engine->minimap_tracks) track = SpatialTrack{};
        engine->player_dead_recent = 0;
        engine->player_alive_recent = 0;
        engine->player_state = MA_PLAYER_STATE_UNKNOWN;
    }
    engine->last_step_ms = now_ms;
    bool seen[15] = {};
    bool player_dead_seen = false;
    bool player_alive_seen = false;
    for (SpatialTrack &track : engine->minimap_tracks) {
        track.matched = false;
        track.event = MA_VISION_EVENT_NONE;
    }
    for (int i = 0; i < observation_count; ++i) {
        const ma_observation &obs = observations[i];
        if (obs.kind < MA_MAIN_ENEMY || obs.kind > MA_PLAYER_ALIVE ||
            obs.direction < MA_DIR_NONE || obs.direction > MA_DIR_DOWN ||
            obs.confidence < engine->config.min_confidence ||
            obs.timestamp_ms > now_ms ||
            now_ms - obs.timestamp_ms > engine->config.max_observation_age_ms) continue;
        if (obs.kind == MA_PLAYER_DEAD || obs.kind == MA_PLAYER_ALIVE) {
            player_dead_seen = player_dead_seen || obs.kind == MA_PLAYER_DEAD;
            player_alive_seen = player_alive_seen || obs.kind == MA_PLAYER_ALIVE;
            continue;
        }
        if (obs.kind == MA_MINIMAP_ENEMY) {
            const float x = obs.bbox.x + obs.bbox.w * 0.5f;
            const float y = obs.bbox.y + obs.bbox.h * 0.5f;
            int nearest = -1;
            float nearest_distance = 0.05f * 0.05f;
            for (int index = 0; index < 8; ++index) {
                SpatialTrack &candidate = engine->minimap_tracks[index];
                if (!candidate.active || candidate.matched) continue;
                const float dx = x - candidate.x;
                const float dy = y - candidate.y;
                const float distance = dx * dx + dy * dy;
                if (distance < nearest_distance) {
                    nearest = index;
                    nearest_distance = distance;
                }
            }
            if (nearest < 0) {
                for (int index = 0; index < 8; ++index) {
                    if (!engine->minimap_tracks[index].active) {
                        nearest = index;
                        break;
                    }
                }
            }
            if (nearest < 0) continue;
            SpatialTrack &track = engine->minimap_tracks[nearest];
            const bool reappeared_after_loss = track.active && track.confirmed &&
                    track.missing >= engine->config.reset_after_missing_frames;
            if (reappeared_after_loss) {
                // A track that crossed the LOST transition starts a fresh
                // confirmation cycle. Old motion and announcement state must
                // not suppress or misdescribe its next APPEAR transition.
                track.confirmed = false;
                track.announced = false;
                track.recent = 0;
                track.velocity_x = 0.0f;
                track.velocity_y = 0.0f;
                track.x = x;
                track.y = y;
                track.w = obs.bbox.w;
                track.h = obs.bbox.h;
                track.track_id = engine->next_minimap_track_id++;
            }
            if (!track.active) {
                track = SpatialTrack{};
                track.active = true;
                track.x = x;
                track.y = y;
                track.w = obs.bbox.w;
                track.h = obs.bbox.h;
                track.track_id = engine->next_minimap_track_id++;
            } else if (!reappeared_after_loss) {
                const float delta_x = x - track.x;
                const float delta_y = y - track.y;
                track.velocity_x = track.velocity_x * 0.55f + delta_x * 0.45f;
                track.velocity_y = track.velocity_y * 0.55f + delta_y * 0.45f;
                track.x = track.x * 0.65f + x * 0.35f;
                track.y = track.y * 0.65f + y * 0.35f;
                track.w = track.w * 0.65f + obs.bbox.w * 0.35f;
                track.h = track.h * 0.65f + obs.bbox.h * 0.35f;
            }
            track.direction = obs.direction;
            track.matched = true;
            track.missing = 0;
            track.last_seen_ms = now_ms;
            track.disappeared_at_ms = 0;
            track.recent = static_cast<uint8_t>(((track.recent << 1) | 1) & 7);
            if (!track.confirmed &&
                hit_count(track.recent) >= engine->config.min_hits_in_three_frames) {
                track.confirmed = true;
                track.event = MA_VISION_EVENT_APPEAR;
            }
            continue;
        }
        seen[track_index(obs.kind, obs.direction)] = true;
    }
    engine->player_dead_recent = static_cast<uint8_t>(
            ((engine->player_dead_recent << 1) | (player_dead_seen ? 1 : 0)) & 7);
    engine->player_alive_recent = static_cast<uint8_t>(
            ((engine->player_alive_recent << 1) | (player_alive_seen ? 1 : 0)) & 7);
    int player_transition = 0;
    constexpr int player_confirm_hits = 2;
    if (player_dead_seen && hit_count(engine->player_dead_recent) >= player_confirm_hits &&
        engine->player_state != MA_PLAYER_STATE_DEAD) {
        engine->player_state = MA_PLAYER_STATE_DEAD;
        engine->player_alive_recent = 0;
        player_transition = MA_PLAYER_DEAD;
    } else if (player_alive_seen &&
               hit_count(engine->player_alive_recent) >= player_confirm_hits) {
        if (engine->player_state == MA_PLAYER_STATE_DEAD)
            player_transition = MA_PLAYER_ALIVE;
        engine->player_state = MA_PLAYER_STATE_ALIVE;
        engine->player_dead_recent = 0;
    }
    constexpr int64_t vision_memory_retention_ms = 4000;
    for (SpatialTrack &track : engine->minimap_tracks) {
        if (!track.active || track.matched) continue;
        track.recent = static_cast<uint8_t>((track.recent << 1) & 7);
        ++track.missing;
        if (track.confirmed &&
            track.missing == engine->config.reset_after_missing_frames) {
            track.event = MA_VISION_EVENT_DISAPPEAR;
            track.disappeared_at_ms = now_ms;
        }
        const bool lost = track.confirmed &&
                track.missing >= engine->config.reset_after_missing_frames;
        if (!track.confirmed && (track.recent == 0 ||
            track.missing >= engine->config.reset_after_missing_frames)) {
            // Once all three confirmation-window bits have shifted out, an
            // unconfirmed candidate can never become visible and must release
            // its bounded spatial slot without waiting on a larger reset gap.
            track = SpatialTrack{};
        } else if (lost &&
                   now_ms - track.disappeared_at_ms >= vision_memory_retention_ms) {
            track = SpatialTrack{};
        }
    }
    if (player_transition != 0) {
        const int ttl = player_transition == MA_PLAYER_DEAD ? 2000 : 2500;
        engine->last_emitted_ms = now_ms;
        engine->last_emitted_priority = priority(player_transition);
        out[0] = {player_transition, MA_DIR_NONE, priority(player_transition), now_ms,
                  now_ms + ttl};
        return 1;
    }
    int best = -1;
    int best_priority = -1;
    for (int i = 0; i < 15; ++i) {
        if (i / 5 + 1 == MA_MINIMAP_ENEMY) continue;
        Track &track = engine->tracks[i];
        track.recent = static_cast<uint8_t>(((track.recent << 1) | (seen[i] ? 1 : 0)) & 7);
        if (seen[i]) {
            track.missing = 0;
        } else if (++track.missing >= engine->config.reset_after_missing_frames) {
            track.announced = false;
            track.recent = 0;
        }
        const int kind = i / 5 + 1;
        if (seen[i] && !track.announced &&
            hit_count(track.recent) >= engine->config.min_hits_in_three_frames &&
            priority(kind) > best_priority) {
            best = i;
            best_priority = priority(kind);
        }
    }
    int best_minimap = -1;
    if (best_priority < priority(MA_MINIMAP_ENEMY)) {
        for (int index = 0; index < 8; ++index) {
            const SpatialTrack &track = engine->minimap_tracks[index];
            if (track.active && track.matched && !track.announced &&
                hit_count(track.recent) >= engine->config.min_hits_in_three_frames) {
                best_minimap = index;
                best_priority = priority(MA_MINIMAP_ENEMY);
                break;
            }
        }
    }
    if (best < 0 && best_minimap < 0)
        return 0;
    const bool global_gap_active =
            now_ms - engine->last_emitted_ms < engine->config.min_global_gap_ms;
    if (global_gap_active && best_priority <= engine->last_emitted_priority)
        return 0;
    if (best < 0 && now_ms - engine->last_minimap_emitted_ms <
                            engine->config.minimap_min_gap_ms)
        return 0;
    engine->last_emitted_ms = now_ms;
    engine->last_emitted_priority = best_priority;
    if (best >= 0) {
        engine->tracks[best].announced = true;
        out[0] = {best / 5 + 1, best % 5, best_priority, now_ms,
                  now_ms + engine->config.max_observation_age_ms};
    } else {
        SpatialTrack &track = engine->minimap_tracks[best_minimap];
        track.announced = true;
        engine->last_minimap_emitted_ms = now_ms;
        out[0] = {MA_MINIMAP_ENEMY, track.direction, best_priority, now_ms,
                  now_ms + engine->config.max_observation_age_ms};
    }
    return 1;
}

extern "C" int ma_engine_read_minimap_markers(
        const ma_engine *engine, ma_minimap_marker *out, int capacity) {
    if (!engine || !out || capacity <= 0) return 0;
    int count = 0;
    for (const SpatialTrack &track : engine->minimap_tracks) {
        if (!track.active || !track.confirmed || count >= capacity) continue;
        const bool lost = !track.matched &&
                track.missing >= engine->config.reset_after_missing_frames;
        const int64_t age_ms = lost
                ? engine->last_step_ms - track.disappeared_at_ms
                : engine->last_step_ms - track.last_seen_ms;
        out[count++] = {
            lost ? MA_MARKER_LOST : MA_MARKER_VISIBLE,
            movement_direction(track.velocity_x, track.velocity_y),
            {track.x - track.w * 0.5f, track.y - track.h * 0.5f,
             track.w, track.h},
            static_cast<int>(std::clamp<int64_t>(age_ms, 0, 4000)),
            track.event,
            track.track_id,
        };
    }
    return count;
}

extern "C" void ma_engine_clear_minimap_tracks(ma_engine *engine) {
    if (!engine) return;
    for (SpatialTrack &track : engine->minimap_tracks) track = SpatialTrack{};
}
