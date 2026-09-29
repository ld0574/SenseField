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
import zipfile
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
MATCH_IMAGE = re.compile(r"^(?P<match_id>.+)_(?P<at_ms>[0-9]{9})\.png$")
EXPECTED_PREPROCESSING = (
    "native minimap locator when configured -> RGBA crop -> ncnn "
    "PIXEL_RGBA2BGR resize -> right/bottom 114 border"
)
APK_ASSETS = {
    "profile": "assets/profile.json",
    "model_param": "assets/minimap-yolox-nano-320.param",
    "model_bin": "assets/minimap-yolox-nano-320.bin",
    "model_metadata": "assets/minimap-yolox-nano-320.metadata.json",
}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


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


def _apk_artifacts(path: Path) -> dict[str, object]:
    """Read and internally verify the frozen profile/model bundled in an APK."""
    try:
        with zipfile.ZipFile(path) as archive:
            contents = {
                key: archive.read(member)
                for key, member in APK_ASSETS.items()
            }
    except (OSError, KeyError, zipfile.BadZipFile) as error:
        raise ValueError(f"APK is missing required frozen profile/model assets: {path}") from error
    try:
        metadata = json.loads(contents["model_metadata"].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("APK model metadata is not valid UTF-8 JSON") from error
    if not isinstance(metadata, dict) or metadata.get("schema_version") != 1:
        raise ValueError("APK model metadata must use schema_version 1")
    runtime = metadata.get("runtime")
    if not isinstance(runtime, dict):
        raise ValueError("APK model metadata.runtime must be an object")
    param_sha = _sha256_bytes(contents["model_param"])
    bin_sha = _sha256_bytes(contents["model_bin"])
    if runtime.get("param_sha256") != param_sha:
        raise ValueError("APK model param does not match its bundled metadata")
    if runtime.get("bin_sha256") != bin_sha:
        raise ValueError("APK model bin does not match its bundled metadata")
    return {
        "profile_sha256": _sha256_bytes(contents["profile"]),
        "model_param_sha256": param_sha,
        "model_bin_sha256": bin_sha,
        "model_metadata_sha256": _sha256_bytes(contents["model_metadata"]),
        "ncnn_version": runtime.get("version"),
    }


def _prediction_timeline(path: Path, fps: int) -> dict[str, object]:
    """Verify the deterministic CFR JSONL timeline produced for final evaluation."""
    frame_count = 0
    cue_count = 0
    last_timestamp = -1
    with path.open(encoding="utf-8") as stream:
        for line_number, raw in enumerate(stream, start=1):
            if not raw.strip():
                continue
            try:
                record = json.loads(raw)
            except json.JSONDecodeError as error:
                raise ValueError(
                    f"Predictions line {line_number} is not valid JSON"
                ) from error
            if not isinstance(record, dict):
                raise ValueError(f"Predictions line {line_number} must be an object")
            frame_index = record.get("frame_index")
            timestamp_ms = record.get("timestamp_ms")
            expected_timestamp = round(frame_count * 1000 / fps)
            if frame_index != frame_count:
                raise ValueError(
                    f"Predictions line {line_number} frame_index must be {frame_count}"
                )
            if timestamp_ms != expected_timestamp or timestamp_ms <= last_timestamp:
                raise ValueError(
                    f"Predictions line {line_number} timestamp does not match the CFR timeline"
                )
            for field in ("observations", "detections", "cues"):
                if not isinstance(record.get(field), list):
                    raise ValueError(
                        f"Predictions line {line_number} needs a {field} list"
                    )
            for cue in record["cues"]:
                if not isinstance(cue, dict) or cue.get("emitted_at_ms") != timestamp_ms:
                    raise ValueError(
                        f"Predictions line {line_number} has a cue outside its frame timestamp"
                    )
            cue_count += len(record["cues"])
            frame_count += 1
            last_timestamp = timestamp_ms
    if frame_count == 0:
        raise ValueError("Predictions JSONL contains no frames")
    return {
        "frame_count": frame_count,
        "cue_count": cue_count,
        "first_timestamp_ms": 0,
        "last_timestamp_ms": last_timestamp,
    }


def _dataset_split_groups(path: Path, split: str) -> set[str]:
    """Read source-match IDs from COCO names emitted by detection_dataset."""
    document = _json(path, f"training_data.{split}_annotations")
    images = document.get("images")
    if not isinstance(images, list):
        raise ValueError(f"training_data.{split}_annotations.images must be a list")
    groups: set[str] = set()
    for index, image in enumerate(images):
        if not isinstance(image, dict) or not isinstance(image.get("file_name"), str):
            raise ValueError(
                f"training_data.{split}_annotations.images[{index}].file_name is required"
            )
        file_name = image["file_name"]
        match = MATCH_IMAGE.fullmatch(file_name)
        if match is None:
            raise ValueError(
                f"training_data.{split}_annotations image name must be "
                "<match_id>_<9 digit timestamp>.png"
            )
        groups.add(match.group("match_id"))
    return groups


def _match_manifest(path: Path) -> tuple[dict[str, list[dict[str, str]]], dict[str, set[str]]]:
    """Read match-level split and recording hashes without opening any recordings."""
    document = _json(path, "training_data.match_manifest")
    matches = document.get("matches")
    if not isinstance(matches, list):
        raise ValueError("training_data.match_manifest.matches must be a list")
    by_id: dict[str, list[dict[str, str]]] = {}
    by_hash: dict[str, set[str]] = {}
    for index, item in enumerate(matches):
        if not isinstance(item, dict):
            raise ValueError(f"training_data.match_manifest.matches[{index}] must be an object")
        match_id, split, digest = item.get("id"), item.get("split"), item.get("video_sha256")
        if not _nonempty(match_id) or split not in ("train", "val", "test"):
            raise ValueError(
                f"training_data.match_manifest.matches[{index}] needs id and train/val/test split"
            )
        if not isinstance(digest, str) or not SHA256.fullmatch(digest.lower()):
            raise ValueError(
                f"training_data.match_manifest.matches[{index}].video_sha256 must be SHA-256"
            )
        record = {"id": match_id, "split": split, "video_sha256": digest.lower()}
        by_id.setdefault(match_id, []).append(record)
        by_hash.setdefault(digest.lower(), set()).add(split)
    return by_id, by_hash


def evaluate_gate(evidence_file: Path) -> dict:
    evidence_file = evidence_file.resolve()
    data = _json(evidence_file, "evidence manifest")
    if data.get("schema_version") != 3:
        raise ValueError("Expected evidence schema_version 3")
    base = evidence_file.parent

    training_data = data.get("training_data")
    if not isinstance(training_data, dict):
        raise ValueError("Evidence needs a training_data object")
    match_manifest_path, match_manifest_sha = _reference(
        base, training_data.get("match_manifest"), "training_data.match_manifest"
    )
    manifest_by_id, source_hash_splits = _match_manifest(match_manifest_path)
    split_references = training_data.get("split_annotations")
    if not isinstance(split_references, dict):
        raise ValueError("training_data.split_annotations must contain train, val, and test")
    split_annotation_records: dict[str, tuple[Path, str]] = {}
    split_groups: dict[str, set[str]] = {}
    for split in ("train", "val", "test"):
        annotation_path, digest = _reference(
            base, split_references.get(split), f"training_data.split_annotations.{split}"
        )
        split_annotation_records[split] = (annotation_path, digest)
        split_groups[split] = _dataset_split_groups(annotation_path, split)

    candidate_metadata_path, _ = _reference(
        base, data.get("model_candidate_metadata"), "model_candidate_metadata"
    )
    candidate_metadata = _json(candidate_metadata_path, "model candidate metadata")

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
    if metadata.get("replay") != "frozen_yolox_ncnn_native_event_replay":
        raise ValueError("Prediction metadata must come from the frozen ncnn replay")
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
    model = metadata.get("model")
    if not isinstance(model, dict):
        raise ValueError("prediction metadata.model must be an object")
    model_records = {}
    for key in ("param", "bin"):
        path, digest = _reference(
            metadata_base, model.get(key), f"prediction metadata.model.{key}"
        )
        model_records[key] = (path, digest)

    _, frozen_param_sha = _reference(
        base, holdout.get("model_param"), "holdout.model_param"
    )
    _, frozen_bin_sha = _reference(
        base, holdout.get("model_bin"), "holdout.model_bin"
    )
    _, frozen_library_sha = _reference(
        base, holdout.get("native_library"), "holdout.native_library"
    )

    sampling = metadata.get("sampling")
    timeline = metadata.get("timeline")
    runtime = metadata.get("runtime")
    if not isinstance(sampling, dict) or not isinstance(timeline, dict) or not isinstance(
            runtime, dict):
        raise ValueError("Prediction metadata needs sampling, timeline, and runtime objects")
    sampling_fps = sampling.get("fps")
    if not isinstance(sampling_fps, int) or isinstance(sampling_fps, bool) or sampling_fps < 1:
        raise ValueError("prediction metadata.sampling.fps must be a positive integer")
    prediction_timeline = _prediction_timeline(predictions_path, sampling_fps)

    profile_document = _json(profile_path, "holdout profile")
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
    apk_path, apk_sha = _reference(base, device.get("apk"), "device_session.apk")
    apk_artifacts = _apk_artifacts(apk_path)
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
    match_holdout_id = holdout_id if isinstance(holdout_id, str) else ""
    check("dataset.train_nonempty", bool(split_groups["train"]), len(split_groups["train"]),
          "training COCO annotations include at least one source match")
    check("dataset.val_nonempty", bool(split_groups["val"]), len(split_groups["val"]),
          "development-val COCO annotations include at least one source match")
    check("dataset.test_nonempty", bool(split_groups["test"]), len(split_groups["test"]),
          "independent test COCO annotations include at least one source match")
    group_overlaps = {
        "train_val": sorted(split_groups["train"] & split_groups["val"]),
        "train_test": sorted(split_groups["train"] & split_groups["test"]),
        "val_test": sorted(split_groups["val"] & split_groups["test"]),
    }
    check("dataset.match_groups_disjoint", not any(group_overlaps.values()), group_overlaps,
          "source match IDs must not cross train, val, and test")
    manifest_group_mismatches = sorted(
        (split, match_id, [item["split"] for item in manifest_by_id.get(match_id, [])])
        for split, groups in split_groups.items()
        for match_id in groups
        if len(manifest_by_id.get(match_id, [])) != 1
        or manifest_by_id[match_id][0]["split"] != split
    )
    check("dataset.manifest_matches_splits", not manifest_group_mismatches,
          manifest_group_mismatches,
          "COCO source IDs must map to exactly one matching split-manifest entry")
    reused_recordings = sorted(
        digest for digest, splits in source_hash_splits.items() if len(splits) > 1
    )
    check("dataset.recording_hashes_disjoint", not reused_recordings, reused_recordings,
          "the same source recording SHA-256 must not occur in multiple splits")
    test_manifest_records = manifest_by_id.get(match_holdout_id, [])
    check("holdout.matches_test_group", match_holdout_id in split_groups["test"], holdout_id,
          "holdout.id must be a populated source group in the test split")
    check("holdout.test_video_hash", len(test_manifest_records) == 1 and
          test_manifest_records[0]["split"] == "test" and
          test_manifest_records[0]["video_sha256"] == video_sha,
          {"holdout_video_sha256": video_sha,
           "test_manifest_records": test_manifest_records},
          "test source manifest must bind holdout.id to the holdout video SHA-256")
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
    check("provenance.model_param", model_records["param"][1] == frozen_param_sha,
          model_records["param"][1], f"must equal frozen param SHA-256 {frozen_param_sha}")
    check("provenance.model_bin", model_records["bin"][1] == frozen_bin_sha,
          model_records["bin"][1], f"must equal frozen bin SHA-256 {frozen_bin_sha}")
    check("provenance.native_library",
          metadata_records["native_library"][1] == frozen_library_sha,
          metadata_records["native_library"][1],
          f"must equal frozen native library SHA-256 {frozen_library_sha}")
    check("provenance.apk_profile", apk_artifacts["profile_sha256"] == profile_sha,
          apk_artifacts["profile_sha256"],
          f"APK profile must equal replay profile SHA-256 {profile_sha}")
    check("provenance.apk_model_param",
          apk_artifacts["model_param_sha256"] == frozen_param_sha,
          apk_artifacts["model_param_sha256"],
          f"APK param must equal frozen param SHA-256 {frozen_param_sha}")
    check("provenance.apk_model_bin",
          apk_artifacts["model_bin_sha256"] == frozen_bin_sha,
          apk_artifacts["model_bin_sha256"],
          f"APK bin must equal frozen bin SHA-256 {frozen_bin_sha}")
    profile_detectors = profile_document.get("detectors")
    profile_thresholds = profile_document.get("thresholds")
    profile_models = profile_document.get("models")
    check("provenance.profile_verified", profile_document.get("verified") is True,
          profile_document.get("verified"), "frozen release profile must be verified")
    check("provenance.profile_yolox",
          isinstance(profile_detectors, dict)
          and profile_detectors.get("minimap_yolox") is True,
          profile_detectors,
          "frozen profile must enable minimap_yolox")
    check("provenance.profile_input_size",
          isinstance(profile_thresholds, dict)
          and profile_thresholds.get("minimap_yolox_input_size", 320) == 320,
          profile_thresholds.get("minimap_yolox_input_size")
          if isinstance(profile_thresholds, dict) else None,
          "frozen Android YOLOX input size must equal 320")
    check("provenance.profile_model_bin",
          isinstance(profile_models, dict)
          and profile_models.get("minimap_yolox_bin_sha256") == frozen_bin_sha,
          profile_models.get("minimap_yolox_bin_sha256")
          if isinstance(profile_models, dict) else None,
          "profile model bin SHA-256 must match the frozen ncnn bin")
    replay_fps = (metadata.get("stats") or {}).get("fps") if isinstance(
        metadata.get("stats"), dict) else None
    check("provenance.replay_fps",
          replay_fps == sampling_fps == TARGETS["replay_fps"],
          {"stats_fps": replay_fps, "sampling_fps": sampling_fps},
          f"both must equal {TARGETS['replay_fps']} sampled FPS")
    stats = metadata.get("stats")
    stats_frames = stats.get("frames") if isinstance(stats, dict) else None
    stats_cues = stats.get("cues") if isinstance(stats, dict) else None
    check("provenance.frame_count",
          stats_frames == sampling.get("frame_count") == prediction_timeline["frame_count"],
          {"stats": stats_frames, "sampling": sampling.get("frame_count"),
           "predictions": prediction_timeline["frame_count"]},
          "stats, sampling, and predictions must contain the same frame count")
    check("provenance.cue_count", stats_cues == prediction_timeline["cue_count"],
          {"stats": stats_cues, "predictions": prediction_timeline["cue_count"]},
          "stats cue count must equal predictions")
    check("provenance.timeline",
          timeline.get("kind") == "synthetic_media_time_ms"
          and timeline.get("timestamp_formula") == "round(frame_index * 1000 / fps)"
          and timeline.get("source_pts_preserved") is False,
          timeline,
          "declared deterministic CFR synthetic media timeline")
    event_now_policy = metadata.get("event_now_policy")
    check("provenance.event_now_policy",
          isinstance(event_now_policy, str)
          and event_now_policy.startswith("zero_queue_delay;"),
          event_now_policy,
          "zero queue delay media-time event policy")
    preprocessing = runtime.get("preprocessing")
    check("provenance.preprocessing",
          preprocessing == EXPECTED_PREPROCESSING,
          preprocessing,
          f"must equal the frozen runtime preprocessing contract: {EXPECTED_PREPROCESSING}")
    desktop_ncnn_version = runtime.get("ncnn")
    apk_ncnn_version = apk_artifacts.get("ncnn_version")
    check("provenance.ncnn_version",
          isinstance(desktop_ncnn_version, str)
          and isinstance(apk_ncnn_version, str)
          and desktop_ncnn_version.split(".")[-1] == apk_ncnn_version,
          {"desktop": desktop_ncnn_version, "apk": apk_ncnn_version},
          "desktop replay and Android APK must use the same dated ncnn release")
    postprocess = runtime.get("postprocess")
    expected_confidence = profile_thresholds.get("minimap_yolox_confidence") \
        if isinstance(profile_thresholds, dict) else None
    expected_nms = profile_thresholds.get("minimap_yolox_nms") \
        if isinstance(profile_thresholds, dict) else None
    check("provenance.postprocess",
          isinstance(postprocess, dict)
          and postprocess.get("confidence") == expected_confidence
          and postprocess.get("nms_iou") == expected_nms
          and postprocess.get("strides") == [8, 16, 32],
          postprocess,
          "postprocess thresholds must match the frozen profile and strides 8/16/32")

    candidate = candidate_metadata.get("candidate")
    candidate_runtime = candidate_metadata.get("runtime")
    candidate_postprocess = candidate_metadata.get("postprocess")
    candidate_parity = candidate_metadata.get("parity")
    onnx_parity = candidate_parity.get("pytorch_vs_onnx") \
        if isinstance(candidate_parity, dict) else None
    ncnn_parity = candidate_parity.get("torchscript_vs_ncnn_and_android_preprocess") \
        if isinstance(candidate_parity, dict) else None
    candidate_profile_sha = (
        candidate.get("profile_sha256", candidate.get("development_profile_android_sha256"))
        if isinstance(candidate, dict) else None
    )
    check("candidate.verified", candidate_metadata.get("verified") is True,
          candidate_metadata.get("verified"), "candidate metadata must be verified")
    check("candidate.release_ready",
          isinstance(candidate, dict) and candidate.get("release_ready") is True
          and candidate.get("status") == "release_ready",
          candidate,
          "candidate metadata must declare release_ready after all gates pass")
    check("candidate.profile_hash", candidate_profile_sha == profile_sha,
          candidate_profile_sha, f"candidate profile SHA-256 must equal {profile_sha}")
    check("candidate.input_shape", candidate_metadata.get("input") == [1, 3, 320, 320],
          candidate_metadata.get("input"), "candidate model input must be [1, 3, 320, 320]")
    check("candidate.runtime_model_hashes",
          isinstance(candidate_runtime, dict)
          and candidate_runtime.get("param_sha256") == frozen_param_sha
          and candidate_runtime.get("bin_sha256") == frozen_bin_sha,
          candidate_runtime,
          "candidate metadata param/bin hashes must match the frozen ncnn model")
    check("candidate.runtime_version",
          isinstance(candidate_runtime, dict)
          and isinstance(desktop_ncnn_version, str)
          and candidate_runtime.get("version") == desktop_ncnn_version.split(".")[-1]
          and candidate_runtime.get("version") == apk_ncnn_version,
          candidate_runtime.get("version") if isinstance(candidate_runtime, dict) else None,
          "candidate, desktop replay, and APK must use the same ncnn release")
    check("candidate.postprocess",
          isinstance(candidate_postprocess, dict)
          and candidate_postprocess.get("confidence") == expected_confidence
          and candidate_postprocess.get("nms_iou") == expected_nms
          and candidate_postprocess.get("strides") == [8, 16, 32],
          candidate_postprocess,
          "candidate metadata thresholds and strides must match the frozen profile")
    check("candidate.onnx_parity",
          isinstance(onnx_parity, dict) and onnx_parity.get("passed") is True,
          onnx_parity, "strict PyTorch-to-ONNX parity must pass")
    check("candidate.ncnn_parity",
          isinstance(ncnn_parity, dict) and ncnn_parity.get("overall_passed") is True,
          ncnn_parity, "strict TorchScript-to-ncnn and Android preprocessing parity must pass")
    check("candidate.android_preprocess_parity",
          isinstance(ncnn_parity, dict)
          and ncnn_parity.get("android_preprocess_gate_passed") is True,
          ncnn_parity, "Android resize and padding parity must pass")

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
        ("apk_installed_from_evidence", "the referenced APK was installed for this session"),
        ("bundled_profile_used", "the APK bundled profile was used without importing another profile"),
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
        "apk": {"sha256": apk_sha, **apk_artifacts},
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
