"""Near-zone relation layer: ctypes parity, profiles, calibration and audit logs."""

from __future__ import annotations

import copy
import ctypes
import hashlib
import json
import math
import re
import subprocess
from pathlib import Path

import pytest

from mapassist import native
from mapassist.android_session_log import parse_session_log
from mapassist.calibrate_near_zone import apply as apply_calibration
from mapassist.calibrate_near_zone import calibrate

ROOT = Path(__file__).resolve().parents[1]
DESKTOP_PROFILE = ROOT / "profiles/hok_minimap_dual_512_near_zone.experimental.json"
ANDROID_PROFILE = ROOT / "profiles/hok_minimap_dual_512_near_zone.experimental.android.json"
ASSETS = ROOT / "android/app/src/main/assets"
MAP_BODY = native.Rect(0.10, 0.10, 0.40, 0.40)  # 400 px short edge in a 1000 px frame
SELF_X, SELF_Y = 0.30, 0.30


@pytest.fixture(scope="session")
def relation_library(tmp_path_factory: pytest.TempPathFactory):
    build_dir = tmp_path_factory.mktemp("relation-native-build")
    subprocess.run(["cmake", "-S", str(ROOT / "native"), "-B", str(build_dir)],
                   check=True, capture_output=True)
    subprocess.run(["cmake", "--build", str(build_dir)], check=True, capture_output=True)
    return native.load_library(next(build_dir.glob("libmapassist.*")))


def _entity(kind: int, x: float, y: float, state: int = native.MA_TRACK_STATE_VISIBLE,
            freshness: int = 0, track_id: int = 1) -> native.TrackedEntity:
    value = native.TrackedEntity()
    value.entity_kind = kind
    value.track_id = track_id
    value.state = state
    value.bbox = native.Rect(x - 0.01, y - 0.01, 0.02, 0.02)
    value.confidence = 0.9
    value.freshness_ms = freshness
    return value


def _self() -> native.TrackedEntity:
    return _entity(native.MA_MINIMAP_PLAYER, SELF_X, SELF_Y)


def _enemy(distance: float, bearing: float, **kwargs) -> native.TrackedEntity:
    radians = math.radians(bearing)
    return _entity(native.MA_MINIMAP_ENEMY, SELF_X + distance * math.cos(radians) * 0.40,
                   SELF_Y - distance * math.sin(radians) * 0.40, **kwargs)


def _relation(library) -> native.Relation:
    return native.Relation(library, native.RelationConfig(
        0.20, 0.25, 7.5, 0.2, 0.1, 2, 3000, 2000, 10000, 500))


def _step(relation: native.Relation, now_ms: int, *items) -> dict:
    for item in items:
        item.last_seen_ms = now_ms - item.freshness_ms
    buffer = (native.TrackedEntity * max(1, len(items)))(*items)
    return relation.update(buffer, len(items), MAP_BODY, True, 1000, 1000, now_ms)

def test_far_enemy_walking_in_cues_once_with_bearing(relation_library) -> None:
    with _relation(relation_library) as relation:
        outputs = [_step(relation, index * 83, _self(), _enemy(0.60 - 0.05 * index, 135.0))
                   for index in range(11)]
        outputs += [_step(relation, (11 + index) * 83, _self(), _enemy(0.10, 135.0))
                    for index in range(30)]
    enters = [item for item in outputs if item["event"] == "NEAR_ENTER"]
    assert len(enters) == 1
    assert enters[0]["sector"] == "up_left"
    assert enters[0]["pan"] == pytest.approx(math.cos(math.radians(135.0)), abs=1e-4)
    assert outputs[0]["state"] == "CLEAR"


def test_continuously_visible_range_return_stays_quiet(relation_library) -> None:
    now = 0
    events = []
    with _relation(relation_library) as relation:
        for distance, frames in ((0.15, 2), (0.40, 40), (0.15, 2)):
            for _ in range(frames):
                events.append((now, _step(relation, now, _self(), _enemy(distance, 0.0))))
                now += 83
    enters = [(at, item) for at, item in events if item["event"] == "NEAR_ENTER"]
    assert [item["episode_id"] for _, item in enters] == [1]


