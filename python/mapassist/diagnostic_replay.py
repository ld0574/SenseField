"""Offline age analysis and native event-layer replay for Sensefield diagnostics."""

from __future__ import annotations

import argparse
import ctypes as C
import json
import math
import sys
import zipfile
from collections import Counter
from pathlib import Path
from typing import Any

from mapassist import native


REPORT_SCHEMA = "mapassist.diagnostic_replay"
REPLAY_BUDGETS_MS = (250, 500)
RESET_BLACK_FRAME = "CaptureBlackFrame state=WAITING"


def _integer(value: Any, field: str, *, minimum: int | None = None) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValueError(f"{field} must be an integer")
    if minimum is not None and value < minimum:
        raise ValueError(f"{field} must be at least {minimum}")
    return value


def _number(value: Any, field: str, *, minimum: float | None = None,
            maximum: float | None = None) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{field} must be a number")
    result = float(value)
    if not math.isfinite(result):
        raise ValueError(f"{field} must be finite")
    if minimum is not None and result < minimum:
        raise ValueError(f"{field} must be at least {minimum}")
    if maximum is not None and result > maximum:
        raise ValueError(f"{field} must be at most {maximum}")
    return result


def _read_member(archive: zipfile.ZipFile, member: str) -> Any:
    try:
        raw = archive.read(member)
    except KeyError as error:
        raise ValueError(f"diagnostic ZIP is missing {member}") from error
    try:
        return json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"diagnostic ZIP has invalid JSON in {member}") from error


def _valid_observation(item: Any, frame_now_ms: int, frame_index: int) -> dict[str, Any]:
    prefix = f"frame[{frame_index}].raw_observations"
    if not isinstance(item, dict):
        raise ValueError(f"{prefix} entries must be objects")
    kind = _integer(item.get("kind"), f"{prefix}.kind", minimum=1)
    direction = _integer(item.get("direction"), f"{prefix}.direction", minimum=0)
    if kind not in range(1, 7):
        raise ValueError(f"{prefix}.kind must be in 1..6")
    if direction not in range(5):
        raise ValueError(f"{prefix}.direction must be in 0..4")
    box = item.get("bbox_ppm")
    if not isinstance(box, list) or len(box) != 4:
        raise ValueError(f"{prefix}.bbox_ppm must contain x, y, width, height")
    values = [_integer(value, f"{prefix}.bbox_ppm[{i}]", minimum=0)
              for i, value in enumerate(box)]
    if any(value > 1_000_000 for value in values):
        raise ValueError(f"{prefix}.bbox_ppm values must be at most 1000000")
    if values[2] == 0 or values[3] == 0 or values[0] + values[2] > 1_000_000 or \
            values[1] + values[3] > 1_000_000:
        raise ValueError(f"{prefix}.bbox_ppm must be a positive box within the frame")
    confidence = _integer(item.get("confidence_milli"),
                          f"{prefix}.confidence_milli", minimum=0)
    if confidence > 1000:
        raise ValueError(f"{prefix}.confidence_milli must be at most 1000")
    observed_at = _integer(item.get("observed_at_ms"), f"{prefix}.observed_at_ms",
                           minimum=0)
    # Future observations remain valid input: ma_engine_step rejects them using
    # its own timestamp check, and the report preserves the original value.
    return {"kind": kind, "direction": direction, "bbox_ppm": values,
            "confidence_milli": confidence, "observed_at_ms": observed_at,
            "age_at_engine_ms": frame_now_ms - observed_at}


