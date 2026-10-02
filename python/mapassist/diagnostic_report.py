"""Build a privacy-conscious offline report from a SenseField diagnostic ZIP."""

from __future__ import annotations

import argparse
import base64
import csv
import hashlib
import html
import json
import math
import re
import sys
import zipfile
from collections import Counter, defaultdict
from pathlib import Path, PurePosixPath
from typing import Any

from mapassist import diagnostic_replay
from mapassist.android_session_log import CHANNEL_BITS, KNOWN_CHANNEL_MASK, PLAYBACK_RESULTS
from mapassist.measure_latency import REQUIRED_COLUMNS, measure as measure_latency


REPORT_SCHEMA = "mapassist.diagnostic_report"
MAX_JSON_BYTES = 4 * 1024 * 1024
MAX_EVENTS_BYTES = 128 * 1024 * 1024
MAX_IMAGE_BYTES = 8 * 1024 * 1024
MAX_TOTAL_IMAGE_BYTES = 160 * 1024 * 1024
_SAFE_IMAGE_NAME = re.compile(r"images/(?:screen|map)-[A-Za-z0-9._-]+\.jpg\Z")
_CHANNEL_NAMES = {value: key for key, value in CHANNEL_BITS.items()}
_NEAR_DISPATCH_AUDIT_TOLERANCE_MS = 10
_EVENT_CONTEXT_AUDIT_TOLERANCE_MS = 1000
_MANUAL_CONTEXT_TOLERANCE_MS = 6000
_LATENCY_KIND_BY_DISPATCH = {
    "MAIN_ENEMY": "main_enemy",
    "MINIMAP_ENEMY": "minimap_enemy",
    "VISION_APPEAR": "minimap_enemy",
    "VISION_DISAPPEAR": "minimap_enemy",
    "DANGER_PING": "danger_ping",
    "PERIPHERAL_THREAT": "main_enemy",
    "NEAR_ZONE": "near_zone",
    "PLAYER_DEAD": "player_dead",
    "PLAYER_ALIVE": "player_alive",
    "RADAR_STATUS": "radar_status",
}


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _bounded_read(archive: zipfile.ZipFile, member: str, limit: int) -> bytes:
    try:
        info = archive.getinfo(member)
    except KeyError as error:
        raise ValueError(f"diagnostic ZIP is missing {member}") from error
    if info.file_size > limit:
        raise ValueError(f"diagnostic ZIP member {member} exceeds the {limit}-byte limit")
    chunks = bytearray()
    with archive.open(info, "r") as stream:
        while True:
            block = stream.read(min(1024 * 1024, limit + 1 - len(chunks)))
            if not block:
                break
            chunks.extend(block)
            if len(chunks) > limit:
                raise ValueError(f"diagnostic ZIP member {member} exceeds the {limit}-byte limit")
    return bytes(chunks)


def _validate_archive_members(archive: zipfile.ZipFile) -> dict[str, zipfile.ZipInfo]:
    members: dict[str, zipfile.ZipInfo] = {}
    for info in archive.infolist():
        name = info.filename
        if not name or "\x00" in name or "\\" in name:
            raise ValueError("diagnostic ZIP contains an unsafe member path")
        path = PurePosixPath(name)
        clean_parts = path.parts[:-1] if info.is_dir() and path.parts else path.parts
        if path.is_absolute() or any(part in {".", ".."} for part in clean_parts) or \
                (clean_parts and ":" in clean_parts[0]):
            raise ValueError("diagnostic ZIP contains a path that escapes its archive root")
        if name in members:
            raise ValueError(f"diagnostic ZIP contains duplicate member {name}")
        mode = info.external_attr >> 16
        if (mode & 0o170000) == 0o120000:
            raise ValueError("diagnostic ZIP contains a symbolic-link member")
        members[name] = info
    return members


def _read_json_member(archive: zipfile.ZipFile, name: str) -> dict[str, Any]:
    raw = _bounded_read(archive, name, MAX_JSON_BYTES)
    try:
        value = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"diagnostic ZIP has invalid JSON in {name}") from error
    if not isinstance(value, dict):
        raise ValueError(f"{name} must contain a JSON object")
    return value


def _read_events(archive: zipfile.ZipFile, session_id: str) -> tuple[list[dict[str, Any]], Counter[str]]:
    try:
        info = archive.getinfo("events.jsonl")
    except KeyError as error:
        raise ValueError("diagnostic ZIP is missing events.jsonl") from error
    if info.file_size > MAX_EVENTS_BYTES:
        raise ValueError("diagnostic ZIP events.jsonl exceeds the safety limit")
    events: list[dict[str, Any]] = []
    counts: Counter[str] = Counter()
    consumed = 0
    with archive.open(info, "r") as stream:
        for line_number, raw in enumerate(stream, start=1):
            consumed += len(raw)
            if consumed > MAX_EVENTS_BYTES:
                raise ValueError("diagnostic ZIP events.jsonl exceeds the safety limit")
            if not raw.strip():
                continue
            try:
                event = json.loads(raw)
            except (UnicodeDecodeError, json.JSONDecodeError) as error:
                raise ValueError(f"events.jsonl line {line_number} is invalid JSON") from error
            if not isinstance(event, dict) or not isinstance(event.get("type"), str):
                raise ValueError(f"events.jsonl line {line_number} must have an event type")
            event_session = event.get("session_id")
            if event_session is not None and event_session != session_id:
                raise ValueError(f"events.jsonl line {line_number} belongs to another session")
            if "data" in event and not isinstance(event["data"], dict):
                raise ValueError(f"events.jsonl line {line_number}.data must be an object")
            events.append(event)
            counts[event["type"]] += 1
    return events, counts


def _audit_fields(message: Any, marker: str) -> dict[str, str] | None:
    if not isinstance(message, str) or not message.startswith(marker + " "):
        return None
    fields: dict[str, str] = {}
    for token in message[len(marker) + 1 :].split():
        if "=" in token:
            key, value = token.split("=", 1)
            fields[key] = value
    return fields


def _int(value: Any, label: str, *, minimum: int | None = None) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{label} must be an integer")
    if minimum is not None and value < minimum:
        raise ValueError(f"{label} must be at least {minimum}")
    return value


def _maybe_int(value: Any) -> int | None:
    return value if isinstance(value, int) and not isinstance(value, bool) else None


def _nonnegative_int(value: Any) -> int | None:
    value = _maybe_int(value)
    return value if value is not None and value >= 0 else None


def _percentile(values: list[float], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(percentile * len(ordered)) - 1)
    return round(ordered[index], 3)


def _distribution(values: list[float], unit: str) -> dict[str, Any]:
    return {
        "count": len(values),
        "unit": unit,
        "min": round(min(values), 3) if values else None,
        "p50": _percentile(values, 0.50),
        "p95": _percentile(values, 0.95),
        "max": round(max(values), 3) if values else None,
    }


def _channel_names(mask: int) -> list[str]:
    return [name for bit, name in sorted(_CHANNEL_NAMES.items()) if mask & bit]


