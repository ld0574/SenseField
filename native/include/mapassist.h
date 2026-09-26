#ifndef MAPASSIST_H
#define MAPASSIST_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MA_API_VERSION 2

enum ma_kind {
    MA_MAIN_ENEMY = 1,
    MA_MINIMAP_ENEMY = 2,
    MA_DANGER_PING = 3
};

enum ma_direction {
    MA_DIR_NONE = 0,
    MA_DIR_LEFT = 1,
    MA_DIR_RIGHT = 2,
    MA_DIR_UP = 3,
    MA_DIR_DOWN = 4
};

typedef struct ma_rect {
    float x;
    float y;
    float w;
    float h;
} ma_rect;

typedef struct ma_profile {
    ma_rect minimap;
    ma_rect ping_area;
    ma_rect center_mask;
    int enable_main_bar;
    int enable_minimap_template;
    int enable_minimap_red_ring;
    int enable_ping_template;
    int red_min;
    float red_dominance;
    float main_min_width_ratio;
    float main_max_height_ratio;
    float main_min_aspect;
    float template_threshold;
} ma_profile;

typedef struct ma_template {
    const uint8_t *rgba;
    int width;
    int height;
    int row_stride;
} ma_template;

typedef struct ma_observation {
    int kind;
    int direction;
    ma_rect bbox;
    float confidence;
    int64_t timestamp_ms;
} ma_observation;

typedef struct ma_cue {
    int kind;
    int direction;
    int priority;
    int64_t emitted_at_ms;
    int64_t expires_at_ms;
} ma_cue;

typedef struct ma_engine_config {
    float min_confidence;
    int64_t max_observation_age_ms;
    int64_t min_global_gap_ms;
    int64_t minimap_min_gap_ms;
    int min_hits_in_three_frames;
    int reset_after_missing_frames;
} ma_engine_config;

typedef struct ma_engine ma_engine;

/* Reads only pixels supplied by the caller. All boxes are normalized to the full frame. */
int ma_detect_rgba(const uint8_t *rgba, int width, int height, int row_stride,
                   int64_t timestamp_ms, const ma_profile *profile,
                   const ma_template *minimap_enemy_template,
                   const ma_template *danger_ping_template,
                   ma_observation *out, int capacity);

ma_engine *ma_engine_create(const ma_engine_config *config);
void ma_engine_destroy(ma_engine *engine);
void ma_engine_reset(ma_engine *engine);
/* Call once per sampled frame, including frames with zero observations. */
int ma_engine_step(ma_engine *engine, const ma_observation *observations,
                   int observation_count, int64_t now_ms, ma_cue *out,
                   int capacity);

#ifdef __cplusplus
}
#endif

#endif
