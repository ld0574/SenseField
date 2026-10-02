from __future__ import annotations

import csv
import hashlib
import json
import zipfile
from pathlib import Path

import pytest

from mapassist.diagnostic_report import (
    analyze_diagnostic_zip,
    write_outputs,
)


REAL_DIAGNOSTIC_DIR = Path("build/heat-diag-0.3.5-20261002")


def _archive_from_extracted_recording(
    tmp_path: Path,
    *,
    malicious_drop_reason: str | None = None,
    dispatch_kind: str = "MINIMAP_ENEMY",
) -> Path:
    """Package real DiagnosticRecorder entries or a small schema-valid variant."""
    output = tmp_path / "recording.zip"
    if malicious_drop_reason is None:
        source = REAL_DIAGNOSTIC_DIR
        events = [json.loads(line) for line in (source / "events.jsonl").read_text(encoding="utf-8").splitlines()]
        image_names = {event["file"] for event in events if event.get("type") == "image"}
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
            for name in ("metadata.json", "summary.json", "events.jsonl"):
                archive.write(source / name, name)
            for name in image_names:
                archive.write(source / name, name)
        return output

    session_id = "session-fixture"
    metadata = {
        "schema": "sensefield.diagnostics", "schema_version": 1,
        "session_id": session_id, "version_name": "fixture", "version_code": 1,
        "sdk": 34,
    }
    summary = {
        "session_id": session_id, "duration_ms": 1000, "reason": "complete",
        "interrupted": False, "end_observed": True, "frame_count": 0,
        "image_count": 0, "last_state": {"detected_cues": 1},
    }
    dispatch = (
        f"CueDispatch cueId=session-fixture:1 kind={dispatch_kind} category=VISION_MEMORY "
        "createdAtMs=100 dispatchAtMs=110 dispatchDelayMs=10 requestedMask=1 acceptedMask=1 "
        f"outcome=ACCEPTED dropReason={malicious_drop_reason}"
    )
    events = [
        {"type": "audit", "session_id": session_id, "at_ms": 110, "data": {"message": dispatch}},
    ]
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("metadata.json", json.dumps(metadata))
        archive.writestr("summary.json", json.dumps(summary))
        archive.writestr("events.jsonl", "\n".join(json.dumps(event) for event in events) + "\n")
    return output


def _latency_csv(path: Path, *, cue_id: str = "session-fixture:1", kind: str = "minimap_enemy") -> Path:
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=["event_id", "cue_id", "kind", "evidence_ms", "audio_ms", "source_note"],
        )
        writer.writeheader()
        writer.writerow({
            "event_id": "event-1", "cue_id": cue_id, "kind": kind,
            "evidence_ms": "1000", "audio_ms": "1450", "source_note": "frame checked",
        })
    return path


def _write_near_event_link_zip(path: Path) -> Path:
    session_id = "event-link-test"
    metadata = {
        "schema": "sensefield.diagnostics", "schema_version": 1,
        "session_id": session_id, "version_name": "fixture", "version_code": 1,
    }
    summary = {
        "session_id": session_id, "duration_ms": 1000, "reason": "complete",
        "interrupted": False, "end_observed": True, "frame_count": 2,
        "image_count": 0, "last_state": {"detected_cues": 2},
    }
    events: list[dict[str, object]] = []
    for index, observed, completed in ((1, 800, 900), (2, 1200, 1300)):
        events.append({
            "type": "frame", "session_id": session_id,
            "data": {
                "frame_index": index, "observed_at_ms": observed,
                "completed_at_ms": completed, "engine_at_ms": completed,
                "native_micros": 100, "frame_width": 100, "frame_height": 50,
                "raw_observations": [],
            },
        })
    for cue_id, created, dispatched, episode in (
        ("event-link-test:1", 800, 1000, 1),
        ("event-link-test:2", 1200, 1400, 2),
    ):
        message = (
            f"CueDispatch cueId={cue_id} kind=NEAR_ZONE category=NEAR_ZONE "
            f"createdAtMs={created} dispatchAtMs={dispatched} dispatchDelayMs={dispatched-created} "
            "requestedMask=7 acceptedMask=7 outcome=ACCEPTED dropReason=none"
        )
        events.append({"type": "audit", "session_id": session_id, "at_ms": dispatched,
                       "data": {"message": message}})
        near_message = (
            f"NearZoneEvent event=NEAR_ENTER episode={episode} sector=2 "
            "suppression=none outcome=ACCEPTED"
        )
        events.append({"type": "audit", "session_id": session_id, "at_ms": dispatched,
                       "data": {"message": near_message}})
    # The first event deliberately has no context. The later window must not be
    # borrowed just because it is the nearest available one.
    events.append({
        "type": "image_context", "session_id": session_id, "at_ms": 1401,
        "data": {
            "window_id": 1, "status": "ACCEPTED", "reason": "near_enter",
            "requested_at_ms": 1300, "start_observed_at_ms": 1200,
            "end_observed_at_ms": 3200, "pre_frame_indices": [],
        },
    })
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("metadata.json", json.dumps(metadata))
        archive.writestr("summary.json", json.dumps(summary))
        archive.writestr("events.jsonl", "\n".join(json.dumps(event) for event in events) + "\n")
    return path


