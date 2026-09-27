from __future__ import annotations

import json
from pathlib import Path

import pytest

from mapassist.coco_dataset_audit import audit_coco_dataset


def _write_split(root: Path, split: str, image_name: str, category_name: str = "0") -> None:
    image_dir = root / f"{split}2017"
    image_dir.mkdir(parents=True, exist_ok=True)
    (image_dir / image_name).write_bytes(b"same-image")
    annotations = root / "annotations"
    annotations.mkdir(exist_ok=True)
    (annotations / f"instances_{split}2017.json").write_text(json.dumps({
        "images": [{"id": 1, "file_name": image_name, "width": 100, "height": 50}],
        "annotations": [{"id": 1, "image_id": 1, "category_id": 1, "bbox": [1, 2, 3, 4]}],
        "categories": [{"id": 1, "name": category_name}],
    }), encoding="utf-8")


def test_audit_reports_numeric_classes_empty_test_and_cross_split_duplicates(tmp_path: Path) -> None:
    _write_split(tmp_path, "train", "train.jpg")
    _write_split(tmp_path, "val", "val.jpg")

    report = audit_coco_dataset(tmp_path)

    assert report["numeric_category_names_only"] is True
    assert report["splits"]["train"]["images"] == 1
    assert len(report["cross_split_duplicate_groups"]) == 1
    assert any("No populated test split" in item for item in report["training_blockers"])
    assert any("semantic class map" in item for item in report["training_blockers"])


def test_audit_accepts_semantic_categories_and_separate_images(tmp_path: Path) -> None:
    _write_split(tmp_path, "train", "train.jpg", "minimap_enemy")
    _write_split(tmp_path, "test", "test.jpg", "minimap_enemy")
    (tmp_path / "test2017" / "test.jpg").write_bytes(b"different-image")

    report = audit_coco_dataset(tmp_path)

    assert report["numeric_category_names_only"] is False
    assert report["cross_split_duplicate_groups"] == []
    assert report["training_blockers"] == []
    assert len(report["source_image_manifest_sha256"]) == 64
    assert report["source_image_manifest_sha256"] == audit_coco_dataset(tmp_path)[
        "source_image_manifest_sha256"
    ]


def test_audit_detects_duplicate_image_category_and_annotation_ids(tmp_path: Path) -> None:
    _write_split(tmp_path, "train", "train.jpg", "minimap_enemy")
    annotation = tmp_path / "annotations/instances_train2017.json"
    document = json.loads(annotation.read_text(encoding="utf-8"))
    document["images"].append({
        "id": 1, "file_name": "missing.jpg", "width": 100, "height": 50,
    })
    document["categories"].append({"id": 1, "name": "minimap_enemy"})
    document["annotations"].append(dict(document["annotations"][0]))
    annotation.write_text(json.dumps(document), encoding="utf-8")

    report = audit_coco_dataset(tmp_path)
    split = report["splits"]["train"]

    assert split["duplicate_image_ids"] == [1]
    assert split["duplicate_category_ids"] == [1]
    assert split["duplicate_annotation_ids"] == [1]
    assert any("duplicate image IDs" in blocker for blocker in report["training_blockers"])
    assert any("duplicate category IDs" in blocker for blocker in report["training_blockers"])
    assert any("duplicate annotation IDs" in blocker for blocker in report["training_blockers"])


def test_source_image_manifest_hash_tracks_relative_names_and_content(tmp_path: Path) -> None:
    _write_split(tmp_path, "train", "train.jpg", "minimap_enemy")
    _write_split(tmp_path, "test", "test.jpg", "minimap_enemy")

    original = audit_coco_dataset(tmp_path)["source_image_manifest_sha256"]
    (tmp_path / "train2017/train.jpg").write_bytes(b"changed-image")
    changed = audit_coco_dataset(tmp_path)["source_image_manifest_sha256"]

    assert original != changed


def test_audit_supports_roboflow_coco_and_ignores_unused_parent_category(
    tmp_path: Path,
) -> None:
    for directory in ("train", "valid"):
        split_dir = tmp_path / directory
        split_dir.mkdir()
        (split_dir / f"{directory}.jpg").write_bytes(directory.encode())
        (split_dir / "_annotations.coco.json").write_text(json.dumps({
            "images": [{
                "id": 1, "file_name": f"{directory}.jpg", "width": 640, "height": 640,
            }],
            "annotations": [{
                "id": 1, "image_id": 1, "category_id": 1, "bbox": [1, 2, 3, 4],
            }],
            "categories": [
                {"id": 0, "name": "heroes"},
                {"id": 1, "name": "0"},
            ],
        }), encoding="utf-8")

    report = audit_coco_dataset(tmp_path)

    assert report["splits"]["train"]["layout"] == "roboflow_coco"
    assert report["splits"]["val"]["images"] == 1
    assert report["numeric_category_names_only"] is True
    assert report["unused_categories"] == [{"id": 0, "name": "heroes"}]
    assert report["dataset_root"] is None
    assert len(report["source_image_manifest_sha256"]) == 64
    assert report["splits"]["train"]["annotation_file"] == "train/_annotations.coco.json"
    assert str(tmp_path) not in json.dumps(report)


