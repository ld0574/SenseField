"""Near-zone relation layer: ctypes parity, profiles, calibration and audit logs."""

from __future__ import annotations

import copy
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
            freshness: int = 0) -> native.TrackedEntity:
    value = native.TrackedEntity()
    value.entity_kind = kind
    value.track_id = 1
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


def test_rearm_window_rearms_without_global_cooldown(relation_library) -> None:
    now = 0
    events = []
    with _relation(relation_library) as relation:
        for distance, frames in ((0.15, 2), (0.40, 40), (0.15, 2)):
            for _ in range(frames):
                events.append((now, _step(relation, now, _self(), _enemy(distance, 0.0))))
                now += 83
    enters = [(at, item) for at, item in events if item["event"] == "NEAR_ENTER"]
    assert [item["episode_id"] for _, item in enters] == [1, 2]
    assert enters[1][0] - enters[0][0] < 15000


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
    ("exit_radius", 0.20), ("confirm_hits", 0), ("short_gap_ms", 100),
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
    thresholds = bundled["thresholds"]
    assert thresholds["minimap_yolox_input_size"] == 512
    # The event engine filters every class again with events.min_confidence;
    # it must not exceed the lowest per-class detector threshold.
    assert bundled["events"]["min_confidence"] <= min(
        thresholds["minimap_yolox_confidence_by_class"].values())
    metadata = json.loads(
        (ASSETS / "minimap-yolox-nano-dual-512.metadata.json").read_text(encoding="utf-8"))
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