def _write_coalesced_context_zip(path: Path) -> Path:
    session_id = "coalesced-link-test"
    metadata = {
        "schema": "sensefield.diagnostics", "schema_version": 1,
        "session_id": session_id, "version_name": "fixture", "version_code": 1,
    }
    summary = {
        "session_id": session_id, "duration_ms": 1000, "reason": "complete",
        "interrupted": False, "end_observed": True, "frame_count": 4,
        "image_count": 8, "last_state": {"detected_cues": 2},
    }
    events: list[dict[str, object]] = []
    frame_times = ((1, 100, 110), (2, 150, 160), (3, 200, 210), (4, 250, 260))
    for index, observed, completed in frame_times:
        events.append({
            "type": "frame", "session_id": session_id,
            "data": {
                "frame_index": index, "observed_at_ms": observed,
                "completed_at_ms": completed, "engine_at_ms": completed,
                "native_micros": 100, "frame_width": 100, "frame_height": 50,
                "raw_observations": [],
            },
        })

    for cue_id, created, dispatched, episode in (
        (f"{session_id}:1", 100, 120, 1),
        (f"{session_id}:2", 200, 220, 2),
    ):
        events.append({
            "type": "audit", "session_id": session_id, "at_ms": dispatched,
            "data": {"message": (
                f"CueDispatch cueId={cue_id} kind=NEAR_ZONE category=NEAR_ZONE "
                f"createdAtMs={created} dispatchAtMs={dispatched} dispatchDelayMs={dispatched-created} "
                "requestedMask=7 acceptedMask=7 outcome=ACCEPTED dropReason=none"
            )},
        })
        events.append({
            "type": "audit", "session_id": session_id, "at_ms": dispatched,
            "data": {"message": (
                f"NearZoneEvent event=NEAR_ENTER episode={episode} sector=2 "
                "suppression=none outcome=ACCEPTED"
            )},
        })

    # Both events share window 1. The COALESCED context keeps the first
    # window's start, so its requested/completed frame must identify its own
    # trigger and its post screenshots must begin after that later trigger.
    for at_ms, context in (
        (115, {
            "window_id": 1, "status": "ACCEPTED", "reason": "near_enter",
            "requested_at_ms": 110, "start_observed_at_ms": 100,
            "end_observed_at_ms": 350, "pre_frame_indices": [],
        }),
        (215, {
            "window_id": 1, "status": "COALESCED", "reason": "near_enter",
            "requested_at_ms": 210, "start_observed_at_ms": 100,
            "end_observed_at_ms": 350, "pre_frame_indices": [],
        }),
    ):
        events.append({"type": "image_context", "session_id": session_id,
                       "at_ms": at_ms, "data": context})

    image_specs = (
        (1, 100, "near_enter"), (2, 150, "post_context"),
        (3, 200, "near_enter"), (4, 250, "post_context"),
    )
    image_payloads: dict[str, bytes] = {}
    for frame_index, observed, reason in image_specs:
        for kind in ("screen", "map"):
            name = f"images/{kind}-{frame_index:04d}.jpg"
            image_payloads[name] = b"\xff\xd8\xfffixture"
            events.append({
                "type": "image", "session_id": session_id,
                "at_ms": observed, "frame_index": frame_index,
                "observed_at_ms": observed, "reason": reason,
                "window_id": 1, "file": name,
            })

    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("metadata.json", json.dumps(metadata))
        archive.writestr("summary.json", json.dumps(summary))
        archive.writestr("events.jsonl", "\n".join(json.dumps(event) for event in events) + "\n")
        for name, payload in image_payloads.items():
            archive.writestr(name, payload)
    return path