def read_diagnostic_zip(path: Path) -> dict[str, Any]:
    """Read only JSON members; image payloads are never extracted or opened."""
    try:
        archive = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as error:
        raise ValueError(f"cannot open diagnostic ZIP: {path}") from error
    with archive:
        metadata = _read_member(archive, "metadata.json")
        if not isinstance(metadata, dict) or metadata.get("schema") != "sensefield.diagnostics" or \
                metadata.get("schema_version") != 1:
            raise ValueError("expected sensefield.diagnostics schema version 1")
        session_id = metadata.get("session_id")
        if not isinstance(session_id, str) or not session_id:
            raise ValueError("metadata.session_id is missing")
        summary = _read_member(archive, "summary.json")
        if not isinstance(summary, dict):
            raise ValueError("summary.json must contain an object")
        frames: list[dict[str, Any]] = []
        resets: list[dict[str, Any]] = []
        profile_records: list[dict[str, Any]] = []
        event_counts: Counter[str] = Counter()
        try:
            event_stream = archive.open("events.jsonl", "r")
        except KeyError as error:
            raise ValueError("diagnostic ZIP is missing events.jsonl") from error
        with event_stream:
            for line_number, raw in enumerate(event_stream, start=1):
                if not raw.strip():
                    continue
                try:
                    event = json.loads(raw)
                except (UnicodeDecodeError, json.JSONDecodeError) as error:
                    raise ValueError(f"events.jsonl line {line_number} is invalid JSON") from error
                if not isinstance(event, dict):
                    raise ValueError(f"events.jsonl line {line_number} must be an object")
                event_type = event.get("type")
                if not isinstance(event_type, str):
                    raise ValueError(f"events.jsonl line {line_number} has no event type")
                event_counts[event_type] += 1
                event_session = event.get("session_id")
                if event_session is not None and event_session != session_id:
                    raise ValueError(f"events.jsonl line {line_number} belongs to another session")
                data = event.get("data", {})
                if event_type == "profile":
                    if not isinstance(data, dict):
                        raise ValueError("profile.data must be an object")
                    profile_records.append(data)
                elif event_type == "frame":
                    if not isinstance(data, dict):
                        raise ValueError("frame.data must be an object")
                    index = _integer(data.get("frame_index"), "frame.frame_index", minimum=0)
                    now = _integer(data.get("engine_at_ms"),
                                   f"frame[{index}].engine_at_ms", minimum=0)
                    observed = _integer(data.get("observed_at_ms"),
                                        f"frame[{index}].observed_at_ms", minimum=0)
                    width = _integer(data.get("frame_width"), f"frame[{index}].frame_width",
                                     minimum=1)
                    height = _integer(data.get("frame_height"), f"frame[{index}].frame_height",
                                      minimum=1)
                    latency = _integer(data.get("native_micros"),
                                       f"frame[{index}].native_micros", minimum=0)
                    raw_observations = data.get("raw_observations", [])
                    if not isinstance(raw_observations, list):
                        raise ValueError(f"frame[{index}].raw_observations must be an array")
                    observations = [_valid_observation(item, now, index)
                                    for item in raw_observations]
                    count = data.get("observation_count")
                    if count is not None and _integer(
                            count, f"frame[{index}].observation_count", minimum=0) != len(observations):
                        raise ValueError(f"frame[{index}] observation_count does not match raw data")
                    locator_state = data.get("locator_state")
                    if locator_state is not None:
                        locator_state = _integer(locator_state,
                                                 f"frame[{index}].locator_state")
                    frames.append({"frame_index": index, "engine_at_ms": now,
                                   "observed_at_ms": observed, "frame_age_ms": now - observed,
                                   "native_micros": latency, "width": width, "height": height,
                                   "locator_state": locator_state,
                                   "observations": observations})
                elif event_type == "audit" and isinstance(data, dict):
                    message = data.get("message")
                    if isinstance(message, str) and (
                            message.startswith("CaptureResized ") or
                            message.startswith(RESET_BLACK_FRAME)):
                        at_ms = _integer(event.get("at_ms"),
                                         f"events.jsonl line {line_number}.at_ms", minimum=0)
                        cause = ("capture_resized" if message.startswith("CaptureResized ")
                                 else "capture_black_frame_waiting")
                        resets.append({"at_ms": at_ms, "cause": cause, "message": message})
        if len(profile_records) > 1:
            raise ValueError("diagnostic ZIP has multiple profile records; replay is ambiguous")
        if not frames:
            raise ValueError("diagnostic ZIP contains no frame records")
        expected_frames = summary.get("frame_count")
        if expected_frames is not None and _integer(expected_frames, "summary.frame_count", minimum=0) != len(frames):
            raise ValueError("summary.frame_count does not match frame records")
        return {"session_id": session_id, "metadata": metadata, "summary": summary,
                "profile_event": profile_records[0] if profile_records else None,
                "frames": frames, "resets": sorted(resets, key=lambda value: value["at_ms"]),
                "event_counts": dict(event_counts)}