def _parse_cues(events: list[dict[str, Any]]) -> tuple[dict[str, dict[str, Any]], list[dict[str, Any]], Counter[str]]:
    dispatch_by_id: dict[str, dict[str, Any]] = {}
    callbacks: list[dict[str, Any]] = []
    audit_counts: Counter[str] = Counter()
    for event in events:
        if event["type"] != "audit":
            continue
        data = event.get("data", {})
        message = data.get("message") if isinstance(data, dict) else None
        dispatch = _audit_fields(message, "CueDispatch")
        if dispatch is not None:
            audit_counts["CueDispatch"] += 1
            cue_id = dispatch.get("cueId")
            if not cue_id:
                raise ValueError("CueDispatch audit is missing cueId")
            if cue_id in dispatch_by_id:
                raise ValueError(f"duplicate CueDispatch cueId {cue_id!r}")
            requested = _int(_parse_decimal(dispatch.get("requestedMask"), "requestedMask"),
                             "CueDispatch.requestedMask", minimum=0)
            accepted = _int(_parse_decimal(dispatch.get("acceptedMask"), "acceptedMask"),
                             "CueDispatch.acceptedMask", minimum=0)
            if requested & ~KNOWN_CHANNEL_MASK or accepted & ~KNOWN_CHANNEL_MASK or accepted & ~requested:
                raise ValueError(f"CueDispatch {cue_id!r} has invalid channel masks")
            outcome = dispatch.get("outcome")
            if outcome not in {"ACCEPTED", "DROPPED"} or (outcome == "ACCEPTED") != (accepted != 0):
                raise ValueError(f"CueDispatch {cue_id!r} has an inconsistent outcome")
            created = _int(_parse_decimal(dispatch.get("createdAtMs"), "createdAtMs"),
                           "CueDispatch.createdAtMs", minimum=0)
            dispatched_at = _int(_parse_decimal(dispatch.get("dispatchAtMs"), "dispatchAtMs"),
                                 "CueDispatch.dispatchAtMs", minimum=0)
            delay_value = dispatch.get("dispatchDelayMs")
            dispatch_delay = _int(_parse_decimal(delay_value, "dispatchDelayMs"),
                                  "CueDispatch.dispatchDelayMs", minimum=0) if delay_value is not None else max(0, dispatched_at - created)
            dispatch_by_id[cue_id] = {
                "cue_id": cue_id,
                "kind": dispatch.get("kind", "unknown"),
                "category": dispatch.get("category", "unknown"),
                "created_at_ms": created,
                "dispatch_at_ms": dispatched_at,
                "dispatch_delay_ms": dispatch_delay,
                "requested_mask": requested,
                "requested_channels": _channel_names(requested),
                "accepted_mask": accepted,
                "accepted_channels": _channel_names(accepted),
                "dropped_channels": _channel_names(requested & ~accepted),
                "outcome": outcome,
                "drop_reason": dispatch.get("dropReason", "unknown"),
            }
            continue

        playback = _audit_fields(message, "CuePlayback")
        if playback is not None:
            audit_counts["CuePlayback"] += 1
            cue_id = playback.get("cueId")
            channel = playback.get("channel")
            result = playback.get("result")
            if not cue_id or channel not in CHANNEL_BITS or result not in PLAYBACK_RESULTS:
                raise ValueError("CuePlayback audit has an invalid cue, channel, or result")
            at_ms = _int(_parse_decimal(playback.get("atMs"), "atMs"),
                         "CuePlayback.atMs", minimum=0)
            provided_delay = playback.get("playbackDelayMs")
            callbacks.append({
                "cue_id": cue_id,
                "channel": channel,
                "at_ms": at_ms,
                "playback_delay_ms": _int(_parse_decimal(provided_delay, "playbackDelayMs"),
                                           "CuePlayback.playbackDelayMs", minimum=0)
                if provided_delay is not None else None,
                "result": result,
            })
    return dispatch_by_id, callbacks, audit_counts


def _parse_decimal(value: Any, label: str) -> int:
    if not isinstance(value, str) or not value or not value.isdecimal():
        raise ValueError(f"{label} must be an integer")
    return int(value)


def _frame_stats(events: list[dict[str, Any]], summary: dict[str, Any], metadata: dict[str, Any],
                 replay_timing: dict[str, Any] | None) -> tuple[dict[str, Any], dict[str, Any], list[dict[str, Any]]]:
    frames = [event for event in events if event["type"] == "frame"]
    native_ms: list[float] = []
    frame_age_ms: list[float] = []
    completion_gaps: list[float] = []
    ordered_completion: list[int] = []
    frame_rows: list[dict[str, Any]] = []
    for position, event in enumerate(frames, start=1):
        data = event.get("data", {})
        index = _int(data.get("frame_index"), f"frame[{position}].frame_index", minimum=0)
        observed_at = _maybe_int(data.get("observed_at_ms"))
        completed_at = _maybe_int(data.get("completed_at_ms"))
        engine_at = _maybe_int(data.get("engine_at_ms"))
        latency = _maybe_int(data.get("native_micros"))
        if latency is not None and latency >= 0:
            native_ms.append(latency / 1000.0)
        age = _maybe_int(data.get("age_at_engine_ms"))
        if age is None and observed_at is not None and engine_at is not None:
            age = engine_at - observed_at
        if age is not None and age >= 0:
            frame_age_ms.append(float(age))
        if completed_at is not None and completed_at >= 0:
            ordered_completion.append(completed_at)
        frame_rows.append({
            "frame_index": index,
            "observed_at_ms": observed_at,
            "completed_at_ms": completed_at,
            "engine_at_ms": engine_at,
            "age_at_engine_ms": age,
            "native_micros": latency,
            "relation_event": (data.get("relation") or {}).get("event")
            if isinstance(data.get("relation"), dict) else None,
        })
    ordered_completion.sort()
    completion_gaps = [float(right - left) for left, right in zip(ordered_completion, ordered_completion[1:])]

    replay_age = replay_timing.get("frame_age_ms") if replay_timing else None
    if isinstance(replay_age, dict) and replay_age.get("count") == len(frames):
        # Use diagnostic_replay's already validated, nearest-rank statistics.
        age_distribution = replay_age
    else:
        age_distribution = _distribution(frame_age_ms, "milliseconds")

    last_state = summary.get("last_state") if isinstance(summary.get("last_state"), dict) else {}
    checkpoints = [event.get("data", {}) for event in events if event["type"] == "checkpoint"]
    devices = [event.get("data", {}) for event in events if event["type"] == "device"]
    image_context_stats = summary.get("image_context_stats")
    if not isinstance(image_context_stats, dict):
        image_context_stats = next((item.get("image_context_stats") for item in reversed(checkpoints)
                                    if isinstance(item.get("image_context_stats"), dict)), {})
    load_modes = Counter(str(item["load_control_mode"]) for item in checkpoints
                         if item.get("load_control_mode") is not None)
    missing_load_modes = sum("load_control_mode" not in item for item in checkpoints)
    rests = [float(item["load_rest_ms"]) for item in checkpoints
             if isinstance(item.get("load_rest_ms"), int) and not isinstance(item.get("load_rest_ms"), bool)
             and item["load_rest_ms"] >= 0]
    temps = [float(item["battery_temp_tenths_c"]) / 10.0 for item in devices
             if isinstance(item.get("battery_temp_tenths_c"), int)
             and item["battery_temp_tenths_c"] >= 0]
    thermal = Counter(str(item["thermal_status_name"]) for item in devices
                      if isinstance(item.get("thermal_status_name"), str))
    if last_state.get("load_control_mode") is not None:
        load_modes[str(last_state["load_control_mode"])] += 1
    if isinstance(last_state.get("load_rest_ms"), int) and last_state["load_rest_ms"] >= 0:
        rests.append(float(last_state["load_rest_ms"]))

    copied_context_frames = _maybe_int(image_context_stats.get("copied_context_frames"))
    context_copy_micros = _maybe_int(image_context_stats.get("context_copy_micros"))
    context_copy_max_micros = _maybe_int(image_context_stats.get("max_context_copy_micros"))
    image_diagnostics = {
        "copied_context_frames": copied_context_frames,
        "context_copy_total_ms": round(context_copy_micros / 1000.0, 3)
        if context_copy_micros is not None and context_copy_micros >= 0 else None,
        "context_copy_mean_ms": round(context_copy_micros / copied_context_frames / 1000.0, 3)
        if context_copy_micros is not None and context_copy_micros >= 0
        and copied_context_frames is not None and copied_context_frames > 0 else None,
        "context_copy_max_ms": round(context_copy_max_micros / 1000.0, 3)
        if context_copy_max_micros is not None and context_copy_max_micros >= 0 else None,
        "context_windows": _maybe_int(image_context_stats.get("context_windows")),
        "context_rate_limited": _maybe_int(image_context_stats.get("context_rate_limited")),
        "context_deferred_frames": _maybe_int(image_context_stats.get("context_deferred_frames")),
        "peak_image_frames": _maybe_int(image_context_stats.get("peak_image_frames")),
        "peak_image_bytes": _maybe_int(image_context_stats.get("peak_image_bytes")),
        "pending_image_frames": _maybe_int(image_context_stats.get("pending_image_frames")),
        "pending_image_bytes": _maybe_int(image_context_stats.get("pending_image_bytes")),
        "queue_scope": image_context_stats.get("image_queue_scope")
        if isinstance(image_context_stats.get("image_queue_scope"), str) else None,
    }

    replay_native = replay_timing.get("logged_native_latency") if replay_timing else None
    if isinstance(replay_native, dict) and replay_native.get("count") == len(frames):
        native_distribution = {
            "count": replay_native["count"],
            "unit": "milliseconds",
            **{key: round(replay_native[key] / 1000.0, 3)
               if replay_native.get(key) is not None else None
               for key in ("min", "p50", "p95", "max")},
        }
    else:
        native_distribution = _distribution(native_ms, "milliseconds")
    processing = {
        "frame_count_in_events": len(frames),
        "frame_age_ms": age_distribution,
        "native_processing_ms": native_distribution,
        "completion_gap_ms": _distribution(completion_gaps, "milliseconds"),
        "processed_frames": _maybe_int(last_state.get("processed_frames")),
        "landscape_processed_frames": _maybe_int(last_state.get("landscape_processed_frames")),
        "max_processed_gap_ms": _maybe_int(last_state.get("max_processed_gap_ms")),
        "total_processing_wall_ms": _maybe_int(last_state.get("total_processing_wall_ms")),
        "load_control_modes": dict(load_modes),
        "checkpoints_missing_load_mode": missing_load_modes,
        "load_rest_ms": _distribution(rests, "milliseconds"),
        "load_skipped_frames": _maybe_int(last_state.get("load_skipped_frames")),
        "dropped_events": _maybe_int(summary.get("dropped_events")),
        "dropped_image_requests": _maybe_int(summary.get("dropped_image_requests")),
        "images_limited": summary.get("images_limited") if isinstance(summary.get("images_limited"), bool) else None,
        "image_diagnostics": image_diagnostics,
    }
    thermal_report = {
        "battery_temperature_c": _distribution(temps, "degrees_celsius"),
        "thermal_status_counts": dict(thermal),
        "thermal_status_samples": sum(thermal.values()),
        "android_api_level": _maybe_int(metadata.get("sdk")),
    }
    return processing, thermal_report, frame_rows


