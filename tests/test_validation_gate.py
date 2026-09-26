from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from mapassist.validation_gate import evaluate_gate


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _ref(path: Path) -> dict:
    return {"path": path.name, "sha256": _sha(path)}


def _write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8")


def _evidence_bundle(tmp_path: Path) -> tuple[Path, dict, dict]:
    video = tmp_path / "holdout.mp4"
    profile = tmp_path / "profile.json"
    library = tmp_path / "libmapassist.so"
    recording = tmp_path / "external-recording.mp4"
    predictions = tmp_path / "predictions.jsonl"
    labels = tmp_path / "labels.json"
    latency = tmp_path / "latency.csv"
    metadata_file = tmp_path / "predictions.jsonl.meta.json"
    evidence_file = tmp_path / "final-evidence.json"

    video.write_bytes(b"frozen holdout video")
    profile.write_text('{"schema_version":1,"templates":{}}\n', encoding="utf-8")
    library.write_bytes(b"frozen native engine")
    recording.write_bytes(b"physical phone screen and audible cue recording")
    cues = [
        {"kind": "main_enemy", "direction": "left", "emitted_at_ms": 1050},
        {"kind": "minimap_enemy", "direction": "right", "emitted_at_ms": 2050},
        {"kind": "danger_ping", "direction": None, "emitted_at_ms": 3050},
    ]
    predictions.write_text(json.dumps({"timestamp_ms": 3050, "cues": cues}) + "\n",
                           encoding="utf-8")
    _write_json(labels, {
        "schema_version": 1,
        "video_id": "holdout-match-06",
        "events": [
            {"kind": "main_enemy", "start_ms": 1000, "end_ms": 1100,
             "direction": "left"},
            {"kind": "minimap_enemy", "start_ms": 2000, "end_ms": 2100,
             "direction": "right"},
            {"kind": "danger_ping", "start_ms": 3000, "end_ms": 3100,
             "direction": None},
        ],
    })
    latency.write_text(
        "kind,evidence_ms,audio_ms\n"
        "main_enemy,1000,1100\n"
        "minimap_enemy,2000,2120\n"
        "danger_ping,3000,3130\n",
        encoding="utf-8",
    )
    metadata = {
        "schema_version": 1,
        "video": _ref(video),
        "profile": _ref(profile),
        "profile_assets": [],
        "native_library": _ref(library),
        "predictions": _ref(predictions),
        "stats": {"fps": 12},
    }
    _write_json(metadata_file, metadata)
    evidence = {
        "schema_version": 1,
        "enabled_kinds": ["main_enemy", "minimap_enemy", "danger_ping"],
        "directional_kinds": ["main_enemy", "minimap_enemy"],
        "holdout": {
            "id": "holdout-match-06",
            "split": "test",
            "profile_frozen_before_review": True,
            "used_for_tuning": False,
            "video": _ref(video),
            "profile": _ref(profile),
            "labels": _ref(labels),
            "predictions": _ref(predictions),
            "prediction_metadata": _ref(metadata_file),
        },
        "device_session": {
            "physical_device": True,
            "model": "Test Phone",
            "android_api": 34,
            "duration_minutes": 16,
            "full_screen_capture_ok": True,
            "landscape_rotation_ok": True,
            "actual_audio_verified": True,
            "game_audio_mix_ok": True,
            "authorized_test_scene": True,
            "fps_notes": "Stable during the 16 minute practice session.",
            "thermal_notes": "Warm without thermal warning.",
            "hero_feedback": "Directional minimap cues were understandable.",
            "external_recording": _ref(recording),
            "latency_csv": _ref(latency),
        },
    }
    _write_json(evidence_file, evidence)
    return evidence_file, evidence, metadata


def test_validation_gate_accepts_complete_holdout_and_phone_evidence(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr("mapassist.validation_gate._probe_duration_seconds", lambda _: 960.0)
    evidence_file, _, _ = _evidence_bundle(tmp_path)
    report = evaluate_gate(evidence_file)
    assert report["passed"] is True
    assert report["failures"] == []
    assert report["event_metrics"]["overall"]["precision"] == 1.0
    assert report["latency_metrics"]["overall"]["p95_ms"] == 130.0


def test_validation_gate_lists_semantic_failures(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr("mapassist.validation_gate._probe_duration_seconds", lambda _: 800.0)
    evidence_file, evidence, metadata = _evidence_bundle(tmp_path)
    predictions = tmp_path / "predictions.jsonl"
    with predictions.open("a", encoding="utf-8") as stream:
        stream.write(json.dumps({"timestamp_ms": 9000, "cues": [{
            "kind": "minimap_enemy", "direction": "left", "emitted_at_ms": 9000,
        }]}) + "\n")
    metadata["predictions"] = _ref(predictions)
    metadata_file = tmp_path / "predictions.jsonl.meta.json"
    _write_json(metadata_file, metadata)
    evidence["holdout"]["split"] = "train"
    evidence["holdout"]["used_for_tuning"] = True
    evidence["holdout"]["predictions"] = _ref(predictions)
    evidence["holdout"]["prediction_metadata"] = _ref(metadata_file)
    evidence["device_session"]["duration_minutes"] = 14
    evidence["device_session"]["actual_audio_verified"] = False
    _write_json(evidence_file, evidence)

    report = evaluate_gate(evidence_file)
    assert report["passed"] is False
    assert {
        "holdout.test_split",
        "holdout.not_used_for_tuning",
        "events.minimap_enemy.precision",
        "device.duration",
        "device.actual_audio_verified",
        "device.external_recording_duration",
    }.issubset(report["failures"])


def test_validation_gate_rejects_changed_evidence_file(tmp_path: Path) -> None:
    evidence_file, evidence, _ = _evidence_bundle(tmp_path)
    evidence["holdout"]["video"]["sha256"] = "0" * 64
    _write_json(evidence_file, evidence)
    with pytest.raises(ValueError, match="SHA-256 mismatch"):
        evaluate_gate(evidence_file)
