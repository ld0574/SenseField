from __future__ import annotations

import hashlib
import json
import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "python"))

from mapassist.coco_dataset_audit import audit_coco_dataset
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
            {"image_id": 1, "truth": 1, "fp": 1, "fn": 0},
            {"image_id": 2, "truth": 0, "fp": 1, "fn": 0},
            {"image_id": 3, "truth": 0, "fp": 0, "fn": 0},
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
    assert summary["fp_hard_source_images"] == 2
    assert summary["fn_hard_source_images"] == 0
    assert summary["pure_negative_hard_sources"] == 1
    assert summary["hard_source_image_ids"] == [1, 2]
    assert summary["fn_hard_source_image_ids"] == []
    assert summary["policy"]["include_false_negatives"] is False
    assert summary["extra"] == {"images": 3, "boxes": 1}
    assert summary["output"] == {"images": 6, "boxes": 2}
    assert [item["hard_example_source_image_id"] for item in weighted["images"][3:]] == [1, 2, 2]
    assert len({item["id"] for item in weighted["images"]}) == 6
    assert len({item["id"] for item in weighted["annotations"]}) == 2
    assert all((output / f"{split}2017").is_dir() for split in ("train", "val", "test"))
    assert all(not (output / f"{split}2017").is_symlink()
               for split in ("train", "val", "test"))
    materialization = summary["image_materialization"]
    assert materialization["strategy"] == "hardlink_first_copy2_fallback"
    assert materialization["files"] == 3
    assert materialization["hardlinked_files"] + materialization["copied_files"] == 3
    assert {split: materialization["by_split"][split]["files"]
            for split in ("train", "val", "test")} == {
        "train": 1, "val": 1, "test": 1,
    }
    assert all(
        materialization["by_split"][split]["hardlinked_files"] +
        materialization["by_split"][split]["copied_files"] == 1
        for split in ("train", "val", "test")
    )
    audit = audit_coco_dataset(output)
    assert audit["splits"]["train"]["missing_files"] == []
    assert all("escapes the dataset root" not in blocker for blocker in audit["training_blockers"])
    for split in ("val", "test"):
        assert (output / f"{split}2017" / f"{split}.png").read_bytes() == \
            (data / f"{split}2017" / f"{split}.png").read_bytes()
    assert (output / "annotations/instances_val2017.json").read_bytes() == \
        (data / "annotations/instances_val2017.json").read_bytes()


def test_image_materialization_uses_copy2_when_hardlink_fails(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
) -> None:
    import training.reweight_coco_hard_examples as reweighter

    data, evaluation = _fixture(tmp_path)

    def fail_hardlink(*_args: object, **_kwargs: object) -> None:
        raise OSError("hard links are unavailable")

    monkeypatch.setattr(reweighter.os, "link", fail_hardlink)
    output = tmp_path / "copy-fallback"
    summary = build(data, evaluation, output)

    assert summary["image_materialization"] == {
        "strategy": "hardlink_first_copy2_fallback",
        "description": "use os.link per image file and shutil.copy2 after any hard-link OSError",
        "files": 3,
        "hardlinked_files": 0,
        "copied_files": 3,
        "by_split": {
            split: {"files": 1, "hardlinked_files": 0, "copied_files": 1}
            for split in ("train", "val", "test")
        },
    }
    assert not os.path.samefile(
        data / "train2017/train.png", output / "train2017/train.png"
    )


def test_repeats_false_negative_only_positive_sources_when_enabled(tmp_path: Path) -> None:
    data, evaluation = _fixture(tmp_path)
    payload = json.loads(evaluation.read_text(encoding="utf-8"))
    payload["per_image"][0]["fp"] = 0
    payload["per_image"][0]["fn"] = 1
    payload["per_image"][1]["fp"] = 0
    evaluation.write_text(json.dumps(payload), encoding="utf-8")
    output = tmp_path / "fn-weighted"

    summary = build(
        data, evaluation, output, max_extra_copies=2, negative_bonus=1,
        include_false_negatives=True,
    )
    weighted = json.loads(
        (output / "annotations/instances_train2017.json").read_text(encoding="utf-8")
    )

    assert summary["hard_source_images"] == 1
    assert summary["fp_hard_source_images"] == 0
    assert summary["fn_hard_source_images"] == 1
    assert summary["hard_source_image_ids"] == [1]
    assert summary["fn_hard_source_image_ids"] == [1]
    assert summary["extra"] == {"images": 1, "boxes": 1}
    assert weighted["images"][-1]["hard_example_source_image_id"] == 1
    assert summary["policy"]["include_false_negatives"] is True


