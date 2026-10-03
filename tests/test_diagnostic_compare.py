from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import zipfile

import pytest

from mapassist.diagnostic_compare import (
    _read_report,
    _parse_json_bytes,
    _validate_conditions,
    compare_reports,
    render_html,
    validate_report,
    write_outputs,
)
from mapassist.diagnostic_report import REPORT_SCHEMA


def _distribution(count: int, minimum: float | None, p50: float | None,
                  p95: float | None, maximum: float | None,
                  unit: str = "milliseconds") -> dict[str, object]:
    return {"count": count, "unit": unit, "min": minimum,
            "p50": p50, "p95": p95, "max": maximum}


def _report(*, version: str = "0.3.5", frame_count: int = 100,
            duration_ms: int | None = 1_200_000) -> dict[str, object]:
    return {
        "schema": REPORT_SCHEMA,
        "schema_version": 1,
        "source": {
            "archive_sha256": ("a" if version == "0.3.5" else "b") * 64,
            "archive_bytes": 321,
            "app_version": {"name": version, "code": 13 if version == "0.3.5" else 14},
        },
        "session": {"duration_ms": duration_ms, "duration_lower_bound_ms": duration_ms,
                    "interrupted": False, "end_observed": True},
        "processing_and_load": {
            "frame_count_in_events": frame_count,
            "landscape_processed_frames": frame_count,
            "frame_age_ms": _distribution(100, 1, 4, 8, 15),
            "native_processing_ms": _distribution(100, 2, 5, 9, 16),
            "completion_gap_ms": _distribution(99, 30, 50, 70, 90),
            "image_diagnostics": {
                "copied_context_frames": 10,
                "context_copy_total_ms": 50.0,
                "context_copy_mean_ms": 5.0,
                "context_copy_max_ms": 9.0,
                "context_windows": 2,
                "context_rate_limited": 0,
                "context_deferred_frames": 1,
                "peak_image_frames": 3,
                "peak_image_bytes": 4096,
                "pending_image_frames": 0,
                "pending_image_bytes": 0,
            },
        },
        "thermal": {
            "battery_temperature_c": _distribution(5, 32.0, 33.0, 34.0, 35.0,
                                                    unit="degrees_celsius"),
            "thermal_status_counts": {"NONE": 5},
            "thermal_status_samples": 5,
        },
        "cues": {
            "queue_replaced_count": 1,
            "queue_evicted_count": 2,
            "dropped_dispatch_count": 0,
            "items": [{"cue_id": "private-session-uuid:1"}],
        },
        "image_evidence": {
            "image_event_count": 4,
            "available_embedded_images": 4,
            "images": [{"image_id": f"image-{index}", "available": True}
                       for index in range(1, 5)],
            "context_windows": [{"status": "ACCEPTED"}],
            "markers": [{"cue_id": "private-session-uuid:1",
                          "pre_image_ids": ["image-1"],
                          "trigger_image_ids": ["image-2"],
                          "post_image_ids": []}],
        },
        "evidence_gaps": [],
    }


def _artifact(report: dict[str, object], artifact_hash: str) -> dict[str, str]:
    return {"artifact_kind": "diagnostic_report_json", "input_sha256": artifact_hash * 64,
            "source_archive_sha256": report["source"]["archive_sha256"]}  # type: ignore[index]


def _conditions(*, after_charging: str = "unplugged", after_quality: str = "high",
                after_audio: str = "speaker 60%", after_start: str = "cool idle") -> dict[str, object]:
    common = {"device_code": "lab-device-a", "charging": "unplugged", "quality": "high",
              "audio": "speaker 60%", "starting_conditions": "cool idle"}
    after = {**common, "charging": after_charging, "quality": after_quality,
             "audio": after_audio, "starting_conditions": after_start}
    return {"schema": "mapassist.diagnostic_compare.conditions", "schema_version": 1,
            "before": common, "after": after}


def _compared(before: dict[str, object], after: dict[str, object],
              conditions: dict[str, object] | None = None) -> dict[str, object]:
    checked_conditions = _validate_conditions(conditions) if conditions is not None else None
    return compare_reports(before, after, _artifact(before, "c"), _artifact(after, "d"),
                           checked_conditions)


