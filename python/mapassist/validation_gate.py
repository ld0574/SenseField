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

from .android_session_log import parse_session_log
from .evaluate import KINDS, evaluate, read_labels, read_predictions
from .measure_latency import REQUIRED_COLUMNS, measure


TARGETS = {
    "precision": 0.90,
    "recall": 0.80,
    "direction_accuracy": 0.90,
    "physical_p95_ms": 250.0,
    "session_minutes": 15.0,
    "replay_fps": 12,
    "latency_samples_per_kind": 5,
    "latency_samples_overall": 20,
    "max_recording_packet_gap_seconds": 2.0,
    "max_recording_av_offset_seconds": 2.0,
    "minimum_landscape_fps": 8.0,
    "max_processed_gap_ms": 2000,
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


def _packet_timeline(path: Path, selector: str) -> dict[str, float | int]:
    try:
        process = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", selector,
             "-show_entries", "packet=dts_time,pts_time,duration_time",
             "-of", "json", str(path)],
            check=True, text=True, capture_output=True,
        )
        packets = json.loads(process.stdout)["packets"]
    except (FileNotFoundError, subprocess.CalledProcessError, KeyError, TypeError,
            ValueError, json.JSONDecodeError) as error:
        raise ValueError(
            f"Could not read {selector} packet timeline from external recording: {path}"
        ) from error
    if not isinstance(packets, list):
        raise ValueError(f"External recording has no {selector} packet list: {path}")
    first: float | None = None
    previous: float | None = None
    previous_duration = 0.0
    end: float | None = None
    maximum_gap = 0.0
    count = 0
    for packet in packets:
        if not isinstance(packet, dict):
            continue
        raw_timestamp = packet.get("dts_time", packet.get("pts_time"))
        try:
            timestamp = float(raw_timestamp)
            duration = float(packet.get("duration_time", 0.0))
        except (TypeError, ValueError):
            continue
        if not math.isfinite(timestamp) or not math.isfinite(duration) or duration < 0:
            continue
        if previous is not None:
            if timestamp < previous - 1e-6:
                raise ValueError(
                    f"External recording {selector} packet timestamps are not monotonic"
                )
            maximum_gap = max(
                maximum_gap, max(0.0, timestamp - (previous + previous_duration))
            )
        if first is None:
            first = timestamp
        previous = timestamp
        previous_duration = duration
        end = timestamp + duration
        count += 1
    if first is None or end is None or count < 2 or end <= first:
        raise ValueError(f"External recording has insufficient {selector} packets: {path}")
    return {
        "packet_count": count,
        "start_seconds": first,
        "end_seconds": end,
        "span_seconds": end - first,
        "max_packet_gap_seconds": maximum_gap,
    }


def _probe_external_recording(path: Path) -> dict[str, object]:
    try:
        process = subprocess.run(
            ["ffprobe", "-v", "error",
             "-show_entries", "format=duration:stream=codec_type",
             "-of", "json", str(path)],
            check=True, text=True, capture_output=True,
        )
        payload = json.loads(process.stdout)
        duration = float(payload["format"]["duration"])
        stream_types = [item.get("codec_type") for item in payload["streams"]
                        if isinstance(item, dict)]
    except (FileNotFoundError, subprocess.CalledProcessError, KeyError, TypeError,
            ValueError, json.JSONDecodeError) as error:
        raise ValueError(f"Could not inspect external recording: {path}") from error
    if not math.isfinite(duration) or duration <= 0:
        raise ValueError(f"External recording has invalid duration: {path}")
    if "video" not in stream_types or "audio" not in stream_types:
        raise ValueError("External recording must contain both video and audio streams")
    video = _packet_timeline(path, "v:0")
    audio = _packet_timeline(path, "a:0")
    return {
        "format_duration_seconds": duration,
        "video": video,
        "audio": audio,
        "start_offset_seconds": abs(
            float(video["start_seconds"]) - float(audio["start_seconds"])
        ),
        "end_offset_seconds": abs(
            float(video["end_seconds"]) - float(audio["end_seconds"])
        ),
    }


