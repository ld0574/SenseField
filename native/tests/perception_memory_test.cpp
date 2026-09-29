#include "mapassist.h"

#include <cassert>
#include <cmath>

namespace {

ma_observation observation(int kind, int64_t timestamp, float x = 0.10f) {
    return {kind, MA_DIR_RIGHT, {x, 0.10f, 0.02f, 0.04f}, 0.90f, timestamp};
}

int step(ma_engine *engine, const ma_observation *observation_ptr,
         int count, int64_t timestamp, ma_cue &cue) {
    return ma_engine_step(engine, observation_ptr, count, timestamp, &cue, 1);
}

int read_entities(ma_engine *engine, ma_tracked_entity *entities, int capacity) {
    return ma_engine_read_tracked_entities(engine, entities, capacity);
}

}  // namespace

int main() {
    const ma_engine_config config{0.10f, 1000, 0, 0, 2, 3};
    ma_engine *engine = ma_engine_create(&config);
    assert(engine != nullptr);
    ma_cue cue{};
    ma_tracked_entity entities[MA_MAX_TRACKED_ENTITIES]{};

    ma_observation enemy = observation(MA_MINIMAP_ENEMY, 0);
    assert(step(engine, &enemy, 1, 0, cue) == 0);
    enemy.timestamp_ms = 83;
    assert(step(engine, &enemy, 1, 83, cue) == 1);
    assert(cue.kind == MA_MINIMAP_ENEMY);
    assert(read_entities(engine, entities, MA_MAX_TRACKED_ENTITIES) == 1);
    const int first_id = entities[0].track_id;
    assert(entities[0].entity_kind == MA_MINIMAP_ENEMY);
    assert(entities[0].state == MA_TRACK_STATE_VISIBLE);
    assert(entities[0].transition == MA_VISION_EVENT_APPEAR);
    assert(std::fabs(entities[0].confidence - 0.90f) < 0.001f);

    // Three misses make the enemy LOST, while the last box remains available.
    assert(step(engine, nullptr, 0, 166, cue) == 0);
    assert(step(engine, nullptr, 0, 249, cue) == 0);
    assert(step(engine, nullptr, 0, 332, cue) == 0);
    assert(read_entities(engine, entities, MA_MAX_TRACKED_ENTITIES) == 1);
    assert(entities[0].state == MA_TRACK_STATE_LOST);
    assert(entities[0].transition == MA_VISION_EVENT_DISAPPEAR);
    assert(entities[0].track_id == first_id);

    // A sighting inside the two-second grace period resumes the same track
    // silently and does not create a second APPEAR transition.
    enemy.timestamp_ms = 415;
    assert(step(engine, &enemy, 1, 415, cue) == 0);
    assert(read_entities(engine, entities, MA_MAX_TRACKED_ENTITIES) == 1);
    assert(entities[0].state == MA_TRACK_STATE_VISIBLE);
    assert(entities[0].transition == MA_VISION_EVENT_NONE);
    assert(entities[0].track_id == first_id);

    // Lose it again, then wait at least two seconds. A fresh confirmation is
    // required and receives a new track id.
    assert(step(engine, nullptr, 0, 498, cue) == 0);
    assert(step(engine, nullptr, 0, 581, cue) == 0);
    assert(step(engine, nullptr, 0, 664, cue) == 0);
    // Advance through the grace boundary with bounded frame gaps. A large
    // jump would intentionally reset the whole session before reappearance.
    assert(step(engine, nullptr, 0, 1064, cue) == 0);
    assert(step(engine, nullptr, 0, 1464, cue) == 0);
    assert(step(engine, nullptr, 0, 1864, cue) == 0);
    assert(step(engine, nullptr, 0, 2264, cue) == 0);
    assert(step(engine, nullptr, 0, 2664, cue) == 0);
    enemy.timestamp_ms = 2747;
    assert(step(engine, &enemy, 1, 2747, cue) == 0);
    assert(read_entities(engine, entities, MA_MAX_TRACKED_ENTITIES) == 0);
    enemy.timestamp_ms = 2830;
    assert(step(engine, &enemy, 1, 2830, cue) == 1);
    assert(read_entities(engine, entities, MA_MAX_TRACKED_ENTITIES) == 1);
    assert(entities[0].track_id != first_id);
    assert(entities[0].transition == MA_VISION_EVENT_APPEAR);

    // The newly confirmed track also expires exactly four seconds after its
    // LOST transition when the detector stays silent.
    assert(step(engine, nullptr, 0, 2913, cue) == 0);
    assert(step(engine, nullptr, 0, 2996, cue) == 0);
    assert(step(engine, nullptr, 0, 3079, cue) == 0);
    for (int64_t timestamp = 3479; timestamp < 7079; timestamp += 400)
        assert(step(engine, nullptr, 0, timestamp, cue) == 0);
    assert(step(engine, nullptr, 0, 7079, cue) == 0);
    assert(read_entities(engine, entities, MA_MAX_TRACKED_ENTITIES) == 0);

    // Snapshot capacity is caller-owned and bounded. Eight confirmed enemy
    // tracks must not write a ninth record when capacity is one.
    ma_observation enemies[MA_MAX_MINIMAP_TRACKS]{};
    for (int index = 0; index < MA_MAX_MINIMAP_TRACKS; ++index)
        enemies[index] = observation(MA_MINIMAP_ENEMY, 8000,
                                     0.04f + index * 0.10f);
    assert(step(engine, enemies, MA_MAX_MINIMAP_TRACKS, 8000, cue) == 0);
    for (ma_observation &item : enemies) item.timestamp_ms = 8083;
    assert(step(engine, enemies, MA_MAX_MINIMAP_TRACKS, 8083, cue) == 1);
    ma_tracked_entity limited[2]{};
    limited[1].track_id = 991;
    assert(read_entities(engine, limited, 1) == 1);
    assert(limited[1].track_id == 991);

    ma_engine_reset(engine);

    // The local player's minimap icon shares confirmation and memory, but it
    // is state-only: ma_engine_step never emits a player cue.
    ma_observation player = observation(MA_MINIMAP_PLAYER, 9000, 0.60f);
    assert(step(engine, &player, 1, 9000, cue) == 0);
    player.timestamp_ms = 9083;
    assert(step(engine, &player, 1, 9083, cue) == 0);
    const int count = read_entities(engine, entities, MA_MAX_TRACKED_ENTITIES);
    assert(count == 1);
    assert(entities[0].entity_kind == MA_MINIMAP_PLAYER);
    assert(entities[0].transition == MA_VISION_EVENT_APPEAR);
    assert(entities[0].freshness_ms == 0);
    assert(entities[0].freshness_ms <= MA_PLAYER_RELEVANCE_MAX_AGE_MS);

    assert(step(engine, nullptr, 0, 9584, cue) == 0);
    assert(read_entities(engine, entities, MA_MAX_TRACKED_ENTITIES) == 1);
    assert(entities[0].freshness_ms > MA_PLAYER_RELEVANCE_MAX_AGE_MS);

    // The player class has one logical target. A teleport or large minimap
    // jump updates that track immediately instead of being dropped because it
    // exceeds the enemy association radius.
    player = observation(MA_MINIMAP_PLAYER, 9667, 0.80f);
    assert(step(engine, &player, 1, 9667, cue) == 0);
    assert(read_entities(engine, entities, MA_MAX_TRACKED_ENTITIES) == 1);
    assert(entities[0].entity_kind == MA_MINIMAP_PLAYER);
    assert(entities[0].bbox.x > 0.70f);
    assert(entities[0].freshness_ms == 0);

    ma_engine_reset(engine);
    assert(read_entities(engine, entities, MA_MAX_TRACKED_ENTITIES) == 0);

    // A main-screen edge threat stays armed through brief detector dropouts.
    // Only two continuous seconds in CLEARED allow the same side to announce
    // again, and the returning candidate still needs two hits in three frames.
    ma_observation edge = observation(MA_MAIN_ENEMY, 10000, 0.02f);
    edge.direction = MA_DIR_LEFT;
    assert(step(engine, &edge, 1, 10000, cue) == 0);
    edge.timestamp_ms = 10083;
    assert(step(engine, &edge, 1, 10083, cue) == 1);
    assert(cue.kind == MA_MAIN_ENEMY);
    assert(step(engine, nullptr, 0, 10166, cue) == 0);
    assert(step(engine, nullptr, 0, 10249, cue) == 0);
    assert(step(engine, nullptr, 0, 10332, cue) == 0);
    edge.timestamp_ms = 10415;
    assert(step(engine, &edge, 1, 10415, cue) == 0);
    edge.timestamp_ms = 10498;
    assert(step(engine, &edge, 1, 10498, cue) == 0);

    assert(step(engine, nullptr, 0, 10581, cue) == 0);
    assert(step(engine, nullptr, 0, 10664, cue) == 0);
    assert(step(engine, nullptr, 0, 10747, cue) == 0);
    for (int64_t timestamp = 11147; timestamp <= 12747; timestamp += 400)
        assert(step(engine, nullptr, 0, timestamp, cue) == 0);
    edge.timestamp_ms = 12830;
    assert(step(engine, &edge, 1, 12830, cue) == 0);
    edge.timestamp_ms = 12913;
    assert(step(engine, &edge, 1, 12913, cue) == 1);
    assert(cue.kind == MA_MAIN_ENEMY);

    // Main-screen memory only represents the two horizontal edges. Vertical
    // red UI fragments must never become an "above/below enemy" cue.
    ma_engine_reset(engine);
    ma_observation vertical = observation(MA_MAIN_ENEMY, 13000, 0.50f);
    vertical.direction = MA_DIR_UP;
    assert(step(engine, &vertical, 1, 13000, cue) == 0);
    vertical.timestamp_ms = 13083;
    assert(step(engine, &vertical, 1, 13083, cue) == 0);
    vertical.direction = MA_DIR_DOWN;
    vertical.timestamp_ms = 13166;
    assert(step(engine, &vertical, 1, 13166, cue) == 0);
    vertical.timestamp_ms = 13249;
    assert(step(engine, &vertical, 1, 13249, cue) == 0);

    // Zero is a valid frame timestamp. The edge rearm clock must not use it
    // as an "unset" marker or a session starting at zero will never rearm.
    ma_engine_reset(engine);
    edge.timestamp_ms = 0;
    assert(step(engine, &edge, 1, 0, cue) == 0);
    edge.timestamp_ms = 83;
    assert(step(engine, &edge, 1, 83, cue) == 1);
    for (int64_t timestamp = 166; timestamp <= 2407; timestamp += 83)
        assert(step(engine, nullptr, 0, timestamp, cue) == 0);
    edge.timestamp_ms = 2490;
    assert(step(engine, &edge, 1, 2490, cue) == 0);
    edge.timestamp_ms = 2573;
    assert(step(engine, &edge, 1, 2573, cue) == 1);
    assert(cue.kind == MA_MAIN_ENEMY);

    ma_engine_reset(engine);
    ma_engine_destroy(engine);
    return 0;
}