def test_long_self_loss_pauses_then_resumes(relation_library) -> None:
    now = 0
    events = []
    with _relation(relation_library) as relation:
        for frame in range(2):
            events.append(_step(relation, now, _self(), _enemy(0.15, 0.0)))
            now += 83
        for frame in range(30):
            events.append(_step(relation, now, _enemy(0.15, 0.0)))
            now += 83
        for frame in range(2):
            events.append(_step(relation, now, _self(), _enemy(0.15, 0.0)))
            now += 83
    names = [item["event"] for item in events if item["event"]]
    assert names == ["NEAR_ENTER", "RADAR_PAUSED", "RADAR_RESUMED", "NEAR_ENTER"]


def test_lost_ghosts_are_not_evidence(relation_library) -> None:
    with _relation(relation_library) as relation:
        outputs = [_step(relation, index * 83, _self(),
                         _enemy(0.05, 0.0, state=native.MA_TRACK_STATE_LOST))
                   for index in range(10)]
    assert all(item["event"] is None and item["state"] == "CLEAR" for item in outputs)
    assert all(item["nearest_distance"] is None for item in outputs)


@pytest.mark.parametrize("missing_frames", [1, 2, 3, 10, 30, 45, 60])
@pytest.mark.parametrize("another_enemy_stays", [False, True])
def test_tracker_to_relation_requires_confirmed_absence(
    relation_library, missing_frames: int, another_enemy_stays: bool
) -> None:
    # Exercise real confirmation, retained VISIBLE boxes, LOST, the old
    # two-second identity grace and four-second memory expiry. No footage or
    # model inference is needed to verify the event contract.
    config = native.EngineConfig(.1, 500, 0, 0, 2, 3)
    engine = relation_library.ma_engine_create(ctypes.byref(config))
    assert engine
    entities = (native.TrackedEntity * native.MA_MAX_TRACKED_ENTITIES)()
    cues = (native.Cue * 1)()
    now = 0
    target = _enemy(.18, 135)
    staying = _enemy(.10, 0)
    other = [staying] if another_enemy_stays else []

    try:
        with _relation(relation_library) as relation:
            def step(enemies: list) -> dict:
                nonlocal now
                observations = (native.Observation * (1 + len(enemies)))(
                    native.Observation(native.MA_MINIMAP_PLAYER, 0, _self().bbox, .9, now),
                    *(native.Observation(native.MA_MINIMAP_ENEMY, 0, enemy.bbox, .9, now)
                      for enemy in enemies),
                )
                relation_library.ma_engine_step(engine, observations, len(observations),
                                              now, cues, 1)
                count = relation_library.ma_engine_read_tracked_entities(
                    engine, entities, len(entities))
                result = relation.update(entities, count, MAP_BODY, True, 1000, 1000, now)
                now += 83
                return result

            initial = [step([target, *other]) for _ in range(3)]
            assert [item["episode_id"] for item in initial if item["event"] == "NEAR_ENTER"] == [1]
            absent = [step(other) for _ in range(missing_frames)]
            assert all(item["event"] != "NEAR_ENTER" for item in absent)
            returned = [step([target, *other]) for _ in range(4)]
            enters = [item for item in returned if item["event"] == "NEAR_ENTER"]
            if missing_frames < 45:
                # LOST alone is uncertainty, including replacement ids after
                # the tracker's two-second grace. Require 3s reliable absence.
                assert enters == []
            else:
                assert len(enters) == 1
                assert enters[0]["episode_id"] == 2
                assert enters[0]["sector"] == "up_left"
                assert enters[0]["nearest_distance"] == pytest.approx(.18, abs=1e-4)
            assert all(step([target, *other])["event"] is None for _ in range(10))
    finally:
        relation_library.ma_engine_destroy(engine)


def test_one_returned_detection_and_cached_boxes_do_not_trigger(relation_library) -> None:
    with _relation(relation_library) as relation:
        assert _step(relation, 0, _self(), _enemy(.15, 0))["event"] is None
        assert _step(relation, 83, _self(), _enemy(.15, 0))["event"] == "NEAR_ENTER"
        for i in range(38):
            _step(relation, 166 + i * 83, _self(),
                  _enemy(.15, 0, state=native.MA_TRACK_STATE_LOST))
        assert _step(relation, 3320, _self(), _enemy(.15, 0))["event"] is None
        assert _step(relation, 3403, _self(), _enemy(.15, 0, freshness=83))["event"] is None
        assert _step(relation, 3486, _self(), _enemy(.15, 0, freshness=166))["event"] is None
        _step(relation, 3569, _self(), _enemy(.15, 0, state=native.MA_TRACK_STATE_LOST))
        assert _step(relation, 3652, _self(), _enemy(.15, 0))["event"] is None
        assert _step(relation, 3735, _self(), _enemy(.15, 0))["episode_id"] == 2



