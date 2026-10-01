#include "mapassist.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
#include <new>

namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr int kMaxRelationEntities = MA_MAX_OBSERVATIONS;

bool finite_between(float value, float minimum, float maximum) {
    return std::isfinite(value) && value >= minimum && value <= maximum;
}

float degrees(float radians) {
    float value = radians * 180.0f / kPi;
    if (value < 0.0f) value += 360.0f;
    return value >= 360.0f ? value - 360.0f : value;
}

// Sector 1 is centred on minimap-right (0 degrees) and numbering proceeds
// counter-clockwise, so sector 3 is minimap-up.
int raw_sector(float angle_deg) {
    return static_cast<int>(std::floor((angle_deg + 22.5f) / 45.0f)) % 8 + 1;
}

float sector_centre(int sector) {
    return static_cast<float>(sector - 1) * 45.0f;
}

float angular_distance(float a, float b) {
    const float difference = std::fabs(a - b);
    return difference > 180.0f ? 360.0f - difference : difference;
}

// Keep the previous sector while the bearing stays inside its 45-degree span
// widened by the hysteresis margin on both sides.
int hysteretic_sector(float angle_deg, int previous, float hysteresis_deg) {
    if (previous >= MA_SECTOR_RIGHT && previous <= MA_SECTOR_DOWN_RIGHT &&
        angular_distance(angle_deg, sector_centre(previous)) <= 22.5f + hysteresis_deg)
        return previous;
    return raw_sector(angle_deg);
}

// Pan follows the reported sector so the spoken direction and the stereo
// position always agree. Up and down stay centred; speech carries them.
float sector_pan(int sector) {
    if (sector < MA_SECTOR_RIGHT || sector > MA_SECTOR_DOWN_RIGHT) return 0.0f;
    const float value = std::cos(sector_centre(sector) * kPi / 180.0f);
    return std::fabs(value) < 1e-4f ? 0.0f : value;
}

int hits(uint8_t history) {
    return (history & 1) + ((history >> 1) & 1) + ((history >> 2) & 1);
}

bool usable_box(const ma_rect &box) {
    return std::isfinite(box.x) && std::isfinite(box.y) &&
           std::isfinite(box.w) && std::isfinite(box.h) && box.w > 0.0f && box.h > 0.0f;
}

bool usable_entity(const ma_tracked_entity &entity, int kind, int max_freshness_ms) {
    return entity.entity_kind == kind && entity.state == MA_TRACK_STATE_VISIBLE &&
           entity.freshness_ms >= 0 && entity.freshness_ms <= max_freshness_ms &&
           usable_box(entity.bbox);
}

}  // namespace

struct ma_relation {
    ma_relation_config config;
    int state = MA_RELATION_UNKNOWN;
    bool ever_reliable = false;
    bool gap_active = false;
    int64_t gap_started_ms = 0;
    bool paused_announced = false;
    int64_t last_pause_tone_ms = std::numeric_limits<int64_t>::min() / 2;
    uint8_t near_history = 0;
    int pending_sector = MA_SECTOR_NONE;
    int64_t clear_since_ms = 0;
    int episode_id = 0;
    int next_episode_id = 1;
    bool has_last_step = false;
    int64_t last_step_ms = 0;
};

namespace {

// Episode ids stay monotonic across resets so a platform dedupe key from a
// cleared episode can never be reused by a later one.
void clear_runtime_state(ma_relation &relation) {
    relation.state = MA_RELATION_UNKNOWN;
    relation.ever_reliable = false;
    relation.gap_active = false;
    relation.gap_started_ms = 0;
    relation.paused_announced = false;
    relation.last_pause_tone_ms = std::numeric_limits<int64_t>::min() / 2;
    relation.near_history = 0;
    relation.pending_sector = MA_SECTOR_NONE;
    relation.clear_since_ms = 0;
    relation.episode_id = 0;
}

void enter_clear(ma_relation &relation) {
    relation.state = MA_RELATION_CLEAR;
    relation.near_history = 0;
    relation.pending_sector = MA_SECTOR_NONE;
    relation.episode_id = 0;
}

}  // namespace

