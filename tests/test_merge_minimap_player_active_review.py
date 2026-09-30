from __future__ import annotations

import copy
import json
import sqlite3
from pathlib import Path

import pytest
from PIL import Image

from mapassist.annotation_server import AnnotationStore
from training.merge_minimap_player_active_review import merge


def _queue_root(tmp_path: Path, name: str, count: int = 3) -> Path:
    root = tmp_path / name
    media = root / "train" / "video3-player"
    media.mkdir(parents=True)
    samples = []
    for index in range(count):
        relative = f"train/video3-player/frame-{index}.png"
        Image.new("RGB", (100, 60), (20 + index, 40, 60)).save(root / relative)
        samples.append({
            "at_ms": index * 1000,
            "selection": "green_ring_suggestion",
            "frame": relative,
            "overlay": relative,
            "suggested_boxes": [[0.1, 0.1, 0.04, 0.06]] if index == 0 else [],
            "suggested_categories": ["minimap_player"] if index == 0 else [],
            "directions": [None] if index == 0 else [],
            "review_status": "pending",
            "reviewed_boxes": None,
            "reviewed_categories": None,
        })
    manifest = {
        "schema_version": 1,
        "kind": "minimap_player",
        "classes": ["minimap_player"],
        "review_mode": "manual",
        "roi": [0.0, 0.0, 1.0, 1.0],
        "widget_roi": [0.0, 0.0, 1.0, 1.0],
        "matches": [{
            "id": "video3-player",
            "split": "train",
            "samples": samples,
        }],
    }
    (root / "review-manifest.json").write_text(
        json.dumps(manifest), encoding="utf-8"
    )
    AnnotationStore(root)
    return root


def _active_batch(
    tmp_path: Path,
    source: Path,
    selected: tuple[int, ...] = (0, 1, 2),
    name: str = "active",
) -> Path:
    source_manifest = json.loads(
        (source / "review-manifest.json").read_text(encoding="utf-8")
    )
    active = tmp_path / name
    active_media = active / "train" / "video3-player"
    active_media.mkdir(parents=True)
    active_manifest = copy.deepcopy(source_manifest)
    source_samples = active_manifest["matches"][0]["samples"]
    active_manifest["matches"][0]["samples"] = [
        copy.deepcopy(source_samples[index]) for index in selected
    ]
    active_manifest["active_learning"] = {
        "schema": "mapassist.minimap_player_active_batch",
        "schema_version": 1,
        "source_queue": str(source.resolve()),
    }
    active_manifest["suggestion_provenance"] = {
        "is_ground_truth": False,
        "authority": "machine_suggestion_only",
    }
    for active_sample, index in zip(
        active_manifest["matches"][0]["samples"], selected
    ):
        active_sample["active_learning_source"] = {
            "source_task_id": index + 1,
            "is_ground_truth": False,
        }
        active_sample["suggestion_metadata"] = {
            "source": "learned_candidate_ranker",
            "is_ground_truth": False,
        }
        for field in ("review_status", "reviewed_boxes", "reviewed_categories"):
            active_sample[field] = "pending" if field == "review_status" else None
        active_sample.pop("reviewed_by", None)
        active_sample.pop("reviewed_at", None)
        relative = active_sample["frame"]
        (active / relative).parent.mkdir(parents=True, exist_ok=True)
        (active / relative).write_bytes((source / relative).read_bytes())
        if active_sample["overlay"] != relative:
            overlay = active_sample["overlay"]
            (active / overlay).parent.mkdir(parents=True, exist_ok=True)
            (active / overlay).write_bytes((source / overlay).read_bytes())
    (active / "review-manifest.json").write_text(
        json.dumps(active_manifest), encoding="utf-8"
    )
    AnnotationStore(active)
    return active