def _latency_report(path: Path, dispatch_by_id: dict[str, dict[str, Any]]) -> dict[str, Any]:
    with path.open(newline="", encoding="utf-8-sig") as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames is None or not REQUIRED_COLUMNS.issubset(reader.fieldnames):
            raise ValueError("latency CSV needs event_id,cue_id,kind,evidence_ms,audio_ms,source_note columns")
        rows = list(reader)
    # This deliberately reuses the external-evidence analyzer; it preserves its
    # finite-number, unique-ID, and no-negative-delay checks.
    aggregate = measure_latency(rows)
    pairs: list[dict[str, Any]] = []
    csv_ids: set[str] = set()
    for index, row in enumerate(rows, start=2):
        cue_id = (row.get("cue_id") or "").strip()
        if cue_id not in dispatch_by_id:
            raise ValueError(f"latency CSV line {index} has unknown cue_id {cue_id!r}")
        expected_kind = _LATENCY_KIND_BY_DISPATCH.get(str(dispatch_by_id[cue_id]["kind"]).upper())
        row_kind = (row.get("kind") or "").strip()
        if expected_kind is None or row_kind != expected_kind:
            raise ValueError(
                f"latency CSV line {index} kind {row_kind!r} does not match CueDispatch "
                f"{cue_id!r} kind {dispatch_by_id[cue_id]['kind']!r}"
            )
        if cue_id in csv_ids:
            raise ValueError(f"latency CSV line {index} duplicates cue_id {cue_id!r}")
        csv_ids.add(cue_id)
        evidence_ms = float(row["evidence_ms"])
        audio_text = (row.get("audio_ms") or "").strip()
        audio_ms = float(audio_text) if audio_text else None
        pairs.append({
            "cue_id": cue_id,
            "kind": row_kind,
            "evidence_ms": evidence_ms,
            "audio_ms": audio_ms,
            "measured_delay_ms": round(audio_ms - evidence_ms, 1) if audio_ms is not None else None,
        })
    dispatch_ids = set(dispatch_by_id)
    missing_csv_ids = sorted(dispatch_ids - csv_ids)
    return {
        "source": "external recording CSV; evidence/audio time is user annotated",
        "summary": aggregate,
        "matched_pair_count": len(pairs),
        "cue_id_coverage": {
            "dispatch_count": len(dispatch_ids),
            "csv_count": len(csv_ids),
            "unpaired_dispatch_ids": missing_csv_ids,
            "complete_against_recorded_dispatches": not missing_csv_ids,
        },
        "pairs": pairs,
        "interpretation": "External evidence-to-audio timing. Only independently annotated audio_ms supports audible latency; app callbacks alone do not.",
    }


def _image_member_name(value: Any) -> str:
    if not isinstance(value, str) or not _SAFE_IMAGE_NAME.fullmatch(value):
        raise ValueError("image event references an unsafe or unsupported ZIP member path")
    return value


def _image_assets(archive: zipfile.ZipFile, members: dict[str, zipfile.ZipInfo],
                  events: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], dict[str, str], list[str]]:
    records: list[dict[str, Any]] = []
    inline: dict[str, str] = {}
    gaps: list[str] = []
    total_bytes = 0
    for event in events:
        if event["type"] != "image":
            continue
        name = _image_member_name(event.get("file"))
        data = event
        image_id = f"image-{len(records) + 1:04d}"
        member = members.get(name)
        record: dict[str, Any] = {
            "image_id": image_id,
            "frame_index": _maybe_int(data.get("frame_index")),
            "observed_at_ms": _maybe_int(data.get("observed_at_ms")),
            "reason": str(data.get("reason", "unknown")),
            "window_id": _maybe_int(data.get("window_id")),
            "kind": "screen" if PurePosixPath(name).name.startswith("screen-") else "map",
            "archive_member": name,
            "available": False,
        }
        if member is None:
            gaps.append(f"图像记录 {image_id} 引用的截图文件不在 ZIP 中")
        elif member.file_size > MAX_IMAGE_BYTES or total_bytes + member.file_size > MAX_TOTAL_IMAGE_BYTES:
            gaps.append(f"图像记录 {image_id} 超过离线报告内嵌大小上限")
        else:
            payload = _bounded_read(archive, name, MAX_IMAGE_BYTES)
            total_bytes += len(payload)
            if not payload.startswith(b"\xff\xd8\xff"):
                gaps.append(f"图像记录 {image_id} 不是有效 JPEG，未嵌入报告")
            else:
                inline[image_id] = "data:image/jpeg;base64," + base64.b64encode(payload).decode("ascii")
                record["available"] = True
        records.append(record)
    return records, inline, gaps