@pytest.mark.parametrize("width,height", [(1000, 1000), (4000, 1000)])
def test_id_continuity_uses_marker_size_in_pixels(relation_library, width, height):
    # Square 400px map body in differently shaped capture frames. A 10px
    # replacement must inherit state; an unrelated 100px-away enemy must not.
    body = native.Rect(.1, .1, 400 / width, 400 / height)
    def marker(kind, track_id, x_px, y_px):
        value = _entity(kind, .1 + x_px / width, .1 + y_px / height, track_id=track_id)
        value.bbox.w = 20 / width
        value.bbox.h = 20 / height
        value.bbox.x = .1 + (x_px - 10) / width
        value.bbox.y = .1 + (y_px - 10) / height
        return value
    now = 0
    with _relation(relation_library) as relation:
        def step(*items):
            nonlocal now
            for item in items:
                item.last_seen_ms = now
            buf = (native.TrackedEntity * len(items))(*items)
            result = relation.update(buf, len(items), body, True, width, height, now)
            now += 83
            return result
        player = marker(native.MA_MINIMAP_PLAYER, 100, 200, 200)
        old = marker(native.MA_MINIMAP_ENEMY, 1, 150, 200)
        assert step(player, old)["event"] is None
        assert step(player, old)["event"] == "NEAR_ENTER"
        old.state = native.MA_TRACK_STATE_LOST
        step(player, old)
        replaced = marker(native.MA_MINIMAP_ENEMY, 9, 140, 200)
        extra = marker(native.MA_MINIMAP_ENEMY, 10, 250, 200)
        assert step(player, replaced, extra)["event"] is None
        result = step(player, replaced, extra)
        assert result["event"] == "NEAR_ENTER"
        assert result["sector"] == "right"
        assert result["nearest_distance"] == pytest.approx(.125)

def test_ctypes_constants_match_the_c_header() -> None:
    header = (ROOT / "native/include/mapassist.h").read_text(encoding="utf-8")

    def define(name: str) -> str:
        match = re.search(rf"#define {name} (.+)", header)
        assert match, name
        return match.group(1).strip()

    assert define("MA_API_VERSION") == "9"
    assert int(define("MA_MAX_MINIMAP_TRACKS")) == native.MA_MAX_MINIMAP_TRACKS
    assert define("MA_MAX_TRACKED_ENTITIES") == "(MA_MAX_MINIMAP_TRACKS + 1)"
    assert native.MA_MAX_TRACKED_ENTITIES == native.MA_MAX_MINIMAP_TRACKS + 1
    assert int(define("MA_PLAYER_RELEVANCE_MAX_AGE_MS")) == \
        native.MA_PLAYER_RELEVANCE_MAX_AGE_MS
    for name, value in (("MA_NEAR_ZONE", native.MA_NEAR_ZONE),
                        ("MA_RADAR_STATUS", native.MA_RADAR_STATUS),
                        ("MA_MINIMAP_PLAYER", native.MA_MINIMAP_PLAYER)):
        assert re.search(rf"\b{name} = {value}\b", header), name
    for code, name in native.RELATION_STATE_NAMES.items():
        assert re.search(rf"MA_RELATION_{name} = {code}\b", header), name


def _relation_profile() -> dict:
    return json.loads(DESKTOP_PROFILE.read_text(encoding="utf-8"))


def test_profile_relation_section_is_validated() -> None:
    profile = _relation_profile()
    config = native.relation_config_from_profile(profile)
    assert config is not None and config.confirm_hits == 2 and config.rearm_ms == 3000
    disabled = copy.deepcopy(profile)
    disabled["minimap_relation"]["enabled"] = False
    assert native.relation_config_from_profile(disabled) is None
    del disabled["minimap_relation"]
    assert native.relation_config_from_profile(disabled) is None
    no_body = copy.deepcopy(profile)
    del no_body["rois"]["minimap_direction"]
    with pytest.raises(ValueError, match="minimap_direction"):
        native.relation_config_from_profile(no_body)


