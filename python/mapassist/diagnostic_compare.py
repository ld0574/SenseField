"""Compare two sanitized diagnostic reports offline without making a verdict."""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import math
import re
import zipfile
from pathlib import Path
from typing import Any

from mapassist.diagnostic_report import REPORT_SCHEMA as DIAGNOSTIC_REPORT_SCHEMA
from mapassist.diagnostic_report import analyze_diagnostic_zip


COMPARE_SCHEMA = "mapassist.diagnostic_compare"
COMPARE_SCHEMA_VERSION = 1
CONDITIONS_SCHEMA = "mapassist.diagnostic_compare.conditions"
CONDITIONS_SCHEMA_VERSION = 1
MAX_REPORT_JSON_BYTES = 16 * 1024 * 1024
MAX_CONDITIONS_JSON_BYTES = 64 * 1024
MIN_SESSION_DURATION_MS = 15 * 60 * 1000
_SHA256_RE = re.compile(r"[0-9a-f]{64}\Z")
_VERSION_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9.+_-]{0,63}\Z")
_ANONYMOUS_CODE_RE = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,31}\Z")
_SAFE_CATEGORY_RE = re.compile(r"[A-Z][A-Z0-9_]{0,31}\Z")
_THERMAL_STATUS_NAMES = frozenset({
    "UNKNOWN", "UNAVAILABLE", "NONE", "LIGHT", "MODERATE", "SEVERE",
    "CRITICAL", "EMERGENCY", "SHUTDOWN",
})
_UUID_RE = re.compile(
    r"(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
    r"[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f])"
)


def _reject_constant(value: str) -> None:
    raise ValueError(f"JSON contains a non-finite number: {value}")


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"JSON contains a duplicate key: {key}")
        result[key] = value
    return result


def _parse_json_bytes(raw: bytes, label: str) -> Any:
    try:
        value = json.loads(raw, parse_constant=_reject_constant, object_pairs_hook=_unique_object)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError(f"{label} is not valid UTF-8 JSON") from error
    _reject_non_finite(value, label)
    return value