def _contexts_and_markers(events: list[dict[str, Any]], images: list[dict[str, Any]],
                          dispatch_by_id: dict[str, dict[str, Any]],
                          frame_rows: list[dict[str, Any]], metadata: dict[str, Any]
                          ) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    context_events = [(event.get("data", {}), _nonnegative_int(event.get("at_ms")))
                      for event in events if event["type"] == "image_context"]
    image_windows: dict[int, list[dict[str, Any]]] = defaultdict(list)
    image_frames: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for image in images:
        window_id = image.get("window_id")
        if window_id is not None:
            image_windows[window_id].append(image)
        frame_index = image.get("frame_index")
        if frame_index is not None:
            image_frames[frame_index].append(image)

    frames_by_observed: dict[int, list[dict[str, Any]]] = defaultdict(list)
    frames_by_completed: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for frame in frame_rows:
        if frame.get("observed_at_ms") is not None:
            frames_by_observed[frame["observed_at_ms"]].append(frame)
        if frame.get("completed_at_ms") is not None:
            frames_by_completed[frame["completed_at_ms"]].append(frame)

    context_rows: list[dict[str, Any]] = []
    for context, event_at_ms in context_events:
        window_id = _maybe_int(context.get("window_id"))
        pre_frame_indices = context.get("pre_frame_indices")
        if not isinstance(pre_frame_indices, list):
            pre_frame_indices = []
        pre_frames = [_nonnegative_int(item) for item in pre_frame_indices]
        pre_frames = [item for item in pre_frames if item is not None]
        start_observed = _nonnegative_int(context.get("start_observed_at_ms"))
        requested_at = _nonnegative_int(context.get("requested_at_ms"))
        end_observed = _nonnegative_int(context.get("end_observed_at_ms"))
        reason = context.get("reason")
        trigger_candidates: list[dict[str, Any]] = []
        trigger_match_method = None
        if reason in {"near_enter", "user_marker_frame"} and requested_at is not None:
            # These records are exported after the triggering frame completes.
            # requested_at_ms therefore identifies that frame even when an
            # active/coalesced window retains an older start_observed_at_ms.
            trigger_candidates = frames_by_completed.get(requested_at, [])
            if len(trigger_candidates) == 1:
                trigger_match_method = "exact_completed_frame"
        if not trigger_candidates and reason == "user_marker" and start_observed is not None:
            trigger_candidates = frames_by_observed.get(start_observed, [])
            if len(trigger_candidates) == 1:
                trigger_match_method = "exact_observed_frame"
        if (not trigger_candidates and start_observed is not None
                and not (reason in {"near_enter", "user_marker_frame"} and requested_at is not None)):
            trigger_candidates = frames_by_observed.get(start_observed, [])
            if len(trigger_candidates) == 1:
                trigger_match_method = "start_frame_fallback"
        trigger_frame_index = trigger_candidates[0]["frame_index"] if len(trigger_candidates) == 1 else None
        trigger_observed_at_ms = trigger_candidates[0].get("observed_at_ms") if len(trigger_candidates) == 1 else None
        trigger_reasons = {"near_enter", "user_marker", "user_marker_frame"}
        trigger_images = [image for image in image_frames.get(trigger_frame_index, [])
                          if image.get("reason") in trigger_reasons] if trigger_frame_index is not None else []
        pre_context_images = [image for frame_index in pre_frames
                              for image in image_frames.get(frame_index, [])
                              if image.get("reason") == "pre_context"]
        pre_ids = [image["image_id"] for image in pre_context_images]
        window_images = image_windows.get(window_id, []) if window_id not in (None, 0) else []
        post_ids = [image["image_id"] for image in window_images
                    if image.get("reason") == "post_context"
                    and (trigger_observed_at_ms is None or _nonnegative_int(image.get("observed_at_ms")) is None
                         or image["observed_at_ms"] >= trigger_observed_at_ms)
                    and (end_observed is None or _nonnegative_int(image.get("observed_at_ms")) is None
                         or image["observed_at_ms"] <= end_observed)]
        context_rows.append({
            "window_id": window_id,
            "status": context.get("status", "unknown"),
            "reason": context.get("reason", "unknown"),
            "event_at_ms": event_at_ms,
            "requested_at_ms": requested_at,
            "start_observed_at_ms": start_observed,
            "end_observed_at_ms": end_observed,
            "trigger_frame_index": trigger_frame_index,
            "trigger_observed_at_ms": trigger_observed_at_ms,
            "trigger_match_method": trigger_match_method,
            "pre_frame_indices": pre_frames,
            "pre_captured_frame_indices": sorted({image.get("frame_index") for image in pre_context_images
                                                   if image.get("frame_index") is not None}),
            "pre_image_ids": pre_ids,
            "trigger_image_ids": [image["image_id"] for image in trigger_images],
            "post_image_ids": post_ids,
            "sampling_note": str(context.get("sampling_note", "")),
        })

    settings_pre = _maybe_int(metadata.get("context_pre_ms"))
    settings_post = _maybe_int(metadata.get("context_post_ms"))
    markers: list[dict[str, Any]] = []
    marker_records = [(event.get("data", {}), _nonnegative_int(event.get("at_ms")))
                      for event in events if event["type"] == "user_marker"]
    used_contexts: set[int] = set()
    manual_options: list[list[int]] = []
    for marker, _ in marker_records:
        requested_at = _nonnegative_int(marker.get("requested_at_ms"))
        exact = [position for position, ctx in enumerate(context_rows)
                 if requested_at is not None and ctx.get("reason") == "user_marker"
                 and ctx.get("requested_at_ms") == requested_at]
        if exact:
            manual_options.append(exact)
            continue
        approximate = [position for position, ctx in enumerate(context_rows)
                       if ctx.get("reason") == "user_marker_frame"
                       and requested_at is not None
                       and ctx.get("requested_at_ms") is not None
                       and 0 <= ctx["requested_at_ms"] - requested_at <= _MANUAL_CONTEXT_TOLERANCE_MS]
        manual_options.append(approximate)
    candidate_uses = Counter(position for options in manual_options if len(options) == 1
                             for position in options)
    for index, ((marker, marker_event_at), options) in enumerate(zip(marker_records, manual_options), start=1):
        requested_at = _nonnegative_int(marker.get("requested_at_ms"))
        if len(options) == 1 and candidate_uses[options[0]] == 1 and options[0] not in used_contexts:
            ctx_index = options[0]
            context = context_rows[ctx_index]
            used_contexts.add(ctx_index)
            marker_item = _marker_from_context(f"manual-{index}", "manual", requested_at,
                                               context, settings_pre, settings_post)
            marker_item["association_method"] = "exact_requested_at" if context["reason"] == "user_marker" else "bounded_forward_time"
            marker_item["marker_event_at_ms"] = marker_event_at
            markers.append(marker_item)
        else:
            markers.append({
                "marker_id": f"manual-{index}", "kind": "manual", "at_ms": requested_at,
                "cue_id": None,
                "context_status": None, "pre_image_ids": [], "trigger_image_ids": [],
                "post_image_ids": [], "pre_frame_indices": [],
                "association_method": None,
                "evidence_gap": "手动标记没有唯一且时间受限的截图上下文匹配",
            })

    near_marker_index = 0
    matched_dispatches: Counter[str] = Counter()
    for event in events:
        if event["type"] != "audit":
            continue
        fields = _audit_fields(event.get("data", {}).get("message"), "NearZoneEvent")
        if fields is None:
            continue
        at_ms = _nonnegative_int(event.get("at_ms"))
        event_name = fields.get("event")
        if event_name != "NEAR_ENTER":
            continue
        near_marker_index += 1
        dispatch_candidates = [dispatch for dispatch in dispatch_by_id.values()
                               if dispatch.get("kind", "").upper() == "NEAR_ZONE"
                               and at_ms is not None
                               and abs(dispatch["dispatch_at_ms"] - at_ms) <= _NEAR_DISPATCH_AUDIT_TOLERANCE_MS]
        if len(dispatch_candidates) > 1:
            markers.append({
                "marker_id": f"event-{near_marker_index}", "kind": "event", "at_ms": at_ms,
                "cue_id": None, "context_status": None, "window_id": None,
                "pre_frame_indices": [], "pre_image_ids": [], "trigger_image_ids": [],
                "post_image_ids": [], "association_method": None,
                "evidence_gap": "NearZoneEvent 时间匹配到多个 CueDispatch；不推断 Cue ID",
            })
            continue
        dispatch = dispatch_candidates[0] if len(dispatch_candidates) == 1 else None
        cue_id = dispatch["cue_id"] if dispatch is not None else None
        if dispatch is not None:
            matched_dispatches[cue_id] += 1
            exact_contexts = [position for position, ctx in enumerate(context_rows)
                              if ctx.get("reason") == "near_enter"
                              and ctx.get("trigger_frame_index") in {
                                  frame["frame_index"] for frame in frames_by_observed.get(dispatch["created_at_ms"], [])
                              }]
            if not exact_contexts:
                exact_contexts = [position for position, ctx in enumerate(context_rows)
                                  if ctx.get("reason") == "near_enter"
                                  and ctx.get("trigger_frame_index") is None
                                  and ctx.get("requested_at_ms") is None
                                  and ctx.get("start_observed_at_ms") == dispatch["created_at_ms"]]
            context_index = exact_contexts[0] if len(exact_contexts) == 1 else None
            context = context_rows[context_index] if context_index is not None else None
            if context_index is not None:
                used_contexts.add(context_index)
            frame_matches = frames_by_observed.get(dispatch["created_at_ms"], [])
            trigger_frame = frame_matches[0]["frame_index"] if len(frame_matches) == 1 else None
            trigger_ids = [image["image_id"] for image in image_frames.get(trigger_frame, [])
                           if image.get("reason") == "near_enter"] if trigger_frame is not None else []
            marker_item = (_marker_from_context(
                f"event-{near_marker_index}", "event", at_ms, context,
                settings_pre, settings_post, cue_id=cue_id,
            ) if context is not None else {
                "marker_id": f"event-{near_marker_index}", "kind": "event", "at_ms": at_ms,
                "cue_id": cue_id, "context_status": None, "window_id": None,
                "pre_frame_indices": [], "pre_image_ids": [], "post_image_ids": [],
                "evidence_gap": "Cue 的创建时间没有精确匹配的 near_enter 截图上下文",
            })
            marker_item["trigger_image_ids"] = trigger_ids
            marker_item["dispatch_at_ms"] = dispatch["dispatch_at_ms"]
            marker_item["dispatch_audit_delta_ms"] = at_ms - dispatch["dispatch_at_ms"] if at_ms is not None else None
            marker_item["context_match_method"] = "exact_created_at_frame" if context is not None else None
            if len(dispatch_candidates) != 1:
                marker_item["evidence_gap"] = "NearZoneEvent 与 CueDispatch 时间匹配不唯一"
            elif not trigger_ids:
                marker_item["evidence_gap"] = "已关联 Cue，但创建帧没有 near_enter 清晰截图"
            markers.append(marker_item)
            continue

        outcome = fields.get("outcome")
        context_candidates = [position for position, ctx in enumerate(context_rows)
                              if ctx.get("reason") == "near_enter"
                              and position not in used_contexts
                              and outcome == "CATEGORY_DISABLED"
                              and at_ms is not None
                              and ctx.get("event_at_ms") is not None
                              and abs(ctx["event_at_ms"] - at_ms) <= _EVENT_CONTEXT_AUDIT_TOLERANCE_MS]
        context_index = context_candidates[0] if len(context_candidates) == 1 else None
        context = context_rows[context_index] if context_index is not None else None
        if context_index is not None:
            used_contexts.add(context_index)
        marker_item = (_marker_from_context(
            f"event-{near_marker_index}", "event", at_ms, context,
            settings_pre, settings_post,
        ) if context is not None else {
            "marker_id": f"event-{near_marker_index}", "kind": "event", "at_ms": at_ms,
            "cue_id": None, "context_status": None, "window_id": None,
            "pre_frame_indices": [], "pre_image_ids": [], "trigger_image_ids": [],
            "post_image_ids": [],
            "evidence_gap": "NearZoneEvent 没有可配对的 CueDispatch 或受限时间内的截图上下文",
        })
        marker_item["association_method"] = "bounded_time_approximation" if context is not None else None
        if outcome != "CATEGORY_DISABLED" and dispatch is None:
            marker_item["evidence_gap"] = "NearZoneEvent 未记录 CueDispatch；未推断 Cue ID"
        markers.append(marker_item)

    # A context may be recorded even when its audit event is missing. Preserve
    # those rows as evidence too; do not create a false event match.
    for index, context in enumerate(context_rows, start=1):
        if context.get("reason") not in {"near_enter", "user_marker", "user_marker_frame"}:
            continue
        if index - 1 not in used_contexts:
            markers.append(_marker_from_context(
                f"context-only-{index}", "context_only", context.get("start_observed_at_ms"),
                context, settings_pre, settings_post,
            ))
    for cue_id, count in matched_dispatches.items():
        if count > 1:
            for marker in markers:
                if marker.get("cue_id") == cue_id:
                    marker["context_match_method"] = None
                    marker["evidence_gap"] = "多个 NearZoneEvent 记录匹配到同一 CueDispatch"
    return context_rows, markers


