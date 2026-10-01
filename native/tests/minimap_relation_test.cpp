#include "mapassist.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>

// Always-on checks: the scenarios must stay meaningful in Release builds,
// where assert() compiles away.
#define CHECK(condition)                                                          \
    do {                                                                          \
        if (!(condition)) {                                                       \
            std::fprintf(stderr, "%s:%d: CHECK failed: %s\n", __FILE__, __LINE__, \
                         #condition);                                             \
            std::abort();                                                         \
        }                                                                         \
    } while (0)

namespace {

constexpr float kPi = 3.14159265358979323846f;
// 1000x1000 frame whose map body short edge is 400 px, so one short-edge
// unit spans 0.4 of the normalized frame.
constexpr ma_rect kMapBody{0.10f, 0.10f, 0.40f, 0.40f};
constexpr float kSelfX = 0.30f;
constexpr float kSelfY = 0.30f;

using Entities = std::vector<ma_tracked_entity>;

ma_relation_config default_config() {
    return {0.20f, 0.25f, 7.5f, 0.2f, 0.1f, 2, 3000, 2000, 10000, 500};
}

ma_tracked_entity entity(int kind, int track_id, float centre_x, float centre_y,
                         int state = MA_TRACK_STATE_VISIBLE, int freshness_ms = 0) {
    ma_tracked_entity value{};
    value.entity_kind = kind;
    value.track_id = track_id;
    value.state = state;
    value.bbox = {centre_x - 0.01f, centre_y - 0.01f, 0.02f, 0.02f};
    value.confidence = 0.9f;
    value.freshness_ms = freshness_ms;
    return value;
}

ma_tracked_entity self_at(float x = kSelfX, float y = kSelfY) {
    return entity(MA_MINIMAP_PLAYER, 100, x, y);
}

// Enemy at `distance` short-edge units and a minimap bearing in degrees
// (0 = right, 90 = up) from the player marker.
ma_tracked_entity enemy_at(float distance, float bearing_deg, int track_id = 1,
                           int state = MA_TRACK_STATE_VISIBLE, int freshness_ms = 0) {
    const float radians = bearing_deg * kPi / 180.0f;
    const float x = kSelfX + distance * std::cos(radians) * kMapBody.w;
    const float y = kSelfY - distance * std::sin(radians) * kMapBody.h;
    return entity(MA_MINIMAP_ENEMY, track_id, x, y, state, freshness_ms);
}

float expected_pan(float centre_deg) {
    const float value = std::cos(centre_deg * kPi / 180.0f);
    return std::fabs(value) < 1e-4f ? 0.0f : value;
}

class Harness {
public:
    explicit Harness(ma_relation_config config = default_config())
            : relation_(ma_relation_create(&config)) {
        CHECK(relation_ != nullptr);
    }
    ~Harness() { ma_relation_destroy(relation_); }
    Harness(const Harness &) = delete;
    Harness &operator=(const Harness &) = delete;

    ma_relation_output step(const Entities &entities, int map_valid = 1) {
        ma_relation_output out{};
        CHECK(ma_relation_update(relation_, entities.data(),
                                 static_cast<int>(entities.size()), kMapBody, map_valid,
                                 1000, 1000, now_, &out) == 1);
        now_ += 83;
        return out;
    }

    void reset() { ma_relation_reset(relation_); }
    void advance(int64_t milliseconds) { now_ += milliseconds; }
    int64_t now() const { return now_; }

private:
    ma_relation *relation_;
    int64_t now_ = 0;
};

// Two near frames confirm a fresh episode from CLEAR.
ma_relation_output confirm(Harness &harness, const Entities &entities) {
    const ma_relation_output first = harness.step(entities);
    CHECK(first.event == MA_RELATION_EVENT_NONE);
    CHECK(first.state == MA_RELATION_PENDING);
    const ma_relation_output second = harness.step(entities);
    CHECK(second.event == MA_RELATION_EVENT_NEAR_ENTER);
    CHECK(second.state == MA_RELATION_OCCUPIED);
    return second;
}

// An enemy that appeared far away and later walks in needs no new APPEAR.
void far_enemy_walking_in_cues_once() {
    Harness harness;
    int enters = 0;
    ma_relation_output enter{};
    for (int index = 0; index <= 10; ++index) {
        const float distance = 0.60f - 0.05f * index;
        const ma_relation_output out = harness.step({self_at(), enemy_at(distance, 135.0f)});
        CHECK(out.reliable == 1);
        if (index == 0) CHECK(out.state == MA_RELATION_CLEAR);
        if (out.event == MA_RELATION_EVENT_NEAR_ENTER) {
            ++enters;
            enter = out;
        }
    }
    for (int index = 0; index < 30; ++index) {
        const ma_relation_output out = harness.step({self_at(), enemy_at(0.10f, 135.0f)});
        CHECK(out.event == MA_RELATION_EVENT_NONE);
    }
    CHECK(enters == 1);
    CHECK(enter.sector == MA_SECTOR_UP_LEFT);
    CHECK(std::fabs(enter.pan - expected_pan(135.0f)) < 1e-4f);
    CHECK(enter.episode_id == 1);
    CHECK(enter.nearest_distance > 0.0f && enter.nearest_distance <= 0.20f);
}

// The player walking towards an already visible enemy also enters the zone.
void player_walking_in_cues_once() {
    Harness harness;
    // Both markers stay inside the map body while the distance shrinks
    // from 0.60 to 0.05 short-edge units.
    const float enemy_x = 0.44f;
    int enters = 0;
    int sector = MA_SECTOR_NONE;
    for (int index = 0; index < 12; ++index) {
        const float self_x = 0.20f + 0.02f * index;
        const ma_tracked_entity enemy = entity(MA_MINIMAP_ENEMY, 1, enemy_x, kSelfY);
        const ma_relation_output out = harness.step({self_at(self_x), enemy});
        if (out.event == MA_RELATION_EVENT_NEAR_ENTER) {
            ++enters;
            sector = out.sector;
        }
    }
    CHECK(enters == 1);
    CHECK(sector == MA_SECTOR_RIGHT);
}

// Oscillating between R_enter and R_exit keeps one occupancy episode.
void boundary_jitter_cues_once() {
    Harness harness;
    confirm(harness, {self_at(), enemy_at(0.15f, 0.0f)});
    for (int index = 0; index < 40; ++index) {
        const float distance = index % 2 == 0 ? 0.22f : 0.18f;
        const ma_relation_output out = harness.step({self_at(), enemy_at(distance, 0.0f)});
        CHECK(out.event == MA_RELATION_EVENT_NONE);
        CHECK(out.state == MA_RELATION_OCCUPIED);
    }
    // Crossing R_exit for one frame and returning inside the REARM window
    // only records suppressions.
    int suppressed = 0;
    for (int index = 0; index < 40; ++index) {
        const float distance = index % 2 == 0 ? 0.26f : 0.18f;
        const ma_relation_output out = harness.step({self_at(), enemy_at(distance, 0.0f)});
        CHECK(out.event != MA_RELATION_EVENT_NEAR_ENTER);
        if (out.event == MA_RELATION_EVENT_SUPPRESSED) {
            CHECK(out.suppression == MA_RELATION_SUPPRESSION_REARM_PENDING);
            ++suppressed;
        }
    }
    CHECK(suppressed == 20);
}

// Thirty seconds of occupancy never repeats the cue.
void continuous_occupancy_stays_quiet() {
    Harness harness;
    confirm(harness, {self_at(), enemy_at(0.12f, 90.0f)});
    for (int index = 0; index < 362; ++index) {
        const ma_relation_output out = harness.step({self_at(), enemy_at(0.12f, 90.0f)});
        CHECK(out.event == MA_RELATION_EVENT_NONE);
        CHECK(out.state == MA_RELATION_OCCUPIED);
    }
}

// Several enemies entering, leaving and swapping track ids inside one
// episode are one cue: identity is not the dedupe key.
void multiple_enemies_and_id_swaps_cue_once() {
    Harness harness;
    int enters = 0;
    for (int index = 0; index < 60; ++index) {
        Entities entities{self_at()};
        entities.push_back(enemy_at(0.10f, 45.0f, index % 2 == 0 ? 1 : 2));
        if (index % 3 != 0) entities.push_back(enemy_at(0.15f, 45.0f, 3 + index));
        const ma_relation_output out = harness.step(entities);
        if (out.event == MA_RELATION_EVENT_NEAR_ENTER) ++enters;
    }
    CHECK(enters == 1);
}

// Leaving beyond REARM re-arms; there is no 15-second global cooldown.
void return_after_rearm_window_cues_again() {
    Harness harness;
    const int64_t first_at = harness.now() + 83;
    const ma_relation_output first = confirm(harness, {self_at(), enemy_at(0.15f, 0.0f)});
    CHECK(first.episode_id == 1);
    for (int index = 0; index < 40; ++index) {
        const ma_relation_output out = harness.step({self_at(), enemy_at(0.40f, 0.0f)});
        CHECK(out.event == MA_RELATION_EVENT_NONE);
    }
    CHECK(harness.step({self_at()}).state == MA_RELATION_CLEAR);
    const int64_t second_at = harness.now() + 83;
    const ma_relation_output second = confirm(harness, {self_at(), enemy_at(0.15f, 0.0f)});
    CHECK(second.episode_id == 2);
    CHECK(second_at - first_at < 15000);
}

// Coming back before the REARM window completes stays silent.
void return_inside_rearm_window_is_suppressed() {
    Harness harness;
    const ma_relation_output first = confirm(harness, {self_at(), enemy_at(0.15f, 0.0f)});
    for (int index = 0; index < 10; ++index) {
        const ma_relation_output out = harness.step({self_at(), enemy_at(0.40f, 0.0f)});
        CHECK(out.state == MA_RELATION_REARM);
        CHECK(out.event == MA_RELATION_EVENT_NONE);
    }
    const ma_relation_output back = harness.step({self_at(), enemy_at(0.15f, 0.0f)});
    CHECK(back.event == MA_RELATION_EVENT_SUPPRESSED);
    CHECK(back.suppression == MA_RELATION_SUPPRESSION_REARM_PENDING);
    CHECK(back.state == MA_RELATION_OCCUPIED);
    CHECK(back.episode_id == first.episode_id);
    for (int index = 0; index < 20; ++index)
        CHECK(harness.step({self_at(), enemy_at(0.15f, 0.0f)}).event == MA_RELATION_EVENT_NONE);
}

// A self-marker gap below the tolerance holds the episode: no clear, no replay.
void short_self_loss_holds_episode() {
    Harness harness;
    const ma_relation_output first = confirm(harness, {self_at(), enemy_at(0.15f, 0.0f)});
    for (int index = 0; index < 10; ++index) {
        const ma_relation_output out = harness.step({enemy_at(0.15f, 0.0f)});
        CHECK(out.event == MA_RELATION_EVENT_NONE);
        CHECK(out.state == MA_RELATION_OCCUPIED);
        CHECK(out.reliable == 0);
    }
    const ma_relation_output back = harness.step({self_at(), enemy_at(0.15f, 0.0f)});
    CHECK(back.event == MA_RELATION_EVENT_SUPPRESSED);
    CHECK(back.suppression == MA_RELATION_SUPPRESSION_SHORT_GAP);
    CHECK(back.episode_id == first.episode_id);
    CHECK(harness.step({self_at(), enemy_at(0.15f, 0.0f)}).event == MA_RELATION_EVENT_NONE);
}

// A long gap pauses once, resumes once, and a still-occupied zone becomes a
// new episode after fresh confirmation.
void long_self_loss_pauses_and_resumes() {
    Harness harness;
    const ma_relation_output first = confirm(harness, {self_at(), enemy_at(0.15f, 0.0f)});
    int paused = 0;
    for (int index = 0; index < 30; ++index) {
        const ma_relation_output out = harness.step({enemy_at(0.15f, 0.0f)});
        if (out.event == MA_RELATION_EVENT_RADAR_PAUSED) {
            ++paused;
            CHECK(out.state == MA_RELATION_UNKNOWN);
            CHECK(index == 25);
        } else {
            CHECK(out.event == MA_RELATION_EVENT_NONE);
        }
    }
    CHECK(paused == 1);
    const ma_relation_output resumed = harness.step({self_at(), enemy_at(0.15f, 0.0f)});
    CHECK(resumed.event == MA_RELATION_EVENT_RADAR_RESUMED);
    CHECK(resumed.state == MA_RELATION_PENDING);
    const ma_relation_output again = harness.step({self_at(), enemy_at(0.15f, 0.0f)});
    CHECK(again.event == MA_RELATION_EVENT_NEAR_ENTER);
    CHECK(again.episode_id == first.episode_id + 1);
}

// Repeated occlusion keeps pause tones at least pause_min_gap_ms apart and
// always pairs a resume tone with an announced pause.
void flapping_occlusion_rate_limits_pause_tones() {
    Harness harness;
    harness.step({self_at()});
    int64_t last_pause = -1;
    int pauses = 0;
    bool awaiting_resume = false;
    for (int cycle = 0; cycle < 10; ++cycle) {
        for (int index = 0; index <= 30; ++index) {
            const int64_t at = harness.now();
            const ma_relation_output out =
                    index < 30 ? harness.step({}) : harness.step({self_at()});
            if (out.event == MA_RELATION_EVENT_RADAR_PAUSED) {
                CHECK(!awaiting_resume);
                CHECK(last_pause < 0 || at - last_pause >= 10000);
                last_pause = at;
                awaiting_resume = true;
                ++pauses;
            } else if (out.event == MA_RELATION_EVENT_RADAR_RESUMED) {
                CHECK(awaiting_resume);
                awaiting_resume = false;
            } else {
                CHECK(out.event == MA_RELATION_EVENT_NONE);
            }
        }
    }
    CHECK(pauses >= 2);
}

// A pause tone blocked by the minimum gap is deferred while the radar stays
// down, so a resume tone is never the last thing the player heard.
void deferred_pause_tone_is_not_dropped() {
    Harness harness;
    harness.step({self_at()});
    for (int index = 0; index < 30; ++index) harness.step({});
    CHECK(harness.step({self_at()}).event == MA_RELATION_EVENT_RADAR_RESUMED);
    int paused_at = -1;
    for (int index = 0; index < 150; ++index) {
        const ma_relation_output out = harness.step({});
        CHECK(out.state == (index >= 25 ? MA_RELATION_UNKNOWN : MA_RELATION_CLEAR));
        if (out.event == MA_RELATION_EVENT_RADAR_PAUSED) {
            CHECK(paused_at < 0);
            paused_at = index;
        } else {
            CHECK(out.event == MA_RELATION_EVENT_NONE);
        }
    }
    CHECK(paused_at == 115);
}

// LOST ghosts and stale detections are never near-zone evidence.
void ghosts_and_stale_enemies_are_ignored() {
    Harness harness;
    for (int index = 0; index < 20; ++index) {
        const ma_relation_output out = harness.step({
                self_at(),
                enemy_at(0.05f, 0.0f, 1, MA_TRACK_STATE_LOST),
                enemy_at(0.05f, 90.0f, 2, MA_TRACK_STATE_VISIBLE, 900)});
        CHECK(out.event == MA_RELATION_EVENT_NONE);
        CHECK(out.state == MA_RELATION_CLEAR);
        CHECK(out.nearest_distance < 0.0f);
        CHECK(out.sector == MA_SECTOR_NONE);
    }
}

// A self marker outside the map body, a stale self marker, or an invalid map
// is UNKNOWN; a session that never worked stays completely silent.
void unreliable_self_or_map_is_unknown() {
    Harness harness;
    for (int index = 0; index < 60; ++index) {
        const Entities entities = index % 2 == 0
                ? Entities{self_at(0.05f, kSelfY), enemy_at(0.10f, 0.0f)}
                : Entities{entity(MA_MINIMAP_PLAYER, 100, kSelfX, kSelfY,
                                  MA_TRACK_STATE_VISIBLE, 900), enemy_at(0.10f, 0.0f)};
        const ma_relation_output out = harness.step(entities);
        CHECK(out.state == MA_RELATION_UNKNOWN);
        CHECK(out.reliable == 0);
        CHECK(out.event == MA_RELATION_EVENT_NONE);
    }
    CHECK(harness.step({self_at()}).event == MA_RELATION_EVENT_NONE);

    int paused = 0;
    for (int index = 0; index < 30; ++index) {
        const ma_relation_output out = harness.step({self_at()}, 0);
        if (out.event == MA_RELATION_EVENT_RADAR_PAUSED) ++paused;
    }
    CHECK(paused == 1);
    const ma_relation_output back = harness.step({self_at()});
    CHECK(back.event == MA_RELATION_EVENT_RADAR_RESUMED);
    CHECK(back.state == MA_RELATION_CLEAR);
}

// All eight sectors, with pan following the sector centre.
void eight_sectors_and_pan() {
    const int sectors[] = {MA_SECTOR_RIGHT, MA_SECTOR_UP_RIGHT, MA_SECTOR_UP, MA_SECTOR_UP_LEFT,
                           MA_SECTOR_LEFT, MA_SECTOR_DOWN_LEFT, MA_SECTOR_DOWN,
                           MA_SECTOR_DOWN_RIGHT};
    for (int index = 0; index < 8; ++index) {
        Harness harness;
        const float bearing = 45.0f * index;
        const ma_relation_output out =
                confirm(harness, {self_at(), enemy_at(0.12f, bearing)});
        CHECK(out.sector == sectors[index]);
        CHECK(std::fabs(out.pan - expected_pan(bearing)) < 1e-4f);
    }
}

// Bearings jittering across a sector boundary inside the hysteresis margin
// keep the first sector; a clear move outside the margin changes it.
void sector_boundary_hysteresis() {
    {
        Harness harness;
        CHECK(harness.step({self_at(), enemy_at(0.12f, 20.0f)}).state == MA_RELATION_PENDING);
        const ma_relation_output out = harness.step({self_at(), enemy_at(0.12f, 25.0f)});
        CHECK(out.event == MA_RELATION_EVENT_NEAR_ENTER);
        CHECK(out.sector == MA_SECTOR_RIGHT);
    }
    {
        Harness harness;
        harness.step({self_at(), enemy_at(0.12f, 25.0f)});
        const ma_relation_output out = harness.step({self_at(), enemy_at(0.12f, 20.0f)});
        CHECK(out.sector == MA_SECTOR_UP_RIGHT);
    }
    {
        Harness harness;
        harness.step({self_at(), enemy_at(0.12f, 20.0f)});
        const ma_relation_output out = harness.step({self_at(), enemy_at(0.12f, 60.0f)});
        CHECK(out.sector == MA_SECTOR_UP_RIGHT);
    }
}

// A marker practically on top of the player, or two similar distances in
// different sectors, is announced without a direction.
void adjacent_or_tied_targets_have_no_bearing() {
    {
        Harness harness;
        const ma_relation_output out = confirm(harness, {self_at(), enemy_at(0.03f, 0.0f)});
        CHECK(out.sector == MA_SECTOR_NONE);
        CHECK(out.pan == 0.0f);
    }
    {
        Harness harness;
        const ma_relation_output out = confirm(
                harness, {self_at(), enemy_at(0.12f, 0.0f, 1), enemy_at(0.125f, 180.0f, 2)});
        CHECK(out.sector == MA_SECTOR_NONE);
    }
    {
        Harness harness;
        const ma_relation_output out = confirm(
                harness, {self_at(), enemy_at(0.10f, 0.0f, 1), enemy_at(0.18f, 180.0f, 2)});
        CHECK(out.sector == MA_SECTOR_RIGHT);
    }
}

// Pause, rotation, capture recovery and native reset clear state; nothing
// missed is replayed and episode ids stay monotonic.
void reset_and_discontinuity_start_over_silently() {
    Harness harness;
    const ma_relation_output first = confirm(harness, {self_at(), enemy_at(0.15f, 0.0f)});
    harness.reset();
    const ma_relation_output after_reset = harness.step({self_at(), enemy_at(0.15f, 0.0f)});
    CHECK(after_reset.event == MA_RELATION_EVENT_NONE);
    CHECK(after_reset.state == MA_RELATION_PENDING);
    const ma_relation_output again = harness.step({self_at(), enemy_at(0.15f, 0.0f)});
    CHECK(again.event == MA_RELATION_EVENT_NEAR_ENTER);
    CHECK(again.episode_id == first.episode_id + 1);

    // A long silent interval between updates is a discontinuity, not an
    // occupancy change: the zone is re-confirmed as a new episode.
    harness.advance(2500);
    const ma_relation_output after_gap = harness.step({self_at(), enemy_at(0.15f, 0.0f)});
    CHECK(after_gap.event == MA_RELATION_EVENT_NONE);
    CHECK(after_gap.state == MA_RELATION_PENDING);
    const ma_relation_output third = harness.step({self_at(), enemy_at(0.15f, 0.0f)});
    CHECK(third.event == MA_RELATION_EVENT_NEAR_ENTER);
    CHECK(third.episode_id == first.episode_id + 2);

    // A clock running backwards also starts over.
    harness.advance(-5000);
    const ma_relation_output backwards = harness.step({self_at(), enemy_at(0.15f, 0.0f)});
    CHECK(backwards.event == MA_RELATION_EVENT_NONE);
    CHECK(backwards.state == MA_RELATION_PENDING);
}

void invalid_arguments_are_rejected() {
    ma_relation_config config = default_config();
    config.exit_radius = config.enter_radius;
    CHECK(ma_relation_create(&config) == nullptr);
    config = default_config();
    config.confirm_hits = 0;
    CHECK(ma_relation_create(&config) == nullptr);
    config = default_config();
    config.short_gap_ms = 100;
    CHECK(ma_relation_create(&config) == nullptr);
    config = default_config();
    config.enter_radius = std::nanf("");
    CHECK(ma_relation_create(&config) == nullptr);
    CHECK(ma_relation_create(nullptr) == nullptr);

    ma_relation_output out{};
    out.state = 42;
    CHECK(ma_relation_update(nullptr, nullptr, 0, kMapBody, 1, 1000, 1000, 0, &out) == 0);
    CHECK(out.state == MA_RELATION_UNKNOWN && out.event == MA_RELATION_EVENT_NONE);
    config = default_config();
    ma_relation *relation = ma_relation_create(&config);
    CHECK(relation != nullptr);
    CHECK(ma_relation_update(relation, nullptr, 1, kMapBody, 1, 1000, 1000, 0, &out) == 0);
    CHECK(ma_relation_update(relation, nullptr, 0, kMapBody, 1, 0, 1000, 0, &out) == 0);
    ma_relation_destroy(relation);
    ma_relation_destroy(nullptr);
    ma_relation_reset(nullptr);
}

}  // namespace

int main() {
    far_enemy_walking_in_cues_once();
    player_walking_in_cues_once();
    boundary_jitter_cues_once();
    continuous_occupancy_stays_quiet();
    multiple_enemies_and_id_swaps_cue_once();
    return_after_rearm_window_cues_again();
    return_inside_rearm_window_is_suppressed();
    short_self_loss_holds_episode();
    long_self_loss_pauses_and_resumes();
    flapping_occlusion_rate_limits_pause_tones();
    deferred_pause_tone_is_not_dropped();
    ghosts_and_stale_enemies_are_ignored();
    unreliable_self_or_map_is_unknown();
    eight_sectors_and_pan();
    sector_boundary_hysteresis();
    adjacent_or_tied_targets_have_no_bearing();
    reset_and_discontinuity_start_over_silently();
    invalid_arguments_are_rejected();
    std::puts("minimap relation: all scenarios passed");
    return 0;
}
