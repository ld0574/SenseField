from __future__ import annotations

import json
from pathlib import Path

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