def _marker_from_context(marker_id: str, kind: str, at_ms: int | None,
                         context: dict[str, Any], pre_window_ms: int | None,
                         post_window_ms: int | None, cue_id: str | None = None) -> dict[str, Any]:
    status = context.get("status") if context is not None else None
    if context is None:
        return {
            "marker_id": marker_id, "kind": kind, "at_ms": at_ms, "cue_id": cue_id,
            "context_status": None, "window_id": None,
            "pre_image_ids": [], "trigger_image_ids": [], "post_image_ids": [],
            "pre_frame_indices": [], "evidence_gap": "没有匹配的截图上下文",
        }
    return {
        "marker_id": marker_id,
        "kind": kind,
        "at_ms": at_ms,
        "cue_id": cue_id,
        "window_id": context.get("window_id"),
        "context_status": status,
        "context_reason": context.get("reason"),
        "pre_image_ids": context.get("pre_image_ids", []),
        "trigger_image_ids": context.get("trigger_image_ids", []),
        "post_image_ids": context.get("post_image_ids", []),
        "pre_frame_indices": context.get("pre_frame_indices", []),
        "pre_expected_frame_count": len(context.get("pre_frame_indices", [])),
        "pre_captured_frame_count": len(context.get("pre_captured_frame_indices", [])),
        "trigger_frame_index": context.get("trigger_frame_index"),
        "pre_window_ms": pre_window_ms,
        "post_window_ms": post_window_ms,
        "start_observed_at_ms": context.get("start_observed_at_ms"),
        "end_observed_at_ms": context.get("end_observed_at_ms"),
        "sampling_note": context.get("sampling_note", ""),
        "evidence_gap": None if status in {"ACCEPTED", "COALESCED"} else
        "上下文请求受限或未接受；前后窗口覆盖缺失或不完整",
    }