def evaluate_gate(evidence_file: Path) -> dict:
    evidence_file = evidence_file.resolve()
    data = _json(evidence_file, "evidence manifest")
    if data.get("schema_version") != 2:
        raise ValueError("Expected evidence schema_version 2")
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
    recording_probe = _probe_external_recording(recording_path)
    recording_duration_seconds = float(recording_probe["format_duration_seconds"])
    device_log_path, _ = _reference(
        base, device.get("device_log"), "device_session.device_log"
    )
    session_id = device.get("session_id")
    if not _nonempty(session_id):
        raise ValueError("device_session.session_id is required")
    android_session = parse_session_log(device_log_path, session_id.strip())
    latency_path, _ = _reference(
        base, device.get("latency_csv"), "device_session.latency_csv"
    )
    with latency_path.open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames is None or not REQUIRED_COLUMNS.issubset(reader.fieldnames):
            raise ValueError(
                "Latency CSV needs event_id,cue_id,kind,evidence_ms,audio_ms,source_note columns"
            )
        latency_rows = list(reader)
    unexpected_latency_kinds = sorted({(row.get("kind") or "").strip()
                                       for row in latency_rows} - set(enabled))
    if unexpected_latency_kinds:
        raise ValueError(
            f"Latency CSV contains disabled or unknown kinds: {unexpected_latency_kinds}"
        )
    latency_metrics = measure(latency_rows)
    latency_by_cue_id = {
        str(row["cue_id"]).strip(): row
        for row in latency_rows
    }
    logged_cues = {
        str(cue["cue_id"]): cue
        for cue in android_session["cues"]
        if not cue["stale"]
    }
    latency_cue_ids = set(latency_by_cue_id)
    logged_cue_ids = set(logged_cues)
    missing_latency_cues = sorted(logged_cue_ids - latency_cue_ids)
    unknown_latency_cues = sorted(latency_cue_ids - logged_cue_ids)
    cue_kind_mismatches = sorted(
        cue_id for cue_id in logged_cue_ids & latency_cue_ids
        if str(latency_by_cue_id[cue_id]["kind"]).strip()
        != str(logged_cues[cue_id]["kind"])
    )
    recording_duration_ms = recording_duration_seconds * 1000.0
    for line_number, row in enumerate(latency_rows, start=2):
        evidence_ms = float(row["evidence_ms"])
        audio_text = (row.get("audio_ms") or "").strip()
        audio_ms = float(audio_text) if audio_text else None
        if evidence_ms > recording_duration_ms or (
                audio_ms is not None and audio_ms > recording_duration_ms):
            raise ValueError(
                f"Latency CSV line {line_number} lies outside the external recording"
            )

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
    check("holdout.predictions_frozen", holdout.get(
              "predictions_frozen_before_label_review") is True,
          holdout.get("predictions_frozen_before_label_review"),
          "predictions frozen before reviewing holdout labels")
    check("holdout.not_used_for_tuning", holdout.get("used_for_tuning") is False,
          holdout.get("used_for_tuning"), "holdout was not used for tuning")
    check("holdout.real_match", holdout.get("real_match") is True,
          holdout.get("real_match"), "recording is a real player match")
    check("holdout.continuous_recording", holdout.get("continuous_recording") is True,
          holdout.get("continuous_recording"), "recording is a continuous match timeline")
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
        ("external_recording_unedited", "external recording is the unedited session capture"),
        ("latency_annotation_complete", "every non-stale logged cue was annotated"),
    ):
        check(f"device.{field}", device.get(field) is True, device.get(field), label)
    for field in ("fps_notes", "thermal_notes", "hero_feedback", "sync_method",
                  "audio_capture_method"):
        check(f"device.{field}", _nonempty(device.get(field)), device.get(field),
              "nonempty observation")
    check("device.external_recording", recording_path.stat().st_size > 0,
          recording_path.stat().st_size, "nonempty external recording")
    check("device.external_recording_duration",
          recording_duration_seconds >= TARGETS["session_minutes"] * 60,
          round(recording_duration_seconds, 3),
          f">= {TARGETS['session_minutes']:.0f} continuous minutes")
    check("device.duration_within_recording",
          duration is not None and duration * 60 <= recording_duration_seconds + 1.0,
          duration, "declared session duration must fit inside external recording")
    minimum_stream_seconds = TARGETS["session_minutes"] * 60
    for stream_kind in ("video", "audio"):
        stream = recording_probe[stream_kind]
        check(f"device.external_recording_{stream_kind}_duration",
              float(stream["span_seconds"]) >= minimum_stream_seconds,
              round(float(stream["span_seconds"]), 3),
              f">= {minimum_stream_seconds:.0f} seconds")
        check(f"device.external_recording_{stream_kind}_continuity",
              float(stream["max_packet_gap_seconds"])
              <= TARGETS["max_recording_packet_gap_seconds"],
              round(float(stream["max_packet_gap_seconds"]), 6),
              f"maximum packet gap <= {TARGETS['max_recording_packet_gap_seconds']:.1f} seconds")
    for edge in ("start", "end"):
        value = float(recording_probe[f"{edge}_offset_seconds"])
        check(f"device.external_recording_av_{edge}_alignment",
              value <= TARGETS["max_recording_av_offset_seconds"],
              round(value, 6),
              f"audio/video {edge} offset <= "
              f"{TARGETS['max_recording_av_offset_seconds']:.1f} seconds")

    summary = android_session["summary"]
    logged_duration_ms = int(summary["durationMs"])
    landscape_span_ms = int(android_session["landscape_span_ms"])
    average_landscape_fps = float(android_session["average_landscape_fps"])
    check("device.session_id", android_session["session_id"] == session_id.strip(),
          android_session["session_id"], "must select the captured Android session")
    check("device.log_duration",
          logged_duration_ms >= TARGETS["session_minutes"] * 60_000,
          logged_duration_ms,
          f">= {TARGETS['session_minutes']:.0f} continuous minutes")
    check("device.log_duration_within_recording",
          logged_duration_ms <= recording_duration_ms + 2000,
          logged_duration_ms,
          "logged session must fit inside the external recording")
    check("device.landscape_processed_span",
          landscape_span_ms >= TARGETS["session_minutes"] * 60_000,
          landscape_span_ms,
          f">= {TARGETS['session_minutes']:.0f} minutes of landscape processing")
    check("device.landscape_average_fps",
          average_landscape_fps >= TARGETS["minimum_landscape_fps"],
          average_landscape_fps,
          f">= {TARGETS['minimum_landscape_fps']:.1f} processed frames per second")
    check("device.landscape_max_gap",
          int(summary["maxProcessedGapMs"]) <= TARGETS["max_processed_gap_ms"],
          int(summary["maxProcessedGapMs"]),
          f"<= {TARGETS['max_processed_gap_ms']} ms between processed frames")
    check("device.logged_audio_queue_failures", int(summary["audioFailures"]) == 0,
          int(summary["audioFailures"]), "0 non-stale cues rejected by the audio queue")
    check("device.latency_cue_ids",
          not missing_latency_cues and not unknown_latency_cues,
          {"missing_from_csv": missing_latency_cues,
           "not_in_non_stale_log": unknown_latency_cues},
          "latency CSV cue IDs must exactly equal all non-stale Android CueEvent IDs")
    check("device.latency_cue_kinds", not cue_kind_mismatches,
          cue_kind_mismatches,
          "each latency CSV kind must match its Android CueEvent kind")

    for kind in enabled:
        metrics = latency_metrics["by_kind"][kind]
        check(f"latency.{kind}.paired",
              metrics["paired_events"] >= TARGETS["latency_samples_per_kind"],
              metrics["paired_events"],
              f">= {TARGETS['latency_samples_per_kind']} audible matched events")
        check(f"latency.{kind}.missing_audio", metrics["missing_audio"] == 0,
              metrics["missing_audio"], "0 missing audible cues")
        check(f"latency.{kind}.p95_ms",
              metrics["p95_ms"] is not None and metrics["p95_ms"] <= TARGETS["physical_p95_ms"],
              metrics["p95_ms"], f"<= {TARGETS['physical_p95_ms']:.0f} ms")
    overall_latency = latency_metrics["overall"]
    check("latency.overall.paired",
          overall_latency["paired_events"] >= TARGETS["latency_samples_overall"],
          overall_latency["paired_events"],
          f">= {TARGETS['latency_samples_overall']} audible matched events")
    check("latency.overall.missing_audio", overall_latency["missing_audio"] == 0,
          overall_latency["missing_audio"], "0 missing audible cues")
    check("latency.overall.p95_ms",
          overall_latency["p95_ms"] is not None and
          overall_latency["p95_ms"] <= TARGETS["physical_p95_ms"],
          overall_latency["p95_ms"], f"<= {TARGETS['physical_p95_ms']:.0f} ms")

    failures = [item["id"] for item in checks if not item["passed"]]
    return {
        "schema_version": 2,
        "passed": not failures,
        "enabled_kinds": enabled,
        "directional_kinds": directional,
        "targets": TARGETS,
        "event_metrics": event_metrics,
        "latency_metrics": latency_metrics,
        "external_recording_probe": recording_probe,
        "android_session": android_session,
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
