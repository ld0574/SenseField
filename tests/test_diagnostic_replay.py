from __future__ import annotations

import json
import subprocess
import zipfile
from pathlib import Path

import pytest

from mapassist.diagnostic_replay import build_report, read_diagnostic_zip


ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture(scope="session")
def replay_library(tmp_path_factory: pytest.TempPathFactory) -> Path:
    build = tmp_path_factory.mktemp("diagnostic-replay-native")
    subprocess.run(["cmake", "-S", str(ROOT / "native"), "-B", str(build)],
                   check=True, capture_output=True)
    subprocess.run(["cmake", "--build", str(build)],
                   check=True, capture_output=True)
    return next(build.glob("libmapassist.*"))


def _profile_event() -> dict:
    return {
        "type": "profile",
        "session_id": "session-test",
        "at_ms": 1,
        "data": {
            "name": "test_profile",
            "version": "1.0",
            "rois_xywh": [0.0] * 12 + [0.05, 0.05, 0.40, 0.40],
            "confidence": 0.50,
            "class_thresholds": [0.50, 0.60],
            "relation_floats": [0.20, 0.25, 7.5, 0.2, 0.1],
            "relation_ints": [2, 3000, 2000, 10000, 500],
        },
    }


def _observation(kind: int, x: int, y: int, observed_at_ms: int) -> dict:
    return {"kind": kind, "direction": 0,
            "bbox_ppm": [x, y, 20_000, 20_000],
            "confidence_milli": 900,
            "observed_at_ms": observed_at_ms}


def _frame(index: int, now_ms: int, age_ms: int,
           observations: list[dict] | None = None) -> dict:
    observed_at_ms = now_ms - age_ms
    rows = observations if observations is not None else [
        _observation(6, 100_000, 100_000, observed_at_ms),
        _observation(2, 140_000, 140_000, observed_at_ms),
    ]
    return {
        "type": "frame",
        "session_id": "session-test",
        "at_ms": now_ms + 10,
        "data": {"frame_index": index, "observed_at_ms": observed_at_ms,
                 "completed_at_ms": now_ms + 10, "engine_at_ms": now_ms,
                 "native_micros": 100_000 * index,
                 "observation_count": len(rows), "raw_observations": rows,
                 "frame_width": 1000, "frame_height": 1000,
                 "locator_state": -1},
    }


def _write_archive(path: Path, events: list[dict]) -> Path:
    frame_count = sum(event.get("type") == "frame" for event in events)
    metadata = {"schema": "sensefield.diagnostics", "schema_version": 1,
                "session_id": "session-test"}
    summary = {"session_id": "session-test", "frame_count": frame_count}
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("metadata.json", json.dumps(metadata))
        archive.writestr("summary.json", json.dumps(summary))
        archive.writestr("events.jsonl", "".join(json.dumps(item) + "\n" for item in events))
        archive.writestr("images/ignored.jpg", b"this file is never extracted")
    return path


def _provided_profile(path: Path, min_confidence: float = 0.5) -> Path:
    profile = {
        "name": "test_profile",
        "profile_version": "1.0",
        "events": {"min_confidence": min_confidence, "max_observation_age_ms": 250,
                   "min_global_gap_ms": 1000, "minimap_min_gap_ms": 15000,
                   "min_hits_in_three_frames": 2,
                   "reset_after_missing_frames": 3},
    }
    path.write_text(json.dumps(profile), encoding="utf-8")
    return path


def test_statistics_use_actual_frame_and_observation_timestamps(tmp_path: Path) -> None:
    events = [_profile_event(),
              _frame(1, 1000, 260, [_observation(2, 100_000, 100_000, 750)]),
              _frame(2, 2000, 500, [_observation(2, 100_000, 100_000, 1500)]),
              _frame(3, 3000, 501, [_observation(2, 100_000, 100_000, 2600)])]
    report = build_report(read_diagnostic_zip(_write_archive(tmp_path / "stats.zip", events)))

    assert report["observed_timing"]["frames"] == 3
    assert report["observed_timing"]["frame_age_ms"]["p50"] == 500
    assert report["observed_timing"]["logged_native_latency"]["p95"] == 300_000
    comparison = report["observed_timing"]["age_budget_comparison"]
    assert comparison["250"]["frames"]["over_budget_count"] == 3
    assert comparison["500"]["frames"]["over_budget_count"] == 1
    assert comparison["500"]["observations"]["over_budget_count"] == 0
    assert report["replay"]["status"] == "statistics_only"
    assert any("quantized" in item for item in report["limitations"])


def test_native_replay_compares_250_500_resets_and_preserves_age_boundary(
    tmp_path: Path, replay_library: Path,
) -> None:
    events = [_profile_event(),
              _frame(1, 1000, 501),
              _frame(2, 1100, 500),
              _frame(3, 1200, 500),
              _frame(4, 1300, 500),
              {"type": "audit", "session_id": "session-test", "at_ms": 1350,
               "data": {"message": "CaptureResized width=1000 height=1000 rotation=1"}},
              _frame(5, 1400, 500),
              _frame(6, 1500, 500),
              _frame(7, 1600, 500)]
    archive_path = _write_archive(tmp_path / "replay.zip", events)
    profile_path = _provided_profile(tmp_path / "profile.json")

    report = build_report(read_diagnostic_zip(archive_path), profile_path, replay_library)
    runs = {run["budget_ms"]: run for run in report["replay"]["runs"]}

    assert report["replay"]["status"] == "completed"
    assert report["replay"]["timestamps_rewritten"] is False
    assert runs[250]["eligible_observation_count"] == 0
    assert runs[250]["near_zone"]["event_count"] == 0
    assert runs[500]["eligible_observation_count"] == 12
    assert runs[500]["near_zone"]["events_by_kind"]["NEAR_ENTER"] == 2
    assert runs[500]["near_zone"]["events"][0]["frame_index"] == 4
    assert runs[500]["state_resets_applied"] == {"capture_resized": 1}
    assert report["replay"]["profile_identity"]["matches"] is True


def test_invalid_observation_count_is_rejected(tmp_path: Path) -> None:
    frame = _frame(1, 1000, 250)
    frame["data"]["observation_count"] += 1
    archive_path = _write_archive(tmp_path / "bad.zip", [_profile_event(), frame])

    with pytest.raises(ValueError, match="observation_count does not match"):
        read_diagnostic_zip(archive_path)


def test_full_profile_event_threshold_still_filters_logged_detections(
    tmp_path: Path, replay_library: Path,
) -> None:
    events = [_profile_event(), _frame(1, 1000, 500),
              _frame(2, 1100, 500), _frame(3, 1200, 500)]
    archive_path = _write_archive(tmp_path / "high-threshold.zip", events)
    profile_path = _provided_profile(tmp_path / "high-threshold-profile.json",
                                     min_confidence=0.95)

    report = build_report(read_diagnostic_zip(archive_path), profile_path, replay_library)
    run = next(item for item in report["replay"]["runs"] if item["budget_ms"] == 500)

    assert run["engine_config_sources"]["min_confidence"] == \
        "provided profile events.min_confidence"
    assert run["eligible_observation_count"] == 0
    assert run["native_cues"]["count"] == 0
    assert run["tracked_entity_samples"] == {}
    assert run["near_zone"]["event_count"] == 0