def test_min_false_negatives_filters_only_pure_fn_sources(tmp_path: Path) -> None:
    data, evaluation = _fixture(tmp_path)
    train_path = data / "annotations/instances_train2017.json"
    train = json.loads(train_path.read_text(encoding="utf-8"))
    train["annotations"].extend([
        {"id": 2, "image_id": 2, "category_id": 1,
         "bbox": [1, 2, 3, 4], "area": 12, "iscrowd": 0},
        {"id": 3, "image_id": 3, "category_id": 1,
         "bbox": [1, 2, 3, 4], "area": 12, "iscrowd": 0},
    ])
    train_path.write_text(json.dumps(train), encoding="utf-8")
    report = json.loads(evaluation.read_text(encoding="utf-8"))
    report["dataset"]["annotations_sha256"] = _sha256(train_path)
    report["per_image"] = [
        {"image_id": 1, "truth": 1, "fp": 1, "fn": 1},
        {"image_id": 2, "truth": 1, "fp": 0, "fn": 1},
        {"image_id": 3, "truth": 1, "fp": 0, "fn": 2},
    ]
    evaluation.write_text(json.dumps(report), encoding="utf-8")

    summary = build(
        data, evaluation, tmp_path / "min-fn", include_false_negatives=True,
        min_false_negatives=2,
    )

    assert summary["hard_source_image_ids"] == [1, 3]
    assert summary["fn_hard_source_image_ids"] == [1, 3]
    assert summary["fp_hard_source_images"] == 1
    assert summary["fn_hard_source_images"] == 2
    assert summary["match_quota"]["excluded_image_ids"] == []
    assert summary["policy"]["min_false_negatives"] == 2


def test_match_quota_prefers_fp_then_fn_count_then_image_id(tmp_path: Path) -> None:
    data, evaluation = _fixture(tmp_path)
    train_dir = data / "train2017"
    train_path = data / "annotations/instances_train2017.json"
    train = json.loads(train_path.read_text(encoding="utf-8"))
    names = ["matchA_100.png", "matchA_200.png", "matchA_300.png", "matchA_400.png"]
    for image, file_name in zip(train["images"], names):
        image["file_name"] = file_name
        (train_dir / file_name).write_bytes(b"pixels")
    train["images"].append({
        "id": 4, "file_name": "matchA_500.png", "width": 20, "height": 20,
    })
    (train_dir / "matchA_500.png").write_bytes(b"pixels")
    train["images"].append({
        "id": 5, "file_name": "unrelated_600.png", "match_id": "explicit-match",
        "width": 20, "height": 20,
    })
    (train_dir / "unrelated_600.png").write_bytes(b"pixels")
    for annotation_id, image_id in ((2, 2), (3, 3), (4, 4), (5, 5)):
        train["annotations"].append({
            "id": annotation_id, "image_id": image_id, "category_id": 1,
            "bbox": [1, 2, 3, 4], "area": 12, "iscrowd": 0,
        })
    train_path.write_text(json.dumps(train), encoding="utf-8")
    report = json.loads(evaluation.read_text(encoding="utf-8"))
    report["dataset"]["annotations_sha256"] = _sha256(train_path)
    report["per_image"] = [
        {"image_id": 1, "truth": 1, "fp": 1, "fn": 2},
        {"image_id": 2, "truth": 1, "fp": 0, "fn": 9},
        {"image_id": 3, "truth": 1, "fp": 1, "fn": 3},
        {"image_id": 4, "truth": 1, "fp": 1, "fn": 3},
        {"image_id": 5, "truth": 1, "fp": 0, "fn": 2},
    ]
    evaluation.write_text(json.dumps(report), encoding="utf-8")

    summary = build(
        data, evaluation, tmp_path / "match-quota", include_false_negatives=True,
        max_hard_sources_per_match=2,
    )

    assert summary["hard_source_image_ids"] == [3, 4, 5]
    assert summary["match_quota"] == {
        "max_hard_sources_per_match": 2,
        "candidate_sources": 5,
        "selected_sources": 3,
        "excluded_sources": 2,
        "candidate_sources_by_match": {"explicit-match": 1, "matchA": 4},
        "selected_sources_by_match": {"explicit-match": 1, "matchA": 2},
        "excluded_sources_by_match": {"matchA": 2},
        "excluded_image_ids": [1, 2],
    }


