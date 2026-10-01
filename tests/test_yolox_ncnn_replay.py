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
    FrozenReplay, _direction_for, _direction_reference, _pixel_roi,
    _validate_replay_profile, run,
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


def test_explicit_widget_reference_overrides_expanded_crop_center() -> None:
    width, height = 2376, 1080
    safe_crop = (79, 0, 481, 371)
    widget = [106 / width, 0, 348 / width, 344 / height]
    reference = _direction_reference({"rois": {"minimap_direction": widget}},
                                     width, height, safe_crop)

    assert safe_crop == (79, 0, 481, 371)
    assert reference == (106, 0, 454, 344)
    # The point is below the crop-centre dead zone but inside the widget-centre
    # dead zone, so expanding the crop must not manufacture an "up" event.
    assert _direction_for(280, 157, safe_crop) == 3
    assert _direction_for(280, 157, reference) == 0


def test_replay_requires_android_yolox_profile() -> None:
    base = {"detectors": {"minimap_yolox": True},
            "thresholds": {"minimap_yolox_input_size": 320},
            "models": {"minimap_yolox_bin_sha256": "a" * 64}}
    _validate_replay_profile(base)
    with pytest.raises(ValueError, match="minimap_yolox=true"):
        _validate_replay_profile({"detectors": {}, "thresholds": {}})
    # 512 matches the dual-class near-zone candidate; sizes outside the
    # Android tensor contract are still rejected.
    _validate_replay_profile({"detectors": {"minimap_yolox": True},
                              "thresholds": {"minimap_yolox_input_size": 512},
                              "models": {"minimap_yolox_bin_sha256": "a" * 64}})
    for invalid in (330, 256, 1056):
        with pytest.raises(ValueError, match="320"):
            _validate_replay_profile({"detectors": {"minimap_yolox": True},
                                      "thresholds": {"minimap_yolox_input_size": invalid},
                                      "models": {"minimap_yolox_bin_sha256": "a" * 64}})


def test_replay_profile_requires_and_checks_ncnn_bin_sha256(tmp_path: Path) -> None:
    model_bin = tmp_path / "model.bin"
    model_bin.write_bytes(b"candidate ncnn weights")
    actual_sha256 = hashlib.sha256(model_bin.read_bytes()).hexdigest()
    profile = {
        "detectors": {"minimap_yolox": True},
        "thresholds": {"minimap_yolox_input_size": 320},
        "models": {"minimap_yolox_bin_sha256": actual_sha256},
    }

    _validate_replay_profile(profile, model_bin)

    profile["models"]["minimap_yolox_bin_sha256"] = "0" * 64
    with pytest.raises(ValueError, match="does not match.*supplied ncnn bin"):
        _validate_replay_profile(profile, model_bin)

    del profile["models"]["minimap_yolox_bin_sha256"]
    with pytest.raises(ValueError, match="64 lowercase hex"):
        _validate_replay_profile(profile, model_bin)

    del profile["models"]
    with pytest.raises(ValueError, match="needs models.minimap_yolox_bin_sha256"):
        _validate_replay_profile(profile, model_bin)


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
    model_bin = tmp_path / "model.bin"
    model_bin.write_bytes(b"test weights")
    profile_json = {
        "detectors": {"minimap_yolox": True},
        "thresholds": {"minimap_yolox_input_size": 320},
        "models": {"minimap_yolox_bin_sha256": hashlib.sha256(
            model_bin.read_bytes()).hexdigest()},
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
                     model_bin, tmp_path / "native", 2,
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
    assert all({"frame_index", "timestamp_ms", "observations", "detections", "cues", "layout"}
               <= set(record) for record in records)
    assert all(record["layout"]["state"] == "fixed" for record in records)
    predictions = read_predictions(output)
    labels = read_labels(ROOT / "build/synthetic/labels.json")
    report = evaluate(predictions, labels)
    assert report["overall"]["tp"] == 3
    assert report["overall"]["fp"] == 0
    assert report["overall"]["fn"] == 0
    assert result["fps"] == 12
    assert result["replay_fps"] > 0
    assert result["layout_states"] == {
        "fixed": 84, "searching": 0, "locked": 0, "held": 0,
    }
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
