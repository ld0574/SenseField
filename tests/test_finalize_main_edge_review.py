from __future__ import annotations

import hashlib
import importlib
import json
import sqlite3
from pathlib import Path

import pytest

from mapassist.annotation_server import AnnotationStore
from training.audit_main_edge_queue import run
from training.finalize_main_edge_review import (
    _safe_media_path,
    _sealed,
    _validate_rows,
    finalize_main_edge_review,
)

from test_main_edge_review_batch import _edge_source


def _complete_as_negative(batch: Path) -> None:
    with sqlite3.connect(batch / "annotations.sqlite3") as connection:
        connection.execute(
            """
            UPDATE tasks SET review_status = 'negative', reviewed_by = ?,
                reviewed_at = ?, reviewed_boxes = NULL, reviewed_categories = NULL,
                lease_owner = NULL, lease_until = NULL
            """,
            ("human", "2026-09-30T00:00:00Z"),
        )
        connection.commit()
    # Export through the existing store to keep the fixture's manifest and
    # database representations identical, as a real review server would.
    AnnotationStore(batch).export_manifest()


def _set_first_corrected(batch: Path) -> None:
    with sqlite3.connect(batch / "annotations.sqlite3") as connection:
        connection.execute(
            """
            UPDATE tasks SET review_status = 'corrected', reviewed_boxes = ?,
                reviewed_categories = ?, version = version + 1
            WHERE id = (SELECT MIN(id) FROM tasks)
            """,
            (json.dumps([[0.1, 0.2, 0.05, 0.02]]), json.dumps(["main_enemy"])),
        )
        connection.commit()
    AnnotationStore(batch).export_manifest()


def test_completion_audit_is_reproducible_and_preserves_selection_history(tmp_path: Path) -> None:
    source = _edge_source(tmp_path)
    batch = tmp_path / "edge-focus"
    run(source, batch)
    _complete_as_negative(batch)
    history_before = {
        name: (batch / name).read_bytes() for name in ("audit.json", "summary.json")
    }

    audit = finalize_main_edge_review(batch)
    audit_path = batch / "review-completion-audit.json"
    detection_path = batch / "detection-manifest.json"
    assert audit_path.is_file()
    assert detection_path.is_file()
    assert audit["terminal"]["status_counts"] == {"negative": 20}
    assert audit["terminal"]["pending"] == 0
    assert audit["terminal"]["leases"]["any"] == 0
    assert audit["terminal"]["images"]["frame_files"] == 20
    assert audit["terminal"]["images"]["overlay_files"] == 20
    assert audit["dataset_scope"]["enemy_hero_training_truth"] is False
    assert "Camera drift" in audit["dataset_scope"]["warning"]
    assert audit["selection_history"]["audit.json"]["sha256"] == hashlib.sha256(
        history_before["audit.json"]
    ).hexdigest()
    assert audit["selection_history"]["summary.json"]["sha256"] == hashlib.sha256(
        history_before["summary.json"]
    ).hexdigest()
    assert {name: (batch / name).read_bytes()
            for name in history_before} == history_before

    detection = json.loads(detection_path.read_text(encoding="utf-8"))
    assert detection["source_category"] == "main_enemy"
    assert detection["category"] == "main_red_candidate"
    assert detection["classes"] == ["main_red_candidate"]
    assert detection["dataset_scope"] == audit["dataset_scope"]
    assert len(detection["matches"]) == 1
    assert len(detection["matches"][0]["frames"]) == 20
    assert all(not frame["boxes"] for frame in detection["matches"][0]["frames"])

    audit_bytes = audit_path.read_bytes()
    detection_bytes = detection_path.read_bytes()
    rerun = finalize_main_edge_review(batch)
    assert audit_path.read_bytes() == audit_bytes
    assert detection_path.read_bytes() == detection_bytes
    assert rerun["detection_manifest"]["boxes"] == 0


def test_completion_audit_rejects_lease_without_replacing_existing_detection(tmp_path: Path) -> None:
    source = _edge_source(tmp_path)
    batch = tmp_path / "edge-focus"
    run(source, batch)
    _complete_as_negative(batch)
    detection_path = batch / "detection-manifest.json"
    detection_path.write_text('{"sentinel": true}\n', encoding="utf-8")
    with sqlite3.connect(batch / "annotations.sqlite3") as connection:
        connection.execute(
            "UPDATE tasks SET lease_owner = ?, lease_until = ? WHERE id = 1",
            ("someone-else", 9_999_999_999),
        )
        connection.commit()

    with pytest.raises(ValueError, match="leased tasks"):
        finalize_main_edge_review(batch)
    assert detection_path.read_text(encoding="utf-8") == '{"sentinel": true}\n'
    assert not (batch / "review-completion-audit.json").exists()