def _percentile(values: list[int], percentile: float) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(percentile * len(ordered)) - 1)]


def _distribution(values: list[int], unit: str) -> dict[str, Any]:
    return {"count": len(values), "unit": unit,
            "min": min(values) if values else None,
            "p50": _percentile(values, 0.50),
            "p95": _percentile(values, 0.95),
            "p99": _percentile(values, 0.99),
            "max": max(values) if values else None}


def _age_rate(values: list[int], budget_ms: int) -> dict[str, Any]:
    over = sum(value > budget_ms for value in values)
    return {"sample_count": len(values), "over_budget_count": over,
            "over_budget_rate": over / len(values) if values else None,
            "over_budget_rate_percent": round(100 * over / len(values), 2) if values else None,
            "rule": f"age_ms > {budget_ms}"}


def _logged_profile_values(profile_event: dict[str, Any] | None,
                           profile: dict[str, Any]) -> tuple[native.RelationConfig | None,
                                                            native.Rect | None, dict[str, str]]:
    rois = profile.get("rois")
    log_rois = profile_event.get("rois_xywh") if profile_event else None
    if isinstance(log_rois, list) and len(log_rois) == 16:
        body_values = log_rois[12:16]
        roi_source = "diagnostic profile rois_xywh[12:16]"
    else:
        direction_roi = rois.get("minimap_direction") if isinstance(rois, dict) else None
        body_values = direction_roi
        roi_source = "provided profile rois.minimap_direction"
    map_body = None
    if isinstance(body_values, list) and len(body_values) == 4:
        vals = [_number(value, f"minimap_direction[{i}]", minimum=0, maximum=1)
                for i, value in enumerate(body_values)]
        if vals[2] <= 0 or vals[3] <= 0 or vals[0] + vals[2] > 1.001 or \
                vals[1] + vals[3] > 1.001:
            raise ValueError("minimap_direction must be a positive normalized rectangle")
        map_body = native.Rect(*vals)
    sources = {"map_body": roi_source}
    sources["map_valid"] = (
        "frame locator_state is -1 (locator disabled) or 1/2 (locked/held); "
        "state 0 is invalid"
    )

    relation: native.RelationConfig | None = None
    logged_floats = profile_event.get("relation_floats") if profile_event else None
    logged_ints = profile_event.get("relation_ints") if profile_event else None
    if profile_event and (logged_floats is None or logged_ints is None):
        # A logged null means this session explicitly had the relation layer off.
        if "relation_floats" in profile_event or "relation_ints" in profile_event:
            sources["relation"] = "disabled in diagnostic profile event"
            return None, map_body, sources
    if logged_floats is not None or logged_ints is not None:
        if not isinstance(logged_floats, list) or len(logged_floats) != 5 or \
                not isinstance(logged_ints, list) or len(logged_ints) != 5:
            raise ValueError("profile relation_floats/relation_ints must each contain five values")
        relation_section = {
            "schema": native.RELATION_SCHEMA, "schema_version": 1, "enabled": True,
            "enter_radius": logged_floats[0], "exit_radius": logged_floats[1],
            "sector_hysteresis_deg": logged_floats[2], "adjacent_ratio": logged_floats[3],
            "tie_ratio": logged_floats[4], "confirm_hits": logged_ints[0],
            "rearm_ms": logged_ints[1], "short_gap_ms": logged_ints[2],
            "pause_min_gap_ms": logged_ints[3], "max_freshness_ms": logged_ints[4],
        }
        pseudo_profile = {"minimap_relation": relation_section,
                          "rois": {"minimap_direction": [0.0, 0.0, 1.0, 1.0]}}
        relation = native.relation_config_from_profile(pseudo_profile)
        sources["relation"] = "diagnostic profile relation_floats/relation_ints"
    else:
        relation = native.relation_config_from_profile(profile)
        sources["relation"] = "provided profile minimap_relation" if relation else "disabled"
    return relation, map_body, sources