def _cue_items(dispatch_by_id: dict[str, dict[str, Any]], callbacks: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], Counter[str]]:
    by_id: dict[str, list[dict[str, Any]]] = defaultdict(list)
    results: Counter[str] = Counter()
    for callback in callbacks:
        by_id[callback["cue_id"]].append(callback)
        results[callback["result"]] += 1
    cue_ids = sorted(set(dispatch_by_id) | set(by_id))
    items = []
    for cue_id in cue_ids:
        dispatch = dispatch_by_id.get(cue_id)
        cue_callbacks = sorted(by_id.get(cue_id, []), key=lambda item: item["at_ms"])
        if dispatch is None:
            item: dict[str, Any] = {"cue_id": cue_id, "dispatch": None}
        else:
            item = {"cue_id": cue_id, "dispatch": dispatch}
        item["callbacks"] = cue_callbacks
        item["queue_replaced_count"] = sum(callback["result"] == "QUEUE_REPLACED" for callback in cue_callbacks)
        item["queue_evicted_count"] = sum(callback["result"] == "QUEUE_EVICTED" for callback in cue_callbacks)
        item["dropped_dispatch"] = bool(dispatch and dispatch["outcome"] == "DROPPED")
        items.append(item)
    return items, results


def analyze_diagnostic_zip(path: Path, latency_csv: Path | None = None) -> tuple[dict[str, Any], dict[str, str]]:
    """Analyze ZIP entries in place; only referenced JPEG bytes are read, never extracted."""
    archive_path = path.expanduser().resolve()
    if not archive_path.is_file():
        raise ValueError(f"diagnostic ZIP does not exist: {path}")
    digest = _sha256(archive_path)
    try:
        archive = zipfile.ZipFile(archive_path, "r")
    except (OSError, zipfile.BadZipFile) as error:
        raise ValueError(f"cannot open diagnostic ZIP: {path}") from error
    with archive:
        members = _validate_archive_members(archive)
        metadata = _read_json_member(archive, "metadata.json")
        if metadata.get("schema") != "sensefield.diagnostics" or metadata.get("schema_version") != 1:
            raise ValueError("expected sensefield.diagnostics schema version 1")
        session_id = metadata.get("session_id")
        if not isinstance(session_id, str) or not session_id:
            raise ValueError("metadata.session_id is missing")
        summary = _read_json_member(archive, "summary.json")
        summary_session = summary.get("session_id")
        if summary_session is not None and summary_session != session_id:
            raise ValueError("summary.json belongs to another session")
        events, event_counts = _read_events(archive, session_id)

        # Reuse the replay reader/statistics for frame ages and native timing.
        # A replay-specific limitation should remain visible but must not erase
        # an otherwise readable diagnostic report (for example, a zero-frame session).
        replay_timing = None
        replay_status: dict[str, Any] = {"status": "unavailable"}
        try:
            replay_data = diagnostic_replay.read_diagnostic_zip(archive_path)
            replay_report = diagnostic_replay.build_report(replay_data)
            replay_timing = replay_report["observed_timing"]
            replay_status = {
                "status": "statistics_reused",
                "frame_count": replay_report["source"]["frame_count"],
                "recorded_event_counts": replay_report["source"]["recorded_event_counts"],
            }
        except (ValueError, OSError, RuntimeError, AttributeError) as error:
            # Do not leak a local path or raw session identifier through an
            # exception message copied from another parser.
            replay_status = {"status": "unavailable", "reason": type(error).__name__}

        dispatch_by_id, callbacks, audit_counts = _parse_cues(events)
        cue_items, callback_results = _cue_items(dispatch_by_id, callbacks)
        image_records, inline_images, image_gaps = _image_assets(archive, members, events)

    processing, thermal, frame_rows = _frame_stats(events, summary, metadata, replay_timing)
    contexts, markers = _contexts_and_markers(
        events, image_records, dispatch_by_id, frame_rows, metadata
    )
    gaps: list[str] = list(image_gaps)
    last_state_value = summary.get("last_state")
    last_state = last_state_value if isinstance(last_state_value, dict) else {}
    if "last_state" in summary and not isinstance(last_state_value, dict):
        gaps.append("summary.last_state 不是对象，无法核对会话计数")
    if summary.get("interrupted") is True or summary.get("end_observed") is False:
        gaps.append("会话被中断或结束状态未观察到；实际停止时间和原因可能未知")
    if summary.get("diagnostic_error"):
        gaps.append("DiagnosticRecorder 记录了错误")
    if summary.get("dropped_events", 0):
        gaps.append("DiagnosticRecorder 有事件写入丢失")
    if summary.get("dropped_image_requests", 0):
        gaps.append("DiagnosticRecorder 有截图请求丢失")
    if summary.get("frame_count") is not None and summary.get("frame_count") != event_counts["frame"]:
        gaps.append("summary.frame_count 与 events.jsonl 帧记录数量不一致")
    if summary.get("image_count") is not None and summary.get("image_count") != event_counts["image"]:
        gaps.append("summary.image_count 与 events.jsonl 图像记录数量不一致")
    if processing["checkpoints_missing_load_mode"]:
        gaps.append("部分负载检查点没有 load_control_mode 记录")
    if replay_status["status"] == "unavailable":
        gaps.append("diagnostic_replay.py 未能完成帧年龄统计；本报告保留可直接读取的原始帧统计")
    detected_cues = _maybe_int(last_state.get("detected_cues"))
    if detected_cues is not None and detected_cues != len(dispatch_by_id):
        gaps.append("summary 检测 cue 数与可配对的 CueDispatch 数量不一致")
    for marker in markers:
        if marker.get("evidence_gap"):
            gaps.append(f"{marker['marker_id']}：{marker['evidence_gap']}")
    unknown_callbacks = sorted(set(callback["cue_id"] for callback in callbacks) - set(dispatch_by_id))
    if unknown_callbacks:
        gaps.append("有播放回调没有对应的 CueDispatch")

    latency: dict[str, Any] | None = None
    if latency_csv is not None:
        latency = _latency_report(latency_csv.expanduser().resolve(), dispatch_by_id)
        if latency["cue_id_coverage"]["unpaired_dispatch_ids"]:
            gaps.append("外部延迟 CSV 没有覆盖全部已记录的 CueDispatch cue ID")
        if any(pair["audio_ms"] is None for pair in latency["pairs"]):
            gaps.append("外部延迟 CSV 中有 cue 没有实际听到时间")

    app_version = {
        "name": metadata.get("version_name") if isinstance(metadata.get("version_name"), str) else None,
        "code": _maybe_int(metadata.get("version_code")),
    }
    profiles = [event.get("data", {}) for event in events if event["type"] == "profile"]
    profile_versions = [profile.get("version") for profile in profiles
                        if isinstance(profile.get("version"), str)]
    session_section = {
        "duration_ms": _maybe_int(summary.get("duration_ms")),
        "duration_lower_bound_ms": _maybe_int(summary.get("duration_lower_bound_ms")),
        "reason": summary.get("reason") if isinstance(summary.get("reason"), str) else None,
        "interrupted": summary.get("interrupted") if isinstance(summary.get("interrupted"), bool) else None,
        "end_observed": summary.get("end_observed") if isinstance(summary.get("end_observed"), bool) else None,
        "frame_count_reported": _maybe_int(summary.get("frame_count")),
        "image_count_reported": _maybe_int(summary.get("image_count")),
    }
    cue_report = {
        "detected_count_reported": detected_cues,
        "dispatch_count": len(dispatch_by_id),
        "callback_count": len(callbacks),
        "callback_results": dict(callback_results),
        "queue_replaced_count": sum(callback["result"] == "QUEUE_REPLACED" for callback in callbacks),
        "queue_evicted_count": sum(callback["result"] == "QUEUE_EVICTED" for callback in callbacks),
        "dropped_dispatch_count": sum(item["outcome"] == "DROPPED" for item in dispatch_by_id.values()),
        "dispatch_outcomes": dict(Counter(item["outcome"] for item in dispatch_by_id.values())),
        "items": cue_items,
    }
    report: dict[str, Any] = {
        "schema": REPORT_SCHEMA,
        "schema_version": 1,
        "source": {
            "archive_sha256": digest,
            "archive_bytes": archive_path.stat().st_size,
            "archive_schema": "sensefield.diagnostics/1",
            "recorded_event_counts": dict(event_counts),
            "cue_audit_record_counts": dict(audit_counts),
            "app_version": app_version,
            "profile_versions": profile_versions,
            "android_api_level": thermal["android_api_level"],
            "replay_statistics": replay_status,
            "privacy": {
                "device_model_and_nickname_included": False,
                "session_id_field_included": False,
                "cue_ids_include_session_prefix": True,
                "cue_ids_included_for_pairing": True,
            },
        },
        "session": session_section,
        "processing_and_load": processing,
        "thermal": thermal,
        "cues": cue_report,
        "image_evidence": {
            "image_event_count": len(image_records),
            "available_embedded_images": sum(item["available"] for item in image_records),
            "context_windows": contexts,
            "markers": markers,
            "images": image_records,
        },
        "external_latency": latency,
        "evidence_gaps": list(dict.fromkeys(gaps)),
        "acceptance": {
            "status": "not_assessed",
            "statement": "本报告只汇总已记录证据；它不判定产品验收，也不把请求、派发或播放回调当作实际听见。",
        },
        "limitations": [
            "CueDispatch 与 CuePlayback 记录反映请求、派发和应用回调，不证明玩家实际听到声音或感到震动。",
            "未记录的 CueEvent 细节、环境状态或外部录音时间不会在报告中补造。",
            "诊断截图是抽样 JPEG，不是连续录像，也不保证覆盖完整。",
        ],
    }
    # Raw pixel data is emitted only as JPEG data URIs inside the offline HTML
    # and is never extracted to disk.
    return report, inline_images


