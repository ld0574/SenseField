from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import zipfile
from pathlib import Path

import pytest

from mapassist.validation_gate import _probe_external_recording, evaluate_gate


def _sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _ref(path: Path) -> dict:
    return {"path": path.name, "sha256": _sha(path)}


def _write_json(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n",
                    encoding="utf-8")


def _recording_probe(duration: float) -> dict:
    timeline = {
        "packet_count": 1000,
        "start_seconds": 0.0,
        "end_seconds": duration,
        "span_seconds": duration,
        "max_packet_gap_seconds": 0.04,
    }
    return {
        "format_duration_seconds": duration,
        "video": dict(timeline),
        "audio": dict(timeline),
        "start_offset_seconds": 0.0,
        "end_offset_seconds": 0.0,
    }


def _evidence_bundle(tmp_path: Path) -> tuple[Path, dict, dict]:
    video = tmp_path / "holdout.mp4"
    profile = tmp_path / "profile.json"
    model_param = tmp_path / "model.param"
    model_bin = tmp_path / "model.bin"
    library = tmp_path / "libmapassist.so"
    apk = tmp_path / "mapassist.apk"
    recording = tmp_path / "external-recording.mp4"
    predictions = tmp_path / "predictions.jsonl"
    labels = tmp_path / "labels.json"
    latency = tmp_path / "latency.csv"
    device_log = tmp_path / "device-logcat.txt"
    metadata_file = tmp_path / "predictions.jsonl.meta.json"
    evidence_file = tmp_path / "final-evidence.json"

    video.write_bytes(b"frozen holdout video")
    _write_json(profile, {
        "schema_version": 1,
        "detectors": {"minimap_yolox": True},
        "thresholds": {
            "minimap_yolox_input_size": 320,
            "minimap_yolox_confidence": 0.29,
            "minimap_yolox_nms": 0.5,
        },
        "events": {},
        "templates": {},
    })
    model_param.write_bytes(b"frozen ncnn param")
    model_bin.write_bytes(b"frozen ncnn weights")
    library.write_bytes(b"frozen native engine")
    recording.write_bytes(b"physical phone screen and audible cue recording")
    cues = [
        {"kind": "main_enemy", "direction": "left", "emitted_at_ms": 1050},
        {"kind": "minimap_enemy", "direction": "right", "emitted_at_ms": 2050},
        {"kind": "danger_ping", "direction": None, "emitted_at_ms": 3050},
    ]
    prediction_rows = []
    cues_by_frame = {12: [cues[0]], 24: [cues[1]], 36: [cues[2]]}
    for frame_index in range(37):
        timestamp_ms = round(frame_index * 1000 / 12)
        frame_cues = []
        for cue in cues_by_frame.get(frame_index, []):
            frame_cues.append({**cue, "emitted_at_ms": timestamp_ms})
        prediction_rows.append(json.dumps({
            "frame_index": frame_index,
            "timestamp_ms": timestamp_ms,
            "observations": [],
            "detections": [],
            "cues": frame_cues,
        }))
    predictions.write_text("\n".join(prediction_rows) + "\n", encoding="utf-8")
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
    latency_rows = ["event_id,cue_id,kind,evidence_ms,audio_ms,source_note"]
    kinds = ("main_enemy", "minimap_enemy", "danger_ping")
    kind_codes = {"main_enemy": 1, "minimap_enemy": 2, "danger_ping": 3}
    delays = (100, 110, 120, 130)
    device_log_rows = [
        "I/MapAssistCapture: SessionStart sessionId=session-123 startId=1 "
        "startedElapsedRealtimeMs=1000"
    ]
    for index in range(20):
        evidence_ms = 1000 + index * 1000
        kind = kinds[index % 3]
        latency_rows.append(
            f"ext-{index + 1:03d},session-123:{index + 1},{kind},"
            f"{evidence_ms},{evidence_ms + delays[index % 4]},frame checked"
        )
        device_log_rows.append(
            "I/MapAssistCapture: CueEvent sessionId=session-123 "
            f"cueId=session-123:{index + 1} kind={kind_codes[kind]} direction=1 "
            f"observedAtMs={2000 + index * 1000} frameAgeMs=20 nativeMicros=12000 "
            "stale=false audioQueued=true"
        )
    device_log_rows.append(
        "I/MapAssistCapture: SessionSummary sessionId=session-123 durationMs=960000 "
        "processedFrames=11521 landscapeProcessedFrames=11521 "
        "firstProcessedElapsedRealtimeMs=1000 lastProcessedElapsedRealtimeMs=961000 "
        "maxProcessedGapMs=84 detected=20 queued=20 stale=0 audioFailures=0 "
        "reason=stopped"
    )
    latency.write_text("\n".join(latency_rows) + "\n", encoding="utf-8")
    device_log.write_text("\n".join(device_log_rows) + "\n", encoding="utf-8")
    metadata = {
        "schema_version": 1,
        "replay": "frozen_yolox_ncnn_native_event_replay",
        "video": _ref(video),
        "profile": _ref(profile),
        "profile_assets": [],
        "model": {"param": _ref(model_param), "bin": _ref(model_bin)},
        "native_library": _ref(library),
        "predictions": _ref(predictions),
        "stats": {"fps": 12, "frames": 37, "cues": 3},
        "sampling": {"method": "ffmpeg CFR fps filter", "fps": 12,
                     "frame_count": 37},
        "timeline": {
            "kind": "synthetic_media_time_ms",
            "timestamp_formula": "round(frame_index * 1000 / fps)",
            "source_pts_preserved": False,
        },
        "event_now_policy": (
            "zero_queue_delay; ma_engine_step now_ms equals synthetic frame timestamp"
        ),
        "runtime": {
            "ncnn": "1.0.test-ncnn",
            "preprocessing": (
                "RGBA crop -> ncnn PIXEL_RGBA2BGR resize -> right/bottom 114 border"
            ),
            "postprocess": {
                "confidence": 0.29,
                "nms_iou": 0.5,
                "strides": [8, 16, 32],
            },
        },
    }
    _write_json(metadata_file, metadata)
    apk_model_metadata = json.dumps({
        "schema_version": 1,
        "runtime": {
            "version": "test-ncnn",
            "param_sha256": _sha(model_param),
            "bin_sha256": _sha(model_bin),
        },
    }).encode()
    with zipfile.ZipFile(apk, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("assets/profile.json", profile.read_bytes())
        archive.writestr("assets/minimap-yolox-nano-320.param", model_param.read_bytes())
        archive.writestr("assets/minimap-yolox-nano-320.bin", model_bin.read_bytes())
        archive.writestr("assets/minimap-yolox-nano-320.metadata.json", apk_model_metadata)
    evidence = {
        "schema_version": 2,
        "enabled_kinds": ["main_enemy", "minimap_enemy", "danger_ping"],
        "directional_kinds": ["main_enemy", "minimap_enemy"],
        "holdout": {
            "id": "holdout-match-06",
            "split": "test",
            "profile_frozen_before_review": True,
            "predictions_frozen_before_label_review": True,
            "used_for_tuning": False,
            "real_match": True,
            "continuous_recording": True,
            "video": _ref(video),
            "profile": _ref(profile),
            "model_param": _ref(model_param),
            "model_bin": _ref(model_bin),
            "native_library": _ref(library),
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
            "external_recording_unedited": True,
            "latency_annotation_complete": True,
            "apk_installed_from_evidence": True,
            "bundled_profile_used": True,
            "sync_method": "External recording timestamps start at its first video frame.",
            "audio_capture_method": "External camera microphone captured phone speaker audio.",
            "fps_notes": "Stable during the 16 minute practice session.",
            "thermal_notes": "Warm without thermal warning.",
            "hero_feedback": "Directional minimap cues were understandable.",
            "external_recording": _ref(recording),
            "apk": _ref(apk),
            "device_log": _ref(device_log),
            "session_id": "session-123",
            "latency_csv": _ref(latency),
        },
    }
    _write_json(evidence_file, evidence)
    return evidence_file, evidence, metadata


def test_validation_gate_accepts_complete_holdout_and_phone_evidence(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        "mapassist.validation_gate._probe_external_recording",
        lambda _: _recording_probe(960.0),
    )
    evidence_file, _, _ = _evidence_bundle(tmp_path)
    report = evaluate_gate(evidence_file)
    assert report["passed"] is True
    assert report["failures"] == []
    assert report["event_metrics"]["overall"]["precision"] == 1.0
    assert report["latency_metrics"]["overall"]["p95_ms"] == 130.0
    assert report["android_session"]["average_landscape_fps"] == 12.0
    assert report["android_session"]["summary"]["audioFailures"] == 0


def test_validation_gate_lists_semantic_failures(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        "mapassist.validation_gate._probe_external_recording",
        lambda _: _recording_probe(800.0),
    )
    evidence_file, evidence, metadata = _evidence_bundle(tmp_path)
    predictions = tmp_path / "predictions.jsonl"
    records = [json.loads(line) for line in predictions.read_text(encoding="utf-8").splitlines()]
    false_timestamp = records[5]["timestamp_ms"]
    records[5]["cues"].append({
        "kind": "minimap_enemy", "direction": "left",
        "emitted_at_ms": false_timestamp,
    })
    predictions.write_text(
        "\n".join(json.dumps(record) for record in records) + "\n",
        encoding="utf-8",
    )
    metadata["predictions"] = _ref(predictions)
    metadata["stats"]["cues"] = 4
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


def test_validation_gate_requires_exact_logged_cue_ids(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        "mapassist.validation_gate._probe_external_recording",
        lambda _: _recording_probe(960.0),
    )
    evidence_file, evidence, _ = _evidence_bundle(tmp_path)
    latency = tmp_path / "latency.csv"
    rows = latency.read_text(encoding="utf-8").splitlines()
    fields = rows[-1].split(",")
    fields[1] = "session-123:999"
    rows[-1] = ",".join(fields)
    latency.write_text("\n".join(rows) + "\n", encoding="utf-8")
    evidence["device_session"]["latency_csv"] = _ref(latency)
    _write_json(evidence_file, evidence)

    report = evaluate_gate(evidence_file)
    assert report["passed"] is False
    cue_check = next(item for item in report["checks"]
                     if item["id"] == "device.latency_cue_ids")
    assert cue_check["actual"] == {
        "missing_from_csv": ["session-123:20"],
        "not_in_non_stale_log": ["session-123:999"],
    }


def test_validation_gate_binds_predictions_to_frozen_model(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(
        "mapassist.validation_gate._probe_external_recording",
        lambda _: _recording_probe(960.0),
    )
    evidence_file, evidence, metadata = _evidence_bundle(tmp_path)
    alternate = tmp_path / "alternate.param"
    alternate.write_bytes(b"different ncnn graph")
    metadata["model"]["param"] = _ref(alternate)
    metadata_file = tmp_path / "predictions.jsonl.meta.json"
    _write_json(metadata_file, metadata)
    evidence["holdout"]["prediction_metadata"] = _ref(metadata_file)
    _write_json(evidence_file, evidence)

    report = evaluate_gate(evidence_file)
    assert report["passed"] is False
    assert "provenance.model_param" in report["failures"]


def test_validation_gate_rejects_predictions_outside_declared_cfr_timeline(
    tmp_path: Path,
) -> None:
    evidence_file, evidence, metadata = _evidence_bundle(tmp_path)
    predictions = tmp_path / "predictions.jsonl"
    records = [json.loads(line) for line in predictions.read_text(encoding="utf-8").splitlines()]
    records[4]["timestamp_ms"] += 1
    predictions.write_text(
        "\n".join(json.dumps(record) for record in records) + "\n",
        encoding="utf-8",
    )
    metadata["predictions"] = _ref(predictions)
    metadata_file = tmp_path / "predictions.jsonl.meta.json"
    _write_json(metadata_file, metadata)
    evidence["holdout"]["predictions"] = _ref(predictions)
    evidence["holdout"]["prediction_metadata"] = _ref(metadata_file)
    _write_json(evidence_file, evidence)

    with pytest.raises(ValueError, match="CFR timeline"):
        evaluate_gate(evidence_file)


@pytest.mark.skipif(not shutil.which("ffmpeg") or not shutil.which("ffprobe"),
                    reason="ffmpeg and ffprobe are required")
def test_external_recording_probe_requires_continuous_audio_and_video(
    tmp_path: Path,
) -> None:
    recording = tmp_path / "external.mp4"
    subprocess.run([
        "ffmpeg", "-v", "error", "-f", "lavfi", "-i",
        "color=c=black:s=160x90:r=30:d=2", "-f", "lavfi", "-i",
        "sine=frequency=600:sample_rate=48000:duration=2", "-shortest",
        "-c:v", "mpeg4", "-c:a", "aac", "-y", str(recording),
    ], check=True)
    probe = _probe_external_recording(recording)
    assert probe["video"]["packet_count"] >= 50
    assert probe["audio"]["packet_count"] >= 80
    assert probe["video"]["max_packet_gap_seconds"] < 0.1
    assert probe["audio"]["max_packet_gap_seconds"] < 0.1

    video_only = tmp_path / "video-only.mp4"
    subprocess.run([
        "ffmpeg", "-v", "error", "-f", "lavfi", "-i",
        "color=c=black:s=160x90:r=30:d=1", "-c:v", "mpeg4", "-an", "-y",
        str(video_only),
    ], check=True)
    with pytest.raises(ValueError, match="both video and audio"):
        _probe_external_recording(video_only)