def _set_active_results(active: Path, statuses: list[str], *, boxes: list[list[list[float]]] | None = None) -> None:
    store = AnnotationStore(active)
    with sqlite3.connect(active / "annotations.sqlite3") as connection:
        for index, status in enumerate(statuses, start=1):
            reviewed = None
            categories = None
            if status == "corrected":
                reviewed = (boxes or [[[0.2, 0.2, 0.04, 0.06]]])[index - 1]
                categories = ["minimap_player"]
            connection.execute(
                """
                UPDATE tasks SET review_status = ?, reviewed_boxes = ?,
                    reviewed_categories = ?, reviewed_by = ?, reviewed_at = ?,
                    lease_owner = NULL, lease_until = NULL
                WHERE id = ?
                """,
                (
                    status,
                    json.dumps(reviewed) if reviewed is not None else None,
                    json.dumps(categories) if categories is not None else None,
                    "tester",
                    f"2026-09-30T00:00:0{index}Z",
                    index,
                ),
            )
    store.export_manifest()


def test_dry_run_is_default_and_does_not_write_source(tmp_path: Path) -> None:
    source = _queue_root(tmp_path, "source")
    active = _active_batch(tmp_path, source, name="active-accepted")
    _set_active_results(active, ["corrected", "negative", "skip"])
    source_manifest_before = (source / "review-manifest.json").read_bytes()
    source_db_before = (source / "annotations.sqlite3").read_bytes()

    audit = merge(source, active)

    assert audit["mode"] == "dry-run"
    assert audit["would_update"] == 3
    assert [row["source_task_id"] for row in audit["mapping"]] == [1, 2, 3]
    assert audit["suggestions_used_as_truth"] is False
    assert (source / "review-manifest.json").read_bytes() == source_manifest_before
    assert (source / "annotations.sqlite3").read_bytes() == source_db_before
    assert not (source / "backups").exists()


def test_apply_backs_up_and_maps_only_human_terminal_fields(tmp_path: Path) -> None:
    source = _queue_root(tmp_path, "source")
    active = _active_batch(tmp_path, source, name="active-accepted")
    _set_active_results(active, ["corrected", "negative", "skip"])
    backup = tmp_path / "backup"

    audit = merge(source, active, apply=True, backup_dir=backup)

    assert audit["mode"] == "apply"
    assert audit["backup_dir"] == str(backup.resolve())
    assert (backup / "review-manifest.json").is_file()
    assert (backup / "annotations.sqlite3").is_file()
    assert (backup / "audit.json").is_file()
    backup_manifest = json.loads((backup / "review-manifest.json").read_text())
    assert all(
        sample["review_status"] == "pending"
        for sample in backup_manifest["matches"][0]["samples"]
    )

    with sqlite3.connect(source / "annotations.sqlite3") as connection:
        connection.row_factory = sqlite3.Row
        rows = [dict(row) for row in connection.execute(
            "SELECT id, review_status, reviewed_boxes, reviewed_categories, "
            "reviewed_by, reviewed_at, lease_owner, lease_until FROM tasks ORDER BY id"
        )]
    assert [row["review_status"] for row in rows] == [
        "corrected", "negative", "skip"
    ]
    assert json.loads(rows[0]["reviewed_boxes"]) == [[0.2, 0.2, 0.04, 0.06]]
    assert json.loads(rows[0]["reviewed_categories"]) == ["minimap_player"]
    assert rows[1]["reviewed_boxes"] is None
    assert all(row["lease_owner"] is None and row["lease_until"] is None for row in rows)
    merged_manifest = json.loads((source / "review-manifest.json").read_text())
    assert [sample["review_status"] for sample in merged_manifest["matches"][0]["samples"]] == [
        "corrected", "negative", "skip"
    ]
    assert merged_manifest["matches"][0]["samples"][0]["suggested_boxes"] == [
        [0.1, 0.1, 0.04, 0.06]
    ]
    assert json.loads((backup / "audit.json").read_text())["mapping_sha256"] == audit[
        "mapping_sha256"
    ]