def _h(value: Any) -> str:
    return html.escape(str(value), quote=True)


def _display(value: Any) -> str:
    if value is None:
        return "未记录"
    if isinstance(value, bool):
        return "是" if value else "否"
    return _h(value)


def _image_html(image: dict[str, Any], inline: dict[str, str]) -> str:
    image_id = image["image_id"]
    if image_id not in inline:
        return f'<span class="missing">截图文件缺失或未嵌入：{_h(image_id)}</span>'
    caption = f"{image.get('kind', '图像')} · 帧 {image.get('frame_index')} · {image.get('reason')} · {image.get('observed_at_ms')} ms"
    uri = inline[image_id]
    return (f'<a class="image-link" href="{uri}" download="{_h(image_id)}.jpg">'
            f'<img loading="lazy" alt="{_h(caption)}" src="{uri}"></a>'
            f'<small>点击图片保存此截图 · {_h(caption)}</small>')


def render_html(report: dict[str, Any], inline_images: dict[str, str]) -> str:
    processing = report["processing_and_load"]
    thermal = report["thermal"]
    cues = report["cues"]
    image_data = report["image_evidence"]
    version = report["source"]["app_version"]
    html_images = {item["image_id"]: _image_html(item, inline_images)
                   for item in image_data["images"]}
    cue_anchor_by_id = {
        item["cue_id"]: "cue-" + hashlib.sha256(item["cue_id"].encode("utf-8")).hexdigest()[:16]
        for item in cues["items"]
    }
    marker_by_cue: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for marker in image_data["markers"]:
        if marker.get("cue_id"):
            marker_by_cue[marker["cue_id"]].append(marker)

    cue_rows = []
    for item in cues["items"]:
        dispatch = item.get("dispatch")
        if dispatch is None:
            requested = accepted = outcome = reason = "未匹配"
            dispatch_time = "未记录"
        else:
            requested = "、".join(dispatch["requested_channels"]) or "无"
            accepted = "、".join(dispatch["accepted_channels"]) or "无"
            outcome = dispatch["outcome"]
            reason = dispatch["drop_reason"]
            dispatch_time = dispatch["dispatch_delay_ms"]
        callbacks = "<br>".join(
            f"{_h(callback['channel'])}: {_h(callback['result'])} @ {_h(callback['at_ms'])} ms"
            f" (回调延迟 {_display(callback['playback_delay_ms'])} ms)"
            for callback in item["callbacks"]
        ) or "无回调记录"
        matching_markers = marker_by_cue.get(item["cue_id"], [])
        context_link = (f'<br><a href="#marker-{_h(matching_markers[0]["marker_id"])}">截图上下文</a>'
                        if len(matching_markers) == 1 else "")
        cue_rows.append(
            f'<tr id="{cue_anchor_by_id[item["cue_id"]]}">'
            f"<td><code>{_h(item['cue_id'])}</code>{context_link}</td>"
            f"<td>{_h(requested)}</td><td>{_h(accepted)}</td>"
            f"<td>{_h(outcome)} / {_h(reason)}</td>"
            f"<td>{_h(dispatch_time)} ms</td><td>{callbacks}</td>"
            f"<td>{_h(item['queue_replaced_count'])} / {_h(item['queue_evicted_count'])}</td>"
            "</tr>"
        )

    marker_rows = []
    for marker in image_data["markers"]:
        links = [*marker.get("pre_image_ids", []), *marker.get("trigger_image_ids", []), *marker.get("post_image_ids", [])]
        gallery = " ".join(f'<a href="#img-{_h(image_id)}">{_h(image_id)}</a>' for image_id in links) or "无截图链接"
        window = f"前 {len(marker.get('pre_image_ids', []))} / 触发 {len(marker.get('trigger_image_ids', []))} / 后 {len(marker.get('post_image_ids', []))} 张"
        cue_id = marker.get("cue_id")
        cue_link = (f'<a href="#{cue_anchor_by_id[cue_id]}"><code>{_h(cue_id)}</code></a>'
                    if cue_id in cue_anchor_by_id else "未记录")
        association = marker.get("context_match_method") or marker.get("association_method") or "未关联"
        marker_rows.append(
            f'<tr id="marker-{_h(marker["marker_id"])}">'
            f"<td>{_h(marker.get('kind'))}</td><td>{_h(marker.get('at_ms'))} ms</td>"
            f"<td>{cue_link}<br><small>{_h(association)}</small></td>"
            f"<td>{_h(marker.get('context_status'))}</td><td>{_h(window)}</td>"
            f"<td>{gallery}</td><td>{_h(marker.get('evidence_gap') or '—')}</td>"
            "</tr>"
        )

    image_cards = []
    for image in image_data["images"]:
        image_id = image["image_id"]
        image_cards.append(f'<figure id="img-{_h(image_id)}">{html_images[image_id]}</figure>')

    gaps = "".join(f"<li>{_h(value)}</li>" for value in report["evidence_gaps"]) or "<li>未发现已知完整性缺口；这不代表验证通过。</li>"
    mode_counts = "、".join(f"{_h(key)} {_h(value)} 次" for key, value in processing["load_control_modes"].items()) or "未记录"
    thermal_counts = "、".join(f"{_h(key)} {_h(value)} 次" for key, value in thermal["thermal_status_counts"].items()) or "未记录"
    latency_html = "<p>未提供外部 latency CSV。</p>"
    if report["external_latency"]:
        latency = report["external_latency"]
        coverage = latency["cue_id_coverage"]
        latency_html = (
            f"<p>外部录音配对 {latency['matched_pair_count']} 条；覆盖 CueDispatch "
            f"{coverage['csv_count']} / {coverage['dispatch_count']} 个 ID。未配对 ID："
            f"{_h(', '.join(coverage['unpaired_dispatch_ids']) or '无')}。</p>"
            "<p>此统计只依据人工填写的外部画面与音频时间；本地请求和回调不会替代该证据。</p>"
        )
    return f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>离线诊断报告</title><style>
