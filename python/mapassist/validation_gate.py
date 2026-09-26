"""Verify final holdout and physical-device evidence against release targets."""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import re
import subprocess
import sys
from pathlib import Path

from .evaluate import KINDS, evaluate, read_labels, read_predictions
from .measure_latency import measure


TARGETS = {
    "precision": 0.90,
    "recall": 0.80,
    "direction_accuracy": 0.90,
    "physical_p95_ms": 250.0,
    "session_minutes": 15.0,
    "replay_fps": 12,
}
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _json(path: Path, label: str) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{label} must contain a JSON object")
    return value


def _reference(base: Path, value: object, label: str) -> tuple[Path, str]:
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be a file reference")
    raw_path = value.get("path")
    expected = value.get("sha256")
    if not isinstance(raw_path, str) or not raw_path:
        raise ValueError(f"{label}.path is required")
    if not isinstance(expected, str) or not SHA256.fullmatch(expected.lower()):
        raise ValueError(f"{label}.sha256 must be 64 hexadecimal characters")
    path = (base / raw_path).resolve()
    if not path.is_file():
        raise ValueError(f"{label} does not exist: {path}")
    actual = _sha256(path)
    if actual != expected.lower():
        raise ValueError(f"{label} SHA-256 mismatch: expected {expected.lower()}, got {actual}")
    return path, actual


def _nonempty(value: object) -> bool:
    return isinstance(value, str) and bool(value.strip())


def _number(value: object) -> float | None:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        number = float(value)
        if math.isfinite(number):
            return number
    return None


def _probe_duration_seconds(path: Path) -> float:
    try:
        process = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "json", str(path)],
            check=True, text=True, capture_output=True,
        )
        duration = float(json.loads(process.stdout)["format"]["duration"])
    except (FileNotFoundError, subprocess.CalledProcessError, KeyError, TypeError,
            ValueError, json.JSONDecodeError) as error:
        raise ValueError(f"Could not read external recording duration: {path}") from error
    if not math.isfinite(duration) or duration <= 0:
        raise ValueError(f"External recording has invalid duration: {path}")
    return duration