def test_repeating_same_apply_is_audited_noop(tmp_path: Path) -> None:
    source = _queue_root(tmp_path, "source")
    active = _active_batch(tmp_path, source)
    _set_active_results(active, ["corrected", "negative", "skip"])
    merge(source, active, apply=True, backup_dir=tmp_path / "first-backup")
    source_manifest = (source / "review-manifest.json").read_bytes()
    with sqlite3.connect(source / "annotations.sqlite3") as connection:
        database_rows = connection.execute(
            "SELECT * FROM tasks ORDER BY id"
        ).fetchall()

    audit = merge(source, active, apply=True, backup_dir=tmp_path / "second-backup")

    assert audit["mode"] == "apply-noop"
    assert audit["would_update"] == 0
    assert audit["already_applied"] == 3
    assert (source / "review-manifest.json").read_bytes() == source_manifest
    with sqlite3.connect(source / "annotations.sqlite3") as connection:
        assert connection.execute("SELECT * FROM tasks ORDER BY id").fetchall() == database_rows


@pytest.mark.parametrize("mode", ["pending", "lease"])
def test_rejects_unfinished_active_rows(tmp_path: Path, mode: str) -> None:
    source = _queue_root(tmp_path, "source")
    active = _active_batch(tmp_path, source)
    if mode == "pending":
        # The active batch starts pending and has never been exported as a
        # terminal human review.
        pattern = "pending"
    else:
        with sqlite3.connect(active / "annotations.sqlite3") as connection:
            connection.execute(
                """
                UPDATE tasks SET review_status = 'corrected',
                    reviewed_boxes = '[[0.2, 0.2, 0.04, 0.06]]',
                    reviewed_categories = '[\"minimap_player\"]',
                    reviewed_by = 'tester', reviewed_at = '2026-09-30T00:00:01Z',
                    lease_owner = 'tester', lease_until = 4102444800
                WHERE id = 1
                """
            )
        AnnotationStore(active).export_manifest()
        pattern = "lease"
    with pytest.raises(ValueError, match=pattern):
        merge(source, active)


def test_rejects_duplicate_source_ids_and_identity_mismatch(tmp_path: Path) -> None:
    source = _queue_root(tmp_path, "source")
    active = _active_batch(tmp_path, source)
    _set_active_results(active, ["corrected", "negative", "skip"])
    active_manifest_path = active / "review-manifest.json"
    active_manifest = json.loads(active_manifest_path.read_text())
    active_manifest["matches"][0]["samples"][1]["active_learning_source"][
        "source_task_id"
    ] = 1
    active_manifest_path.write_text(json.dumps(active_manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="duplicate source_task_id"):
        merge(source, active)

    active_manifest["matches"][0]["samples"][1]["active_learning_source"][
        "source_task_id"
    ] = 2
    active_manifest["matches"][0]["samples"][1]["at_ms"] = 999999
    active_manifest_path.write_text(json.dumps(active_manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="missing from manifest"):
        merge(source, active)


def test_rejects_multiple_player_boxes_and_machine_accepted_status(tmp_path: Path) -> None:
    source = _queue_root(tmp_path, "source")
    active = _active_batch(tmp_path, source)
    _set_active_results(
        active,
        ["corrected", "negative", "skip"],
        boxes=[[[0.1, 0.1, 0.04, 0.06], [0.2, 0.2, 0.04, 0.06]], [], []],
    )
    with pytest.raises(ValueError, match="exactly one"):
        merge(source, active)

    active = _active_batch(tmp_path, source, name="active-accepted")
    _set_active_results(active, ["corrected", "negative", "skip"])
    with sqlite3.connect(active / "annotations.sqlite3") as connection:
        connection.execute(
            "UPDATE tasks SET review_status = 'accepted' WHERE id = 1"
        )
    AnnotationStore(active).export_manifest()
    with pytest.raises(ValueError, match="suggestion as truth"):
        merge(source, active)


def test_rejects_sealed_match_without_opening_media(tmp_path: Path) -> None:
    source = _queue_root(tmp_path, "source")
    active = _active_batch(tmp_path, source)
    manifest_path = active / "review-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    manifest["matches"][0]["id"] = "video9-player"
    manifest["matches"][0]["samples"][0]["frame"] = "video9-player/frame.png"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="sealed video9/video12"):
        merge(source, active)
