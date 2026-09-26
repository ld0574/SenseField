from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import pytest

from mapassist.evaluate import evaluate, read_labels, read_predictions
from mapassist import native
from mapassist.native import Rect


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "training"))
from replay_yolox_ncnn import (  # noqa: E402
    FrozenReplay, _direction_for, _pixel_roi, _validate_replay_profile, run,
)


def _replay_artifacts_available() -> bool:
    required = (
        "build/synthetic/fixture-android.mp4",
        "build/synthetic/profile.json",
        "android/app/src/main/assets/minimap-yolox-nano-320.param",
        "android/app/src/main/assets/minimap-yolox-nano-320.bin",
        "build/native/libmapassist.dylib",
    )
    profile = ROOT / "build/synthetic/profile.json"
    if not all((ROOT / relative).is_file() for relative in required):
        return False
    try:
        data = json.loads(profile.read_text(encoding="utf-8"))
        return data.get("detectors", {}).get("minimap_yolox") is True
    except (OSError, json.JSONDecodeError):
        return False


def test_android_roi_pixel_rounding_and_direction() -> None:
    area = _pixel_roi(Rect(0.1, 0.2, 0.3, 0.4), 100, 100)
    assert area == (10, 20, 41, 61)
    assert _direction_for(10, 50, area) == 1
    assert _direction_for(50, 50, area) == 2
    assert _direction_for(25.5, 40.5, area) == 0


def test_replay_requires_android_yolox_profile() -> None:
    base = {"detectors": {"minimap_yolox": True},
            "thresholds": {"minimap_yolox_input_size": 320}}
    _validate_replay_profile(base)
    with pytest.raises(ValueError, match="minimap_yolox=true"):
        _validate_replay_profile({"detectors": {}, "thresholds": {}})
    with pytest.raises(ValueError, match="320"):
        _validate_replay_profile({"detectors": {"minimap_yolox": True},
                                  "thresholds": {"minimap_yolox_input_size": 640}})


def test_frozen_replay_releases_engine_when_setup_fails(monkeypatch: pytest.MonkeyPatch,
                                                        tmp_path: Path) -> None:
    class FakeLib:
        def __init__(self) -> None:
            self.destroyed = []

        def ma_engine_create(self, _config):
            return 123

        def ma_engine_destroy(self, handle):
            self.destroyed.append(handle)

    fake = FakeLib()
    profile_json = {
        "detectors": {"minimap_yolox": True},
        "thresholds": {"minimap_yolox_input_size": 320},
        "events": {"min_confidence": 0.1, "max_observation_age_ms": 100,
                    "min_global_gap_ms": 100, "minimap_min_gap_ms": 100,
                    "min_hits_in_three_frames": 1, "reset_after_missing_frames": 1},
        "templates": {},
    }
    monkeypatch.setattr(native, "load_library", lambda _path: fake)
    monkeypatch.setattr(native, "read_profile", lambda _path: (Rect(), profile_json))
    monkeypatch.setattr(native, "read_template", lambda _path: (_ for _ in ()).throw(RuntimeError("bad template")))
    with pytest.raises(RuntimeError, match="bad template"):
        FrozenReplay(tmp_path / "profile.json", tmp_path / "model.param",
                     tmp_path / "model.bin", tmp_path / "native", 2,
                     "in0", "out0", object())
    assert fake.destroyed == [123]


@pytest.mark.skipif(
    not _replay_artifacts_available(),
    reason="synthetic YOLOX ncnn replay artifacts/profile are not built",
)
def test_frozen_replay_is_event_evaluation_compatible(tmp_path: Path) -> None:
    output = tmp_path / "predictions.jsonl"
    metadata_path = tmp_path / "predictions.meta.json"
    result = run(
        ROOT / "build/synthetic/fixture-android.mp4",
        ROOT / "build/synthetic/profile.json",
        ROOT / "android/app/src/main/assets/minimap-yolox-nano-320.param",
        ROOT / "android/app/src/main/assets/minimap-yolox-nano-320.bin",
        output,
        fps=12,
        library=ROOT / "build/native/libmapassist.dylib",
        metadata=metadata_path,
    )

    records = [json.loads(line) for line in output.read_text().splitlines()]
    assert len(records) == 84
    assert all({"frame_index", "timestamp_ms", "observations", "detections", "cues"}
               <= set(record) for record in records)
    predictions = read_predictions(output)
    labels = read_labels(ROOT / "build/synthetic/labels.json")
    report = evaluate(predictions, labels)
    assert report["overall"]["tp"] == 3
    assert report["overall"]["fp"] == 0
    assert report["overall"]["fn"] == 0
    assert result["fps"] == 12
    assert result["replay_fps"] > 0
    timestamps = [record["timestamp_ms"] for record in records]
    assert timestamps == sorted(timestamps)
    assert len(set(timestamps)) == len(timestamps)

    metadata = json.loads(metadata_path.read_text())
    assert metadata["schema_version"] == 1
    assert metadata["replay"] == "frozen_yolox_ncnn_native_event_replay"
    assert metadata["stats"]["fps"] == 12
    assert metadata["sampling"]["method"] == "ffmpeg CFR fps filter"
    assert metadata["timeline"]["kind"] == "synthetic_media_time_ms"
    assert metadata["event_now_policy"].startswith("zero_queue_delay")
    assert metadata["model"]["param"]["sha256"] == hashlib.sha256(
        (ROOT / "android/app/src/main/assets/minimap-yolox-nano-320.param").read_bytes()
    ).hexdigest()
    assert metadata["native_library"]["sha256"] == hashlib.sha256(
        (ROOT / "build/native/libmapassist.dylib").read_bytes()
    ).hexdigest()
    assert metadata["predictions"]["sha256"] == hashlib.sha256(
        output.read_bytes()
    ).hexdigest()
