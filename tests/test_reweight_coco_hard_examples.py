from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from training.reweight_coco_hard_examples import build


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _fixture(root: Path) -> tuple[Path, Path]:
    data = root / "coco"
    (data / "annotations").mkdir(parents=True)
    for split in ("train", "val", "test"):
        directory = data / f"{split}2017"
        directory.mkdir()
        (directory / f"{split}.png").write_bytes(b"pixels")
    train = {
        "images": [
            {"id": 1, "file_name": "train.png", "width": 20, "height": 20},
            {"id": 2, "file_name": "train.png", "width": 20, "height": 20},
            {"id": 3, "file_name": "train.png", "width": 20, "height": 20},
        ],
        "annotations": [{
            "id": 1, "image_id": 1, "category_id": 1,
            "bbox": [1, 2, 3, 4], "area": 12, "iscrowd": 0,
        }],
        "categories": [{"id": 1, "name": "minimap_enemy"}],
    }
    train_path = data / "annotations/instances_train2017.json"
    train_path.write_text(json.dumps(train), encoding="utf-8")
    empty = {"images": [], "annotations": [], "categories": train["categories"]}
    for split in ("val", "test"):
        (data / f"annotations/instances_{split}2017.json").write_text(
            json.dumps(empty), encoding="utf-8"
        )
    evaluation = root / "evaluation.json"
    evaluation.write_text(json.dumps({
        "schema_version": 1,
        "split": "train",
        "confidence": 0.29,
        "iou_threshold": 0.5,
        "checkpoint": {"sha256": "a" * 64},
        "dataset": {"annotations_sha256": _sha256(train_path)},
        "per_image": [
            {"image_id": 1, "truth": 1, "fp": 1},
            {"image_id": 2, "truth": 0, "fp": 1},
            {"image_id": 3, "truth": 0, "fp": 0},
        ],
    }), encoding="utf-8")
    return data, evaluation


def test_repeats_complete_reviewed_images_and_preserves_other_splits(tmp_path: Path) -> None:
    data, evaluation = _fixture(tmp_path)
    output = tmp_path / "weighted"
    summary = build(data, evaluation, output, max_extra_copies=2, negative_bonus=1)
    weighted = json.loads(
        (output / "annotations/instances_train2017.json").read_text(encoding="utf-8")
    )
    assert summary["hard_source_images"] == 2
    assert summary["pure_negative_hard_sources"] == 1
    assert summary["extra"] == {"images": 3, "boxes": 1}
    assert summary["output"] == {"images": 6, "boxes": 2}
    assert [item["hard_example_source_image_id"] for item in weighted["images"][3:]] == [1, 2, 2]
    assert len({item["id"] for item in weighted["images"]}) == 6
    assert len({item["id"] for item in weighted["annotations"]}) == 2
    assert (output / "train2017").resolve() == (data / "train2017").resolve()
    assert (output / "annotations/instances_val2017.json").read_bytes() == \
        (data / "annotations/instances_val2017.json").read_bytes()


def test_rejects_stale_or_incomplete_evaluation(tmp_path: Path) -> None:
    data, evaluation = _fixture(tmp_path)
    payload = json.loads(evaluation.read_text(encoding="utf-8"))
    payload["dataset"]["annotations_sha256"] = "0" * 64
    evaluation.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(ValueError, match="hash does not match"):
        build(data, evaluation, tmp_path / "stale")

    data, evaluation = _fixture(tmp_path / "second")
    payload = json.loads(evaluation.read_text(encoding="utf-8"))
    payload["per_image"].pop()
    evaluation.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(ValueError, match="cover every source train image"):
        build(data, evaluation, tmp_path / "incomplete")