def _resolve_engine_config(profile: dict[str, Any],
                           profile_event: dict[str, Any] | None,
                           budget_ms: int) -> tuple[native.EngineConfig, dict[str, str]]:
    events = profile.get("events")
    if not isinstance(events, dict):
        raise ValueError("provided profile must contain an events object")
    required = ("min_global_gap_ms", "minimap_min_gap_ms",
                "min_hits_in_three_frames", "reset_after_missing_frames")
    for key in required:
        if key not in events:
            raise ValueError(f"provided profile events is missing {key}")
    confidence: float | None = None
    confidence_source = "provided profile events.min_confidence"
    if "min_confidence" in events:
        confidence = _number(events["min_confidence"], "events.min_confidence",
                             minimum=0, maximum=1)
    thresholds = profile_event.get("class_thresholds") if profile_event else None
    if confidence is None:
        if isinstance(thresholds, list) and thresholds:
            confidence = min(_number(value, f"profile.class_thresholds[{i}]",
                                     minimum=0, maximum=1)
                             for i, value in enumerate(thresholds))
            confidence_source = "approximation: minimum diagnostic profile class_thresholds"
        elif profile_event and "confidence" in profile_event:
            confidence = _number(profile_event["confidence"], "profile.confidence",
                                 minimum=0, maximum=1)
            confidence_source = "approximation: diagnostic profile confidence"
        else:
            raise ValueError("profile provides no min_confidence or diagnostic class threshold")
    values: dict[str, Any] = {"min_confidence": confidence,
                              "max_observation_age_ms": budget_ms}
    for key in required:
        values[key] = _integer(events[key], f"events.{key}", minimum=0)
    values["min_hits_in_three_frames"] = _integer(
        values["min_hits_in_three_frames"], "events.min_hits_in_three_frames", minimum=1)
    values["reset_after_missing_frames"] = _integer(
        values["reset_after_missing_frames"], "events.reset_after_missing_frames", minimum=1)
    sources = {"min_confidence": confidence_source,
               "max_observation_age_ms": "scenario override",
               "min_global_gap_ms": "provided profile events.min_global_gap_ms",
               "minimap_min_gap_ms": "provided profile events.minimap_min_gap_ms",
               "min_hits_in_three_frames": "provided profile events.min_hits_in_three_frames",
               "reset_after_missing_frames": "provided profile events.reset_after_missing_frames"}
    return native.EngineConfig(values["min_confidence"], values["max_observation_age_ms"],
                               values["min_global_gap_ms"], values["minimap_min_gap_ms"],
                               values["min_hits_in_three_frames"],
                               values["reset_after_missing_frames"]), sources


def _locator_valid(frame: dict[str, Any]) -> bool:
    state = frame["locator_state"]
    # -1 is the native bridge's marker for a profile without the locator.
    # With the locator active, only LOCKED (1) and HELD (2) supply a usable map.
    return state == -1 or state in (1, 2)


def _native_observations(frame: dict[str, Any]) -> Any:
    observations = frame["observations"]
    buffer = (native.Observation * max(1, len(observations)))()
    for index, item in enumerate(observations):
        x, y, width, height = item["bbox_ppm"]
        buffer[index] = native.Observation(
            item["kind"], item["direction"],
            native.Rect(x / 1_000_000.0, y / 1_000_000.0,
                        width / 1_000_000.0, height / 1_000_000.0),
            item["confidence_milli"] / 1000.0, item["observed_at_ms"])
    return buffer