def test_reports_real_diagnostic_recording_and_embeds_screenshots(tmp_path: Path) -> None:
    if not (REAL_DIAGNOSTIC_DIR / "events.jsonl").is_file():
        pytest.skip("real extracted DiagnosticRecorder sample is not available")
    archive = _archive_from_extracted_recording(tmp_path)

    report, inline_images = analyze_diagnostic_zip(archive)
    json_path, html_path = write_outputs(report, inline_images, tmp_path / "report")
    rendered_json = json.loads(json_path.read_text(encoding="utf-8"))
    rendered_html = html_path.read_text(encoding="utf-8")

    assert report["source"]["app_version"] == {"name": "0.3.5", "code": 13}
    assert report["source"]["archive_sha256"] == hashlib.sha256(archive.read_bytes()).hexdigest()
    assert report["cues"]["dispatch_count"] == 11
    assert report["cues"]["callback_count"] > 0
    assert report["cues"]["detected_count_reported"] == 11
    assert report["thermal"]["battery_temperature_c"]["count"] == 51
    assert report["processing_and_load"]["frame_count_in_events"] == 1048
    assert report["processing_and_load"]["image_diagnostics"]["context_copy_total_ms"] == 23003.127
    assert report["processing_and_load"]["image_diagnostics"]["context_copy_mean_ms"] == 34.959
    assert report["processing_and_load"]["image_diagnostics"]["context_copy_max_ms"] == 123.375
    assert report["processing_and_load"]["image_diagnostics"]["peak_image_frames"] == 6
    assert report["image_evidence"]["available_embedded_images"] == 214
    markers = [marker for marker in report["image_evidence"]["markers"] if marker["kind"] == "event"]
    assert len(markers) == 11
    assert sum(marker["context_status"] == "ACCEPTED" for marker in markers) == 9
    limited = [marker for marker in markers if marker["context_status"] == "RATE_LIMITED"]
    assert len(limited) == 2
    assert all(len(marker["trigger_image_ids"]) == 2 for marker in limited)
    assert all(not marker["pre_image_ids"] and not marker["post_image_ids"] for marker in limited)
    assert all(marker["cue_id"] for marker in markers)
    assert all(marker["context_match_method"] == "exact_created_at_frame" for marker in markers)
    assert sum(len(marker["pre_image_ids"]) > 0 for marker in markers) == 9
    assert sum(len(marker["post_image_ids"]) > 0 for marker in markers) == 9
    assert "data:image/jpeg;base64," in rendered_html
    assert 'href="#marker-event-1"' in rendered_html
    assert 'download="image-0001.jpg"' in rendered_html
    assert "点击图片保存此截图" in rendered_html
    assert 'target="_blank"' not in rendered_html
    assert "event-link-test:1" in rendered_html or all(marker["cue_id"] for marker in markers)
    assert "V2282A" not in json.dumps(rendered_json)
    assert '"session_id"' not in json.dumps(rendered_json)
    assert rendered_json["source"]["privacy"]["cue_ids_include_session_prefix"] is True
    assert "Cue ID 保留原值" in rendered_html
    assert "acceptance" in rendered_json and rendered_json["acceptance"]["status"] == "not_assessed"
    assert report["session"]["duration_ms"] == 503613


def test_rejects_archive_path_traversal(tmp_path: Path) -> None:
    archive_path = _archive_from_extracted_recording(tmp_path, malicious_drop_reason="none")
    with zipfile.ZipFile(archive_path, "a") as archive:
        archive.writestr("../escape.jpg", b"not an image")

    with pytest.raises(ValueError, match="path that escapes"):
        analyze_diagnostic_zip(archive_path)


