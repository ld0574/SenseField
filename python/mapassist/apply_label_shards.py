"""Validate independent annotation shards and apply them to one review queue."""

from __future__ import annotations

import argparse
import json
import os
import sqlite3
import sys
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path


FINAL_STATUSES = {"corrected", "negative", "excluded", "skip"}


def _roi(value: object, label: str) -> list[float]:
    if (not isinstance(value, list) or len(value) != 4 or
            any(not isinstance(item, (int, float)) or isinstance(item, bool)
                for item in value)):
        raise ValueError(f"{label} must be normalized [x, y, width, height]")
    result = [float(item) for item in value]
    x, y, width, height = result
    if (x < 0 or y < 0 or width <= 0 or height <= 0 or
            x + width > 1.000001 or y + height > 1.000001):
        raise ValueError(f"{label} is outside the frame")
    return result


def _boxes(value: object, roi: list[float], label: str) -> list[list[float]]:
    if not isinstance(value, list) or not value:
        raise ValueError(f"{label} corrected status needs at least one box")
    result = []
    rx, ry, rw, rh = roi
    for box in value:
        if (not isinstance(box, list) or len(box) != 4 or
                any(not isinstance(item, (int, float)) or isinstance(item, bool)
                    for item in box)):
            raise ValueError(f"{label} has an invalid box: {box}")
        x, y, width, height = [float(item) for item in box]
        if (width <= 0 or height <= 0 or x < rx - 1e-6 or y < ry - 1e-6 or
                x + width > rx + rw + 1e-6 or y + height > ry + rh + 1e-6):
            raise ValueError(f"{label} box is outside the minimap roi: {box}")
        result.append([x, y, width, height])
    return result


def _load_shards(paths: list[Path]) -> tuple[dict[tuple[str, int], dict], set[str]]:
    labels: dict[tuple[str, int], dict] = {}
    annotators: set[str] = set()
    for path in paths:
        data = json.loads(path.read_text(encoding="utf-8"))
        if data.get("schema_version") != 1 or not isinstance(data.get("matches"), list):
            raise ValueError(f"Invalid label shard: {path}")
        annotator = data.get("annotator")
        if not isinstance(annotator, str) or not annotator.strip():
            raise ValueError(f"Shard has no annotator: {path}")
        annotators.add(annotator.strip())
        for match in data["matches"]:
            match_id = match.get("id")
            if not isinstance(match_id, str) or not isinstance(match.get("samples"), list):
                raise ValueError(f"Invalid match in shard: {path}")
            for sample in match["samples"]:
                timestamp = sample.get("at_ms")
                if not isinstance(timestamp, int) or isinstance(timestamp, bool):
                    raise ValueError(f"Invalid timestamp in shard: {path}")
                key = (match_id, timestamp)
                if key in labels:
                    raise ValueError(f"Duplicate shard label: {match_id}@{timestamp}")
                labels[key] = {**sample, "annotator": annotator.strip()}
    return labels, annotators


