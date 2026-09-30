from __future__ import annotations

import hashlib
import json
import os
import sqlite3
from pathlib import Path

import pytest
from PIL import Image

from mapassist.annotation_server import AnnotationStore
from mapassist.finalize_review import finalize
from training.audit_main_edge_queue import (
    _database_content_sha256,
    enrich_review_manifest,
    run,
)


def _edge_source(tmp_path: Path) -> Path:
    source = tmp_path / "main-edge"
    queue = source / "queue"
    media = queue / "train/video1-edge"
    media.mkdir(parents=True)
    video = source / "video" / "video1-edge.mp4"
    video.parent.mkdir(parents=True)
    video.write_bytes(b"fixture video metadata only")
    classes = (
        ["empty"] * 5 + ["left_only"] * 3 + ["both"] * 2
        + ["right_only"] * 10
    )
    samples = []
    for index, machine_class in enumerate(classes):
        relative = f"train/video1-edge/frame-{index}.png"
        Image.new("RGB", (96, 54), (20 + index, 30, 40)).save(queue / relative)
        if machine_class == "empty":
            boxes, directions = [], []
        elif machine_class == "left_only":
            boxes, directions = [[0.01, 0.3, 0.04, 0.08]], ["left"]
        elif machine_class == "right_only":
            boxes, directions = [[0.94, 0.3, 0.04, 0.08]], ["right"]
        else:
            boxes = [[0.01, 0.3, 0.04, 0.08], [0.94, 0.4, 0.04, 0.08]]
            directions = ["left", "right"]
        samples.append({
            "at_ms": index * 30_000,
            "selection": "edge_machine_proposal",
            "frame": relative,
            "overlay": relative,
            "suggested_boxes": boxes,
            "suggested_categories": ["main_enemy"] * len(boxes),
            "directions": directions,
            "review_status": "pending",
            "reviewed_boxes": None,
            "reviewed_categories": None,
        })
    manifest = {
        "schema_version": 1,
        "kind": "main_enemy",
        "classes": ["main_enemy"],
        "review_mode": "manual",
        "roi": [0.0, 0.0, 1.0, 1.0],
        "matches": [{
            "id": "video1-edge",
            "split": "train",
            "video": str(video),
            "video_sha256": hashlib.sha256(video.read_bytes()).hexdigest(),
            "orientation": {
                "source_coded_size": [96, 54],
                "display_size": [96, 54],
                "display_rotation_degrees": 0,
                "queue_frames_must_be_display_oriented": True,
            },
            "samples": samples,
        }],
    }
    (queue / "review-manifest.json").write_text(
        json.dumps(manifest), encoding="utf-8"
    )
    AnnotationStore(queue)
    return source


def test_edge_batch_is_self_contained_before_server_reopen(tmp_path: Path) -> None:
    source = _edge_source(tmp_path)
    output = tmp_path / "edge-focus"
    run(source, output)

    manifest = json.loads((output / "review-manifest.json").read_text())
    manifest_rows = {
        (match["id"], sample["at_ms"]): sample
        for match in manifest["matches"] for sample in match["samples"]
    }
    with sqlite3.connect(output / "annotations.sqlite3") as connection:
        connection.row_factory = sqlite3.Row
        database_rows = [dict(row) for row in connection.execute(
            "SELECT * FROM tasks ORDER BY id"
        )]
    assert len(database_rows) == 20
    assert all(row["review_status"] == "pending" for row in database_rows)
    for row in database_rows:
        sample = manifest_rows[(row["match_id"], row["at_ms"])]
        assert row["frame"] == sample["frame"]
        assert row["overlay"] == sample["overlay"]
        assert (output / row["frame"]).is_file()
        source_name = Path(row["frame"]).name
        source_media = source / "queue/train/video1-edge" / source_name
        assert os.stat(source_media).st_ino != os.stat(output / row["frame"]).st_ino

    audit = json.loads((output / "audit.json").read_text())
    assert audit["output_batch"]["database_content_sha256"] == (
        _database_content_sha256(output / "annotations.sqlite3")
    )
    AnnotationStore(output)
    assert audit["output_batch"]["database_content_sha256"] == (
        _database_content_sha256(output / "annotations.sqlite3")
    )


def test_edge_batch_propagates_source_metadata_for_finalization(tmp_path: Path) -> None:
    source = _edge_source(tmp_path)
    output = tmp_path / "edge-focus"
    run(source, output)

    manifest_path = output / "review-manifest.json"
    manifest = json.loads(manifest_path.read_text())
    source_manifest = json.loads(
        (source / "queue/review-manifest.json").read_text()
    )
    expected = source_manifest["matches"][0]
    match = manifest["matches"][0]
    assert match["video"] == expected["video"]
    assert match["video_sha256"] == expected["video_sha256"]
    assert match["orientation"] == expected["orientation"]

    for sample in match["samples"]:
        sample["review_status"] = "negative"
    manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
    detections = tmp_path / "detections.json"
    finalize(manifest_path, detections)
    exported = json.loads(detections.read_text())
    exported_match = exported["matches"][0]
    assert exported_match["video"] == expected["video"]
    assert exported_match["video_sha256"] == expected["video_sha256"]
    assert exported_match["orientation"] == expected["orientation"]


def test_enrich_existing_batch_preserves_reviewed_samples(tmp_path: Path) -> None:
    source = _edge_source(tmp_path)
    generated = tmp_path / "generated"
    run(source, generated)

    legacy = tmp_path / "legacy"
    legacy.mkdir()
    legacy_path = legacy / "review-manifest.json"
    legacy_document = json.loads(
        (generated / "review-manifest.json").read_text()
    )
    for match in legacy_document["matches"]:
        match.pop("video")
        match.pop("video_sha256")
        match.pop("orientation")
        match["samples"][0]["review_status"] = "corrected"
        match["samples"][0]["reviewed_boxes"] = [[0.1, 0.2, 0.03, 0.04]]
        match["samples"][0]["reviewed_categories"] = ["main_enemy"]
    legacy_path.write_text(
        json.dumps(legacy_document, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    before = legacy_path.read_bytes()
    enriched_path = legacy / "review-manifest.enriched.json"

    result = enrich_review_manifest(
        legacy_path,
        source / "queue/review-manifest.json",
        enriched_path,
    )

    assert result["matches"] == 1
    assert result["added_fields"] == 3
    assert legacy_path.read_bytes() == before
    enriched = json.loads(enriched_path.read_text())
    expected = json.loads(
        (source / "queue/review-manifest.json").read_text()
    )["matches"][0]
    actual = enriched["matches"][0]
    assert actual["video"] == expected["video"]
    assert actual["video_sha256"] == expected["video_sha256"]
    assert actual["orientation"] == expected["orientation"]
    assert actual["samples"] == legacy_document["matches"][0]["samples"]


def test_edge_batch_never_replaces_source_directories(tmp_path: Path) -> None:
    source = _edge_source(tmp_path)
    with pytest.raises(ValueError, match="must differ"):
        run(source, source)
    with pytest.raises(ValueError, match="must differ"):
        run(source, source / "queue")
