"""Repeat complete reviewed train images with false positives and optional false negatives.

The input evaluation must cover the source ``train`` split at one fixed
threshold. By default, images with false positives receive additional COCO
image records pointing at the same pixels. With ``--include-false-negatives``,
positive-truth images with false negatives are included too, and positive
images are repeated according to the larger of their false-positive and
false-negative counts. Pure-negative images retain the false-positive plus
bonus policy. Complete reviewed annotations are copied for every repeated
image. Validation and test annotations are copied byte-for-byte.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
from pathlib import Path
from typing import Any


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _load(path: Path, label: str) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"Cannot read {label} {path}: {error}") from error
    if not isinstance(value, dict):
        raise ValueError(f"{label} must contain a JSON object")
    return value


def _integer(value: object, label: str, minimum: int = 0) -> int:
    if not isinstance(value, int) or isinstance(value, bool) or value < minimum:
        raise ValueError(f"{label} must be an integer >= {minimum}")
    return value


def build(data_dir: Path, evaluation_path: Path, output: Path,
          max_extra_copies: int = 2, negative_bonus: int = 1,
          include_false_negatives: bool = False) -> dict[str, Any]:
    """Create a COCO directory with deterministic hard-example repetition."""
    max_extra_copies = _integer(max_extra_copies, "max_extra_copies", 1)
    negative_bonus = _integer(negative_bonus, "negative_bonus")
    if not isinstance(include_false_negatives, bool):
        raise ValueError("include_false_negatives must be a boolean")
    data_dir = data_dir.resolve()
    evaluation_path = evaluation_path.resolve()
    output = output.resolve()
    if output == data_dir or data_dir in output.parents:
        raise ValueError("output must not be the source dataset or inside it")
    if output.exists():
        raise ValueError(f"output already exists: {output}")

    annotation_dir = data_dir / "annotations"
    train_path = annotation_dir / "instances_train2017.json"
    split_paths = {
        split: annotation_dir / f"instances_{split}2017.json"
        for split in ("train", "val", "test")
    }
    if any(not path.is_file() for path in split_paths.values()):
        raise ValueError("source needs train, val, and test COCO annotations")
    source = _load(train_path, "train annotations")
    evaluation = _load(evaluation_path, "evaluation")
    if evaluation.get("schema_version") != 1 or evaluation.get("split") != "train":
        raise ValueError("evaluation must be a schema 1 fixed train-split report")
    if evaluation.get("dataset", {}).get("annotations_sha256") != _sha256(train_path):
        raise ValueError("evaluation annotations hash does not match the source train split")

    images = source.get("images")
    annotations = source.get("annotations")
    per_image = evaluation.get("per_image")
    if not isinstance(images, list) or not isinstance(annotations, list) or \
            not isinstance(per_image, list):
        raise ValueError("COCO images/annotations and evaluation per_image must be lists")
    image_by_id: dict[int, dict[str, Any]] = {}
    for image in images:
        if not isinstance(image, dict):
            raise ValueError("COCO image entries must be objects")
        image_id = _integer(image.get("id"), "image id", 1)
        if image_id in image_by_id:
            raise ValueError(f"duplicate COCO image id: {image_id}")
        file_name = image.get("file_name")
        if not isinstance(file_name, str) or not file_name:
            raise ValueError(f"image {image_id} has no file_name")
        if not (data_dir / "train2017" / file_name).is_file():
            raise ValueError(f"missing train image: {file_name}")
        image_by_id[image_id] = image

    annotations_by_image: dict[int, list[dict[str, Any]]] = {
        image_id: [] for image_id in image_by_id
    }
    max_annotation_id = 0
    for annotation in annotations:
        if not isinstance(annotation, dict):
            raise ValueError("COCO annotation entries must be objects")
        annotation_id = _integer(annotation.get("id"), "annotation id", 1)
        image_id = _integer(annotation.get("image_id"), "annotation image_id", 1)
        if image_id not in annotations_by_image:
            raise ValueError(f"annotation references unknown image id {image_id}")
        annotations_by_image[image_id].append(annotation)
        max_annotation_id = max(max_annotation_id, annotation_id)

    evaluation_by_id: dict[int, dict[str, Any]] = {}
    for row in per_image:
        if not isinstance(row, dict):
            raise ValueError("evaluation per_image entries must be objects")
        image_id = _integer(row.get("image_id"), "evaluation image_id", 1)
        if image_id in evaluation_by_id:
            raise ValueError(f"duplicate evaluation image id: {image_id}")
        fp = _integer(row.get("fp"), f"evaluation fp for image {image_id}")
        fn = _integer(row.get("fn"), f"evaluation fn for image {image_id}")
        truth = _integer(row.get("truth"), f"evaluation truth for image {image_id}")
        if truth != len(annotations_by_image.get(image_id, [])):
            raise ValueError(f"evaluation truth count changed for image {image_id}")
        evaluation_by_id[image_id] = {"fp": fp, "fn": fn, "truth": truth}
    if set(evaluation_by_id) != set(image_by_id):
        raise ValueError("evaluation must cover every source train image exactly once")

    new_images = [dict(image) for image in images]
    new_annotations = [dict(annotation) for annotation in annotations]
    next_image_id = max(image_by_id, default=0) + 1
    next_annotation_id = max_annotation_id + 1
    hard_source_images = fp_hard_source_images = fn_hard_source_images = 0
    pure_negative_sources = extra_images = 0
    extra_boxes = 0
    repeat_histogram: dict[str, int] = {}
    hard_source_image_ids: list[int] = []
    fn_hard_source_image_ids: list[int] = []
    for image_id in sorted(image_by_id):
        row = evaluation_by_id[image_id]
        fp = row["fp"]
        fn = row["fn"]
        truth = row["truth"]
        fn_hard_source = include_false_negatives and truth > 0 and fn > 0
        if fp == 0 and not fn_hard_source:
            continue
        if truth == 0:
            copies = min(max_extra_copies, fp + negative_bonus)
        else:
            copies = min(max_extra_copies, max(fp, fn if include_false_negatives else 0))
        hard_source_images += 1
        fp_hard_source_images += int(fp > 0)
        fn_hard_source_images += int(fn_hard_source)
        pure_negative_sources += int(truth == 0)
        hard_source_image_ids.append(image_id)
        if fn_hard_source:
            fn_hard_source_image_ids.append(image_id)
        repeat_histogram[str(copies)] = repeat_histogram.get(str(copies), 0) + 1
        for copy_index in range(1, copies + 1):
            duplicate = dict(image_by_id[image_id])
            duplicate["id"] = next_image_id
            duplicate["hard_example_source_image_id"] = image_id
            duplicate["hard_example_copy_index"] = copy_index
            new_images.append(duplicate)
            for annotation in annotations_by_image[image_id]:
                copied_annotation = dict(annotation)
                copied_annotation["id"] = next_annotation_id
                copied_annotation["image_id"] = next_image_id
                new_annotations.append(copied_annotation)
                next_annotation_id += 1
                extra_boxes += 1
            next_image_id += 1
            extra_images += 1

    transformed = dict(source)
    transformed["images"] = new_images
    transformed["annotations"] = new_annotations
    output.mkdir(parents=True)
    (output / "annotations").mkdir()
    train_output = output / "annotations/instances_train2017.json"
    train_output.write_text(
        json.dumps(transformed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    for split in ("val", "test"):
        shutil.copyfile(split_paths[split], output / "annotations" / split_paths[split].name)
    for split in ("train", "val", "test"):
        source_images = data_dir / f"{split}2017"
        if not source_images.is_dir():
            raise ValueError(f"missing source image directory: {source_images}")
        os.symlink(source_images, output / f"{split}2017", target_is_directory=True)

    summary = {
        "schema_version": 1,
        "design": "reviewed_train_hard_example_repetition",
        "source_data_dir": str(data_dir),
        "source_train_annotations_sha256": _sha256(train_path),
        "evaluation": {
            "path": str(evaluation_path),
            "sha256": _sha256(evaluation_path),
            "checkpoint": evaluation.get("checkpoint"),
            "confidence": evaluation.get("confidence"),
            "iou_threshold": evaluation.get("iou_threshold"),
        },
        "policy": {
            "selection": (
                "source train images with fp > 0 or, when enabled, positive truth and fn > 0 "
                "at the fixed evaluation threshold"
            ),
            "max_extra_copies": max_extra_copies,
            "negative_bonus": negative_bonus,
            "include_false_negatives": include_false_negatives,
            "positive_extra_copies": (
                "min(max_extra_copies, max(fp, fn)) when include_false_negatives; "
                "otherwise min(max_extra_copies, fp)"
            ),
            "pure_negative_extra_copies": (
                "min(max_extra_copies, fp + negative_bonus)"
            ),
        },
        "source": {"images": len(images), "boxes": len(annotations)},
        "hard_source_images": hard_source_images,
        "fp_hard_source_images": fp_hard_source_images,
        "fn_hard_source_images": fn_hard_source_images,
        "hard_source_image_ids": hard_source_image_ids,
        "fn_hard_source_image_ids": fn_hard_source_image_ids,
        "pure_negative_hard_sources": pure_negative_sources,
        "extra": {"images": extra_images, "boxes": extra_boxes},
        "output": {"images": len(new_images), "boxes": len(new_annotations)},
        "repeat_histogram": repeat_histogram,
        "output_train_annotations_sha256": _sha256(train_output),
    }
    (output / "hard-example-provenance.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--evaluation", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--max-extra-copies", type=int, default=2)
    parser.add_argument("--negative-bonus", type=int, default=1)
    parser.add_argument(
        "--include-false-negatives", action="store_true",
        help="also repeat positive-truth images with false negatives",
    )
    args = parser.parse_args()
    try:
        result = build(
            args.data_dir, args.evaluation, args.output,
            args.max_extra_copies, args.negative_bonus, args.include_false_negatives,
        )
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