def _reject_non_finite(value: Any, path: str = "report") -> None:
    if isinstance(value, float) and not math.isfinite(value):
        raise ValueError(f"{path} contains a non-finite number")
    if isinstance(value, dict):
        for key, item in value.items():
            _reject_non_finite(item, f"{path}.{key}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            _reject_non_finite(item, f"{path}[{index}]")


def _is_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _number_or_none(value: Any, field: str, *, nonnegative: bool = True) -> None:
    if value is None:
        return
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError(f"{field} must be a finite number or null")
    if not math.isfinite(float(value)):
        raise ValueError(f"{field} must be finite")
    if nonnegative and value < 0:
        raise ValueError(f"{field} must be nonnegative")


def _integer_or_none(value: Any, field: str, *, nonnegative: bool = True) -> None:
    if value is None:
        return
    if not _is_int(value):
        raise ValueError(f"{field} must be an integer or null")
    if nonnegative and value < 0:
        raise ValueError(f"{field} must be nonnegative")


def _object(value: Any, field: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ValueError(f"{field} must be an object")
    return value


def _require_fields(value: dict[str, Any], names: tuple[str, ...], field: str) -> None:
    missing = [name for name in names if name not in value]
    if missing:
        raise ValueError(f"{field} is missing required fields: {', '.join(missing)}")


def _validate_distribution(value: Any, field: str, unit: str) -> dict[str, Any]:
    distribution = _object(value, field)
    _require_fields(distribution, ("count", "unit", "min", "p50", "p95", "max"), field)
    if distribution["unit"] != unit:
        raise ValueError(f"{field}.unit must be {unit!r}")
    _integer_or_none(distribution["count"], f"{field}.count")
    if distribution["count"] is None:
        raise ValueError(f"{field}.count must be an integer")
    for name in ("min", "p50", "p95", "max"):
        _number_or_none(distribution[name], f"{field}.{name}")
    if distribution["count"] == 0 and any(distribution[name] is not None
                                           for name in ("min", "p50", "p95", "max")):
        raise ValueError(f"{field} has zero samples but non-null statistics")
    return distribution


def validate_report(report: Any) -> dict[str, Any]:
    """Validate the public v1 diagnostic report shape used by this comparator."""
    root = _object(report, "report")
    _reject_non_finite(root)
    _require_fields(root, ("schema", "schema_version", "source", "session",
                           "processing_and_load", "thermal", "cues", "image_evidence",
                           "evidence_gaps"), "report")
    if root["schema"] != DIAGNOSTIC_REPORT_SCHEMA or not _is_int(root["schema_version"]) or \
            root["schema_version"] != 1:
        raise ValueError("input must use mapassist.diagnostic_report schema version 1")
    if not isinstance(root["evidence_gaps"], list) or \
            any(not isinstance(gap, str) for gap in root["evidence_gaps"]):
        raise ValueError("report.evidence_gaps must be a string array")

    source = _object(root["source"], "report.source")
    _require_fields(source, ("archive_sha256", "archive_bytes", "app_version"), "report.source")
    if not isinstance(source["archive_sha256"], str) or not _SHA256_RE.fullmatch(source["archive_sha256"]):
        raise ValueError("report.source.archive_sha256 must be a lowercase SHA-256 hex digest")
    _integer_or_none(source["archive_bytes"], "report.source.archive_bytes")
    if source["archive_bytes"] is None:
        raise ValueError("report.source.archive_bytes must be an integer")
    version = _object(source["app_version"], "report.source.app_version")
    _require_fields(version, ("name", "code"), "report.source.app_version")
    if version["name"] is not None and (not isinstance(version["name"], str)
                                         or not _VERSION_RE.fullmatch(version["name"])):
        raise ValueError("report.source.app_version.name must be a version-like string or null")
    _integer_or_none(version["code"], "report.source.app_version.code")

    session = _object(root["session"], "report.session")
    _require_fields(session, ("duration_ms", "duration_lower_bound_ms", "interrupted", "end_observed"),
                    "report.session")
    _integer_or_none(session["duration_ms"], "report.session.duration_ms")
    _integer_or_none(session["duration_lower_bound_ms"], "report.session.duration_lower_bound_ms")
    for name in ("interrupted", "end_observed"):
        if session[name] is not None and not isinstance(session[name], bool):
            raise ValueError(f"report.session.{name} must be a boolean or null")

    processing = _object(root["processing_and_load"], "report.processing_and_load")
    _require_fields(processing, ("frame_count_in_events", "landscape_processed_frames",
                                 "frame_age_ms", "native_processing_ms",
                                 "completion_gap_ms", "image_diagnostics"), "report.processing_and_load")
    for name in ("frame_age_ms", "native_processing_ms", "completion_gap_ms"):
        _validate_distribution(processing[name], f"report.processing_and_load.{name}", "milliseconds")
    _integer_or_none(processing["frame_count_in_events"],
                     "report.processing_and_load.frame_count_in_events")
    _integer_or_none(processing["landscape_processed_frames"],
                     "report.processing_and_load.landscape_processed_frames")
    image = _object(processing["image_diagnostics"], "report.processing_and_load.image_diagnostics")
    copy_fields = ("copied_context_frames", "context_copy_total_ms", "context_copy_mean_ms",
                   "context_copy_max_ms", "context_windows", "context_rate_limited",
                   "context_deferred_frames", "peak_image_frames", "peak_image_bytes",
                   "pending_image_frames", "pending_image_bytes")
    _require_fields(image, copy_fields, "report.processing_and_load.image_diagnostics")
    for name in copy_fields:
        if name.endswith("_ms"):
            _number_or_none(image[name], f"report.processing_and_load.image_diagnostics.{name}")
        else:
            _integer_or_none(image[name], f"report.processing_and_load.image_diagnostics.{name}")

    thermal = _object(root["thermal"], "report.thermal")
    _require_fields(thermal, ("battery_temperature_c", "thermal_status_counts",
                              "thermal_status_samples"), "report.thermal")
    _validate_distribution(thermal["battery_temperature_c"],
                           "report.thermal.battery_temperature_c", "degrees_celsius")
    _integer_or_none(thermal["thermal_status_samples"], "report.thermal.thermal_status_samples")
    statuses = _object(thermal["thermal_status_counts"], "report.thermal.thermal_status_counts")
    for status, count in statuses.items():
        if not isinstance(status, str) or not status.isascii() or status.upper() not in _THERMAL_STATUS_NAMES:
            raise ValueError("report.thermal.thermal_status_counts has an unsafe category")
        _integer_or_none(count, f"report.thermal.thermal_status_counts.{status}")
        if count is None:
            raise ValueError(f"report.thermal.thermal_status_counts.{status} must be an integer")

    cues = _object(root["cues"], "report.cues")
    _require_fields(cues, ("queue_replaced_count", "queue_evicted_count", "dropped_dispatch_count"),
                    "report.cues")
    for name in ("queue_replaced_count", "queue_evicted_count", "dropped_dispatch_count"):
        _integer_or_none(cues[name], f"report.cues.{name}")

    evidence = _object(root["image_evidence"], "report.image_evidence")
    _require_fields(evidence, ("image_event_count", "available_embedded_images", "images",
                               "context_windows", "markers"),
                    "report.image_evidence")
    for name in ("image_event_count", "available_embedded_images"):
        _integer_or_none(evidence[name], f"report.image_evidence.{name}")
        if evidence[name] is None:
            raise ValueError(f"report.image_evidence.{name} must be an integer")
    images = evidence["images"]
    if not isinstance(images, list):
        raise ValueError("report.image_evidence.images must be an array")
    image_ids: set[str] = set()
    for index, row in enumerate(images):
        item = _object(row, f"report.image_evidence.images[{index}]")
        _require_fields(item, ("image_id", "available"), f"report.image_evidence.images[{index}]")
        image_id = item["image_id"]
        if not isinstance(image_id, str) or not image_id or len(image_id) > 128:
            raise ValueError(f"report.image_evidence.images[{index}].image_id must be a short string")
        if image_id in image_ids:
            raise ValueError("report.image_evidence.images contains duplicate image_id")
        image_ids.add(image_id)
        if not isinstance(item["available"], bool):
            raise ValueError(f"report.image_evidence.images[{index}].available must be a boolean")
    for list_name in ("context_windows", "markers"):
        if not isinstance(evidence[list_name], list):
            raise ValueError(f"report.image_evidence.{list_name} must be an array")
        for index, row in enumerate(evidence[list_name]):
            item = _object(row, f"report.image_evidence.{list_name}[{index}]")
            if list_name == "context_windows":
                if not isinstance(item.get("status"), str):
                    raise ValueError(f"report.image_evidence.context_windows[{index}].status must be a string")
            else:
                for key in ("pre_image_ids", "trigger_image_ids", "post_image_ids"):
                    if key in item and (not isinstance(item[key], list)
                                        or any(not isinstance(image_id, str) for image_id in item[key])):
                        raise ValueError(f"report.image_evidence.markers[{index}].{key} must be a string array")
    return root


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _read_report(path: Path, label: str) -> tuple[dict[str, Any], dict[str, str]]:
    resolved = path.expanduser().resolve()
    if not resolved.is_file():
        raise ValueError(f"{label} input does not exist")
    input_hash = _sha256(resolved)
    if zipfile.is_zipfile(resolved):
        report, _inline_images = analyze_diagnostic_zip(resolved)
        artifact_kind = "diagnostic_zip"
    else:
        if resolved.stat().st_size > MAX_REPORT_JSON_BYTES:
            raise ValueError(f"{label} JSON exceeds {MAX_REPORT_JSON_BYTES} bytes")
        report = _parse_json_bytes(resolved.read_bytes(), f"{label} report JSON")
        artifact_kind = "diagnostic_report_json"
    report = validate_report(report)
    return report, {
        "artifact_kind": artifact_kind,
        "input_sha256": input_hash,
        "source_archive_sha256": report["source"]["archive_sha256"],
    }


def _validate_text(value: Any, field: str, *, max_length: int = 240) -> str:
    if not isinstance(value, str):
        raise ValueError(f"{field} must be a string")
    normalized = value.strip()
    if not normalized:
        raise ValueError(f"{field} must not be empty")
    if len(normalized) > max_length:
        raise ValueError(f"{field} must be at most {max_length} characters")
    if any(ord(char) < 32 and char not in "\t\n\r" for char in normalized):
        raise ValueError(f"{field} contains a control character")
    if _UUID_RE.search(normalized):
        raise ValueError(f"{field} must not contain a UUID")
    return normalized


def _validate_conditions(value: Any) -> dict[str, dict[str, str]]:
    root = _object(value, "conditions metadata")
    _require_fields(root, ("schema", "schema_version", "before", "after"), "conditions metadata")
    if root["schema"] != CONDITIONS_SCHEMA or not _is_int(root["schema_version"]) or \
            root["schema_version"] != CONDITIONS_SCHEMA_VERSION:
        raise ValueError("conditions metadata must use mapassist.diagnostic_compare.conditions version 1")
    field_names = ("device_code", "charging", "quality", "audio", "starting_conditions")
    result: dict[str, dict[str, str]] = {}
    for side in ("before", "after"):
        data = _object(root[side], f"conditions.{side}")
        _require_fields(data, field_names, f"conditions.{side}")
        code = data["device_code"]
        if not isinstance(code, str) or not _ANONYMOUS_CODE_RE.fullmatch(code.strip()):
            raise ValueError(f"conditions.{side}.device_code must be a short anonymous code")
        if _UUID_RE.search(code):
            raise ValueError(f"conditions.{side}.device_code must not be a UUID")
        result[side] = {"device_code": code.strip()}
        for name in field_names[1:]:
            result[side][name] = _validate_text(data[name], f"conditions.{side}.{name}")
    return result


def _read_conditions(path: Path | None) -> dict[str, dict[str, str]] | None:
    if path is None:
        return None
    resolved = path.expanduser().resolve()
    if not resolved.is_file():
        raise ValueError("conditions metadata file does not exist")
    if resolved.stat().st_size > MAX_CONDITIONS_JSON_BYTES:
        raise ValueError(f"conditions metadata exceeds {MAX_CONDITIONS_JSON_BYTES} bytes")
    return _validate_conditions(_parse_json_bytes(resolved.read_bytes(), "conditions metadata"))


def _metric_snapshot(report: dict[str, Any]) -> tuple[dict[str, Any], list[str]]:
    processing = report["processing_and_load"]
    image = processing["image_diagnostics"]
    thermal = report["thermal"]
    cue = report["cues"]
    evidence = report["image_evidence"]
    temperatures = thermal["battery_temperature_c"]
    contexts = evidence["context_windows"]
    markers = evidence["markers"]
    images = evidence["images"]
    available_image_ids = {row["image_id"] for row in images if row["available"] is True}

    status_counts: dict[str, int] = {}
    for row in contexts:
        status = row["status"]
        # Limit echoed report text to a conservative token; unknown labels remain
        # represented as UNKNOWN so free text cannot leave the report boundary.
        key = status.upper() if _SAFE_CATEGORY_RE.fullmatch(status.upper()) else "UNKNOWN"
        status_counts[key] = status_counts.get(key, 0) + 1
    image_ref_fields = ("pre_image_ids", "trigger_image_ids", "post_image_ids")
    marker_image_reference_count = 0
    available_image_reference_count = 0
    markers_with_trigger_reference = 0
    markers_with_available_trigger = 0
    markers_with_available_window = 0
    for marker in markers:
        references = [image_id for key in image_ref_fields for image_id in marker.get(key, [])]
        available_references = [image_id for image_id in references if image_id in available_image_ids]
        trigger_refs = marker.get("trigger_image_ids", [])
        marker_image_reference_count += len(references)
        available_image_reference_count += len(available_references)
        markers_with_trigger_reference += bool(trigger_refs)
        markers_with_available_trigger += any(image_id in available_image_ids for image_id in trigger_refs)
        markers_with_available_window += bool(available_references)
    marker_count = len(markers)
    snapshot = {
        "frame_count": processing["frame_count_in_events"],
        "landscape_processed_frames": processing["landscape_processed_frames"],
        "native_processing_ms": _distribution_snapshot(processing["native_processing_ms"]),
        "frame_age_ms": _distribution_snapshot(processing["frame_age_ms"]),
        "completion_gap_ms": _distribution_snapshot(processing["completion_gap_ms"]),
        "image_copy": {
            "copied_context_frames": image["copied_context_frames"],
            "total_ms": image["context_copy_total_ms"],
            "mean_ms": image["context_copy_mean_ms"],
            "max_ms": image["context_copy_max_ms"],
        },
        "queues": {
            "image_peak_frames": image["peak_image_frames"],
            "image_peak_bytes": image["peak_image_bytes"],
            "image_pending_frames": image["pending_image_frames"],
            "image_pending_bytes": image["pending_image_bytes"],
            "context_deferred_frames": image["context_deferred_frames"],
            "cue_queue_replaced": cue["queue_replaced_count"],
            "cue_queue_evicted": cue["queue_evicted_count"],
            "cue_dispatch_dropped": cue["dropped_dispatch_count"],
        },
        "image_window_coverage": {
            "context_windows_reported": image["context_windows"],
            "context_rows": len(contexts),
            "context_status_counts": status_counts,
            "marker_count": marker_count,
            "marker_image_reference_count": marker_image_reference_count,
            "available_image_reference_count": available_image_reference_count,
            "markers_with_trigger_image_reference": markers_with_trigger_reference,
            "markers_with_available_trigger_image": markers_with_available_trigger,
            "markers_with_available_window_image": markers_with_available_window,
            "markers_with_available_window_image_ratio": (markers_with_available_window / marker_count
                                                           if marker_count else None),
            "image_events": evidence["image_event_count"],
            "available_embedded_images_reported": evidence["available_embedded_images"],
            "available_image_records": len(available_image_ids),
            "available_image_record_count_delta": evidence["available_embedded_images"] - len(available_image_ids),
            "unavailable_image_reference_count": marker_image_reference_count - available_image_reference_count,
        },
        "battery_temperature_c": _distribution_snapshot(temperatures),
        "thermal_status_samples": thermal["thermal_status_samples"],
        "thermal_status_counts": _normalized_thermal_status_counts(thermal["thermal_status_counts"]),
        "source_evidence_gap_count": len(report["evidence_gaps"]),
    }
    gaps = _metric_gaps(snapshot)
    session = report["session"]
    duration = session["duration_ms"]
    if duration is None:
        gaps.append("session.duration_ms 未记录")
    elif duration < MIN_SESSION_DURATION_MS:
        gaps.append(f"会话时长 {duration} ms 少于 15 分钟")
    gaps.append("记录总时长不证明连续有效 15 分钟采样")
    if session["duration_lower_bound_ms"] is None:
        gaps.append("session.duration_lower_bound_ms 未记录")
    elif session["duration_lower_bound_ms"] < MIN_SESSION_DURATION_MS:
        gaps.append(f"session.duration_lower_bound_ms {session['duration_lower_bound_ms']} ms 少于 15 分钟")
    if session["interrupted"] is True:
        gaps.append("上游报告标记会话中断")
    elif session["interrupted"] is None:
        gaps.append("session.interrupted 未记录")
    if session["end_observed"] is False:
        gaps.append("上游报告未观察到会话结束")
    elif session["end_observed"] is None:
        gaps.append("session.end_observed 未记录")
    if processing["frame_count_in_events"] is None:
        gaps.append("frame_count_in_events 未记录")
    if processing["landscape_processed_frames"] is None:
        gaps.append("landscape_processed_frames 未记录")
    if thermal["thermal_status_samples"] is None:
        gaps.append("thermal_status_samples 未记录")
    elif thermal["thermal_status_samples"] == 0:
        gaps.append("thermal_status_samples 为 0")
    if snapshot["source_evidence_gap_count"]:
        gaps.append(f"原诊断报告有 {snapshot['source_evidence_gap_count']} 条证据缺口；请在本地查看原报告")
    unresolved_refs = snapshot["image_window_coverage"]["unavailable_image_reference_count"]
    if unresolved_refs:
        gaps.append(f"有 {unresolved_refs} 个图窗图像引用未对应到报告中可用的图片")
    available_count_delta = snapshot["image_window_coverage"]["available_image_record_count_delta"]
    if available_count_delta:
        gaps.append("上游可用图片计数与 images.available 记录不一致")
    return snapshot, gaps


def _distribution_snapshot(distribution: dict[str, Any]) -> dict[str, Any]:
    return {key: distribution[key] for key in ("count", "min", "p50", "p95", "max")}


def _normalized_thermal_status_counts(statuses: dict[str, int]) -> dict[str, int]:
    normalized: dict[str, int] = {}
    for status, count in statuses.items():
        key = status.upper()
        normalized[key] = normalized.get(key, 0) + count
    return dict(sorted(normalized.items()))


def _metric_gaps(snapshot: dict[str, Any]) -> list[str]:
    gaps: list[str] = []
    for distribution_name in ("native_processing_ms", "frame_age_ms", "completion_gap_ms",
                              "battery_temperature_c"):
        distribution = snapshot[distribution_name]
        if distribution["count"] == 0:
            gaps.append(f"{distribution_name} 没有有效样本")
        else:
            for stat in ("min", "p50", "p95", "max"):
                if distribution[stat] is None:
                    gaps.append(f"{distribution_name}.{stat} 未记录")
    for section in ("image_copy", "queues", "image_window_coverage"):
        for key, value in snapshot[section].items():
            if value is None:
                gaps.append(f"{section}.{key} 未记录")
    if snapshot["image_window_coverage"]["marker_count"] == 0:
        gaps.append("image_window_coverage 没有事件或手动标记")
    return gaps


def _walk_numeric_differences(before: Any, after: Any, path: str = "") -> list[dict[str, Any]]:
    if isinstance(before, dict) and isinstance(after, dict):
        rows = []
        for key in sorted(set(before) | set(after)):
            child = f"{path}.{key}" if path else key
            rows.extend(_walk_numeric_differences(before.get(key), after.get(key), child))
        return rows
    if isinstance(before, (int, float)) and not isinstance(before, bool) and \
            isinstance(after, (int, float)) and not isinstance(after, bool):
        return [{"metric": path, "before": before, "after": after,
                 "delta": round(after - before, 6)}]
    if before is None or after is None:
        return [{"metric": path, "before": before, "after": after, "delta": None}]
    if isinstance(before, dict) or isinstance(after, dict):
        return [{"metric": path, "before": before, "after": after, "delta": None}]
    return []


def compare_reports(before_report: dict[str, Any], after_report: dict[str, Any],
                    before_artifact: dict[str, str], after_artifact: dict[str, str],
                    conditions: dict[str, dict[str, str]] | None = None) -> dict[str, Any]:
    before_report = validate_report(before_report)
    after_report = validate_report(after_report)
    before_metrics, before_gaps = _metric_snapshot(before_report)
    after_metrics, after_gaps = _metric_snapshot(after_report)
    same_source = (before_report["source"]["archive_sha256"] ==
                   after_report["source"]["archive_sha256"])
    condition_diff: list[str] = []
    same_device_reported: bool | None = None
    if conditions is not None:
        same_device_reported = conditions["before"]["device_code"] == conditions["after"]["device_code"]
        condition_diff = [key for key in ("device_code", "charging", "quality", "audio", "starting_conditions")
                          if conditions["before"][key] != conditions["after"][key]]
        if condition_diff:
            before_gaps.append("条件差异，差值不构成同条件对照")
            after_gaps.append("条件差异，差值不构成同条件对照")
    else:
        before_gaps.append("未提供人工比较条件；同机和测试条件未知")
        after_gaps.append("未提供人工比较条件；同机和测试条件未知")
    if same_source:
        duplicate_gap = "重复输入/管线演示，不构成独立前后对照"
        before_gaps.append(duplicate_gap)
        after_gaps.append(duplicate_gap)

    before_session = before_report["session"]
    after_session = after_report["session"]
    return {
        "schema": COMPARE_SCHEMA,
        "schema_version": COMPARE_SCHEMA_VERSION,
        "same_source": same_source,
        "interpretation": "重复输入/管线演示，不构成独立前后对照；指标差值仍予显示。" if same_source else
        "仅并列已记录指标与证据缺口；人工填写条件不构成同机或条件相同的自动证明。",
        "inputs": {
            "before": {
                **before_artifact,
                "app_version": before_report["source"]["app_version"],
                "duration_ms": before_session["duration_ms"],
                "session": {key: before_session[key] for key in
                            ("duration_ms", "duration_lower_bound_ms", "interrupted", "end_observed")},
                "metrics": before_metrics,
                "evidence_gaps": before_gaps,
            },
            "after": {
                **after_artifact,
                "app_version": after_report["source"]["app_version"],
                "duration_ms": after_session["duration_ms"],
                "session": {key: after_session[key] for key in
                            ("duration_ms", "duration_lower_bound_ms", "interrupted", "end_observed")},
                "metrics": after_metrics,
                "evidence_gaps": after_gaps,
            },
        },
        "differences": _walk_numeric_differences(before_metrics, after_metrics),
        "conditions": {
            "source": "manually_supplied_metadata" if conditions is not None else "not_supplied",
            "reported_same_device": same_device_reported,
            "same_device_or_equal_conditions_proven": False,
            "differing_fields": condition_diff,
            "before": conditions["before"] if conditions is not None else None,
            "after": conditions["after"] if conditions is not None else None,
        },
    }


def _h(value: Any) -> str:
    return html.escape(str(value), quote=True)


def _pretty(value: Any) -> str:
    if value is None:
        return "未记录"
    return json.dumps(value, ensure_ascii=False, sort_keys=True, allow_nan=False)


def render_html(result: dict[str, Any]) -> str:
    before = result["inputs"]["before"]
    after = result["inputs"]["after"]
    metric_rows = []
    for item in result["differences"]:
        metric_rows.append(
            f"<tr><th>{_h(item['metric'])}</th><td>{_h(_pretty(item['before']))}</td>"
            f"<td>{_h(_pretty(item['after']))}</td><td>{_h(_pretty(item['delta']))}</td></tr>"
        )
    gap_rows = []
    for side, label in ((before, "前测"), (after, "后测")):
        gap_rows.extend(f"<li><strong>{label}：</strong>{_h(gap)}</li>" for gap in side["evidence_gaps"])

    conditions = result["conditions"]
    condition_rows = []
    labels = {"device_code": "匿名设备代号", "charging": "充电状态", "quality": "画质设置",
              "audio": "音频设置", "starting_conditions": "起始条件"}
    for key, label in labels.items():
        left = conditions["before"].get(key) if conditions["before"] else None
        right = conditions["after"].get(key) if conditions["after"] else None
        condition_rows.append(
            f"<tr><th>{label}</th><td>{_h(_pretty(left))}</td><td>{_h(_pretty(right))}</td></tr>"
        )
    before_version = before["app_version"]
    after_version = after["app_version"]
    before_session = before["session"]
    after_session = after["session"]
    same_source_notice = (
        '<p class="same-source"><strong>same_source=true：</strong>'
        '重复输入/管线演示，不构成独立前后对照。指标差值仍予显示。</p>'
        if result["same_source"] else ""
    )
    return f"""<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>离线诊断对照</title><style>
body{{font:15px/1.55 system-ui,-apple-system,"Segoe UI",sans-serif;max-width:1120px;margin:2rem auto;padding:0 1rem;color:#1d2939;background:#f7f9fc}}
h1,h2{{color:#102a43}}section{{background:#fff;border:1px solid #d9e2ec;border-radius:10px;margin:1rem 0;padding:1rem 1.25rem;overflow:auto}}
.notice{{border-left:5px solid #c2410c;background:#fff7ed;padding:.8rem 1rem}}table{{border-collapse:collapse;width:100%;font-size:.9rem;overflow-wrap:anywhere}}
.same-source{{border:2px solid #b42318;background:#fef3f2;color:#7a271a;padding:1rem;font-size:1.1rem;font-weight:600}}
th,td{{border-bottom:1px solid #e5eaf0;text-align:left;padding:.5rem;vertical-align:top}}th{{background:#f0f4f8}}code{{overflow-wrap:anywhere}}
</style></head><body>
<h1>离线诊断对照</h1>
<p class="notice"><strong>解释边界：</strong>{_h(result['interpretation'])} 本工具不生成验收或温控结论。</p>
{same_source_notice}
<section><h2>输入记录</h2><table><thead><tr><th></th><th>前测</th><th>后测</th></tr></thead><tbody>
<tr><th>输入类型</th><td>{_h(before['artifact_kind'])}</td><td>{_h(after['artifact_kind'])}</td></tr>
<tr><th>输入文件 SHA-256</th><td><code>{_h(before['input_sha256'])}</code></td><td><code>{_h(after['input_sha256'])}</code></td></tr>
<tr><th>原诊断 ZIP SHA-256</th><td><code>{_h(before['source_archive_sha256'])}</code></td><td><code>{_h(after['source_archive_sha256'])}</code></td></tr>
<tr><th>版本</th><td>{_h(_pretty(before_version))}</td><td>{_h(_pretty(after_version))}</td></tr>
<tr><th>时长（ms）</th><td>{_h(_pretty(before['duration_ms']))}</td><td>{_h(_pretty(after['duration_ms']))}</td></tr>
<tr><th>时长下界 / 中断 / 观察到结束</th><td>{_h(_pretty(before_session['duration_lower_bound_ms']))} / {_h(_pretty(before_session['interrupted']))} / {_h(_pretty(before_session['end_observed']))}</td><td>{_h(_pretty(after_session['duration_lower_bound_ms']))} / {_h(_pretty(after_session['interrupted']))} / {_h(_pretty(after_session['end_observed']))}</td></tr>
</tbody></table></section>
<section><h2>人工填写的比较条件</h2><p>同机代号和条件由填写者提供，工具无法验证其真实性或测试过程。</p>
<p>填写者报告同机：{_h(_pretty(conditions['reported_same_device']))}；条件差异字段：{_h(_pretty(conditions['differing_fields']))}</p>
<table><thead><tr><th>条件</th><th>前测</th><th>后测</th></tr></thead><tbody>{''.join(condition_rows)}</tbody></table></section>
<section><h2>指标与差值</h2><table><thead><tr><th>指标</th><th>前测</th><th>后测</th><th>后测减前测</th></tr></thead><tbody>{''.join(metric_rows)}</tbody></table></section>
<section><h2>证据缺口</h2><ul>{''.join(gap_rows) or '<li>当前选取指标未发现缺口。</li>'}</ul></section>
<footer>由本机 mapassist.diagnostic_compare 生成；不包含截图或 cue ID，可离线打开。</footer>
</body></html>\n"""


def write_outputs(result: dict[str, Any], output_dir: Path) -> tuple[Path, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / "diagnostic-compare.json"
    html_path = output_dir / "diagnostic-compare.html"
    json_path.write_text(json.dumps(result, ensure_ascii=False, indent=2,
                                    sort_keys=True, allow_nan=False) + "\n", encoding="utf-8")
    html_path.write_text(render_html(result), encoding="utf-8")
    return json_path, html_path


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path, help="before ZIP or generated diagnostic-report.json")
    parser.add_argument("after", type=Path, help="after ZIP or generated diagnostic-report.json")
    parser.add_argument("--conditions-json", type=Path,
                        help="optional manually completed comparison conditions JSON")
    parser.add_argument("--output-dir", type=Path, required=True,
                        help="directory for diagnostic-compare.json and diagnostic-compare.html")
    args = parser.parse_args(argv)
    try:
        before_report, before_artifact = _read_report(args.before, "before")
        after_report, after_artifact = _read_report(args.after, "after")
        conditions = _read_conditions(args.conditions_json)
        result = compare_reports(before_report, after_report, before_artifact, after_artifact, conditions)
        json_path, html_path = write_outputs(result, args.output_dir)
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as error:
        parser.error(str(error))
    print(f"JSON: {json_path}")
    print(f"HTML: {html_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