def test_completion_audit_rejects_source_metadata_drift(tmp_path: Path) -> None:
    source = _edge_source(tmp_path)
    batch = tmp_path / "edge-focus"
    run(source, batch)
    _complete_as_negative(batch)
    source_manifest_path = source / "queue" / "review-manifest.json"
    source_manifest = json.loads(source_manifest_path.read_text(encoding="utf-8"))
    source_manifest["matches"][0]["video_sha256"] = "0" * 64
    source_manifest_path.write_text(json.dumps(source_manifest), encoding="utf-8")

    with pytest.raises(ValueError, match="metadata mismatch"):
        finalize_main_edge_review(batch)
    assert not (batch / "review-completion-audit.json").exists()


def test_completion_export_downgrades_source_enemy_label_to_red_candidate(
    tmp_path: Path,
) -> None:
    source = _edge_source(tmp_path)
    batch = tmp_path / "edge-focus"
    run(source, batch)
    _complete_as_negative(batch)
    _set_first_corrected(batch)

    audit = finalize_main_edge_review(batch)
    detection = json.loads(
        (batch / "detection-manifest.json").read_text(encoding="utf-8")
    )
    categories = [
        category
        for match in detection["matches"]
        for frame in match["frames"]
        for category in frame["categories"]
    ]
    assert categories == ["main_red_candidate"]
    assert audit["dataset_scope"]["source_annotation_category"] == "main_enemy"
    assert audit["dataset_scope"]["export_category"] == "main_red_candidate"
    assert audit["dataset_scope"]["training_truth"] is False


def test_completion_rejects_output_paths_overlapping_inputs(tmp_path: Path) -> None:
    source = _edge_source(tmp_path)
    batch = tmp_path / "edge-focus"
    run(source, batch)
    _complete_as_negative(batch)

    targets = [
        batch / "annotations.sqlite3",
        source / "queue" / "review-manifest.json",
    ]
    for target in targets:
        before = target.read_bytes()
        with pytest.raises(ValueError, match="overlap|json suffix"):
            finalize_main_edge_review(batch, detection_output=target)
        assert target.read_bytes() == before
    assert not (batch / "review-completion-audit.json").exists()


@pytest.mark.parametrize("output_argument", ["detection_output", "audit_output"])
def test_completion_rejects_image_output_path_before_writing(
    tmp_path: Path, output_argument: str,
) -> None:
    source = _edge_source(tmp_path)
    batch = tmp_path / "edge-focus"
    run(source, batch)
    _complete_as_negative(batch)
    manifest = json.loads((batch / "review-manifest.json").read_text(encoding="utf-8"))
    image_path = batch / manifest["matches"][0]["samples"][0]["frame"]
    before = image_path.read_bytes()

    with pytest.raises(ValueError, match="json suffix"):
        finalize_main_edge_review(batch, **{output_argument: image_path})

    assert image_path.read_bytes() == before
    assert not (batch / "detection-manifest.json").exists()
    assert not (batch / "review-completion-audit.json").exists()


def test_completion_rejects_symlink_output_path(tmp_path: Path) -> None:
    source = _edge_source(tmp_path)
    batch = tmp_path / "edge-focus"
    run(source, batch)
    _complete_as_negative(batch)
    alias = tmp_path / "detection-alias.json"
    try:
        alias.symlink_to(batch / "annotations.sqlite3")
    except OSError:
        pytest.skip("symlinks are unavailable")

    with pytest.raises(ValueError, match="symlink"):
        finalize_main_edge_review(batch, detection_output=alias)
    assert not (batch / "review-completion-audit.json").exists()


