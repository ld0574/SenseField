from __future__ import annotations

import copy
import hashlib
import json
import shutil
import sqlite3
from pathlib import Path

import pytest
from PIL import Image

from mapassist.roi_safety import has_supported_coco_roi_audit

from training.export_minimap_dual_class import (
    _load_source_context,
    _new_audit,
    export,
)


TASK_COLUMNS = """
CREATE TABLE tasks (
    id INTEGER PRIMARY KEY,
    match_id TEXT NOT NULL,
    split TEXT NOT NULL,
    at_ms INTEGER NOT NULL,
    selection TEXT NOT NULL,
    frame TEXT NOT NULL,
    overlay TEXT NOT NULL,
    suggested_boxes TEXT NOT NULL,
    suggested_categories TEXT NOT NULL DEFAULT '[]',
    directions TEXT NOT NULL,
    review_status TEXT NOT NULL DEFAULT 'pending',
    reviewed_boxes TEXT,
    reviewed_categories TEXT,
    reviewed_by TEXT,
    reviewed_at TEXT,
    lease_owner TEXT,
    lease_until REAL,
    version INTEGER NOT NULL DEFAULT 1,
    UNIQUE(match_id, at_ms)
)
"""


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _boxes_json(boxes: list[list[float]] | None) -> str | None:
    return json.dumps(boxes, separators=(",", ":")) if boxes is not None else None