@pytest.mark.parametrize(("field", "value"), [
    ("exit_radius", 0.16), ("confirm_hits", 0), ("short_gap_ms", 100),
    ("enter_radius", "0.2"), ("schema_version", 2), ("enabled", "true"),
])
def test_malformed_relation_section_is_rejected(field: str, value) -> None:
    profile = _relation_profile()
    profile["minimap_relation"][field] = value
    with pytest.raises(ValueError):
        native.relation_config_from_profile(profile)

def test_dual_class_profiles_match_bundle_and_metadata() -> None:
    desktop = _relation_profile()
    bundled = json.loads(ANDROID_PROFILE.read_text(encoding="utf-8"))
    shipped = json.loads((ASSETS / "profile-dual-512-near-zone.json").read_text(encoding="utf-8"))
    assert shipped == bundled
    for key in ("schema_version", "name", "profile_version", "verified", "rois", "detectors",
                "models", "thresholds", "events", "minimap_relation"):
        assert bundled[key] == desktop[key]
    assert bundled["verified"] is False
    assert bundled["minimap_relation"]["enter_radius"] == .16
    assert bundled["minimap_relation"]["exit_radius"] == .20
    assert bundled["minimap_relation"]["rearm_ms"] == 3000
    thresholds = bundled["thresholds"]
    assert thresholds["minimap_yolox_input_size"] == 512
    # The event engine filters every class again with events.min_confidence;
    # it must not exceed the lowest per-class detector threshold.
    assert bundled["events"]["min_confidence"] <= min(
        thresholds["minimap_yolox_confidence_by_class"].values())
    metadata = json.loads(
        (ASSETS / "minimap-yolox-nano-dual-512.metadata.json").read_text(encoding="utf-8"))
    assert metadata["candidate"]["profile_version"] == bundled["profile_version"]
    assert metadata["classes"] == ["minimap_enemy", "minimap_player"]
    assert metadata["input"] == [1, 3, 512, 512] and metadata["output"] == [1, 5376, 7]
    assert metadata["runtime"]["bin_sha256"] == bundled["models"]["minimap_yolox_bin_sha256"]
    assert metadata["runtime"]["param_asset"] == bundled["models"]["minimap_yolox_param_asset"]
    assert metadata["runtime"]["bin_asset"] == bundled["models"]["minimap_yolox_bin_asset"]
    assert metadata["postprocess"]["confidence_by_class"] == \
        thresholds["minimap_yolox_confidence_by_class"]
    assert metadata["verified"] is False and metadata["candidate"]["release_ready"] is False
    assert bundled["minimap_relation"]["calibration"]["status"] == "provisional"
    # The frozen 320 enemy-only default stays untouched and has no relation layer.
    baseline = json.loads((ASSETS / "profile.json").read_text(encoding="utf-8"))
    assert "minimap_relation" not in baseline
    assert baseline["thresholds"]["minimap_yolox_input_size"] == 320


@pytest.mark.parametrize("inference_ms,expected_enter", [(300, True), (500, True), (501, False)])
def test_bundled_near_zone_accepts_bounded_inference_latency(
    relation_library, inference_ms: int, expected_enter: bool
) -> None:
    # Exercise the engine -> entity -> relation path with capture time kept
    # separate from completion time. The former 250 ms gate rejected both
    # markers before the near-zone layer could confirm an enemy approaching.
    profile = json.loads((ASSETS / "profile-dual-512-near-zone.json").read_text())
    events = profile["events"]
    assert events["max_observation_age_ms"] == profile["minimap_relation"]["max_freshness_ms"] == 500
    config = native.EngineConfig(
        events["min_confidence"], events["max_observation_age_ms"],
        events["min_global_gap_ms"], events["minimap_min_gap_ms"],
        events["min_hits_in_three_frames"], events["reset_after_missing_frames"],
    )
    engine = relation_library.ma_engine_create(ctypes.byref(config))
    assert engine
    entities = (native.TrackedEntity * native.MA_MAX_TRACKED_ENTITIES)()
    cues = (native.Cue * 1)()
    outputs = []
    try:
        with native.Relation(relation_library, native.relation_config_from_profile(profile)) as relation:
            for index in range(5):
                captured_ms = 1000 + index * 400
                completed_ms = captured_ms + inference_ms
                observations = (native.Observation * 2)(
                    native.Observation(native.MA_MINIMAP_PLAYER, 0,
                                       _self().bbox, 0.9, captured_ms),
                    native.Observation(native.MA_MINIMAP_ENEMY, 0,
                                       _enemy(0.15, 0).bbox, 0.9, captured_ms),
                )
                relation_library.ma_engine_step(engine, observations, 2, completed_ms, cues, 1)
                count = relation_library.ma_engine_read_tracked_entities(
                    engine, entities, len(entities))
                if expected_enter and count:
                    assert all(e.last_seen_ms == captured_ms for e in entities[:count])
                    assert all(e.freshness_ms == inference_ms for e in entities[:count])
                outputs.append(relation.update(entities, count, MAP_BODY, True,
                                               1000, 1000, completed_ms))
    finally:
        relation_library.ma_engine_destroy(engine)
    enters = [output for output in outputs if output["event"] == "NEAR_ENTER"]
    assert len(enters) == (1 if expected_enter else 0)
    assert outputs[-1]["reliable"] is expected_enter


