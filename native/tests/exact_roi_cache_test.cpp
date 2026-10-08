#include "exact_roi_cache.h"
#include <cassert>
#include <limits>

int main() {
    using Cache = mapassist::ExactRoiCache;
    Cache cache;
    constexpr int width = 19, height = 13, stride = 96;
    std::vector<uint8_t> pixels(stride * height, 42);
    const Cache::Rect roi{2, 3, 7, 5};
    assert(!cache.matches(pixels.data(), width, height, stride, roi));
    assert(cache.store(pixels.data(), width, height, stride, roi));
    assert(cache.bytes() == 7 * 5 * 4);
    assert(cache.matches(pixels.data(), width, height, stride, roi));
    // Unrelated HUD and row padding are outside this detector's exact input.
    pixels[0]++;
    pixels[3 * stride + width * 4]++;
    assert(cache.matches(pixels.data(), width, height, stride, roi));
    const size_t last = (roi.y + roi.height - 1) * stride + (roi.x + roi.width - 1) * 4 + 3;
    pixels[last]++;
    assert(!cache.matches(pixels.data(), width, height, stride, roi));
    pixels[last]--;
    assert(cache.matches(pixels.data(), width, height, stride, roi));
    assert(!cache.matches(pixels.data(), width, height, stride, {3, 3, 7, 5}));
    assert(!cache.matches(pixels.data(), width + 1, height, stride, roi));
    assert(!cache.matches(pixels.data(), width, height + 1, stride, roi));
    // Stride may differ: compare actual rows, never padding or borrowed buffers.
    std::vector<uint8_t> packed(width * height * 4, 42);
    assert(cache.matches(packed.data(), width, height, width * 4, roi));
    assert(!cache.store(nullptr, width, height, stride, roi));
    assert(!cache.matches(pixels.data(), width, height, stride, roi));
    assert(!cache.store(pixels.data(), width, height, stride, {-1, 0, 2, 2}));
    assert(!cache.store(pixels.data(), width, height, stride, {0, 0, 0, 2}));
    assert(!cache.store(pixels.data(), width, height, width * 4 - 1, roi));
    assert(!cache.store(pixels.data(), width, height, stride, {0, 0, 20, 14}));
    assert(!cache.store(pixels.data(), width, height, stride,
            {std::numeric_limits<int>::max(), 0, 2, 2}));
    assert(!cache.store(pixels.data(), 8192, 8192, 8192 * 4, {0, 0, 8192, 8192}));
    assert(cache.store(pixels.data(), width, height, stride, roi));
    cache.clear();
    assert(!cache.matches(pixels.data(), width, height, stride, roi));
    // Repeated content is newly observed evidence, not a stale event replay.
    ma_observation old{MA_MINIMAP_ENEMY, MA_DIR_LEFT, {0.1f, 0.2f, 0.03f, 0.04f}, 0.9f, 100};
    std::vector<ma_observation> saved{old};
    std::vector<ma_observation> current{{MA_PLAYER_ALIVE, MA_DIR_NONE, {}, 1.0f, 900}};
    mapassist::append_current_observations(saved, 901, current);
    assert(saved[0].timestamp_ms == 100);
    assert(current.size() == 2 && current[1].timestamp_ms == 901);
    assert(current[1].kind == old.kind && current[1].direction == old.direction);
    assert(current[1].bbox.x == old.bbox.x && current[1].confidence == old.confidence);
}