def apply(review_manifest: Path, shards: list[Path], database: Path | None = None) -> dict:
    original_text = review_manifest.read_text(encoding="utf-8")
    data = json.loads(original_text)
    if data.get("schema_version") != 1 or not isinstance(data.get("matches"), list):
        raise ValueError("Expected review manifest schema_version 1 with matches")
    if data.get("review_mode") != "manual":
        raise ValueError("Label shards can only be applied to a manual review queue")
    default_roi = _roi(data.get("roi"), "review roi")
    labels, annotators = _load_shards(shards)
    expected: dict[tuple[str, int], tuple[dict, list[float]]] = {}
    for match in data["matches"]:
        match_id = match.get("id")
        roi = _roi(match.get("roi", default_roi), f"{match_id} roi")
        for sample in match.get("samples", []):
            key = (match_id, sample.get("at_ms"))
            if key in expected:
                raise ValueError(f"Duplicate review sample: {key}")
            expected[key] = (sample, roi)
    missing = sorted(set(expected) - set(labels))
    unexpected = sorted(set(labels) - set(expected))
    if missing or unexpected:
        raise ValueError(
            f"Shard coverage mismatch: missing={len(missing)} unexpected={len(unexpected)}; "
            f"examples={missing[:3] + unexpected[:3]}"
        )

    now = datetime.now(timezone.utc).isoformat()
    counts: Counter[str] = Counter()
    box_count = 0
    prepared: dict[tuple[str, int], tuple[str, list[list[float]] | None, str]] = {}
    for key, (sample, roi) in expected.items():
        label = labels[key]
        status = label.get("review_status")
        if status not in FINAL_STATUSES:
            raise ValueError(f"{key} has invalid status: {status}")
        reviewed_boxes = label.get("reviewed_boxes")
        if status == "corrected":
            reviewed_boxes = _boxes(reviewed_boxes, roi, f"{key[0]}@{key[1]}")
            box_count += len(reviewed_boxes)
        elif reviewed_boxes is not None:
            raise ValueError(f"{key} {status} status must use reviewed_boxes=null")
        current = sample.get("review_status", "pending")
        if current != "pending":
            current_boxes = sample.get("reviewed_boxes")
            if current != status or current_boxes != reviewed_boxes:
                raise ValueError(f"Refusing to overwrite existing review for {key}")
        prepared[key] = (status, reviewed_boxes, label["annotator"])
        counts[status] += 1

    for key, (sample, _) in expected.items():
        status, reviewed_boxes, annotator = prepared[key]
        sample["review_status"] = status
        sample["reviewed_boxes"] = reviewed_boxes
        sample["reviewed_by"] = annotator
        sample["reviewed_at"] = now
    serialized = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    temporary = review_manifest.with_name(
        f".{review_manifest.name}.{os.getpid()}.{time.time_ns()}.tmp"
    )
    with temporary.open("w", encoding="utf-8") as stream:
        stream.write(serialized)
        stream.flush()
        os.fsync(stream.fileno())

    if database is None:
        database = review_manifest.parent / "annotations.sqlite3"
    database_exists = database.is_file()
    if database_exists:
        connection = sqlite3.connect(database, timeout=10, isolation_level=None)
        connection.row_factory = sqlite3.Row
        manifest_replaced = False
        try:
            connection.execute("PRAGMA busy_timeout = 10000")
            connection.execute("BEGIN IMMEDIATE")
            rows = connection.execute(
                "SELECT match_id, at_ms, review_status, reviewed_boxes FROM tasks"
            ).fetchall()
            indexed = {(row["match_id"], row["at_ms"]): row for row in rows}
            if set(indexed) != set(expected):
                raise ValueError("Annotation database does not match the review manifest")
            for key, (status, reviewed_boxes, annotator) in prepared.items():
                row = indexed[key]
                current_boxes = (json.loads(row["reviewed_boxes"])
                                 if row["reviewed_boxes"] is not None else None)
                if row["review_status"] != "pending" and (
                        row["review_status"] != status or current_boxes != reviewed_boxes):
                    raise ValueError(f"Refusing to overwrite database review for {key}")
                connection.execute(
                    """
                    UPDATE tasks SET review_status=?, reviewed_boxes=?, reviewed_by=?,
                        reviewed_at=?, lease_owner=NULL, lease_until=NULL, version=version+1
                    WHERE match_id=? AND at_ms=?
                    """,
                    (status, json.dumps(reviewed_boxes) if reviewed_boxes is not None else None,
                     annotator, now, key[0], key[1]),
                )
            os.replace(temporary, review_manifest)
            manifest_replaced = True
            connection.execute("COMMIT")
        except Exception:
            if connection.in_transaction:
                connection.execute("ROLLBACK")
            if manifest_replaced:
                rollback = review_manifest.with_name(
                    f".{review_manifest.name}.{os.getpid()}.{time.time_ns()}.rollback"
                )
                with rollback.open("w", encoding="utf-8") as stream:
                    stream.write(original_text)
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(rollback, review_manifest)
            raise
        finally:
            connection.close()
            temporary.unlink(missing_ok=True)
    else:
        try:
            os.replace(temporary, review_manifest)
        finally:
            temporary.unlink(missing_ok=True)
    return {
        "samples": len(expected),
        "boxes": box_count,
        "statuses": dict(sorted(counts.items())),
        "annotators": sorted(annotators),
        "database_updated": database_exists,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("review_manifest", type=Path)
    parser.add_argument("shards", type=Path, nargs="+")
    parser.add_argument("--database", type=Path)
    args = parser.parse_args()
    try:
        result = apply(args.review_manifest, args.shards, args.database)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError,
            sqlite3.DatabaseError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