def test_local_dual_class_weights_match_metadata() -> None:
    param = ASSETS / "minimap-yolox-nano-dual-512.param"
    weights = ASSETS / "minimap-yolox-nano-dual-512.bin"
    if not param.is_file() and not weights.is_file():
        pytest.skip("private dual-class weights are absent; the near-zone profile falls back")
    metadata = json.loads(
        (ASSETS / "minimap-yolox-nano-dual-512.metadata.json").read_text(encoding="utf-8"))
    assert hashlib.sha256(param.read_bytes()).hexdigest() == metadata["runtime"]["param_sha256"]
    assert hashlib.sha256(weights.read_bytes()).hexdigest() == metadata["runtime"]["bin_sha256"]
    assert param.read_text(encoding="utf-8").splitlines()[3].startswith("YoloV5Focus")


def _detection(name: str, x: float, y: float, confidence: float = 0.9) -> dict:
    return {"class_name": name, "confidence": confidence,
            "bbox_norm": [x - 0.005, y - 0.005, 0.01, 0.01]}


def test_calibration_uses_median_of_marked_moments(tmp_path: Path) -> None:
    # 1920x860 frame: the map body short edge is 0.32209 * 860 = 277 px.
    short_px = 0.32209302325581396 * 860
    player = (0.12, 0.16)

    def enemy_at(distance: float) -> dict:
        return _detection("minimap_enemy", player[0] + distance * short_px / 1920, player[1])

    frames = [
        [_detection("minimap_player", *player), enemy_at(0.18)],
        [_detection("minimap_player", *player), enemy_at(0.20)],
        [_detection("minimap_player", *player), enemy_at(0.22)],
        [enemy_at(0.20)],
        [_detection("minimap_player", *player), enemy_at(0.20), enemy_at(0.21)],
    ]
    replay = tmp_path / "replay.jsonl"
    replay.write_text("".join(json.dumps({"timestamp_ms": index * 1000, "detections": frame})
                              + "\n" for index, frame in enumerate(frames)), encoding="utf-8")
    moments = tmp_path / "moments.csv"
    moments.write_text("timestamp_ms\n0\n1000\n2010\n3000\n4000\n99999\n", encoding="utf-8")
    report = calibrate(DESKTOP_PROFILE, replay, moments, (1920, 860))
    assert [item["distance"] for item in report["samples"]] == pytest.approx(
        [0.18, 0.20, 0.22], abs=1e-3)
    assert report["suggested"]["enter_radius"] == pytest.approx(0.20, abs=1e-3)
    assert report["suggested"]["exit_radius"] == pytest.approx(0.25, abs=2e-3)
    assert [item["reason"] for item in report["skipped"]] == [
        "no_player", "ambiguous_second_enemy", "no_frame_in_range"]

    target = tmp_path / "profile.json"
    target.write_text(DESKTOP_PROFILE.read_text(encoding="utf-8"), encoding="utf-8")
    apply_calibration(target, report)
    updated = json.loads(target.read_text(encoding="utf-8"))
    assert updated["minimap_relation"]["calibration"]["status"] == "calibrated"
    assert updated["minimap_relation"]["calibration"]["samples"] == 3
    assert native.relation_config_from_profile(updated).enter_radius == pytest.approx(
        report["suggested"]["enter_radius"], abs=1e-6)