def _replay_budget(frames: list[dict[str, Any]], resets: list[dict[str, Any]],
                   profile_event: dict[str, Any] | None, profile: dict[str, Any],
                   library_path: Path | None, budget_ms: int,
                   relation_config: native.RelationConfig | None,
                   map_body: native.Rect | None) -> dict[str, Any]:
    config, config_sources = _resolve_engine_config(profile, profile_event, budget_ms)
    lib = native.load_library(library_path)
    engine = lib.ma_engine_create(C.byref(config))
    if not engine:
        raise RuntimeError("native event engine rejected the provided profile")
    relation = native.Relation(lib, relation_config) if relation_config is not None else None
    cue_counts: Counter[str] = Counter()
    relation_counts: Counter[str] = Counter()
    cue_examples: list[dict[str, Any]] = []
    relation_examples: list[dict[str, Any]] = []
    entity_samples: Counter[str] = Counter()
    eligible_observations = 0
    next_reset = 0
    applied_resets: Counter[str] = Counter()
    try:
        for frame in frames:
            now_ms = frame["engine_at_ms"]
            while next_reset < len(resets) and resets[next_reset]["at_ms"] <= now_ms:
                lib.ma_engine_reset(engine)
                if relation is not None:
                    relation.reset()
                applied_resets[resets[next_reset]["cause"]] += 1
                next_reset += 1
            items = frame["observations"]
            eligible_observations += sum(
                item["confidence_milli"] / 1000.0 >= config.min_confidence and
                item["observed_at_ms"] <= now_ms and
                now_ms - item["observed_at_ms"] <= budget_ms for item in items)
            observation_buffer = _native_observations(frame)
            cue_buffer = (native.Cue * 4)()
            cue_count = lib.ma_engine_step(engine, observation_buffer, len(items), now_ms,
                                           cue_buffer, len(cue_buffer))
            if cue_count < 0 or cue_count > len(cue_buffer):
                raise RuntimeError("ma_engine_step returned an invalid cue count")
            for index in range(cue_count):
                cue = native.cue_dict(cue_buffer[index])
                cue_counts[cue["kind"]] += 1
                if len(cue_examples) < 100:
                    cue_examples.append({"frame_index": frame["frame_index"], **cue})
            entities_buffer = (native.TrackedEntity * native.MA_MAX_TRACKED_ENTITIES)()
            entity_count = lib.ma_engine_read_tracked_entities(
                engine, entities_buffer, len(entities_buffer))
            if entity_count < 0 or entity_count > len(entities_buffer):
                raise RuntimeError("ma_engine_read_tracked_entities returned an invalid count")
            for index in range(entity_count):
                entity = entities_buffer[index]
                entity_samples["minimap_enemy" if entity.entity_kind == native.MA_MINIMAP_ENEMY
                               else "minimap_player" if entity.entity_kind == native.MA_MINIMAP_PLAYER
                               else str(entity.entity_kind)] += 1
            if relation is not None:
                if map_body is None:
                    raise ValueError("near-zone replay requires a map-body ROI")
                output = relation.update(entities_buffer, entity_count, map_body,
                                         _locator_valid(frame), frame["width"],
                                         frame["height"], now_ms)
                if output["event"]:
                    relation_counts[output["event"]] += 1
                    if len(relation_examples) < 100:
                        relation_examples.append({"frame_index": frame["frame_index"],
                                                  "engine_at_ms": now_ms, **output})
    finally:
        if relation is not None:
            relation.close()
        lib.ma_engine_destroy(engine)
    return {"budget_ms": budget_ms,
            "eligible_observation_count": eligible_observations,
            "native_cues": {"count": sum(cue_counts.values()),
                            "by_kind": dict(cue_counts), "events": cue_examples},
            "near_zone": {"enabled": relation_config is not None,
                          "event_count": sum(relation_counts.values()),
                          "events_by_kind": dict(relation_counts),
                          "events": relation_examples},
            "tracked_entity_samples": dict(entity_samples),
            "state_resets_applied": dict(applied_resets),
            "engine_config_sources": config_sources}