def test_short_session_and_missing_values_remain_evidence_gaps() -> None:
    before = _report(duration_ms=899_999)
    processing = before["processing_and_load"]  # type: ignore[index]
    processing["frame_age_ms"] = _distribution(0, None, None, None, None)
    processing["image_diagnostics"]["peak_image_bytes"] = None
    before["thermal"]["thermal_status_samples"] = 0  # type: ignore[index]
    before["thermal"]["battery_temperature_c"] = _distribution(
        0, None, None, None, None, unit="degrees_celsius")  # type: ignore[index]
    before["processing_and_load"]["frame_count_in_events"] = None  # type: ignore[index]
    after = _report(version="0.3.6")
    after["thermal"]["thermal_status_samples"] = None  # type: ignore[index]

    result = _compared(before, after)
    gaps = result["inputs"]["before"]["evidence_gaps"]  # type: ignore[index]
    assert any("少于 15 分钟" in item for item in gaps)
    assert any("duration_lower_bound_ms" in item and "少于 15 分钟" in item for item in gaps)
    assert any("frame_age_ms 没有有效样本" in item for item in gaps)
    assert any("image_peak_bytes 未记录" in item for item in gaps)
    assert any("thermal_status_samples 为 0" in item for item in gaps)
    assert any("frame_count_in_events 未记录" in item for item in gaps)
    after_gaps = result["inputs"]["after"]["evidence_gaps"]  # type: ignore[index]
    assert any("thermal_status_samples 未记录" in item for item in after_gaps)
    assert any("不证明连续有效 15 分钟采样" in item for item in gaps)
    delta = next(row for row in result["differences"] if row["metric"] == "frame_age_ms.p95")  # type: ignore[index]
    assert delta["before"] is None and delta["delta"] is None


def test_manual_condition_differences_are_visible_but_not_proof() -> None:
    conditions = _conditions(after_charging="charging", after_quality="medium",
                             after_audio="headphones", after_start="warm idle")
    result = _compared(_report(), _report(version="0.3.6"), conditions)
    assert result["conditions"]["reported_same_device"] is True  # type: ignore[index]
    assert result["conditions"]["same_device_or_equal_conditions_proven"] is False  # type: ignore[index]
    assert result["conditions"]["differing_fields"] == [  # type: ignore[index]
        "charging", "quality", "audio", "starting_conditions"]
    assert all("条件差异，差值不构成同条件对照" in side["evidence_gaps"]
               for side in result["inputs"].values())  # type: ignore[index]
    assert "report_archive" not in result


def test_same_source_is_flagged_as_duplicate_but_metrics_still_compare() -> None:
    report = _report()
    result = _compared(report, copy.deepcopy(report), _conditions())
    assert result["same_source"] is True
    assert "重复输入/管线演示，不构成独立前后对照" in result["interpretation"]
    assert result["differences"]
    assert "same_source=true" in render_html(result)
    assert "重复输入/管线演示，不构成独立前后对照" in render_html(result)


def test_lowercase_thermal_statuses_are_accepted_and_case_normalized() -> None:
    before = _report()
    after = _report(version="0.3.6")
    before["thermal"]["thermal_status_counts"] = {"none": 51}  # type: ignore[index]
    after["thermal"]["thermal_status_counts"] = {"NONE": 51}  # type: ignore[index]
    before["thermal"]["thermal_status_samples"] = 51  # type: ignore[index]
    after["thermal"]["thermal_status_samples"] = 51  # type: ignore[index]

    result = _compared(before, after, _conditions())
    before_counts = result["inputs"]["before"]["metrics"]["thermal_status_counts"]  # type: ignore[index]
    after_counts = result["inputs"]["after"]["metrics"]["thermal_status_counts"]  # type: ignore[index]
    assert before_counts == after_counts == {"NONE": 51}

    all_generator_names = {
        "unknown", "unavailable", "none", "light", "moderate", "severe",
        "critical", "emergency", "shutdown",
    }
    all_statuses = _report()
    all_statuses["thermal"]["thermal_status_counts"] = {name: 1 for name in all_generator_names}  # type: ignore[index]
    all_statuses["thermal"]["thermal_status_samples"] = len(all_generator_names)  # type: ignore[index]
    normalized = _compared(all_statuses, _report(version="0.3.6"), _conditions())
    normalized_counts = normalized["inputs"]["before"]["metrics"]["thermal_status_counts"]  # type: ignore[index]
    assert set(normalized_counts) == {name.upper() for name in all_generator_names}

    invalid = _report()
    invalid["thermal"]["thermal_status_counts"] = {"private-device-status": 5}  # type: ignore[index]
    with pytest.raises(ValueError, match="unsafe category"):
        validate_report(invalid)


