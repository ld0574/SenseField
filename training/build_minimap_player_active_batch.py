#!/usr/bin/env python3
"""Build a small, stratified review batch from player suggestion scores.

The input predictions are review aids.  This script deliberately keeps every
selected task pending and never promotes a learned suggestion to ground truth.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import shutil
import sqlite3
import sys
from collections import Counter, defaultdict
from copy import deepcopy
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))

from mapassist.annotation_server import AnnotationStore  # noqa: E402


def evenly_spaced(rows: list[dict], count: int) -> list[dict]:
    """Choose chronological coverage without selecting an item twice."""
    if count <= 0 or not rows:
        return []
    ordered = sorted(rows, key=lambda row: (row["at_ms"], row["id"]))
    if count >= len(ordered):
        return ordered
    if count == 1:
        return [ordered[len(ordered) // 2]]
    indices = {
        round(index * (len(ordered) - 1) / (count - 1))
        for index in range(count)
    }
    # Rounding can theoretically collapse indices. Fill any gap in time order.
    for index in range(len(ordered)):
        if len(indices) >= count:
            break
        indices.add(index)
    return [ordered[index] for index in sorted(indices)[:count]]


def select_match(rows: list[dict], per_match: int,
                 forced_ids: set[int]) -> list[dict]:
    forced = [row for row in rows if row["id"] in forced_ids]
    if len(forced) > per_match:
        raise ValueError("forced ids exceed the per-match batch size")
    selected = {row["id"]: row for row in forced}

    strata = {
        "learned_high": [
            row for row in rows
            if row["id"] not in selected and row["suggested_box"] is not None
            and row["probability"] >= 0.90
        ],
        "learned_boundary": [
            row for row in rows
            if row["id"] not in selected and 0.20 <= row["probability"] < 0.90
        ],
        "learned_low": [
            row for row in rows
            if row["id"] not in selected and row["probability"] < 0.20
        ],
    }
    target = {
        "learned_high": per_match * 2 // 5,
        "learned_boundary": per_match * 2 // 5,
        "learned_low": per_match - 2 * (per_match * 2 // 5),
    }
    for name in ("learned_high", "learned_boundary", "learned_low"):
        available = [row for row in strata[name] if row["id"] not in selected]
        for row in evenly_spaced(available, min(target[name], per_match - len(selected))):
            row = dict(row)
            row["selection_reason"] = name
            selected[row["id"]] = row

    remaining = [row for row in rows if row["id"] not in selected]
    for row in evenly_spaced(remaining, per_match - len(selected)):
        row = dict(row)
        row["selection_reason"] = "stratum_shortfall_fill"
        selected[row["id"]] = row

    for task_id in forced_ids:
        if task_id in selected:
            selected[task_id] = {
                **selected[task_id], "selection_reason": "diagnostic_hard_case",
            }
    if len(selected) != min(per_match, len(rows)):
        raise RuntimeError("active batch selection did not reach its target")
    return sorted(selected.values(), key=lambda row: (row["at_ms"], row["id"]))


def copy_media(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        return
    # Review batches are frozen snapshots. A hard link would let a later
    # source-image replacement silently change an in-progress review batch.
    shutil.copy2(source, destination)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def database_content_sha256(path: Path) -> str:
    with sqlite3.connect(path) as connection:
        connection.row_factory = sqlite3.Row
        rows = [dict(row) for row in connection.execute(
            "SELECT * FROM tasks ORDER BY id"
        )]
    payload = json.dumps(
        rows, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def validate_prediction(row: object) -> dict:
    if not isinstance(row, dict):
        raise ValueError("Each prediction must be an object")
    task_id = row.get("id")
    if not isinstance(task_id, int) or isinstance(task_id, bool):
        raise ValueError("Each prediction needs an integer task id")
    probability = row.get("probability")
    if (not isinstance(probability, (int, float)) or isinstance(probability, bool)
            or not math.isfinite(float(probability))
            or not 0.0 <= float(probability) <= 1.0):
        raise ValueError(f"Prediction {task_id} has an invalid probability")
    box = row.get("suggested_box")
    if box is not None:
        if (not isinstance(box, list) or len(box) != 4 or
                any(not isinstance(value, (int, float)) or isinstance(value, bool)
                    or not math.isfinite(float(value)) for value in box)):
            raise ValueError(f"Prediction {task_id} has an invalid suggested_box")
        x, y, width, height = (float(value) for value in box)
        if (x < 0 or y < 0 or width <= 0 or height <= 0 or
                x + width > 1.000001 or y + height > 1.000001):
            raise ValueError(f"Prediction {task_id} suggested_box is out of bounds")
    return row


def build(source: Path, predictions_path: Path, output: Path,
          per_match: int, forced_ids: set[int]) -> dict:
    source = source.resolve()
    output = output.resolve()
    if output == source:
        raise ValueError("Output directory must differ from the source queue")
    if output.exists():
        if not output.is_dir() or any(output.iterdir()):
            raise ValueError(f"Output directory is not empty: {output}")

    manifest = json.loads((source / "review-manifest.json").read_text(
        encoding="utf-8"
    ))
    predictions_doc = json.loads(predictions_path.read_text(encoding="utf-8"))
    if predictions_doc.get("schema") != "mapassist.minimap_player_learned_suggestions":
        raise ValueError("Unexpected player prediction schema")
    if manifest.get("kind") != "minimap_player":
        raise ValueError("Source queue is not a minimap_player queue")
    if any(str(match.get("id", "")).lower().startswith(("video9", "video12"))
           for match in manifest.get("matches", [])):
        raise ValueError("Sealed video9/video12 cannot enter an active batch")

    with sqlite3.connect(source / "annotations.sqlite3") as connection:
        connection.row_factory = sqlite3.Row
        pending = {
            row["id"]: dict(row)
            for row in connection.execute(
                "SELECT * FROM tasks WHERE review_status = 'pending'"
            )
        }
    result_rows = predictions_doc.get("results")
    if not isinstance(result_rows, list):
        raise ValueError("Predictions document needs a results list")
    validated_rows = [validate_prediction(row) for row in result_rows]
    prediction_ids = [row["id"] for row in validated_rows]
    if len(prediction_ids) != len(set(prediction_ids)):
        raise ValueError("Predictions contain duplicate task ids")
    missing = sorted(set(pending) - set(prediction_ids))
    extra = sorted(set(prediction_ids) - set(pending))
    if missing or extra:
        raise ValueError(
            f"Predictions must exactly cover pending tasks; missing={missing[:10]}, "
            f"extra={extra[:10]}"
        )

    predictions = []
    for row in validated_rows:
        task = pending[row["id"]]
        if task["match_id"] != row["match_id"] or task["at_ms"] != row["at_ms"]:
            raise ValueError(f"Prediction identity mismatch for task {row['id']}")
        predictions.append({**row, "source_task": task})
    unknown_forced = forced_ids - set(pending)
    if unknown_forced:
        raise ValueError(f"Forced ids are not pending source tasks: {sorted(unknown_forced)}")

    by_match: dict[str, list[dict]] = defaultdict(list)
    for row in predictions:
        if row["match_id"].lower().startswith(("video9", "video12")):
            raise ValueError("Sealed video9/video12 cannot enter an active batch")
        by_match[row["match_id"]].append(row)
    selected = []
    for match_id in sorted(by_match):
        selected.extend(select_match(by_match[match_id], per_match, forced_ids))
    selected_keys = {(row["match_id"], row["at_ms"]): row for row in selected}

    result = {
        key: deepcopy(value) for key, value in manifest.items()
        if key != "matches"
    }
    result.update({
        "review_mode": "manual",
        "warning": (
            "Active-learning review subset. Learned boxes are suggestions only; "
            "all samples remain pending until a person reviews them."
        ),
        "suggestion_provenance": {
            "authority": "machine_suggestion_only",
            "is_ground_truth": False,
            "usable_for_training": False,
            "usable_for_metrics": False,
            "release_eligible": False,
            "method": "learned_candidate_ranker_over_green_annulus_proposals",
            "implementation": "training/learn_minimap_player_suggestions.py",
            "source_predictions": str(predictions_path.resolve()),
            "source_prediction_sha256": sha256(predictions_path),
            "threshold_selected_on_development_validation": True,
            "candidate_generator": "green_annulus_proposals",
            "limitations": [
                "Every learned box remains an editable review suggestion.",
                "No suggestion means no accepted candidate, not player absence or death.",
                "Green map markers, effects and decorations remain hard negatives.",
            ],
        },
        "label_assistance": {
            "notice": (
                "这是 218 张人工结果学习后的主动抽样小批次。机器框不是答案；"
                "每张仍需确认，错误框直接重新画。"
            ),
        },
        "active_learning": {
            "schema": "mapassist.minimap_player_active_batch",
            "schema_version": 1,
            "source_queue": str(source),
            "source_predictions": str(predictions_path.resolve()),
            "source_prediction_sha256": sha256(predictions_path),
            "per_match": per_match,
            "forced_source_task_ids": sorted(forced_ids),
            "threshold_selected_on_development_validation": True,
            "warning": (
                "Selection and boxes are review aids, not ground truth, training "
                "truth, evaluation evidence, or release capability evidence."
            ),
        },
        "matches": [],
    })
    copied: set[str] = set()
    for match in manifest["matches"]:
        samples = []
        source_samples = {sample["at_ms"]: sample for sample in match["samples"]}
        for key, row in selected_keys.items():
            if key[0] != match["id"]:
                continue
            original = deepcopy(source_samples[row["at_ms"]])
            task = row["source_task"]
            for media_key in ("frame", "overlay"):
                relative = task[media_key]
                if relative not in copied:
                    copy_media(source / relative, output / relative)
                    copied.add(relative)
                original[media_key] = relative
            box = row["suggested_box"]
            original.update({
                "selection": row["selection_reason"],
                "suggested_boxes": [box] if box is not None else [],
                "suggested_categories": ["minimap_player"] if box is not None else [],
                "directions": [None] if box is not None else [],
                "suggestion_metadata": {
                    "class": "minimap_player",
                    "source": "learned_candidate_ranker",
                    "probability": row["probability"],
                    "threshold": row["threshold"],
                    "candidate_ring_score": row["candidate_ring_score"],
                    "candidate_radius_px": row["candidate_radius_px"],
                    "decision": (
                        "learned_suggestion"
                        if box is not None
                        else "empty_below_learned_threshold"
                    ),
                    "is_confidence": True,
                    "is_ground_truth": False,
                    "usable_for_training": False,
                    "usable_for_metrics": False,
                    "release_eligible": False,
                },
                "review_status": "pending",
                "reviewed_boxes": None,
                "reviewed_categories": None,
                "active_learning_source": {
                    "source_task_id": row["id"],
                    "probability": row["probability"],
                    "threshold": row["threshold"],
                    "candidate_ring_score": row["candidate_ring_score"],
                    "candidate_radius_px": row["candidate_radius_px"],
                    "selection_reason": row["selection_reason"],
                    "is_ground_truth": False,
                },
            })
            for field in ("reviewed_by", "reviewed_at"):
                original.pop(field, None)
            samples.append(original)
        if samples:
            copied_match = deepcopy(match)
            copied_match["samples"] = sorted(samples, key=lambda sample: sample["at_ms"])
            source_provenance = match.get("provenance_samples")
            if source_provenance is not None:
                if not isinstance(source_provenance, list):
                    raise ValueError(
                        f"Match {match['id']} provenance_samples must be a list"
                    )
                selected_by_time = {
                    sample["at_ms"]: sample for sample in copied_match["samples"]
                }
                provenance_by_time: dict[int, dict] = {}
                for entry in source_provenance:
                    if not isinstance(entry, dict) or not isinstance(
                        entry.get("at_ms"), int
                    ):
                        raise ValueError(
                            f"Match {match['id']} has invalid provenance sample"
                        )
                    at_ms = entry["at_ms"]
                    if at_ms in provenance_by_time:
                        raise ValueError(
                            f"Match {match['id']} has duplicate provenance at {at_ms}"
                        )
                    provenance_by_time[at_ms] = entry
                missing_provenance = sorted(
                    set(selected_by_time) - set(provenance_by_time)
                )
                if missing_provenance:
                    raise ValueError(
                        f"Match {match['id']} is missing selected provenance: "
                        f"{missing_provenance[:10]}"
                    )
                copied_provenance = []
                for at_ms, sample in sorted(selected_by_time.items()):
                    entry = deepcopy(provenance_by_time[at_ms])
                    entry.update({
                        "queue_frame": sample["frame"],
                        "queue_overlay": (
                            "same_as_queue_frame"
                            if sample["overlay"] == sample["frame"]
                            else sample["overlay"]
                        ),
                        "suggestion": deepcopy(sample["suggestion_metadata"]),
                        "suggested_box_count": len(sample["suggested_boxes"]),
                        "review_status": "pending",
                    })
                    copied_provenance.append(entry)
                copied_match["provenance_samples"] = copied_provenance
            result["matches"].append(copied_match)

    output.mkdir(parents=True, exist_ok=True)
    manifest_path = output / "review-manifest.json"
    manifest_path.write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    store = AnnotationStore(output)
    stats = store.stats()
    if stats["counts"]["pending"] != len(selected) or stats["completed"] != 0:
        raise RuntimeError("New active batch did not initialize as all-pending")

    selection_csv = output / "selection.csv"
    with selection_csv.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow([
            "source_task_id", "match_id", "at_ms", "selection_reason",
            "probability", "has_suggestion",
        ])
        for row in sorted(selected, key=lambda item: item["id"]):
            writer.writerow([
                row["id"], row["match_id"], row["at_ms"],
                row["selection_reason"], row["probability"],
                row["suggested_box"] is not None,
            ])
    audit = {
        "schema": "mapassist.minimap_player_active_batch_audit",
        "schema_version": 1,
        "source_pending": len(pending),
        "selected": len(selected),
        "all_pending": True,
        "sealed_sources_present": False,
        "by_match": dict(Counter(row["match_id"] for row in selected)),
        "by_reason": dict(Counter(row["selection_reason"] for row in selected)),
        "suggestions": sum(row["suggested_box"] is not None for row in selected),
        "empty_suggestions": sum(row["suggested_box"] is None for row in selected),
        "manifest_sha256": sha256(manifest_path),
        "database_content_sha256": database_content_sha256(
            output / "annotations.sqlite3"
        ),
    }
    (output / "audit.json").write_text(
        json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return audit


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--predictions", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--per-match", type=int, default=15)
    parser.add_argument("--force-id", type=int, action="append", default=[])
    args = parser.parse_args()
    if args.per_match < 3:
        parser.error("--per-match must be at least 3")
    print(json.dumps(build(
        args.source, args.predictions, args.output, args.per_match,
        set(args.force_id),
    ), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