def build_report(archive_data: dict[str, Any], profile_path: Path | None = None,
                 library_path: Path | None = None) -> dict[str, Any]:
    frames = archive_data["frames"]
    frame_ages = [frame["frame_age_ms"] for frame in frames]
    observation_ages = [item["age_at_engine_ms"] for frame in frames
                        for item in frame["observations"]]
    latencies = [frame["native_micros"] for frame in frames]
    timing: dict[str, Any] = {
        "frames": len(frames),
        "observations": len(observation_ages),
        "frame_age_ms": _distribution(frame_ages, "milliseconds"),
        "observation_age_ms": _distribution(observation_ages, "milliseconds"),
        "logged_native_latency": _distribution(latencies, "microseconds"),
        "age_budget_comparison": {
            str(budget): {"frames": _age_rate(frame_ages, budget),
                          "observations": _age_rate(observation_ages, budget)}
            for budget in REPLAY_BUDGETS_MS},
    }
    report: dict[str, Any] = {
        "schema": REPORT_SCHEMA,
        "schema_version": 1,
        "session_id": archive_data["session_id"],
        "source": {"archive_schema": "sensefield.diagnostics/1",
                   "frame_count": len(frames),
                   "recorded_event_counts": archive_data["event_counts"],
                   "resets_detected": [{"at_ms": value["at_ms"],
                                        "cause": value["cause"]}
                                       for value in archive_data["resets"]]},
        "observed_timing": timing,
        "replay": {"status": "statistics_only",
                   "reason": "pass --profile with the full profile used for this session to replay"},
        "limitations": [
            "Replay is counterfactual at the native event and relation layers only.",
            "It cannot verify real audio delivery, touch behavior, or whether image detections were visually correct.",
            "Logged bbox_ppm and confidence_milli are quantized; detections near confidence, geometry, or age boundaries can change after reconstruction.",
            "Replay keeps logged observation and engine timestamps unchanged; only the max observation age budget changes.",
            "Logged native_micros is the device's recorded native processing latency, not the offline replay runtime.",
            "Native and near-zone replay events are not Android dispatch decisions or actual reminder counts; Java dense-combat arbitration and channel playback are not replayed.",
        ],
    }
    if profile_path is None:
        return report
    try:
        profile = json.loads(profile_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"cannot read profile JSON: {profile_path}") from error
    if not isinstance(profile, dict):
        raise ValueError("provided profile must contain a JSON object")
    relation_config, map_body, sources = _logged_profile_values(
        archive_data["profile_event"], profile)
    identity = archive_data["profile_event"] or {}
    archive_name = identity.get("name")
    archive_version = identity.get("version")
    supplied_name = profile.get("name")
    supplied_version = profile.get("profile_version")
    matched: bool | None = None
    if archive_name is not None and supplied_name is not None and \
            archive_version is not None and supplied_version is not None:
        matched = archive_name == supplied_name and archive_version == supplied_version
    replay_runs = [_replay_budget(frames, archive_data["resets"],
                                  archive_data["profile_event"], profile,
                                  library_path, budget, relation_config, map_body)
                   for budget in REPLAY_BUDGETS_MS]
    report["replay"] = {
        "status": "completed",
        "profile_identity": {"archive_name": archive_name,
                             "archive_version": archive_version,
                             "provided_name": supplied_name,
                             "provided_version": supplied_version,
                             "matches": matched},
        "configuration_sources": sources,
        "timestamps_rewritten": False,
        "runs": replay_runs,
    }
    if matched is False:
        report["replay"]["warnings"] = [
            "Provided profile identity differs from the ZIP profile; engine fields absent "
            "from the ZIP may not match the original session."
        ]
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Analyze age/latency in a Sensefield diagnostics ZIP and optionally replay native event logic.")
    parser.add_argument("diagnostics_zip", type=Path,
                        help="sensefield-diag-*.zip; images are not extracted")
    parser.add_argument("--profile", type=Path,
                        help="full profile JSON for 250/500 ms native event-layer replay")
    parser.add_argument("--library", type=Path,
                        help="libmapassist shared library (defaults to build/native)")
    parser.add_argument("--output", type=Path,
                        help="write report JSON to this path (default: stdout)")
    args = parser.parse_args(argv)
    try:
        report = build_report(read_diagnostic_zip(args.diagnostics_zip),
                              args.profile, args.library)
        payload = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
        if args.output:
            args.output.write_text(payload, encoding="utf-8")
        else:
            sys.stdout.write(payload)
    except (ValueError, OSError, RuntimeError, AttributeError) as error:
        parser.error(str(error))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