def test_session_and_upstream_gaps_are_summarized_without_echoing_gap_text() -> None:
    before = _report()
    before["session"].update({"interrupted": True, "end_observed": False,
                              "duration_lower_bound_ms": 800_000})  # type: ignore[index]
    before["processing_and_load"]["landscape_processed_frames"] = None  # type: ignore[index]
    before["evidence_gaps"] = ["private-session-uuid:1 caused a gap"]
    result = _compared(before, _report(version="0.3.6"))
    before_result = result["inputs"]["before"]  # type: ignore[index]
    gaps = before_result["evidence_gaps"]
    assert any("会话中断" in item for item in gaps)
    assert any("未观察到会话结束" in item for item in gaps)
    assert any("landscape_processed_frames 未记录" in item for item in gaps)
    assert any("原诊断报告有 1 条证据缺口" in item for item in gaps)
    assert before_result["metrics"]["source_evidence_gap_count"] == 1
    assert before_result["session"]["duration_lower_bound_ms"] == 800_000
    assert "private-session-uuid:1" not in json.dumps(result, ensure_ascii=False)


def test_image_window_coverage_counts_only_reported_available_image_references() -> None:
    before = _report()
    before["image_evidence"]["images"][1]["available"] = False  # type: ignore[index]
    marker = before["image_evidence"]["markers"][0]  # type: ignore[index]
    marker["trigger_image_ids"] = ["image-2", "missing-image"]
    result = _compared(before, _report(version="0.3.6"))
    coverage = result["inputs"]["before"]["metrics"]["image_window_coverage"]  # type: ignore[index]
    assert coverage["marker_image_reference_count"] == 3
    assert coverage["available_image_reference_count"] == 1
    assert coverage["markers_with_trigger_image_reference"] == 1
    assert coverage["markers_with_available_trigger_image"] == 0
    assert coverage["unavailable_image_reference_count"] == 2
    assert coverage["available_image_record_count_delta"] == 1
    assert "image-2" not in json.dumps(result, ensure_ascii=False)
    assert "missing-image" not in json.dumps(result, ensure_ascii=False)
    assert any("未对应到报告中可用的图片" in item
               for item in result["inputs"]["before"]["evidence_gaps"])  # type: ignore[index]
    assert any("计数与 images.available 记录不一致" in item
               for item in result["inputs"]["before"]["evidence_gaps"])  # type: ignore[index]


def test_free_text_is_escaped_and_source_cue_ids_are_not_emitted() -> None:
    conditions = _conditions(after_quality='<script>alert("x")</script>')
    result = _compared(_report(), _report(version="0.3.6"), conditions)
    page = render_html(result)
    serialized = json.dumps(result, ensure_ascii=False)
    assert "&lt;script&gt;" in page
    assert "<script>" not in page
    assert "private-session-uuid:1" not in page
    assert "private-session-uuid:1" not in serialized
    assert "data:image/" not in page


