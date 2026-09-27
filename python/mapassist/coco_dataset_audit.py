"""Audit a downloaded COCO dataset before it enters training.

The command never changes the dataset.  It reports class semantics, image
dimensions, missing files and exact duplicate images across splits.  This is
especially useful for public datasets whose published image-level split cannot
be trusted as an independent match-level evaluation split.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def audit_coco_dataset(root: Path) -> dict[str, Any]:
    root = root.resolve()
    annotations_dir = root / "annotations"
    split_reports: dict[str, Any] = {}
    hashes: dict[str, list[dict[str, Any]]] = defaultdict(list)
    category_sets: dict[str, list[tuple[int, str]]] = {}
    blockers: list[str] = []

    for split in ("train", "val", "test"):
        annotation_path = annotations_dir / f"instances_{split}2017.json"
        if not annotation_path.exists():
            continue
        document = json.loads(annotation_path.read_text(encoding="utf-8"))
        images = document.get("images")
        annotations = document.get("annotations")
        categories = document.get("categories")
        if not isinstance(images, list) or not isinstance(annotations, list) or not isinstance(categories, list):
            raise ValueError(f"Invalid COCO document: {annotation_path}")
        category_sets[split] = sorted(
            (int(item["id"]), str(item["name"])) for item in categories
        )
        image_ids = {int(item["id"]) for item in images}
        annotation_image_ids = {int(item["image_id"]) for item in annotations}
        unknown_image_ids = sorted(annotation_image_ids - image_ids)
        missing_files: list[str] = []
        dimensions: Counter[str] = Counter()
        image_dir = root / f"{split}2017"
        for image in images:
            file_name = str(image["file_name"])
            path = image_dir / file_name
            dimensions[f'{int(image["width"])}x{int(image["height"])}'] += 1
            if not path.is_file():
                missing_files.append(file_name)
                continue
            hashes[_sha256(path)].append({"split": split, "file_name": file_name})
        class_counts = Counter(int(item["category_id"]) for item in annotations)
        split_reports[split] = {
            "annotation_file": str(annotation_path),
            "annotation_sha256": _sha256(annotation_path),
            "images": len(images),
            "annotations": len(annotations),
            "categories": len(categories),
            "category_annotation_counts": {
                str(key): class_counts[key] for key in sorted(class_counts)
            },
            "dimensions": dict(dimensions.most_common()),
            "missing_files": missing_files,
            "unknown_annotation_image_ids": unknown_image_ids,
        }
        if missing_files:
            blockers.append(f"{split}: {len(missing_files)} image files are missing")
        if unknown_image_ids:
            blockers.append(f"{split}: annotations reference unknown image IDs")

    if not split_reports:
        raise ValueError(f"No instances_<split>2017.json files found under {annotations_dir}")
    canonical_categories = next(iter(category_sets.values()))
    for split, categories in category_sets.items():
        if categories != canonical_categories:
            blockers.append(f"{split}: category mapping differs from the other splits")
    names = [name for _, name in canonical_categories]
    numeric_only = bool(names) and all(name.isdigit() for name in names)
    if numeric_only:
        blockers.append(
            "All category names are numeric; provide a reviewed semantic class map before remapping labels"
        )
    duplicate_groups = [items for items in hashes.values() if len(items) > 1]
    cross_split_duplicates = [
        items for items in duplicate_groups if len({item["split"] for item in items}) > 1
    ]
    if cross_split_duplicates:
        blockers.append(
            f"{len(cross_split_duplicates)} exact image duplicate groups cross split boundaries"
        )
    test_images = split_reports.get("test", {}).get("images", 0)
    if test_images == 0:
        blockers.append("No populated test split; do not quote this dataset as independent evaluation")

    return {
        "schema_version": 1,
        "dataset_root": str(root),
        "categories": [{"id": identifier, "name": name} for identifier, name in canonical_categories],
        "numeric_category_names_only": numeric_only,
        "splits": split_reports,
        "exact_duplicate_groups": duplicate_groups,
        "cross_split_duplicate_groups": cross_split_duplicates,
        "training_blockers": blockers,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset", type=Path, help="COCO root with annotations/ and split folders")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = audit_coco_dataset(args.dataset)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "output": str(args.output),
        "splits": {key: value["images"] for key, value in report["splits"].items()},
        "categories": len(report["categories"]),
        "training_blockers": report["training_blockers"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