def test_completion_rolls_back_both_outputs_when_second_replace_fails(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _edge_source(tmp_path)
    batch = tmp_path / "edge-focus"
    run(source, batch)
    _complete_as_negative(batch)
    detection_path = batch / "detection-manifest.json"
    audit_path = batch / "review-completion-audit.json"
    detection_path.write_text("old detection\n", encoding="utf-8")
    audit_path.write_text("old audit\n", encoding="utf-8")

    module = importlib.import_module("training.finalize_main_edge_review")
    original_replace = module.os.replace
    state = {"failed": False}

    def fail_once(source_path: Path, destination: Path) -> None:
        if Path(destination) == audit_path and not state["failed"]:
            state["failed"] = True
            raise OSError("injected audit replace failure")
        original_replace(source_path, destination)

    monkeypatch.setattr(module.os, "replace", fail_once)
    with pytest.raises(OSError, match="injected audit replace failure"):
        finalize_main_edge_review(batch)
    assert detection_path.read_text(encoding="utf-8") == "old detection\n"
    assert audit_path.read_text(encoding="utf-8") == "old audit\n"


def test_completion_preserves_backups_when_rollback_replace_fails(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _edge_source(tmp_path)
    batch = tmp_path / "edge-focus"
    run(source, batch)
    _complete_as_negative(batch)
    detection_path = batch / "detection-manifest.json"
    audit_path = batch / "review-completion-audit.json"
    detection_path.write_text("old detection\n", encoding="utf-8")
    audit_path.write_text("old audit\n", encoding="utf-8")

    module = importlib.import_module("training.finalize_main_edge_review")
    original_replace = module.os.replace

    def fail_audit_destination(source_path: Path, destination: Path) -> None:
        if Path(destination) == audit_path:
            raise OSError("injected audit replace failure")
        original_replace(source_path, destination)

    monkeypatch.setattr(module.os, "replace", fail_audit_destination)
    with pytest.raises(RuntimeError, match="preserved backups") as raised:
        finalize_main_edge_review(batch)

    backups = sorted(batch.glob(".*.tmp"))
    assert any(path.read_text(encoding="utf-8") == "old detection\n" for path in backups)
    assert any(path.read_text(encoding="utf-8") == "old audit\n" for path in backups)
    assert all(str(path) in str(raised.value) for path in backups)


def test_completion_rejects_source_manifest_self_reference(tmp_path: Path) -> None:
    source = _edge_source(tmp_path)
    batch = tmp_path / "edge-focus"
    run(source, batch)
    manifest_path = batch / "review-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["provenance"]["source_queue"] = str(batch)
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    _complete_as_negative(batch)

    with pytest.raises(ValueError, match="must differ"):
        finalize_main_edge_review(batch)
    assert not (batch / "review-completion-audit.json").exists()


@pytest.mark.parametrize(
    "token",
    ["video9", "video-9", "video_9", "video12", "video-12", "video_12",
     "video9-player", "video_12-hd"],
)
def test_sealed_variants_are_rejected_before_media_access(token: str) -> None:
    assert _sealed(token)


def test_resolved_media_target_is_sealed_before_open(tmp_path: Path) -> None:
    batch = tmp_path / "batch"
    (batch / "frames").mkdir(parents=True)
    sealed_target = batch / "video9" / "frame.png"
    try:
        (batch / "frames" / "link.png").symlink_to(sealed_target)
    except OSError:
        pytest.skip("symlinks are unavailable")

    with pytest.raises(ValueError, match="sealed video9/video12"):
        _safe_media_path(batch, "frames/link.png", "frame")


def test_completion_accepts_root_orientation_fallback(tmp_path: Path) -> None:
    source = _edge_source(tmp_path)
    batch = tmp_path / "edge-focus"
    run(source, batch)
    manifest_path = batch / "review-manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    root_orientation = manifest["matches"][0].pop("orientation")
    manifest["orientation"] = root_orientation
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    _complete_as_negative(batch)

    audit = finalize_main_edge_review(batch)
    assert audit["detection_manifest"]["frames"] == 20


def test_completion_accepts_resolved_relative_video_reference(tmp_path: Path) -> None:
    source = _edge_source(tmp_path)
    source_video = tmp_path / "video1-edge.mp4"
    source_video.write_bytes(b"fixture video metadata only")
    source_manifest_path = source / "queue" / "review-manifest.json"
    source_manifest = json.loads(source_manifest_path.read_text(encoding="utf-8"))
    source_manifest["matches"][0]["video"] = source_video.name
    source_manifest_path.write_text(json.dumps(source_manifest), encoding="utf-8")
    batch = tmp_path / "edge-focus"
    run(source, batch)
    _complete_as_negative(batch)

    audit = finalize_main_edge_review(batch)
    assert audit["detection_manifest"]["frames"] == 20


def test_completion_explicitly_rejects_only_skipped_samples(tmp_path: Path) -> None:
    source = _edge_source(tmp_path)
    batch = tmp_path / "edge-focus"
    run(source, batch)
    with sqlite3.connect(batch / "annotations.sqlite3") as connection:
        connection.execute(
            """UPDATE tasks SET review_status = 'skip', reviewed_by = ?,
                reviewed_at = ?, reviewed_boxes = NULL, reviewed_categories = NULL,
                lease_owner = NULL, lease_until = NULL""",
            ("human", "2026-09-30T00:00:00Z"),
        )
        connection.commit()
    AnnotationStore(batch).export_manifest()

    with pytest.raises(ValueError, match="only skip/excluded"):
        finalize_main_edge_review(batch)
    assert not (batch / "review-completion-audit.json").exists()


def test_completion_requires_selection_history(tmp_path: Path) -> None:
    source = _edge_source(tmp_path)
    batch = tmp_path / "edge-focus"
    run(source, batch)
    _complete_as_negative(batch)
    (batch / "summary.json").unlink()

    with pytest.raises(ValueError, match="selection history is missing"):
        finalize_main_edge_review(batch)
    assert not (batch / "review-completion-audit.json").exists()


def test_invalid_database_identity_raises_value_error(tmp_path: Path) -> None:
    row = {
        "id": 1,
        "match_id": [],
        "at_ms": 0,
    }
    with pytest.raises(ValueError, match="match_id"):
        _validate_rows(tmp_path, {}, {}, ["main_enemy"], [row])


@pytest.mark.parametrize(
    ("target_name", "error_pattern"),
    [
        ("source", "source review manifest changed"),
        ("audit.json", "selection history audit.json changed"),
        ("summary.json", "selection history summary.json changed"),
    ],
)
def test_completion_rechecks_source_and_selection_history_before_commit(
    tmp_path: Path,
    target_name: str,
    error_pattern: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _edge_source(tmp_path)
    batch = tmp_path / "edge-focus"
    run(source, batch)
    _complete_as_negative(batch)

    if target_name == "source":
        target = source / "queue" / "review-manifest.json"
    else:
        target = batch / target_name
    original = target.read_bytes()
    module = importlib.import_module("training.finalize_main_edge_review")
    original_stage = module._stage_json

    def stage_and_mutate(path: Path, value: object) -> Path:
        staged = original_stage(path, value)
        target.write_bytes(original + b"\n")
        return staged

    monkeypatch.setattr(module, "_stage_json", stage_and_mutate)
    with pytest.raises(ValueError, match=error_pattern):
        finalize_main_edge_review(batch)

    assert not (batch / "detection-manifest.json").exists()
    assert not (batch / "review-completion-audit.json").exists()


@pytest.mark.parametrize("name", [
    "review-manifest.json", "annotations.sqlite3", "audit.json", "summary.json",
])
def test_completion_rejects_symlinked_batch_inputs(tmp_path: Path, name: str) -> None:
    source = _edge_source(tmp_path)
    batch = tmp_path / "edge-focus"
    run(source, batch)
    _complete_as_negative(batch)

    path = batch / name
    target = tmp_path / f"real-{name}"
    target.write_bytes(path.read_bytes())
    before = target.read_bytes()
    path.unlink()
    try:
        path.symlink_to(target)
    except OSError:
        pytest.skip("symlinks are unavailable")

    with pytest.raises(ValueError, match="symlink"):
        finalize_main_edge_review(batch)

    assert target.read_bytes() == before
    assert not (batch / "review-completion-audit.json").exists()


def test_completion_accepts_external_source_manifest_symlink(tmp_path: Path) -> None:
    source = _edge_source(tmp_path)
    batch = tmp_path / "edge-focus"
    run(source, batch)
    _complete_as_negative(batch)

    source_manifest = source / "queue" / "review-manifest.json"
    external_target = tmp_path / "external-source-manifest.json"
    external_target.write_bytes(source_manifest.read_bytes())
    source_alias = tmp_path / "external-source-alias.json"
    try:
        source_alias.symlink_to(external_target)
    except OSError:
        pytest.skip("symlinks are unavailable")

    audit = finalize_main_edge_review(batch, source_manifest=source_alias)

    assert audit["source"]["manifest"] == str(external_target.resolve())