def test_valid_metrics_keep_hashes_versions_and_numeric_differences(tmp_path: Path) -> None:
    before = _report()
    after = _report(version="0.3.6", frame_count=125)
    after["processing_and_load"]["native_processing_ms"] = _distribution(120, 3, 6, 11, 18)  # type: ignore[index]
    result = _compared(before, after, _conditions())
    assert result["inputs"]["before"]["source_archive_sha256"] == "a" * 64  # type: ignore[index]
    assert result["inputs"]["after"]["app_version"]["name"] == "0.3.6"  # type: ignore[index]
    assert result["inputs"]["before"]["duration_ms"] == 1_200_000  # type: ignore[index]
    frame_delta = next(row for row in result["differences"] if row["metric"] == "frame_count")  # type: ignore[index]
    native_delta = next(row for row in result["differences"]
                        if row["metric"] == "native_processing_ms.p95")  # type: ignore[index]
    assert frame_delta["delta"] == 25
    assert native_delta["delta"] == 2

    json_path, html_path = write_outputs(result, tmp_path)
    assert json.loads(json_path.read_text(encoding="utf-8"))["schema"] == "mapassist.diagnostic_compare"
    assert "<!doctype html>" in html_path.read_text(encoding="utf-8")


def test_report_schema_and_nonfinite_json_are_rejected() -> None:
    invalid = copy.deepcopy(_report())
    invalid["schema"] = "other.schema"
    with pytest.raises(ValueError, match="schema version"):
        validate_report(invalid)
    invalid = _report()
    invalid["schema_version"] = True
    with pytest.raises(ValueError, match="schema version"):
        validate_report(invalid)
    with pytest.raises(ValueError, match="non-finite"):
        _parse_json_bytes(b'{"temperature":NaN}', "fixture")
    invalid = _report()
    invalid["processing_and_load"]["native_processing_ms"]["p95"] = float("inf")  # type: ignore[index]
    with pytest.raises(ValueError, match="non-finite"):
        validate_report(invalid)
    invalid = _report()
    invalid["processing_and_load"]["native_processing_ms"]["unit"] = "seconds"  # type: ignore[index]
    with pytest.raises(ValueError, match="unit"):
        validate_report(invalid)
    invalid = _report()
    invalid["processing_and_load"]["native_processing_ms"] = _distribution(0, 0, None, None, None)  # type: ignore[index]
    with pytest.raises(ValueError, match="zero samples"):
        validate_report(invalid)
    invalid = _report()
    invalid["thermal"]["battery_temperature_c"] = _distribution(0, None, None, None, None)  # type: ignore[index]
    with pytest.raises(ValueError, match="unit"):
        validate_report(invalid)
    invalid["thermal"]["battery_temperature_c"] = _distribution(0, None, None, None, None,
                                                                  unit="degrees_celsius")  # type: ignore[index]
    invalid["thermal"]["battery_temperature_c"]["max"] = 22  # type: ignore[index]
    with pytest.raises(ValueError, match="zero samples"):
        validate_report(invalid)
    invalid_conditions = _conditions()
    invalid_conditions["schema_version"] = True
    with pytest.raises(ValueError, match="conditions version 1"):
        _validate_conditions(invalid_conditions)


def test_report_json_and_diagnostic_zip_inputs_are_supported(tmp_path: Path) -> None:
    report_path = tmp_path / "report.json"
    report_path.write_text(json.dumps(_report()), encoding="utf-8")
    _report_data, json_artifact = _read_report(report_path, "before")
    assert json_artifact["artifact_kind"] == "diagnostic_report_json"
    assert json_artifact["input_sha256"] == hashlib.sha256(report_path.read_bytes()).hexdigest()

    session_id = "synthetic-session"
    metadata = {"schema": "sensefield.diagnostics", "schema_version": 1,
                "session_id": session_id, "version_name": "0.3.5", "version_code": 13, "sdk": 34}
    summary = {"session_id": session_id, "duration_ms": 1_200_000, "reason": "complete",
               "interrupted": False, "end_observed": True, "frame_count": 0,
               "image_count": 0, "last_state": {"detected_cues": 0}}
    zip_path = tmp_path / "diagnostics.zip"
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("metadata.json", json.dumps(metadata))
        archive.writestr("summary.json", json.dumps(summary))
        archive.writestr("events.jsonl", "")
    _zip_report, zip_artifact = _read_report(zip_path, "after")
    assert zip_artifact["artifact_kind"] == "diagnostic_zip"
    assert zip_artifact["input_sha256"] == zip_artifact["source_archive_sha256"]