def _write_db(path: Path, rows: list[dict]) -> None:
    connection = sqlite3.connect(path)
    connection.execute(TASK_COLUMNS)
    for row in rows:
        connection.execute(
            """
            INSERT INTO tasks (
                id, match_id, split, at_ms, selection, frame, overlay,
                suggested_boxes, suggested_categories, directions,
                review_status, reviewed_boxes, reviewed_categories,
                reviewed_by, reviewed_at, lease_owner, lease_until, version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                row["id"], row["match_id"], row["split"], row["at_ms"],
                "fixture", row["frame"], row["frame"],
                _boxes_json(row.get("suggested_boxes", [])),
                _boxes_json(row.get("suggested_categories", [])), "[]",
                row["review_status"], _boxes_json(row.get("reviewed_boxes")),
                _boxes_json(row.get("reviewed_categories")),
                row.get("reviewed_by"), row.get("reviewed_at"), None, None, 1,
            ),
        )
    connection.commit()
    connection.close()


def _fixture(tmp_path: Path, *, accepted: bool = False,
             corrupt_player_frame: bool = False,
             roi: list[float] | None = None,
             enemy_first_box: list[float] | None = None,
             player_first_box: list[float] | None = None) -> tuple[Path, Path, Path]:
    repository = tmp_path / "repo"
    source = repository / "source-enemy"
    player = repository / "player-queue"
    source.mkdir(parents=True)
    player.mkdir(parents=True)
    source_frames = source / "frames"
    player_frames = player / "frames"
    source_frames.mkdir()
    player_frames.mkdir()

    frame_bytes = []
    for at_ms, color in ((100, (220, 20, 20)), (200, (20, 220, 20)),
                         (300, (20, 20, 220)), (400, (220, 220, 20))):
        source_frame = source_frames / f"enemy-{at_ms}.png"
        Image.new("RGB", (100, 80), color).save(source_frame)
        player_frame = player_frames / f"player-{at_ms}.png"
        player_frame.write_bytes(source_frame.read_bytes())
        frame_bytes.append((at_ms, source_frame, player_frame))
    if corrupt_player_frame:
        Image.new("RGB", (100, 80), (0, 0, 0)).save(frame_bytes[0][2])

    roi = roi or [0.1, 0.1, 0.8, 0.75]
    source_rows = [
        {"id": 1, "match_id": "enemy-1", "split": "train", "at_ms": 100,
         "frame": "frames/enemy-100.png", "review_status": "corrected",
         "reviewed_boxes": [[0.2, 0.2, 0.1, 0.15]],
         "reviewed_categories": ["minimap_enemy"], "reviewed_by": "enemy-human",
         "reviewed_at": "2026-09-30T00:00:01Z"},
        {"id": 2, "match_id": "enemy-1", "split": "train", "at_ms": 200,
         "frame": "frames/enemy-200.png", "review_status": "negative",
         "reviewed_boxes": None, "reviewed_categories": None,
         "reviewed_by": "enemy-human", "reviewed_at": "2026-09-30T00:00:02Z"},
        {"id": 3, "match_id": "enemy-1", "split": "train", "at_ms": 300,
         "frame": "frames/enemy-300.png", "review_status": "skip",
         "reviewed_boxes": None, "reviewed_categories": None,
         "reviewed_by": None, "reviewed_at": None},
        {"id": 4, "match_id": "enemy-1", "split": "train", "at_ms": 400,
         "frame": "frames/enemy-400.png", "review_status": "pending",
         "reviewed_boxes": None, "reviewed_categories": None,
         "reviewed_by": None, "reviewed_at": None},
    ]
    if enemy_first_box is not None:
        source_rows[0]["reviewed_boxes"] = [enemy_first_box]
    source_samples = []
    for row, (_, source_frame, _) in zip(source_rows, frame_bytes):
        sample = {
            "at_ms": row["at_ms"], "frame": row["frame"],
            "selection": "fixture", "suggested_boxes": [], "directions": [],
            "review_status": row["review_status"],
            "reviewed_boxes": row["reviewed_boxes"],
            "reviewed_by": row["reviewed_by"], "reviewed_at": row["reviewed_at"],
        }
        if row["reviewed_categories"] is not None:
            sample["reviewed_categories"] = row["reviewed_categories"]
        source_samples.append(sample)
    source_manifest = {
        "schema_version": 1, "kind": "minimap_enemy", "review_mode": "manual",
        "roi": roi, "classes": ["minimap_enemy"],
        "matches": [{
            "id": "enemy-1", "split": "train", "video": "video.mp4",
            "video_sha256": "a" * 64, "roi": roi, "samples": source_samples,
        }],
    }
    source_manifest_path = source / "review-manifest.json"
    source_manifest_path.write_text(json.dumps(source_manifest), encoding="utf-8")
    video = source / "video.mp4"
    video.write_bytes(b"small allowed video fixture")
    # The fixture declares the real video hash so strict byte verification is
    # available when needed without depending on a video decoder.
    source_manifest["matches"][0]["video_sha256"] = _sha256(video)
    source_manifest_path.write_text(json.dumps(source_manifest), encoding="utf-8")
    source_db = source / "annotations.sqlite3"
    _write_db(source_db, source_rows)

    player_rows = []
    player_samples = []
    statuses = ["corrected", "negative", "skip", "corrected"]
    for index, (row, (at_ms, _, player_frame)) in enumerate(zip(source_rows, frame_bytes), start=1):
        status = statuses[index - 1]
        player_box = (
            [player_first_box] if status == "corrected" and index == 1 and
            player_first_box is not None else
            [[0.3, 0.3, 0.1, 0.12]] if status == "corrected" else None
        )
        player_categories = ["minimap_player"] if status == "corrected" else None
        if accepted and index == 1:
            status = "accepted"
        player_row = {
            "id": index, "match_id": "player-1", "split": "train", "at_ms": at_ms,
            "frame": f"frames/player-{at_ms}.png", "review_status": status,
            "reviewed_boxes": player_box if status == "corrected" else None,
            "reviewed_categories": player_categories if status == "corrected" else None,
            "reviewed_by": "player-human" if status in {"corrected", "negative"} else None,
            "reviewed_at": f"2026-09-30T00:01:0{index}Z" if status in {"corrected", "negative"} else None,
            "suggested_boxes": [[0.5, 0.5, 0.01, 0.01]],
            "suggested_categories": ["minimap_player"],
        }
        player_rows.append(player_row)
        player_sample = {
            "at_ms": at_ms, "frame": player_row["frame"],
            "selection": "fixture", "suggested_boxes": player_row["suggested_boxes"],
            "suggested_categories": player_row["suggested_categories"], "directions": [],
            "review_status": status, "reviewed_boxes": player_row["reviewed_boxes"],
            "reviewed_categories": player_row["reviewed_categories"],
            "reviewed_by": player_row["reviewed_by"], "reviewed_at": player_row["reviewed_at"],
        }
        player_samples.append(player_sample)
    player_manifest = {
        "schema_version": 1, "kind": "minimap_player", "classes": ["minimap_player"],
        "review_mode": "manual", "roi": roi,
        "matches": [{
            "id": "player-1", "split": "train", "roi": roi,
            "source_match_id": "enemy-1", "source_manifest_split": "train",
            "source_manifest": str(source_manifest_path),
            "source_manifest_sha256": _sha256(source_manifest_path),
            "source_database": str(source_db),
            "source_database_sha256": _sha256(source_db),
            "source_video_path": str(video), "source_video_sha256": _sha256(video),
            "orientation": {"display_size": [100, 80], "display_rotation_degrees": 0},
            "provenance_samples": [], "samples": player_samples,
        }],
    }
    for (at_ms, source_frame, player_frame), player_sample in zip(frame_bytes, player_samples):
        player_manifest["matches"][0]["provenance_samples"].append({
            "at_ms": at_ms, "source_frame": str(source_frame),
            "source_frame_sha256": _sha256(source_frame), "source_frame_size": [100, 80],
            "queue_frame": str(player_frame), "review_status": "pending",
        })
    player_manifest_path = player / "review-manifest.json"
    player_manifest_path.write_text(json.dumps(player_manifest), encoding="utf-8")
    player_db = player / "annotations.sqlite3"
    _write_db(player_db, player_rows)
    # Source DB and manifest hashes are recorded only after both are complete.
    # Player manifest's source hashes are already stable; rewrite once in case
    # a test fixture changes the source files before this point.
    return player, source, player_manifest_path


def test_export_uses_only_human_terminal_labels_and_fixed_categories(tmp_path: Path) -> None:
    player, _, _ = _fixture(tmp_path)
    output = tmp_path / "dual-coco"
    audit = export(player, output, verify_video_bytes=True)

    assert audit["status"] == "passed"
    assert audit["counts"]["exported_images"] == 2
    assert audit["counts"]["boxes_by_class"] == {
        "minimap_enemy": 1, "minimap_player": 1,
    }
    assert audit["splits"]["train"]["negative_images"] == 1
    assert {row["reason"] for row in audit["excluded"]} == {
        "player_skip", "enemy_not_human_terminal",
    }
    train = json.loads((output / "annotations/instances_train2017.json").read_text())
    assert [item["name"] for item in train["categories"]] == [
        "minimap_enemy", "minimap_player",
    ]
    assert len(train["images"]) == 2
    assert len(train["annotations"]) == 2
    assert audit["checks"]["video_bytes_verified"] is True


def test_export_writes_complete_roi_audit_and_separates_physical_edge_contacts(
    tmp_path: Path,
) -> None:
    player, _, _ = _fixture(
        tmp_path,
        roi=[0.1, 0.0, 0.8, 0.75],
        enemy_first_box=[0.2, 0.0, 0.1, 0.15],
        player_first_box=[0.3, 0.0, 0.1, 0.12],
    )
    output = tmp_path / "dual-coco"

    export(player, output)

    train = json.loads(
        (output / "annotations/instances_train2017.json").read_text()
    )
    roi_audit = train["info"]["roi_boundary_audit"]
    assert has_supported_coco_roi_audit(train)
    assert roi_audit["edge_tolerance_px"] == 1.0
    assert roi_audit["crop_edge_contacts"] == []
    assert roi_audit["edge_contacts"] == []
    assert roi_audit["physical_edge_contacts"] == [
        {"match_id": "enemy-1", "at_ms": 100, "box_index": 1, "sides": ["top"]},
        {"match_id": "enemy-1", "at_ms": 100, "box_index": 2, "sides": ["top"]},
    ]
    assert roi_audit["training_eligible"] is True
    assert roi_audit["usable_for_training_or_evaluation"] is True


def test_export_blocks_expandable_roi_edge_contacts(tmp_path: Path) -> None:
    player, _, _ = _fixture(
        tmp_path,
        enemy_first_box=[0.1, 0.2, 0.1, 0.15],
    )
    output = tmp_path / "dual-coco"
    audit_path = tmp_path / "dual-audit.json"

    with pytest.raises(ValueError, match="expandable crop edge"):
        export(player, output, audit_path=audit_path)

    assert not output.exists()
    audit = json.loads(audit_path.read_text())
    assert audit["status"] == "blocked"
    assert "expandable crop edge" in audit["fatal_error"]


def test_export_fails_closed_on_player_frame_hash_mismatch(tmp_path: Path) -> None:
    player, _, _ = _fixture(tmp_path, corrupt_player_frame=True)
    output = tmp_path / "dual-coco"
    audit_path = tmp_path / "dual-audit.json"

    with pytest.raises(ValueError, match="frame SHA-256 mismatch|player_queue_frame_sha256_mismatch|conflicts"):
        export(player, output, audit_path=audit_path)

    assert not output.exists()
    audit = json.loads(audit_path.read_text())
    assert audit["status"] == "blocked"
    assert audit["conflicts"]


def test_export_rejects_machine_accepted_player_status(tmp_path: Path) -> None:
    player, _, _ = _fixture(tmp_path, accepted=True)
    with pytest.raises(ValueError, match="machine accepted"):
        export(player, tmp_path / "dual-coco", audit_path=tmp_path / "audit.json")


def test_export_audits_stale_source_database_hash_after_semantic_validation(tmp_path: Path) -> None:
    player, source, _ = _fixture(tmp_path)
    manifest_path = player / "review-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    manifest["matches"][0]["source_database_sha256"] = "b" * 64
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    audit = export(player, tmp_path / "dual-coco", audit_path=tmp_path / "audit.json")
    assert audit["status"] == "passed_with_warnings"
    assert audit["warnings"][0]["reason"] == "source_database_sha256_mismatch_semantically_validated"


def test_export_rejects_inconsistent_cached_source_database_declarations(
    tmp_path: Path,
) -> None:
    player, source, player_manifest_path = _fixture(tmp_path)
    player_match = json.loads(player_manifest_path.read_text())["matches"][0]
    source_cache = {}
    audit = _new_audit(player, tmp_path / "dual-coco", tmp_path / "audit.json")

    _load_source_context(
        player, player_match, audit, source_cache,
        strict_source_database_hash=False, verify_video_bytes=False,
        video_hash_cache={},
    )

    alternate_database = source / "alternate.sqlite3"
    shutil.copy2(source / "annotations.sqlite3", alternate_database)
    different_path_match = copy.deepcopy(player_match)
    different_path_match["source_database"] = str(alternate_database)
    with pytest.raises(ValueError, match="source_database differs for cached source manifest"):
        _load_source_context(
            player, different_path_match, audit, source_cache,
            strict_source_database_hash=False, verify_video_bytes=False,
            video_hash_cache={},
        )

    different_hash_match = copy.deepcopy(player_match)
    different_hash_match["source_database_sha256"] = "b" * 64
    with pytest.raises(ValueError, match="source_database_sha256 differs for cached source manifest"):
        _load_source_context(
            player, different_hash_match, audit, source_cache,
            strict_source_database_hash=False, verify_video_bytes=False,
            video_hash_cache={},
        )


def test_export_fails_closed_when_source_manifest_and_database_disagree(tmp_path: Path) -> None:
    player, source, _ = _fixture(tmp_path)
    source_database = source / "annotations.sqlite3"
    with sqlite3.connect(source_database) as connection:
        connection.execute(
            "UPDATE tasks SET reviewed_boxes = ? WHERE match_id = ? AND at_ms = ?",
            (_boxes_json([[0.4, 0.4, 0.1, 0.1]]), "enemy-1", 100),
        )
    with pytest.raises(ValueError, match="differs from source manifest"):
        export(player, tmp_path / "dual-coco", audit_path=tmp_path / "audit.json")


def test_export_rejects_sealed_source_before_opening_it(tmp_path: Path) -> None:
    player, _, _ = _fixture(tmp_path)
    manifest_path = player / "review-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    manifest["matches"][0]["id"] = "video9-player"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    with pytest.raises(ValueError, match="sealed video9/video12"):
        export(player, tmp_path / "dual-coco", audit_path=tmp_path / "audit.json")