def test_top_edge_copies_merge_with_hard_error_by_max(tmp_path: Path) -> None:
    data, evaluation = _fixture(tmp_path)
    train_path = data / "annotations/instances_train2017.json"
    train = json.loads(train_path.read_text(encoding="utf-8"))
    train["annotations"].append({
        "id": 2, "image_id": 3, "category_id": 1,
        "bbox": [1, 2, 3, 4], "area": 12, "iscrowd": 0,
    })
    train_path.write_text(json.dumps(train), encoding="utf-8")
    report = json.loads(evaluation.read_text(encoding="utf-8"))
    report["dataset"]["annotations_sha256"] = _sha256(train_path)
    report["per_image"][2]["truth"] = 1
    evaluation.write_text(json.dumps(report), encoding="utf-8")

    output = tmp_path / "top-edge"
    summary = build(
        data, evaluation, output, max_extra_copies=2,
        top_edge_y_max=2, top_edge_extra_copies=2,
    )
    weighted = json.loads(
        (output / "annotations/instances_train2017.json").read_text(encoding="utf-8")
    )
    copies_by_source: dict[int, int] = {}
    for image in weighted["images"]:
        source_id = image.get("hard_example_source_image_id")
        if source_id is not None:
            copies_by_source[source_id] = copies_by_source.get(source_id, 0) + 1

    assert copies_by_source == {1: 2, 2: 2, 3: 2}
    assert summary["top_edge"] == {
        "source_images": 2,
        "source_boxes": 2,
        "incremental_extra": {"images": 3, "boxes": 3},
    }
    assert summary["extra"] == {"images": 6, "boxes": 4}
    assert summary["policy"]["top_edge"] == {
        "enabled": True,
        "y_max": 2.0,
        "extra_copies": 2,
        "merge": "max(top_edge_extra_copies, selected_hard_error_copies) per image",
    }


@pytest.mark.parametrize(("kwargs", "message"), [
    ({"min_false_negatives": 0}, "min_false_negatives must be an integer >= 1"),
    ({"min_false_negatives": 1.5}, "min_false_negatives must be an integer >= 1"),
    ({"min_false_negatives": True}, "min_false_negatives must be an integer >= 1"),
    ({"max_hard_sources_per_match": -1}, "max_hard_sources_per_match must be an integer >= 0"),
    ({"max_hard_sources_per_match": 1.5}, "max_hard_sources_per_match must be an integer >= 0"),
    ({"top_edge_y_max": -1}, "top_edge_y_max must be a finite number >= 0"),
    ({"top_edge_y_max": float("nan")}, "top_edge_y_max must be a finite number >= 0"),
    ({"top_edge_y_max": True}, "top_edge_y_max must be a finite number >= 0"),
    ({"top_edge_extra_copies": -1}, "top_edge_extra_copies must be an integer >= 0"),
    ({"top_edge_extra_copies": 1}, "top_edge_y_max is required"),
])
def test_rejects_invalid_reweighting_options(
    tmp_path: Path, kwargs: dict[str, object], message: str,
) -> None:
    data, evaluation = _fixture(tmp_path)

    with pytest.raises(ValueError, match=message):
        build(data, evaluation, tmp_path / "invalid-options", **kwargs)


@pytest.mark.parametrize("fn", [-1, 1.5, True, None])
def test_rejects_invalid_false_negative_counts(tmp_path: Path, fn: object) -> None:
    data, evaluation = _fixture(tmp_path)
    payload = json.loads(evaluation.read_text(encoding="utf-8"))
    payload["per_image"][0]["fn"] = fn
    evaluation.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(ValueError, match="evaluation fn for image 1 must be an integer >= 0"):
        build(data, evaluation, tmp_path / "invalid-fn")


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