def test_pairs_latency_csv_by_cue_id_and_checks_kind(tmp_path: Path) -> None:
    archive = _archive_from_extracted_recording(tmp_path, malicious_drop_reason="none")
    csv_path = _latency_csv(tmp_path / "latency.csv")

    report, _ = analyze_diagnostic_zip(archive, csv_path)

    assert report["external_latency"]["matched_pair_count"] == 1
    assert report["external_latency"]["pairs"][0]["measured_delay_ms"] == 450
    assert report["external_latency"]["cue_id_coverage"]["complete_against_recorded_dispatches"]

    mismatched = _latency_csv(tmp_path / "wrong-kind.csv", kind="main_enemy")
    with pytest.raises(ValueError, match="does not match CueDispatch"):
        analyze_diagnostic_zip(archive, mismatched)

    unknown = _latency_csv(tmp_path / "unknown.csv", cue_id="elsewhere:9")
    with pytest.raises(ValueError, match="unknown cue_id"):
        analyze_diagnostic_zip(archive, unknown)


def test_latency_mapping_uses_emitted_android_kind(tmp_path: Path) -> None:
    archive = _archive_from_extracted_recording(
        tmp_path, malicious_drop_reason="none", dispatch_kind="PERIPHERAL_THREAT"
    )
    csv_path = _latency_csv(tmp_path / "peripheral.csv", kind="main_enemy")

    report, _ = analyze_diagnostic_zip(archive, csv_path)

    assert report["external_latency"]["pairs"][0]["kind"] == "main_enemy"

    radar = _archive_from_extracted_recording(
        tmp_path, malicious_drop_reason="none", dispatch_kind="RADAR_STATUS"
    )
    radar_csv = _latency_csv(tmp_path / "radar.csv", kind="radar_status")
    radar_report, _ = analyze_diagnostic_zip(radar, radar_csv)
    assert radar_report["external_latency"]["pairs"][0]["kind"] == "radar_status"


def test_html_escapes_audit_text(tmp_path: Path) -> None:
    archive = _archive_from_extracted_recording(
        tmp_path, malicious_drop_reason="<svg/onload=alert(1)>"
    )
    report, inline_images = analyze_diagnostic_zip(archive)

    _, html_path = write_outputs(report, inline_images, tmp_path / "escaped")
    rendered = html_path.read_text(encoding="utf-8")

    assert "<svg/onload=alert(1)>" not in rendered
    assert "&lt;svg/onload=alert(1)&gt;" in rendered


def test_near_event_links_use_dispatch_timestamps_without_nearest_window_fallback(
    tmp_path: Path,
) -> None:
    archive = _write_near_event_link_zip(tmp_path / "near-link.zip")

    report, _ = analyze_diagnostic_zip(archive)

    events = [marker for marker in report["image_evidence"]["markers"] if marker["kind"] == "event"]
    assert len(events) == 2
    assert events[0]["cue_id"] == "event-link-test:1"
    assert events[0]["context_status"] is None
    assert "no exact" not in (events[0].get("evidence_gap") or "")
    assert events[1]["cue_id"] == "event-link-test:2"
    assert events[1]["context_status"] == "ACCEPTED"
    assert events[1]["context_match_method"] == "exact_created_at_frame"


def test_coalesced_context_matches_its_own_trigger_frame_and_post_window(tmp_path: Path) -> None:
    archive = _write_coalesced_context_zip(tmp_path / "coalesced-link.zip")

    report, _ = analyze_diagnostic_zip(archive)

    markers = [marker for marker in report["image_evidence"]["markers"] if marker["kind"] == "event"]
    assert len(markers) == 2
    accepted, coalesced = markers
    assert accepted["cue_id"] == "coalesced-link-test:1"
    assert coalesced["cue_id"] == "coalesced-link-test:2"
    assert accepted["window_id"] == coalesced["window_id"] == 1
    assert accepted["context_status"] == "ACCEPTED"
    assert coalesced["context_status"] == "COALESCED"
    assert accepted["trigger_frame_index"] == 1
    assert coalesced["trigger_frame_index"] == 3
    assert accepted["context_match_method"] == coalesced["context_match_method"] == "exact_created_at_frame"
    assert accepted["trigger_image_ids"] == ["image-0001", "image-0002"]
    assert coalesced["trigger_image_ids"] == ["image-0005", "image-0006"]
    assert accepted["post_image_ids"] == ["image-0003", "image-0004", "image-0007", "image-0008"]
    assert coalesced["post_image_ids"] == ["image-0007", "image-0008"]
    assert accepted["evidence_gap"] is None
    assert coalesced["evidence_gap"] is None