body{{font:15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif;margin:2rem auto;padding:0 1rem;max-width:1180px;color:#1d2939;background:#f7f9fc}}
h1,h2{{color:#102a43}}section{{background:#fff;border:1px solid #d9e2ec;border-radius:10px;margin:1rem 0;padding:1rem 1.25rem;overflow:auto}}
.notice{{border-left:5px solid #c2410c;background:#fff7ed;padding:.7rem 1rem}}table{{border-collapse:collapse;width:100%;font-size:.9rem}}th,td{{border-bottom:1px solid #e5eaf0;text-align:left;padding:.5rem;vertical-align:top}}th{{background:#f0f4f8;position:sticky;top:0}}
code{{overflow-wrap:anywhere}}.gallery{{display:grid;grid-template-columns:repeat(auto-fit,minmax(250px,1fr));gap:1rem}}figure{{margin:0;background:#f8fafc;padding:.5rem;border:1px solid #e5eaf0;border-radius:8px}}img{{max-width:100%;height:auto;display:block}}small{{display:block;color:#52606d;margin-top:.3rem}}.missing{{color:#9b1c1c}}.muted{{color:#52606d}}
</style></head><body>
<h1>离线诊断报告</h1>
<p>应用版本 {_display(version['name'])}（版本码 {_display(version['code'])}） · ZIP SHA-256 <code>{_h(report['source']['archive_sha256'])}</code></p>
<p class="notice"><strong>解释边界：</strong>requested、dispatch 和应用回调不表示玩家实际听到声音或感到震动。验收状态：未评估。</p>
<section><h2>会话与负载</h2><table><tbody>
<tr><th>时长</th><td>{_display(report['session']['duration_ms'])} ms</td><th>中断</th><td>{_display(report['session']['interrupted'])}</td></tr>
<tr><th>事件帧数</th><td>{_display(processing['frame_count_in_events'])}</td><th>处理帧数</th><td>{_display(processing['processed_frames'])}</td></tr>
<tr><th>处理时长 P50 / P95 / 最大</th><td>{_display(processing['native_processing_ms']['p50'])} / {_display(processing['native_processing_ms']['p95'])} / {_display(processing['native_processing_ms']['max'])} ms</td><th>帧间隔 P95 / 最大</th><td>{_display(processing['completion_gap_ms']['p95'])} / {_display(processing['completion_gap_ms']['max'])} ms</td></tr>
<tr><th>电池温度 min / max</th><td>{_display(thermal['battery_temperature_c']['min'])} / {_display(thermal['battery_temperature_c']['max'])} °C</td><th>系统热状态样本</th><td>{thermal_counts}</td></tr>
<tr><th>负载模式检查点</th><td>{mode_counts}</td><th>跳过帧 / 最大处理间隔</th><td>{_display(processing['load_skipped_frames'])} / {_display(processing['max_processed_gap_ms'])} ms</td></tr>
<tr><th>诊断画面复制总量 / 均值 / 最大</th><td>{_display(processing['image_diagnostics']['context_copy_total_ms'])} / {_display(processing['image_diagnostics']['context_copy_mean_ms'])} / {_display(processing['image_diagnostics']['context_copy_max_ms'])} ms</td><th>上下文窗口 / 限频</th><td>{_display(processing['image_diagnostics']['context_windows'])} / {_display(processing['image_diagnostics']['context_rate_limited'])}</td></tr>
<tr><th>图像队列峰值</th><td>{_display(processing['image_diagnostics']['peak_image_frames'])} 帧 / {_display(processing['image_diagnostics']['peak_image_bytes'])} 字节</td><th>已复制样本</th><td>{_display(processing['image_diagnostics']['copied_context_frames'])} 帧</td></tr>
</tbody></table><p class="muted">设备型号、昵称、制造商和独立的 session_id 字段未复制。Cue ID 保留原值用于 CSV 配对，其中可能带 session 前缀。</p></section>
<section><h2>Cue 按 ID 汇总</h2><p>检测数（summary）：{_display(cues['detected_count_reported'])}；记录到 dispatch：{cues['dispatch_count']}；播放回调：{cues['callback_count']}；队列替换：{cues['queue_replaced_count']}；队列淘汰：{cues['queue_evicted_count']}；dispatch 丢弃：{cues['dropped_dispatch_count']}。</p>
<table><thead><tr><th>Cue ID</th><th>请求通道</th><th>接受通道</th><th>派发 / 原因</th><th>派发延迟</th><th>应用回调</th><th>替换 / 淘汰</th></tr></thead><tbody>{''.join(cue_rows)}</tbody></table></section>
<section><h2>事件与手动标记截图覆盖</h2><table><thead><tr><th>类型</th><th>时间</th><th>关联 Cue / 方法</th><th>上下文状态</th><th>前 / 触发 / 后</th><th>图像链接</th><th>证据缺口</th></tr></thead><tbody>{''.join(marker_rows)}</tbody></table>
<h3>本地内嵌 JPEG</h3><div class="gallery">{''.join(image_cards)}</div></section>
<section><h2>外部录音延迟</h2>{latency_html}</section>
<section><h2>证据缺口与限制</h2><ul>{gaps}</ul><ul>{''.join(f'<li>{_h(value)}</li>' for value in report['limitations'])}</ul></section>
<footer class="muted"><small>由本机 mapassist.diagnostic_report 生成。HTML 内嵌引用到的 JPEG，可离线打开。Cue ID 保留原值以便与 CSV 配对，可能含 session 前缀。</small></footer>
</body></html>\n"""


def write_outputs(report: dict[str, Any], inline_images: dict[str, str], output_dir: Path) -> tuple[Path, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / "diagnostic-report.json"
    html_path = output_dir / "diagnostic-report.html"
    # Internal frame rows are used only during generation and are not part of
    # the public JSON schema.
    public_report = {key: value for key, value in report.items() if not key.startswith("_")}
    json_path.write_text(json.dumps(public_report, ensure_ascii=False, indent=2,
                                    sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")
    html_path.write_text(render_html(public_report, inline_images), encoding="utf-8")
    return json_path, html_path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("diagnostics_zip", type=Path, help="DiagnosticRecorder ZIP archive")
    parser.add_argument("--output-dir", type=Path, required=True,
                        help="directory for diagnostic-report.json and diagnostic-report.html")
    parser.add_argument("--latency-csv", type=Path,
                        help="optional externally annotated evidence/audio times CSV")
    args = parser.parse_args(argv)
    try:
        report, inline_images = analyze_diagnostic_zip(args.diagnostics_zip, args.latency_csv)
        json_path, html_path = write_outputs(report, inline_images, args.output_dir)
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as error:
        parser.error(str(error))
    print(f"JSON: {json_path}")
    print(f"HTML: {html_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