extern "C" ma_relation *ma_relation_create(const ma_relation_config *config) {
    if (!config ||
        !finite_between(config->enter_radius, 0.01f, 2.0f) ||
        !finite_between(config->exit_radius, 0.01f, 3.0f) ||
        config->exit_radius <= config->enter_radius ||
        !finite_between(config->sector_hysteresis_deg, 0.0f, 22.0f) ||
        !finite_between(config->adjacent_ratio, 0.0f, 1.0f) ||
        !finite_between(config->tie_ratio, 0.0f, 1.0f) ||
        config->confirm_hits < 1 || config->confirm_hits > 3 ||
        config->rearm_ms < 0 || config->rearm_ms > 60000 ||
        // The short gap doubles as the discontinuity limit, so it must stay
        // above an ordinary low-frame-rate sampling interval.
        config->short_gap_ms < 500 || config->short_gap_ms > 10000 ||
        config->pause_min_gap_ms < 0 || config->pause_min_gap_ms > 120000 ||
        config->max_freshness_ms < 0 || config->max_freshness_ms > 5000) return nullptr;
    ma_relation *relation = new (std::nothrow) ma_relation;
    if (!relation) return nullptr;
    relation->config = *config;
    return relation;
}

extern "C" void ma_relation_destroy(ma_relation *relation) { delete relation; }

extern "C" void ma_relation_reset(ma_relation *relation) {
    if (!relation) return;
    clear_runtime_state(*relation);
    relation->has_last_step = false;
}