SESSION_ID = "session-7"


def _near_zone_log(summary: str = "", extra: tuple[str, ...] = ()) -> str:
    tag = "I/MapAssistCapture: "
    lines = [
        f"{tag}SessionStart sessionId={SESSION_ID} startId=1 startedElapsedRealtimeMs=1000",
        f"{tag}NearZoneState sessionId={SESSION_ID} from=RESET to=CLEAR reliable=true atMs=1100",
        f"{tag}NearZoneState sessionId={SESSION_ID} from=CLEAR to=PENDING reliable=true "
        "atMs=1183",
        f"{tag}CueEvent sessionId={SESSION_ID} cueId={SESSION_ID}:1 kind=7 direction=0 "
        "observedAtMs=1266 frameAgeMs=40 nativeMicros=60000 stale=false audioQueued=true",
        f"{tag}NearZoneEvent sessionId={SESSION_ID} event=NEAR_ENTER episode=1 sector=4 "
        f"panMilli=-707 distanceMilli=150 suppression=none cueId={SESSION_ID}:1 "
        "outcome=ACCEPTED atMs=1266",
        f"{tag}NearZoneEvent sessionId={SESSION_ID} event=SUPPRESSED episode=1 sector=4 "
        "panMilli=-707 distanceMilli=150 suppression=rearm_pending cueId=- "
        "outcome=SUPPRESSED atMs=1600",
        *extra,
        f"{tag}NearZoneSummary sessionId={SESSION_ID} unknownFrames=2 clearFrames=4 "
        "pendingFrames=1 occupiedFrames=2 rearmFrames=1 nearEnters=1 suppressed=1 "
        f"radarPauses=0 radarResumes=0 {summary}",
        f"{tag}SessionSummary sessionId={SESSION_ID} durationMs=60000 processedFrames=10 "
        "landscapeProcessedFrames=10 firstProcessedElapsedRealtimeMs=1100 "
        "lastProcessedElapsedRealtimeMs=1847 maxProcessedGapMs=83 detected=1 queued=1 "
        "stale=0 audioFailures=0 reason=stopped",
    ]
    return "\n".join(lines) + "\n"


def test_session_log_reports_near_zone_coverage_and_rate(tmp_path: Path) -> None:
    path = tmp_path / "logcat.txt"
    path.write_text(_near_zone_log(), encoding="utf-8")
    parsed = parse_session_log(path, SESSION_ID)
    near = parsed["near_zone"]
    assert near["coverage"] == pytest.approx(0.8)
    assert near["near_enters"] == 1
    assert near["near_enter_per_minute"] == pytest.approx(1.0)
    assert near["suppressions"] == {"rearm_pending": 1}
    assert [item["to"] for item in near["transitions"]] == ["CLEAR", "PENDING"]
    assert parsed["cues"][0]["kind"] == "near_zone"


def test_session_log_rejects_inconsistent_near_zone_records(tmp_path: Path) -> None:
    path = tmp_path / "logcat.txt"
    path.write_text(_near_zone_log().replace("nearEnters=1", "nearEnters=2"), encoding="utf-8")
    with pytest.raises(ValueError, match="nearEnters"):
        parse_session_log(path, SESSION_ID)
    path.write_text(_near_zone_log().replace("kind=7", "kind=8"), encoding="utf-8")
    with pytest.raises(ValueError, match="near_zone CueEvent"):
        parse_session_log(path, SESSION_ID)
    bad_state = (f"I/MapAssistCapture: NearZoneState sessionId={SESSION_ID} from=CLEAR "
                 "to=SAFE reliable=true atMs=1700",)
    path.write_text(_near_zone_log(extra=bad_state), encoding="utf-8")
    with pytest.raises(ValueError, match="invalid transition"):
        parse_session_log(path, SESSION_ID)


def test_session_without_near_zone_records_keeps_schema(tmp_path: Path) -> None:
    content = "\n".join(line for line in _near_zone_log().splitlines()
                        if "NearZone" not in line and "CueEvent" not in line)
    path = tmp_path / "logcat.txt"
    path.write_text(content.replace("detected=1 queued=1", "detected=0 queued=0") + "\n",
                    encoding="utf-8")
    parsed = parse_session_log(path, SESSION_ID)
    assert "near_zone" not in parsed