def evaluate_gate(evidence_file: Path) -> dict:
    evidence_file = evidence_file.resolve()
    data = _json(evidence_file, "evidence manifest")
    if data.get("schema_version") != 1:
        raise ValueError("Expected evidence schema_version 1")
    base = evidence_file.parent

    enabled = data.get("enabled_kinds")
    if (not isinstance(enabled, list) or not enabled or len(set(enabled)) != len(enabled) or
            any(kind not in KINDS for kind in enabled)):
        raise ValueError(f"enabled_kinds must be a unique nonempty subset of {KINDS}")
    directional = data.get(
        "directional_kinds", [kind for kind in enabled if kind != "danger_ping"]
    )
    if (not isinstance(directional, list) or len(set(directional)) != len(directional) or
            any(kind not in enabled for kind in directional)):
        raise ValueError("directional_kinds must be a unique subset of enabled_kinds")

    holdout = data.get("holdout")
    device = data.get("device_session")
    if not isinstance(holdout, dict) or not isinstance(device, dict):
        raise ValueError("Evidence needs holdout and device_session objects")

    video_path, video_sha = _reference(base, holdout.get("video"), "holdout.video")
    profile_path, profile_sha = _reference(base, holdout.get("profile"), "holdout.profile")
    labels_path, labels_sha = _reference(base, holdout.get("labels"), "holdout.labels")
    predictions_path, predictions_sha = _reference(
        base, holdout.get("predictions"), "holdout.predictions"
    )
    metadata_path, _ = _reference(
        base, holdout.get("prediction_metadata"), "holdout.prediction_metadata"
    )
    metadata = _json(metadata_path, "prediction metadata")
    if metadata.get("schema_version") != 1:
        raise ValueError("Expected prediction metadata schema_version 1")
    metadata_base = metadata_path.parent
    metadata_records = {}
    for key in ("video", "profile", "predictions", "native_library"):
        path, digest = _reference(metadata_base, metadata.get(key), f"prediction metadata.{key}")
        metadata_records[key] = (path, digest)
    assets = metadata.get("profile_assets")
    if not isinstance(assets, list):
        raise ValueError("prediction metadata.profile_assets must be a list")
    for index, asset in enumerate(assets):
        if not isinstance(asset, dict) or not _nonempty(asset.get("role")):
            raise ValueError(f"prediction metadata.profile_assets[{index}] needs a role")
        _reference(metadata_base, asset, f"prediction metadata.profile_assets[{index}]")

    labels_document = _json(labels_path, "holdout labels")
    predictions = [cue for cue in read_predictions(predictions_path)
                   if cue.get("kind") in enabled]
    labels = [event for event in read_labels(labels_path) if event.get("kind") in enabled]
    event_metrics = evaluate(predictions, labels)

    recording_path, _ = _reference(
        base, device.get("external_recording"), "device_session.external_recording"
    )
    recording_duration_seconds = _probe_duration_seconds(recording_path)
    latency_path, _ = _reference(
        base, device.get("latency_csv"), "device_session.latency_csv"
    )
    with latency_path.open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames is None or not {"kind", "evidence_ms", "audio_ms"}.issubset(
                reader.fieldnames):
            raise ValueError("Latency CSV needs kind,evidence_ms,audio_ms columns")
        latency_rows = list(reader)
    unexpected_latency_kinds = sorted({(row.get("kind") or "").strip()
                                       for row in latency_rows} - set(enabled))
    if unexpected_latency_kinds:
        raise ValueError(
            f"Latency CSV contains disabled or unknown kinds: {unexpected_latency_kinds}"
        )
    latency_metrics = measure(latency_rows)

    checks = []

    def check(identifier: str, passed: bool, actual: object, requirement: str) -> None:
        checks.append({"id": identifier, "passed": bool(passed),
                       "actual": actual, "requirement": requirement})

    holdout_id = holdout.get("id")
    check("holdout.id", _nonempty(holdout_id), holdout_id, "nonempty match id")
    check("holdout.labels_video_id", labels_document.get("video_id") == holdout_id,
          labels_document.get("video_id"), "must equal holdout.id")
    check("holdout.test_split", holdout.get("split") == "test", holdout.get("split"),
          "split must be test")
    check("holdout.profile_frozen", holdout.get("profile_frozen_before_review") is True,
          holdout.get("profile_frozen_before_review"),
          "profile frozen before reviewing holdout")
    check("holdout.not_used_for_tuning", holdout.get("used_for_tuning") is False,
          holdout.get("used_for_tuning"), "holdout was not used for tuning")
    check("provenance.video", metadata_records["video"][1] == video_sha,
          metadata_records["video"][1], f"must equal holdout video SHA-256 {video_sha}")
    check("provenance.profile", metadata_records["profile"][1] == profile_sha,
          metadata_records["profile"][1], f"must equal profile SHA-256 {profile_sha}")
    check("provenance.predictions", metadata_records["predictions"][1] == predictions_sha,
          metadata_records["predictions"][1],
          f"must equal predictions SHA-256 {predictions_sha}")
    replay_fps = (metadata.get("stats") or {}).get("fps") if isinstance(
        metadata.get("stats"), dict) else None
    check("provenance.replay_fps", replay_fps == TARGETS["replay_fps"], replay_fps,
          f"must equal {TARGETS['replay_fps']} FPS")

    for kind in enabled:
        metrics = event_metrics[kind]
        truth_events = metrics["tp"] + metrics["fn"]
        check(f"events.{kind}.support", truth_events >= 1, truth_events,
              "at least one ground-truth event")
        check(f"events.{kind}.precision", metrics["precision"] >= TARGETS["precision"],
              metrics["precision"], f">= {TARGETS['precision']:.2f}")
        check(f"events.{kind}.recall", metrics["recall"] >= TARGETS["recall"],
              metrics["recall"], f">= {TARGETS['recall']:.2f}")
        if kind in directional:
            value = metrics["direction_accuracy"]
            check(f"events.{kind}.direction_accuracy",
                  value is not None and value >= TARGETS["direction_accuracy"], value,
                  f">= {TARGETS['direction_accuracy']:.2f} with directed labels")
    overall_events = event_metrics["overall"]
    for metric in ("precision", "recall", "direction_accuracy"):
        target = TARGETS[metric]
        value = overall_events[metric]
        check(f"events.overall.{metric}", value is not None and value >= target, value,
              f">= {target:.2f}")

    api = device.get("android_api")
    duration = _number(device.get("duration_minutes"))
    check("device.physical", device.get("physical_device") is True,
          device.get("physical_device"), "physical Android device")
    check("device.android_api", api in (33, 34), api, "Android 13/API 33 or 14/API 34")
    check("device.model", _nonempty(device.get("model")), device.get("model"),
          "nonempty physical device model")
    check("device.duration", duration is not None and duration >= TARGETS["session_minutes"],
          duration, f">= {TARGETS['session_minutes']:.0f} minutes")
    for field, label in (
        ("full_screen_capture_ok", "full-screen MediaProjection capture succeeded"),
        ("landscape_rotation_ok", "landscape rotation capture succeeded"),
        ("actual_audio_verified", "cue was audibly verified"),
        ("game_audio_mix_ok", "cue and game audio were checked together"),
        ("authorized_test_scene", "test scene was permitted"),
    ):
        check(f"device.{field}", device.get(field) is True, device.get(field), label)
    for field in ("fps_notes", "thermal_notes", "hero_feedback"):
        check(f"device.{field}", _nonempty(device.get(field)), device.get(field),
              "nonempty observation")
    check("device.external_recording", recording_path.stat().st_size > 0,
          recording_path.stat().st_size, "nonempty external recording")
    check("device.external_recording_duration",
          recording_duration_seconds >= TARGETS["session_minutes"] * 60,
          round(recording_duration_seconds, 3),
          f">= {TARGETS['session_minutes']:.0f} continuous minutes")

    for kind in enabled:
        metrics = latency_metrics["by_kind"][kind]
        check(f"latency.{kind}.paired", metrics["paired_events"] >= 1,
              metrics["paired_events"], "at least one audible matched event")
        check(f"latency.{kind}.missing_audio", metrics["missing_audio"] == 0,
              metrics["missing_audio"], "0 missing audible cues")
        check(f"latency.{kind}.p95_ms",
              metrics["p95_ms"] is not None and metrics["p95_ms"] <= TARGETS["physical_p95_ms"],
              metrics["p95_ms"], f"<= {TARGETS['physical_p95_ms']:.0f} ms")
    overall_latency = latency_metrics["overall"]
    check("latency.overall.missing_audio", overall_latency["missing_audio"] == 0,
          overall_latency["missing_audio"], "0 missing audible cues")
    check("latency.overall.p95_ms",
          overall_latency["p95_ms"] is not None and
          overall_latency["p95_ms"] <= TARGETS["physical_p95_ms"],
          overall_latency["p95_ms"], f"<= {TARGETS['physical_p95_ms']:.0f} ms")

    failures = [item["id"] for item in checks if not item["passed"]]
    return {
        "schema_version": 1,
        "passed": not failures,
        "enabled_kinds": enabled,
        "directional_kinds": directional,
        "targets": TARGETS,
        "event_metrics": event_metrics,
        "latency_metrics": latency_metrics,
        "checks": checks,
        "failures": failures,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("evidence", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    try:
        result = evaluate_gate(args.evidence)
        rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(rendered, encoding="utf-8")
        print(rendered, end="")
        if not result["passed"]:
            raise SystemExit(1)
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
