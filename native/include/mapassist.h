#ifndef MAPASSIST_H
#define MAPASSIST_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * The original engine ABI remains source and binary compatible: existing
 * structures and entry points are kept in place and the memory snapshot API
 * is appended below.  Bump the header contract when consumers want to use the
 * appended entity kind and snapshot fields.
 */
#define MA_API_VERSION 9

/* Hard bounds shared by native producers and platform consumers. */
#define MA_MAX_MINIMAP_TRACKS 8
#define MA_MAX_TRACKED_ENTITIES (MA_MAX_MINIMAP_TRACKS + 1)
#define MA_MAX_OBSERVATIONS 64
#define MA_PLAYER_RELEVANCE_MAX_AGE_MS 500

enum ma_kind {
    MA_MAIN_ENEMY = 1,
    MA_MINIMAP_ENEMY = 2,
    MA_DANGER_PING = 3,
    MA_PLAYER_DEAD = 4,
    MA_PLAYER_ALIVE = 5,
    /* Reserved for the local player's minimap icon. It is state-only. */
    MA_MINIMAP_PLAYER = 6,
    /*
     * Relation-layer cue kinds. They are produced by ma_relation_update(),
     * never accepted as detector observations, and share this integer space
     * so platform audit logs use one vocabulary.
     */
    MA_NEAR_ZONE = 7,
    MA_RADAR_STATUS = 8
};