extern "C" int ma_relation_update(ma_relation *relation,
                                  const ma_tracked_entity *entities, int entity_count,
                                  ma_rect map_body, int map_valid, int width, int height,
                                  int64_t now_ms, ma_relation_output *out) {
    if (!out) return 0;
    *out = ma_relation_output{MA_RELATION_UNKNOWN, MA_RELATION_EVENT_NONE, MA_SECTOR_NONE,
                              0.0f, -1.0f, 0, MA_RELATION_SUPPRESSION_NONE, 0};
    if (!relation || entity_count < 0 || entity_count > kMaxRelationEntities ||
        (entity_count > 0 && !entities) || width <= 0 || height <= 0) return 0;
    const ma_relation_config &config = relation->config;

    // A backwards clock or a long silent interval is a technical
    // discontinuity, not evidence about the game. Start over silently.
    if (relation->has_last_step &&
        (now_ms < relation->last_step_ms ||
         now_ms - relation->last_step_ms > config.short_gap_ms))
        clear_runtime_state(*relation);
    relation->has_last_step = true;
    relation->last_step_ms = now_ms;

    const float map_short_px = std::min(map_body.w * static_cast<float>(width),
                                        map_body.h * static_cast<float>(height));
    const bool map_usable = map_valid != 0 && usable_box(map_body) &&
                            std::isfinite(map_short_px) && map_short_px >= 16.0f;
    bool reliable = false;
    float self_x = 0.0f;
    float self_y = 0.0f;
    for (int index = 0; map_usable && index < entity_count; ++index) {
        const ma_tracked_entity &entity = entities[index];
        if (!usable_entity(entity, MA_MINIMAP_PLAYER, config.max_freshness_ms)) continue;
        const float x = entity.bbox.x + entity.bbox.w * 0.5f;
        const float y = entity.bbox.y + entity.bbox.h * 0.5f;
        // A player marker outside the calibrated map body is a layout or
        // detector failure, never a coordinate origin.
        if (x < map_body.x || x > map_body.x + map_body.w ||
            y < map_body.y || y > map_body.y + map_body.h) continue;
        reliable = true;
        self_x = x;
        self_y = y;
        break;
    }

    if (!reliable) {
        if (!relation->gap_active) {
            relation->gap_active = true;
            relation->gap_started_ms = now_ms;
        }
        // Short gaps hold the previous state without evidence either way.
        if (relation->state != MA_RELATION_UNKNOWN &&
            now_ms - relation->gap_started_ms >= config.short_gap_ms) {
            relation->state = MA_RELATION_UNKNOWN;
            relation->near_history = 0;
            relation->pending_sector = MA_SECTOR_NONE;
            relation->episode_id = 0;
        }
        // A pause tone is only meaningful after the radar has worked once. A
        // tone suppressed by the minimum gap is deferred, not dropped, so a
        // resume tone is never the last thing heard while the radar is down.
        if (relation->state == MA_RELATION_UNKNOWN && relation->ever_reliable &&
            !relation->paused_announced &&
            now_ms - relation->last_pause_tone_ms >= config.pause_min_gap_ms) {
            relation->paused_announced = true;
            relation->last_pause_tone_ms = now_ms;
            out->event = MA_RELATION_EVENT_RADAR_PAUSED;
        }
        out->state = relation->state;
        out->episode_id = relation->episode_id;
        return 1;
    }

    relation->ever_reliable = true;
    const bool resumed_from_gap = relation->gap_active;
    relation->gap_active = false;
    if (relation->state == MA_RELATION_UNKNOWN) {
        enter_clear(*relation);
        if (relation->paused_announced) {
            relation->paused_announced = false;
            out->event = MA_RELATION_EVENT_RADAR_RESUMED;
        }
    }

    // Nearest and second-nearest usable enemies in map short-edge units.
    const float scale_x = static_cast<float>(width) / map_short_px;
    const float scale_y = static_cast<float>(height) / map_short_px;
    bool any_enemy = false;
    float nearest = std::numeric_limits<float>::infinity();
    float nearest_angle = 0.0f;
    float second = std::numeric_limits<float>::infinity();
    float second_angle = 0.0f;
    for (int index = 0; index < entity_count; ++index) {
        const ma_tracked_entity &entity = entities[index];
        // LOST ghosts keep a last box for the overlay but are not evidence.
        if (!usable_entity(entity, MA_MINIMAP_ENEMY, config.max_freshness_ms)) continue;
        const float dx = (entity.bbox.x + entity.bbox.w * 0.5f - self_x) * scale_x;
        const float dy = (entity.bbox.y + entity.bbox.h * 0.5f - self_y) * scale_y;
        const float distance = std::hypot(dx, dy);
        if (!std::isfinite(distance)) continue;
        // Screen y grows downward; negate it so minimap-up is 90 degrees.
        const float angle = degrees(std::atan2(-dy, dx));
        any_enemy = true;
        if (distance < nearest) {
            second = nearest;
            second_angle = nearest_angle;
            nearest = distance;
            nearest_angle = angle;
        } else if (distance < second) {
            second = distance;
            second_angle = angle;
        }
    }
    const bool near = any_enemy && nearest <= config.enter_radius;
    const bool inside_exit = any_enemy && nearest <= config.exit_radius;

    if (relation->state == MA_RELATION_CLEAR && near) {
        relation->state = MA_RELATION_PENDING;
        relation->near_history = 0;
        relation->pending_sector = MA_SECTOR_NONE;
    }
    if (relation->state == MA_RELATION_PENDING) {
        relation->near_history = static_cast<uint8_t>(
                ((relation->near_history << 1) | (near ? 1 : 0)) & 7);
        if (near) {
            relation->pending_sector = hysteretic_sector(
                    nearest_angle, relation->pending_sector, config.sector_hysteresis_deg);
        }
        // At most one event per update: a confirmation that coincides with a
        // resume tone waits one frame instead of overlapping it.
        if (near && out->event == MA_RELATION_EVENT_NONE &&
            hits(relation->near_history) >= config.confirm_hits) {
            relation->state = MA_RELATION_OCCUPIED;
            relation->episode_id = relation->next_episode_id++;
            int sector = relation->pending_sector;
            const bool adjacent = nearest < config.adjacent_ratio * config.enter_radius;
            const bool tied = std::isfinite(second) && second > 0.0f &&
                    (second - nearest) <= config.tie_ratio * second &&
                    raw_sector(second_angle) != raw_sector(nearest_angle);
            if (adjacent || tied) sector = MA_SECTOR_NONE;
            out->event = MA_RELATION_EVENT_NEAR_ENTER;
            out->sector = sector;
            out->pan = sector_pan(sector);
        } else if (relation->near_history == 0) {
            enter_clear(*relation);
        }
    } else if (relation->state == MA_RELATION_OCCUPIED) {
        if (!inside_exit) {
            relation->state = MA_RELATION_REARM;
            relation->clear_since_ms = now_ms;
        } else if (resumed_from_gap && out->event == MA_RELATION_EVENT_NONE) {
            // Without the gap hold this frame would have started a new episode.
            out->event = MA_RELATION_EVENT_SUPPRESSED;
            out->suppression = MA_RELATION_SUPPRESSION_SHORT_GAP;
        }
    } else if (relation->state == MA_RELATION_REARM) {
        // Clear time counts only under reliable observation.
        if (resumed_from_gap) relation->clear_since_ms = now_ms;
        if (near) {
            relation->state = MA_RELATION_OCCUPIED;
            if (out->event == MA_RELATION_EVENT_NONE) {
                out->event = MA_RELATION_EVENT_SUPPRESSED;
                out->suppression = MA_RELATION_SUPPRESSION_REARM_PENDING;
            }
        } else if (inside_exit) {
            relation->clear_since_ms = now_ms;
        } else if (now_ms - relation->clear_since_ms >= config.rearm_ms) {
            enter_clear(*relation);
        }
    }

    out->state = relation->state;
    out->reliable = 1;
    out->episode_id = relation->episode_id;
    out->nearest_distance = any_enemy ? nearest : -1.0f;
    if (out->event != MA_RELATION_EVENT_NEAR_ENTER && any_enemy) {
        out->sector = raw_sector(nearest_angle);
        out->pan = sector_pan(out->sector);
    }
    return 1;
}
