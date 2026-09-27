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
import math
import os
import re
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


def _top_edge_threshold(value: object) -> float | None:
    if value is None:
        return None
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ValueError("top_edge_y_max must be a finite number >= 0")
    try:
        numeric_value = float(value)
    except (OverflowError, ValueError):
        raise ValueError("top_edge_y_max must be a finite number >= 0") from None
    if not math.isfinite(numeric_value) or numeric_value < 0:
        raise ValueError("top_edge_y_max must be a finite number >= 0")
    return numeric_value


def _is_finite_number(value: object) -> bool:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return False
    try:
        return math.isfinite(float(value))
    except (OverflowError, ValueError):
        return False


def _match_id(image: dict[str, Any], file_name: str) -> str:
    explicit = image.get("match_id")
    if "match_id" in image:
        if not isinstance(explicit, str) or not explicit.strip():
            raise ValueError(f"image {image.get('id')} match_id must be a non-empty string")
        return explicit
    stem = Path(file_name).stem
    match = re.fullmatch(r"(.+)_\d+(?:\.\d+)?", stem)
    return match.group(1) if match else stem


def build(data_dir: Path, evaluation_path: Path, output: Path,
          max_extra_copies: int = 2, negative_bonus: int = 1,
          include_false_negatives: bool = False,
          min_false_negatives: int | None = None,
          max_hard_sources_per_match: int = 0,
          top_edge_y_max: float | None = None,
          top_edge_extra_copies: int = 0) -> dict[str, Any]:
    """Create a COCO directory with deterministic hard-example repetition."""
    max_extra_copies = _integer(max_extra_copies, "max_extra_copies", 1)
    negative_bonus = _integer(negative_bonus, "negative_bonus")
    if min_false_negatives is not None:
        min_false_negatives = _integer(min_false_negatives, "min_false_negatives", 1)
    max_hard_sources_per_match = _integer(
        max_hard_sources_per_match, "max_hard_sources_per_match"
    )
    top_edge_extra_copies = _integer(top_edge_extra_copies, "top_edge_extra_copies")
    top_edge_y_max = _top_edge_threshold(top_edge_y_max)
    if top_edge_y_max is None and top_edge_extra_copies:
        raise ValueError("top_edge_y_max is required when top_edge_extra_copies is positive")
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
    match_by_id: dict[int, str] = {}
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
        match_by_id[image_id] = _match_id(image, file_name)

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

    top_edge_box_count = 0
    top_edge_image_ids: set[int] = set()
    if top_edge_y_max is not None:
        for annotation in annotations:
            bbox = annotation.get("bbox")
            if not isinstance(bbox, list) or len(bbox) != 4 or any(
                not _is_finite_number(value)
                for value in bbox
            ):
                raise ValueError(
                    f"annotation {annotation['id']} bbox must contain four finite numeric values "
                    "when top-edge weighting is enabled"
                )
            if bbox[1] <= top_edge_y_max:
                image_id = annotation["image_id"]
                top_edge_image_ids.add(image_id)
                top_edge_box_count += 1

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
    hard_candidates: dict[int, dict[str, int]] = {}
    for image_id in sorted(image_by_id):
        row = evaluation_by_id[image_id]
        fp = row["fp"]
        fn = row["fn"]
        truth = row["truth"]
        is_fp_source = fp > 0
        qualifies_fn = include_false_negatives and truth > 0 and fn > 0 and (
            is_fp_source or min_false_negatives is None or fn >= min_false_negatives
        )
        if not is_fp_source and not qualifies_fn:
            continue
        hard_candidates[image_id] = row

    candidates_by_match: dict[str, list[int]] = {}
    for image_id in hard_candidates:
        candidates_by_match.setdefault(match_by_id[image_id], []).append(image_id)
    selected_hard_source_ids: set[int] = set()
    for match_id in sorted(candidates_by_match):
        ordered = sorted(
            candidates_by_match[match_id],
            key=lambda image_id: (
                -int(hard_candidates[image_id]["fp"] > 0),
                -hard_candidates[image_id]["fn"],
                image_id,
            ),
        )
        if max_hard_sources_per_match == 0:
            selected_hard_source_ids.update(ordered)
        else:
            selected_hard_source_ids.update(ordered[:max_hard_sources_per_match])
    quota_excluded_ids = set(hard_candidates) - selected_hard_source_ids

    hard_source_image_ids = sorted(selected_hard_source_ids)
    fn_hard_source_image_ids = [
        image_id for image_id in hard_source_image_ids
        if include_false_negatives and evaluation_by_id[image_id]["truth"] > 0 and
        evaluation_by_id[image_id]["fn"] > 0 and (
            evaluation_by_id[image_id]["fp"] > 0 or min_false_negatives is None or
            evaluation_by_id[image_id]["fn"] >= min_false_negatives
        )
    ]
    fp_hard_source_images = sum(
        evaluation_by_id[image_id]["fp"] > 0 for image_id in hard_source_image_ids
    )
    fn_hard_source_images = len(fn_hard_source_image_ids)
    pure_negative_sources = sum(
        evaluation_by_id[image_id]["truth"] == 0 for image_id in hard_source_image_ids
    )
    hard_copies_by_id: dict[int, int] = {}
    for image_id in hard_source_image_ids:
        row = evaluation_by_id[image_id]
        if row["truth"] == 0:
            copies = min(max_extra_copies, row["fp"] + negative_bonus)
        else:
            copies = min(
                max_extra_copies,
                max(row["fp"], row["fn"] if include_false_negatives else 0),
            )
        hard_copies_by_id[image_id] = copies

    hard_source_images = len(hard_source_image_ids)
    extra_images = extra_boxes = 0
    top_edge_extra_images = top_edge_extra_boxes = 0
    repeat_histogram: dict[str, int] = {}
    for image_id in sorted(image_by_id):
        hard_copies = hard_copies_by_id.get(image_id, 0)
        top_edge_copies = top_edge_extra_copies if image_id in top_edge_image_ids else 0
        copies = max(hard_copies, top_edge_copies)
        if copies:
            repeat_histogram[str(copies)] = repeat_histogram.get(str(copies), 0) + 1
        top_edge_increment = max(0, copies - hard_copies) if image_id in top_edge_image_ids else 0
        top_edge_extra_images += top_edge_increment
        top_edge_extra_boxes += top_edge_increment * len(annotations_by_image[image_id])
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
            "min_false_negatives": min_false_negatives,
            "max_hard_sources_per_match": max_hard_sources_per_match,
            "match_id_source": (
                "COCO image match_id when present; otherwise file_name without its final "
                "numeric _timestamp suffix"
            ),
            "positive_extra_copies": (
                "min(max_extra_copies, max(fp, fn)) when include_false_negatives; "
                "otherwise min(max_extra_copies, fp)"
            ),
            "pure_negative_extra_copies": (
                "min(max_extra_copies, fp + negative_bonus)"
            ),
            "min_false_negatives_scope": (
                "when include_false_negatives is enabled, filters only pure false-negative "
                "sources; FP sources remain eligible"
            ),
            "top_edge": {
                "enabled": top_edge_y_max is not None,
                "y_max": top_edge_y_max,
                "extra_copies": top_edge_extra_copies,
                "merge": "max(top_edge_extra_copies, selected_hard_error_copies) per image",
            },
        },
        "source": {"images": len(images), "boxes": len(annotations)},
        "hard_source_images": hard_source_images,
        "fp_hard_source_images": fp_hard_source_images,
        "fn_hard_source_images": fn_hard_source_images,
        "hard_source_image_ids": hard_source_image_ids,
        "fn_hard_source_image_ids": fn_hard_source_image_ids,
        "pure_negative_hard_sources": pure_negative_sources,
        "match_quota": {
            "max_hard_sources_per_match": max_hard_sources_per_match,
            "candidate_sources": len(hard_candidates),
            "selected_sources": len(selected_hard_source_ids),
            "excluded_sources": len(quota_excluded_ids),
            "candidate_sources_by_match": {
                match_id: len(candidates_by_match[match_id])
                for match_id in sorted(candidates_by_match)
            },
            "selected_sources_by_match": {
                match_id: sum(
                    image_id in selected_hard_source_ids
                    for image_id in candidates_by_match[match_id]
                )
                for match_id in sorted(candidates_by_match)
            },
            "excluded_sources_by_match": {
                match_id: sum(image_id in quota_excluded_ids for image_id in candidates_by_match[match_id])
                for match_id in sorted(candidates_by_match)
                if any(image_id in quota_excluded_ids for image_id in candidates_by_match[match_id])
            },
            "excluded_image_ids": sorted(quota_excluded_ids),
        },
        "top_edge": {
            "source_images": len(top_edge_image_ids),
            "source_boxes": top_edge_box_count,
            "incremental_extra": {
                "images": top_edge_extra_images,
                "boxes": top_edge_extra_boxes,
            },
        },
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
    parser.add_argument(
        "--min-false-negatives", type=int,
        help="minimum fn count for a pure FN source when FN inclusion is enabled; FP sources remain eligible",
    )
    parser.add_argument(
        "--max-hard-sources-per-match", type=int, default=0,
        help="maximum selected hard-error sources per match; 0 means unlimited",
    )
    parser.add_argument(
        "--top-edge-y-max", type=float,
        help="include positive images with a ground-truth bbox y at or above this pixel row",
    )
    parser.add_argument(
        "--top-edge-extra-copies", type=int, default=0,
        help="additional copies for top-edge images, merged with hard-error copies by max",
    )
    args = parser.parse_args()
    try:
        result = build(
            args.data_dir, args.evaluation, args.output,
            args.max_extra_copies, args.negative_bonus, args.include_false_negatives,
            args.min_false_negatives, args.max_hard_sources_per_match,
            args.top_edge_y_max, args.top_edge_extra_copies,
        )
    except (OSError, ValueError, TypeError, KeyError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