/* Stable names for consumers that use entity terminology. */
enum ma_entity_kind {
    MA_ENTITY_MAIN_ENEMY = MA_MAIN_ENEMY,
    MA_ENTITY_MINIMAP_ENEMY = MA_MINIMAP_ENEMY,
    MA_ENTITY_DANGER_PING = MA_DANGER_PING,
    MA_ENTITY_PLAYER_DEAD = MA_PLAYER_DEAD,
    MA_ENTITY_PLAYER_ALIVE = MA_PLAYER_ALIVE,
    MA_ENTITY_MINIMAP_PLAYER = MA_MINIMAP_PLAYER
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
    /* Optional direction reference; zero-sized preserves the minimap ROI behavior. */
    ma_rect minimap_direction;
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

enum ma_minimap_marker_state {
    MA_MARKER_VISIBLE = 1,
    MA_MARKER_LOST = 2
};

enum ma_vision_event {
    MA_VISION_EVENT_NONE = 0,
    MA_VISION_EVENT_APPEAR = 1,
    MA_VISION_EVENT_DISAPPEAR = 2
};

/*
 * State of a tracked visual entity. Candidate tracks are retained internally
 * while the three-frame confirmation window is being filled. Public snapshots
 * contain confirmed entities (VISIBLE or LOST); EXPIRED is included so a
 * future streaming API can report terminal transitions without changing the
 * integer contract.
 */
enum ma_tracked_entity_state {
    MA_TRACK_STATE_CANDIDATE = 0,
    MA_TRACK_STATE_VISIBLE = 1,
    MA_TRACK_STATE_LOST = 2,
    MA_TRACK_STATE_EXPIRED = 3
};

/*
 * A confirmed minimap enemy track for optional visual presentation.  A lost
 * marker keeps the last reliable box and movement direction for a short
 * period after the detector stops seeing the portrait.  Coordinates stay
 * normalized to the full frame so platform UI code can scale them safely.
 */
typedef struct ma_minimap_marker {
    int state;
    int movement_direction;
    ma_rect bbox;
    int age_ms;
    int event;
    int track_id;
} ma_minimap_marker;

/*
 * Versioned, category-aware snapshot consumed by visual memory/overlay code.
 * Coordinates and velocity are normalized to the full capture frame. A LOST
 * entity keeps its last reliable box for the four-second retention period;
 * freshness_ms is measured from last_seen_ms to the most recent engine step.
 * For MA_MINIMAP_PLAYER, callers must require freshness_ms <=
 * MA_PLAYER_RELEVANCE_MAX_AGE_MS before using it as a coordinate origin.
 */
typedef struct ma_tracked_entity {
    int entity_kind;
    int track_id;
    int state;
    ma_rect bbox;
    float confidence;
    int64_t last_seen_ms;
    int freshness_ms;
    float velocity_x;
    float velocity_y;
    int transition;
} ma_tracked_entity;

/* Alternate spelling for bindings that call the value an entity snapshot. */
typedef ma_tracked_entity ma_entity_snapshot;

enum ma_player_state {
    MA_PLAYER_STATE_UNKNOWN = 0,
    MA_PLAYER_STATE_DEAD = 1,
    MA_PLAYER_STATE_ALIVE = 2
};

typedef struct ma_player_state_signature {
    int state;
    uint64_t dhash;
    uint8_t luma[64];
    uint8_t chroma[32];
} ma_player_state_signature;

typedef struct ma_player_state_matcher_config {
    ma_rect roi;
    int max_dhash_distance;
    float max_luma_mae;
    float max_chroma_mae;
    float min_state_margin;
} ma_player_state_matcher_config;

typedef struct ma_player_state_matcher ma_player_state_matcher;

ma_player_state_matcher *ma_player_state_matcher_create(
        const ma_player_state_matcher_config *config,
        const ma_player_state_signature *signatures, int signature_count);
void ma_player_state_matcher_destroy(ma_player_state_matcher *matcher);
int ma_player_state_match_rgba(
        const ma_player_state_matcher *matcher,
        const uint8_t *rgba, int width, int height, int row_stride,
        float *out_confidence);

typedef struct ma_engine_config {
    float min_confidence;
    int64_t max_observation_age_ms;
    int64_t min_global_gap_ms;
    int64_t minimap_min_gap_ms;
    int min_hits_in_three_frames;
    int reset_after_missing_frames;
} ma_engine_config;

typedef struct ma_engine ma_engine;

enum ma_locator_state {
    MA_LOCATOR_SEARCHING = 0,
    MA_LOCATOR_LOCKED = 1,
    MA_LOCATOR_HELD = 2
};

/*
 * Coordinates in base_short are measured in units of the active screen's
 * short edge and are anchored at the active content rectangle's top-left.
 * This keeps the coarse layout stable across different landscape ratios.
 */
typedef struct ma_minimap_locator_config {
    ma_rect base_short;
    float search_radius_x_short;
    float search_radius_y_short;
    float position_step_short;
    float min_scale;
    float max_scale;
    int scale_steps;
    float min_aspect;
    float max_aspect;
    int aspect_steps;
    int descriptor_width;
    int descriptor_height;
    float min_score;
    int confirm_frames;
    int hold_frames;
    int refresh_frames;
    int normalize_black_bars;
    int black_threshold;
    /* Keep the coarse profile ROI inside the detector crop after anchor search. */
    int preserve_base_roi;
} ma_minimap_locator_config;

typedef struct ma_minimap_locator ma_minimap_locator;

/*
 * The descriptor is a signed int8, row-major, low-resolution luminance
 * template. The locator copies it, so the caller may release the input after
 * creation. The returned ROI and content rectangle are normalized to the full
 * input frame. A locator owns mutable tracking state: update, reset, and
 * destroy calls on the same handle must not overlap. Separate handles may be
 * used concurrently.
 */
ma_minimap_locator *ma_minimap_locator_create(
        const ma_minimap_locator_config *config,
        const int8_t *descriptor, int descriptor_length);
void ma_minimap_locator_destroy(ma_minimap_locator *locator);
void ma_minimap_locator_reset(ma_minimap_locator *locator);
int ma_minimap_locator_update(
        ma_minimap_locator *locator,
        const uint8_t *rgba, int width, int height, int row_stride,
        ma_rect *out_minimap, ma_rect *out_content, float *out_score);

/* Reads only pixels supplied by the caller. All boxes are normalized to the full frame. */
int ma_detect_rgba(const uint8_t *rgba, int width, int height, int row_stride,
                   int64_t timestamp_ms, const ma_profile *profile,
                   const ma_template *minimap_enemy_template,
                   const ma_template *danger_ping_template,
                   ma_observation *out, int capacity);

ma_engine *ma_engine_create(const ma_engine_config *config);
void ma_engine_destroy(ma_engine *engine);
void ma_engine_reset(ma_engine *engine);
/* Calls that read or mutate one engine handle must be serialized by the caller. */
/* Call once per sampled frame, including frames with zero observations. */
int ma_engine_step(ma_engine *engine, const ma_observation *observations,
                   int observation_count, int64_t now_ms, ma_cue *out,
                   int capacity);
/* Snapshot confirmed tracks after ma_engine_step; does not mutate the engine. */
int ma_engine_read_minimap_markers(const ma_engine *engine,
                                   ma_minimap_marker *out, int capacity);
/*
 * Read the bounded category-aware memory snapshot. The result contains up to
 * MA_MAX_MINIMAP_TRACKS confirmed enemies followed by the confirmed local
 * player (when a player model is active). `capacity` is always honoured; the
 * function never writes beyond the caller's buffer. This does not emit cues.
 */
int ma_engine_read_tracked_entities(const ma_engine *engine,
                                    ma_tracked_entity *out, int capacity);
/* Clear minimap tracking without disturbing other event tracks or cooldowns. */
void ma_engine_clear_minimap_tracks(ma_engine *engine);

/*
 * Minimap near-zone relation layer.
 *
 * Reads the confirmed entity snapshot after ma_engine_step() and reports when
 * a visible enemy marker enters the neighbourhood of the local player's own
 * minimap marker. Distances are measured in units of the map body's short
 * edge in pixels; bearings are minimap-relative (screen up is up) and centred
 * on the player marker, not on hero facing or attack range.
 *
 * One occupancy episode produces at most one MA_RELATION_EVENT_NEAR_ENTER.
 * There is no global cooldown: re-entry is gated only by the REARM window.
 * Every call returns at most one event.
 */
enum ma_relation_state {
    MA_RELATION_UNKNOWN = 0,   /* self or map unreliable beyond the grace gap */
    MA_RELATION_CLEAR = 1,     /* reliable, no enemy inside the enter radius */
    MA_RELATION_PENDING = 2,   /* enemy inside the enter radius, confirming */
    MA_RELATION_OCCUPIED = 3,  /* confirmed occupancy; already announced */
    MA_RELATION_REARM = 4      /* left the exit radius, waiting to be stable */
};

enum ma_relation_event {
    MA_RELATION_EVENT_NONE = 0,
    MA_RELATION_EVENT_NEAR_ENTER = 1,
    MA_RELATION_EVENT_RADAR_PAUSED = 2,
    MA_RELATION_EVENT_RADAR_RESUMED = 3,
    /* A would-be cue that the rules suppressed; for audit logs only. */
    MA_RELATION_EVENT_SUPPRESSED = 4
};

enum ma_relation_suppression {
    MA_RELATION_SUPPRESSION_NONE = 0,
    /* Re-entered before the REARM window completed. */
    MA_RELATION_SUPPRESSION_REARM_PENDING = 1,
    /* Recovered from a short self-marker gap while the zone stayed occupied. */
    MA_RELATION_SUPPRESSION_SHORT_GAP = 2
};

/* Eight 45-degree minimap sectors; 0 means no reliable bearing. */
enum ma_relation_sector {
    MA_SECTOR_NONE = 0,
    MA_SECTOR_RIGHT = 1,
    MA_SECTOR_UP_RIGHT = 2,
    MA_SECTOR_UP = 3,
    MA_SECTOR_UP_LEFT = 4,
    MA_SECTOR_LEFT = 5,
    MA_SECTOR_DOWN_LEFT = 6,
    MA_SECTOR_DOWN = 7,
    MA_SECTOR_DOWN_RIGHT = 8
};

typedef struct ma_relation_config {
    float enter_radius;          /* R_enter, map short-edge units */
    float exit_radius;           /* R_exit, must exceed R_enter */
    float sector_hysteresis_deg; /* kept-sector margin at each boundary */
    float adjacent_ratio;        /* d < ratio * R_enter reports no bearing */
    float tie_ratio;             /* two targets this close report no bearing */
    int confirm_hits;            /* hits in the last three frames */
    int rearm_ms;                /* stable clear time before CLEAR */
    int short_gap_ms;            /* self-loss tolerance; also UNKNOWN delay */
    int pause_min_gap_ms;        /* minimum spacing between pause tones */
    int max_freshness_ms;        /* entity freshness limit */
} ma_relation_config;

typedef struct ma_relation_output {
    int state;              /* ma_relation_state after this update */
    int event;              /* ma_relation_event, at most one per update */
    int sector;             /* reported sector for NEAR_ENTER, else current */
    float pan;              /* cos(sector centre); 0 without a bearing */
    float nearest_distance; /* nearest eligible enemy, -1 when none/unknown */
    int episode_id;         /* monotonic occupancy episode, 0 before the first */
    int suppression;        /* ma_relation_suppression for SUPPRESSED */
    int reliable;           /* 1 when self and map were usable this frame */
} ma_relation_output;

typedef struct ma_relation ma_relation;

/* Returns NULL when the configuration is out of range. */
ma_relation *ma_relation_create(const ma_relation_config *config);
void ma_relation_destroy(ma_relation *relation);
/* Clear state and tone pairing; episode ids stay monotonic per handle. */
void ma_relation_reset(ma_relation *relation);
/*
 * Call once per processed frame after ma_engine_read_tracked_entities().
 * map_body is the normalized full-frame rectangle of the map widget itself
 * (profile rois.minimap_direction), not the padded detector crop. map_valid
 * is 0 while the layout locator is searching. Returns 1 on success and 0 on
 * invalid arguments; out is always written.
 */
int ma_relation_update(ma_relation *relation,
                       const ma_tracked_entity *entities, int entity_count,
                       ma_rect map_body, int map_valid, int width, int height,
                       int64_t now_ms, ma_relation_output *out);

#ifdef __cplusplus
}
#endif

#endif