def test_audit_rejects_image_paths_outside_split_directory(tmp_path: Path) -> None:
    _write_split(tmp_path, "train", "train.jpg", "minimap_hero")
    annotation = tmp_path / "annotations/instances_train2017.json"
    document = json.loads(annotation.read_text(encoding="utf-8"))
    document["images"][0]["file_name"] = "../secret.txt"
    annotation.write_text(json.dumps(document), encoding="utf-8")

    with pytest.raises(ValueError, match="Unsafe COCO image file_name"):
        audit_coco_dataset(tmp_path)


def test_audit_reports_unclassified_image_edge_boxes_without_blocking_external_coco(
    tmp_path: Path,
) -> None:
    _write_split(tmp_path, "train", "train.jpg", "minimap_enemy")
    _write_split(tmp_path, "test", "test.jpg", "minimap_enemy")
    (tmp_path / "test2017/test.jpg").write_bytes(b"different image")
    annotation = tmp_path / "annotations/instances_train2017.json"
    document = json.loads(annotation.read_text(encoding="utf-8"))
    document["annotations"][0]["bbox"] = [0, 2, 3, 4]
    annotation.write_text(json.dumps(document), encoding="utf-8")

    report = audit_coco_dataset(tmp_path)

    assert report["splits"]["train"]["roi_crop_completeness"] == "unknown"
    assert report["splits"]["train"]["roi_edge_touch_annotations"] == [{
        "annotation_id": 1, "image_id": 1, "sides": ["left"],
    }]
    assert not any("crop edge" in blocker for blocker in report["training_blockers"])


def test_audit_blocks_exporter_dataset_with_expandable_roi_edge_contacts(
    tmp_path: Path,
) -> None:
    _write_split(tmp_path, "train", "train.jpg", "minimap_enemy")
    annotation = tmp_path / "annotations/instances_train2017.json"
    document = json.loads(annotation.read_text(encoding="utf-8"))
    document["info"] = {"roi_boundary_audit": {
        "schema_version": 1,
        "edge_tolerance_px": 1.0,
        "crop_edge_contacts": [{"match_id": "m1", "at_ms": 1000,
                                "box_index": 1, "sides": ["right"]}],
        "physical_edge_contacts": [],
        "edge_contacts": [{"match_id": "m1", "at_ms": 1000,
                           "box_index": 1, "sides": ["right"]}],
        "training_eligible": False,
        "usable_for_training_or_evaluation": False,
        "policy": "Target boxes within an expandable crop-edge safety band require review.",
    }}
    annotation.write_text(json.dumps(document), encoding="utf-8")

    report = audit_coco_dataset(tmp_path)

    assert report["splits"]["train"]["roi_crop_completeness"] == "blocked"
    assert any("expandable crop edge" in blocker for blocker in report["training_blockers"])


def test_audit_reports_incomplete_roi_provenance_as_unknown(tmp_path: Path) -> None:
    _write_split(tmp_path, "train", "train.jpg", "minimap_enemy")
    _write_split(tmp_path, "test", "test.jpg", "minimap_enemy")
    (tmp_path / "test2017/test.jpg").write_bytes(b"different image")
    annotation = tmp_path / "annotations/instances_train2017.json"
    document = json.loads(annotation.read_text(encoding="utf-8"))
    document["annotations"][0]["bbox"] = [0, 2, 3, 4]
    document["info"] = {"roi_boundary_audit": {
        "schema_version": 99,
        "crop_edge_contacts": [{"sides": ["left"]}],
        "training_eligible": False,
    }}
    annotation.write_text(json.dumps(document), encoding="utf-8")

    report = audit_coco_dataset(tmp_path)

    assert report["splits"]["train"]["roi_crop_completeness"] == "unknown"
    assert report["splits"]["train"]["roi_edge_touch_annotations"] == [{
        "annotation_id": 1, "image_id": 1, "sides": ["left"],
    }]
    assert not any("crop edge" in blocker for blocker in report["training_blockers"])


@pytest.mark.parametrize("bad_value", [float("nan"), float("inf"), float("-inf")])
def test_audit_reports_non_finite_boxes_as_invalid(tmp_path: Path, bad_value: float) -> None:
    _write_split(tmp_path, "train", "train.jpg", "minimap_enemy")
    _write_split(tmp_path, "test", "test.jpg", "minimap_enemy")
    (tmp_path / "test2017/test.jpg").write_bytes(b"different image")
    annotation = tmp_path / "annotations/instances_train2017.json"
    document = json.loads(annotation.read_text(encoding="utf-8"))
    document["annotations"][0]["bbox"] = [bad_value, 2, 3, 4]
    annotation.write_text(json.dumps(document), encoding="utf-8")

    report = audit_coco_dataset(tmp_path)

    assert report["splits"]["train"]["invalid_annotation_boxes"] == [1]
    assert any("annotation boxes are outside or invalid" in blocker
               for blocker in report["training_blockers"])
