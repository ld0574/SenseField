"""Audit and thin the private main-screen edge review queue.

This command is intentionally independent of the Android build and never edits
the source queue.  It reads the queue's SQLite database and review manifest,
reports machine-suggestion proxies (they are not labels), and writes a small
manual-review batch with copied/hard-linked frame assets and a fresh pending
SQLite database.

The source queue currently contains one row per sampled frame.  The thinning
policy keeps 20 pending frames per match: five machine-empty frames, three
left-only frames, two frames with both sides, and ten right-only frames.  Each
category is spread over the match timeline and ranked for review value using
geometry and UI-risk proxies.  The policy is deterministic and excludes
sealed video9/video12 identifiers.
"""

from __future__ import annotations

import argparse
import copy
import csv
import hashlib
import json
import math
import os
import shutil
import sqlite3
import tempfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw

from mapassist.annotation_server import AnnotationStore
from mapassist.orientation import from_manifest


SEALED_PREFIXES = ("video9", "video12")
SOURCE_METADATA_KEYS = ("video", "video_sha256", "orientation")


def _json(value: Any) -> Any:
    return json.loads(value) if isinstance(value, str) else value


def _sealed(match_id: str) -> bool:
    normalized = match_id.casefold().replace("_", "-")
    return normalized.startswith(SEALED_PREFIXES)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _canonical_sha256(value: Any) -> str:
    payload = json.dumps(value, ensure_ascii=False, sort_keys=True,
                         separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def _write_json_atomic(path: Path, value: Any) -> None:
    """Write JSON through a sibling temporary file and atomic replace."""
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent,
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _source_metadata(source_manifest: Path, match: dict[str, Any],
                     document: dict[str, Any]) -> dict[str, Any]:
    """Extract the immutable media metadata needed by review finalization."""
    match_id = match.get("id")
    if not isinstance(match_id, str) or not match_id:
        raise ValueError(f"source manifest has a match without a valid id: {source_manifest}")
    if _sealed(match_id):
        raise ValueError(f"sealed match is present in source manifest: {match_id}")

    video = match.get("video")
    if not isinstance(video, str) or not video:
        video = match.get("source_video_path")
    if not isinstance(video, str) or not video:
        raise ValueError(f"{match_id} needs video or source_video_path in {source_manifest}")

    video_sha256 = match.get("video_sha256")
    if not isinstance(video_sha256, str) or not video_sha256:
        video_sha256 = match.get("source_video_sha256")
    if (not isinstance(video_sha256, str) or len(video_sha256) != 64 or
            any(character not in "0123456789abcdefABCDEF"
                for character in video_sha256)):
        raise ValueError(f"{match_id} video_sha256 must be 64 hexadecimal characters")

    orientation = from_manifest(match, match_id)
    if orientation is None:
        orientation = from_manifest(document, f"{source_manifest} orientation")
    if orientation is None:
        raise ValueError(f"{match_id} needs orientation metadata in {source_manifest}")

    return {
        "video": video,
        "video_sha256": video_sha256.lower(),
        "orientation": orientation,
    }


def _load_source_metadata(source_manifest: Path) -> dict[str, dict[str, Any]]:
    """Load per-match source media metadata without opening any video files."""
    source_manifest = source_manifest.resolve()
    try:
        document = json.loads(source_manifest.read_text(encoding="utf-8"))
    except OSError as error:
        raise FileNotFoundError(source_manifest) from error
    except json.JSONDecodeError as error:
        raise ValueError(f"invalid source manifest JSON: {source_manifest}") from error
    if not isinstance(document, dict):
        raise ValueError(f"source manifest must contain an object: {source_manifest}")
    matches = document.get("matches")
    if not isinstance(matches, list) or not matches:
        raise ValueError(f"source manifest needs matches: {source_manifest}")
    metadata: dict[str, dict[str, Any]] = {}
    for match in matches:
        if not isinstance(match, dict):
            raise ValueError(f"source manifest match must be an object: {source_manifest}")
        match_id = match.get("id")
        if not isinstance(match_id, str) or not match_id:
            raise ValueError(f"source manifest has a match without a valid id: {source_manifest}")
        if match_id in metadata:
            raise ValueError(f"duplicate source manifest match id: {match_id}")
        metadata[match_id] = _source_metadata(source_manifest, match, document)
    return metadata


def _sample_payload_sha256(document: dict[str, Any]) -> str:
    """Hash labels and sample provenance while ignoring match metadata fields."""
    matches = document.get("matches")
    if not isinstance(matches, list):
        raise ValueError("review manifest needs matches")
    payload = []
    for match in matches:
        if not isinstance(match, dict):
            raise ValueError("review manifest match must be an object")
        payload.append({
            "id": match.get("id"),
            "split": match.get("split"),
            "samples": copy.deepcopy(match.get("samples")),
        })
    return _canonical_sha256(payload)


def enrich_review_manifest(review_manifest: Path, source_manifest: Path,
                           output: Path | None = None) -> dict[str, Any]:
    """Add source media metadata while preserving every reviewed sample.

    The default output is a sibling ``review-manifest.enriched.json`` so a
    completed batch can be repaired without replacing its original manifest.
    Passing the original path explicitly is supported for a deliberate,
    atomic in-place repair.
    """
    review_manifest = review_manifest.resolve()
    source_manifest = source_manifest.resolve()
    if review_manifest == source_manifest:
        raise ValueError("review manifest and source manifest must differ")
    output = (output or review_manifest.with_name("review-manifest.enriched.json")).resolve()

    try:
        document = json.loads(review_manifest.read_text(encoding="utf-8"))
    except OSError as error:
        raise FileNotFoundError(review_manifest) from error
    except json.JSONDecodeError as error:
        raise ValueError(f"invalid review manifest JSON: {review_manifest}") from error
    if not isinstance(document, dict):
        raise ValueError(f"review manifest must contain an object: {review_manifest}")
    matches = document.get("matches")
    if not isinstance(matches, list) or not matches:
        raise ValueError(f"review manifest needs matches: {review_manifest}")

    metadata_by_id = _load_source_metadata(source_manifest)
    labels_before = _sample_payload_sha256(document)
    enriched = copy.deepcopy(document)
    added_fields = 0
    for match in enriched["matches"]:
        if not isinstance(match, dict):
            raise ValueError("review manifest match must be an object")
        match_id = match.get("id")
        if not isinstance(match_id, str) or not match_id:
            raise ValueError("review manifest has a match without a valid id")
        if _sealed(match_id):
            raise ValueError(f"sealed match is present in review manifest: {match_id}")
        try:
            metadata = metadata_by_id[match_id]
        except KeyError as error:
            raise ValueError(
                f"source manifest has no metadata for review match: {match_id}"
            ) from error
        for key in SOURCE_METADATA_KEYS:
            existing = match.get(key)
            if existing is not None:
                if key == "orientation":
                    existing = from_manifest(match, match_id)
                elif key == "video_sha256" and isinstance(existing, str):
                    existing = existing.lower()
                if existing != metadata[key]:
                    raise ValueError(f"{match_id} has conflicting {key} metadata")
            else:
                added_fields += 1
            match[key] = copy.deepcopy(metadata[key])

    labels_after = _sample_payload_sha256(enriched)
    if labels_after != labels_before:
        raise AssertionError("metadata enrichment changed reviewed samples")
    _write_json_atomic(output, enriched)
    return {
        "output": str(output),
        "matches": len(enriched["matches"]),
        "added_fields": added_fields,
        "sample_payload_sha256": labels_after,
    }


def _dhash(path: Path) -> int:
    """Return a small deterministic perceptual hash for near-frame auditing."""
    with Image.open(path) as image:
        gray = image.convert("L").resize((9, 8), Image.Resampling.BILINEAR)
        # ``tobytes`` avoids Pillow's deprecated ``getdata`` path and is
        # sufficient for the one-byte grayscale image.
        pixels = list(gray.tobytes())
    result = 0
    for row in range(8):
        for col in range(8):
            result = (result << 1) | int(pixels[row * 9 + col] > pixels[row * 9 + col + 1])
    return result


def _hamming(left: int, right: int) -> int:
    return (left ^ right).bit_count()


def _rect_intersects(a: list[float], b: list[float]) -> bool:
    return (a[0] < b[0] + b[2] and a[0] + a[2] > b[0] and
            a[1] < b[1] + b[3] and a[1] + a[3] > b[1])


def _candidate_features(row: dict[str, Any]) -> dict[str, Any]:
    boxes = _json(row["suggested_boxes"])
    directions = _json(row["directions"])
    if not isinstance(boxes, list) or not isinstance(directions, list):
        raise ValueError(f"invalid suggestion JSON at {row['match_id']}@{row['at_ms']}")
    if len(boxes) != len(directions):
        raise ValueError(f"suggestion/direction mismatch at {row['match_id']}@{row['at_ms']}")
    if any(direction not in {"left", "right"} for direction in directions):
        raise ValueError(f"unexpected edge direction at {row['match_id']}@{row['at_ms']}")

    left = sum(direction == "left" for direction in directions)
    right = sum(direction == "right" for direction in directions)
    categories = {
        "empty": not boxes,
        "left_only": bool(left) and not right,
        "right_only": bool(right) and not left,
        "both": bool(left) and bool(right),
    }
    widths = [float(box[2]) for box in boxes]
    heights = [float(box[3]) for box in boxes]
    centers = [(float(box[0]) + float(box[2]) / 2,
                float(box[1]) + float(box[3]) / 2) for box in boxes]
    # The detector intentionally scans these edge bands.  These masks are
    # audit descriptions, not filtering rules or truth labels.
    minimap = [0.0229166667, 0.0, 0.1895833333, 0.3883720930]
    ping = [0.4027777778, 0.0462962963, 0.2222222222, 0.1388888889]
    center = [0.25, 0.0, 0.5, 1.0]
    intersects_mask = sum(
        _rect_intersects([float(v) for v in box], mask)
        for box in boxes for mask in (minimap, ping, center)
    )
    extreme = sum(cx <= 0.05 or cx >= 0.95 for cx, _ in centers)
    top = sum(cy < 0.18 for _, cy in centers)
    bottom = sum(cy >= 0.80 for _, cy in centers)
    wide = sum(width >= 0.05 for width in widths)
    tiny_height = sum(height * 1080 <= 2.5 for height in heights)
    # This score only prioritises human attention. It must never be interpreted
    # as a probability that a box is correct.
    value_score = (
        0.35 * len(boxes) + 0.8 * int(categories["both"])
        + 0.45 * int(categories["left_only"])
        + 0.15 * int(categories["right_only"])
        + 0.35 * extreme + 0.20 * top + 0.25 * bottom
        + 0.25 * wide + 0.20 * tiny_height + 0.25 * intersects_mask
    )
    return {
        "machine_box_count": len(boxes),
        "machine_left_count": left,
        "machine_right_count": right,
        "machine_class": next(name for name, enabled in categories.items() if enabled),
        "box_width_min": min(widths) if widths else None,
        "box_width_max": max(widths) if widths else None,
        "box_height_min": min(heights) if heights else None,
        "box_height_max": max(heights) if heights else None,
        "extreme_edge_box_count": extreme,
        "top_ui_risk_box_count": top,
        "bottom_ui_risk_box_count": bottom,
        "wide_box_count": wide,
        "tiny_height_box_count": tiny_height,
        "excluded_mask_intersections": intersects_mask,
        "review_value_proxy": round(value_score, 6),
    }


def _load_rows(db_path: Path, queue_root: Path) -> list[dict[str, Any]]:
    connection = sqlite3.connect(db_path)
    connection.row_factory = sqlite3.Row
    try:
        rows = [dict(row) for row in connection.execute("SELECT * FROM tasks ORDER BY id")]
    finally:
        connection.close()
    for row in rows:
        if _sealed(row["match_id"]):
            raise ValueError(f"sealed match is present in source queue: {row['match_id']}")
        frame_path = queue_root / row["frame"]
        overlay_path = queue_root / row["overlay"]
        if not frame_path.is_file() or not overlay_path.is_file():
            raise FileNotFoundError(f"missing queue asset for {row['match_id']}@{row['at_ms']}")
        row["features"] = _candidate_features(row)
    return rows


def _score(row: dict[str, Any]) -> tuple[float, int]:
    return (float(row["features"]["review_value_proxy"]), -int(row["id"]))


def _spread_pick(rows: list[dict[str, Any]], count: int,
                 reserved: list[dict[str, Any]] | None = None,
                 temporal_guard_ms: int = 0) -> list[dict[str, Any]]:
    """Pick high proxy value rows across time bins, with an optional guard."""
    if count <= 0 or not rows:
        return []
    if len(rows) <= count:
        candidates = list(rows)
        if reserved and temporal_guard_ms:
            spaced = [row for row in candidates if all(
                abs(row["at_ms"] - other["at_ms"]) >= temporal_guard_ms
                for other in reserved
            )]
            if len(spaced) >= count:
                candidates = spaced
        return sorted(candidates, key=lambda row: row["at_ms"])[:count]
    ordered = sorted(rows, key=lambda row: row["at_ms"])
    reserved = reserved or []
    picked: list[dict[str, Any]] = []
    used: set[int] = set()
    for index in range(count):
        start = (index * len(ordered)) // count
        end = ((index + 1) * len(ordered)) // count
        candidates = [row for row in ordered[start:end]
                      if row["id"] not in used and all(
                          abs(row["at_ms"] - other["at_ms"]) >= temporal_guard_ms
                          for other in reserved + picked
                      )]
        if not candidates:
            candidates = [row for row in ordered
                          if row["id"] not in used and all(
                              abs(row["at_ms"] - other["at_ms"]) >= temporal_guard_ms
                              for other in reserved + picked
                          )]
        # If a category is too temporally concentrated, keep the requested
        # count and let the audit report the source spacing instead of silently
        # dropping a required left/right or empty stratum.
        if not candidates:
            candidates = [row for row in ordered if row["id"] not in used]
        chosen = max(candidates, key=_score)
        picked.append(chosen)
        used.add(chosen["id"])
    return sorted(picked, key=lambda row: row["at_ms"])


def _select_batch(rows: list[dict[str, Any]], per_match: int = 20) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    if per_match != 20:
        raise ValueError("the current policy is defined for exactly 20 rows per match")
    by_match: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        if row["review_status"] == "pending":
            by_match[row["match_id"]].append(row)

    selected: list[dict[str, Any]] = []
    policy = {"empty": 5, "left_only": 3, "both": 2, "right_only": 10}
    temporal_guard_ms = 20_000
    selected_ids: set[int] = set()
    for match_id in sorted(by_match):
        match_rows = by_match[match_id]
        chosen_for_match: list[dict[str, Any]] = []
        for category, count in policy.items():
            candidates = [row for row in match_rows
                          if row["features"]["machine_class"] == category]
            chosen = _spread_pick(
                candidates, count, reserved=chosen_for_match,
                temporal_guard_ms=temporal_guard_ms,
            )
            if len(chosen) < count:
                raise ValueError(
                    f"{match_id} has {len(chosen)} {category} rows; need {count}"
                )
            for row in chosen:
                if row["id"] in selected_ids:
                    raise AssertionError("category selection overlap")
                selected_ids.add(row["id"])
                row = dict(row)
                row["selection_reason"] = {
                    "empty": "machine_empty_negative_coverage",
                    "left_only": "left_edge_diversity",
                    "both": "left_right_cooccurrence",
                    "right_only": "right_edge_coverage",
                }[category]
                chosen_for_match.append(row)
        if len(chosen_for_match) != per_match:
            raise AssertionError(f"unexpected selection size for {match_id}")
        selected.extend(sorted(chosen_for_match, key=lambda row: row["at_ms"]))

    distribution = {
        "per_match": {},
        "selected_total": len(selected),
        "source_pending_total": sum(row["review_status"] == "pending" for row in rows),
        "source_protected_reviewed_total": sum(row["review_status"] != "pending" for row in rows),
        "policy_per_match": policy,
        "temporal_guard_ms": temporal_guard_ms,
    }
    for match_id in sorted(by_match):
        match_selected = [row for row in selected if row["match_id"] == match_id]
        distribution["per_match"][match_id] = {
            "selected": len(match_selected),
            "selection_reason_counts": dict(Counter(
                row["selection_reason"] for row in match_selected
            )),
            "at_ms": [row["at_ms"] for row in match_selected],
            "directions": dict(Counter(
                direction
                for row in match_selected
                for direction in _json(row["directions"])
            )),
        }
    return selected, distribution


def _audit(rows: list[dict[str, Any]], queue_root: Path) -> dict[str, Any]:
    by_match: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        by_match[row["match_id"]].append(row)
    all_boxes = [box for row in rows for box in _json(row["suggested_boxes"])]
    all_directions = [direction for row in rows for direction in _json(row["directions"])]
    dimensions: list[tuple[int, int]] = []
    image_hashes: dict[str, list[dict[str, Any]]] = defaultdict(list)
    dhashes: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        frame_path = queue_root / row["frame"]
        with Image.open(frame_path) as image:
            dimensions.append(image.size)
        image_hashes[_sha256(frame_path)].append({
            "match_id": row["match_id"], "at_ms": row["at_ms"], "frame": row["frame"]
        })
        dhashes[row["match_id"]].append({
            "id": row["id"], "at_ms": row["at_ms"], "frame": row["frame"],
            "hash": _dhash(frame_path),
        })

    near_pairs: list[dict[str, Any]] = []
    near_pair_counts: dict[str, int] = {}
    for match_id, hashed in dhashes.items():
        count = 0
        for left_index, left in enumerate(hashed):
            for right in hashed[left_index + 1:]:
                distance = _hamming(left["hash"], right["hash"])
                if distance <= 4:
                    count += 1
                    if len(near_pairs) < 200:
                        near_pairs.append({
                            "match_id": match_id,
                            "left_id": left["id"], "right_id": right["id"],
                            "left_at_ms": left["at_ms"], "right_at_ms": right["at_ms"],
                            "delta_ms": abs(left["at_ms"] - right["at_ms"]),
                            "dhash_hamming": distance,
                        })
        near_pair_counts[match_id] = count

    def percent(value: int, total: int) -> float:
        return round(100.0 * value / total, 3) if total else 0.0

    statuses = Counter(row["review_status"] for row in rows)
    frame_counts = Counter(len(_json(row["suggested_boxes"])) for row in rows)
    class_counts = Counter(row["features"]["machine_class"] for row in rows)
    direction_counts = Counter(all_directions)
    width_values = [float(box[2]) for box in all_boxes]
    height_values = [float(box[3]) for box in all_boxes]
    center_x = [float(box[0]) + float(box[2]) / 2 for box in all_boxes]
    center_y = [float(box[1]) + float(box[3]) / 2 for box in all_boxes]
    geometry = {
        "box_count": len(all_boxes),
        "width_norm": {
            "min": min(width_values) if width_values else None,
            "median": sorted(width_values)[len(width_values) // 2] if width_values else None,
            "max": max(width_values) if width_values else None,
        },
        "height_norm": {
            "min": min(height_values) if height_values else None,
            "median": sorted(height_values)[len(height_values) // 2] if height_values else None,
            "max": max(height_values) if height_values else None,
        },
        "center_x_norm": {
            "min": min(center_x) if center_x else None,
            "median": sorted(center_x)[len(center_x) // 2] if center_x else None,
            "max": max(center_x) if center_x else None,
        },
        "center_y_norm": {
            "min": min(center_y) if center_y else None,
            "median": sorted(center_y)[len(center_y) // 2] if center_y else None,
            "max": max(center_y) if center_y else None,
        },
        "edge_center_le_0.05_or_ge_0.95": sum(cx <= .05 or cx >= .95 for cx in center_x),
        "top_ui_risk_boxes": sum(cy < .18 for cy in center_y),
        "bottom_ui_risk_boxes": sum(cy >= .80 for cy in center_y),
        "wide_boxes_ge_0.05": sum(width >= .05 for width in width_values),
        "tiny_height_boxes_le_2.5px_at_1080": sum(height * 1080 <= 2.5 for height in height_values),
    }
    per_match = {}
    for match_id in sorted(by_match):
        match_rows = by_match[match_id]
        match_boxes = sum(len(_json(row["suggested_boxes"])) for row in match_rows)
        match_directions = Counter(
            direction for row in match_rows for direction in _json(row["directions"])
        )
        times = sorted(int(row["at_ms"]) for row in match_rows)
        gaps = [right - left for left, right in zip(times, times[1:])]
        per_match[match_id] = {
            "frames": len(match_rows),
            "pending": sum(row["review_status"] == "pending" for row in match_rows),
            "reviewed": sum(row["review_status"] != "pending" for row in match_rows),
            "machine_empty_frames": sum(not _json(row["suggested_boxes"]) for row in match_rows),
            "machine_positive_frames": sum(bool(_json(row["suggested_boxes"])) for row in match_rows),
            "suggested_boxes": match_boxes,
            "suggested_boxes_per_frame": round(match_boxes / len(match_rows), 4),
            "left_boxes": match_directions["left"],
            "right_boxes": match_directions["right"],
            "time_span_ms": [times[0], times[-1]],
            "sample_gap_ms": {
                "min": min(gaps) if gaps else None,
                "median": sorted(gaps)[len(gaps) // 2] if gaps else None,
                "max": max(gaps) if gaps else None,
                "le_12000": sum(gap <= 12000 for gap in gaps),
            },
            "machine_class_counts": dict(Counter(
                row["features"]["machine_class"] for row in match_rows
            )),
            "near_dhash_pairs_le4": near_pair_counts.get(match_id, 0),
        }

    reviewed_rows = [row for row in rows if row["review_status"] != "pending"]
    reviewed_proxy = {
        "reviewed_rows": len(reviewed_rows),
        "reviewed_status_counts": dict(statuses),
        "tiny_observed_agreement": (
            "3/3 reviewed rows are negative; two of those rows contain machine suggestions. "
            "This is a three-row audit proxy only; precision/recall are not estimable."
        ),
        "suggested_boxes_in_reviewed_rows": sum(
            len(_json(row["suggested_boxes"])) for row in reviewed_rows
        ),
    }
    exact_groups = [group for group in image_hashes.values() if len(group) > 1]
    return {
        "schema_version": 1,
        "kind": "main_edge_queue_audit",
        "source_queue": str(queue_root),
        "source_queue_sha256": _canonical_sha256([
            {key: row[key] for key in (
                "id", "match_id", "split", "at_ms", "selection", "frame",
                "overlay", "suggested_boxes", "suggested_categories",
                "directions", "review_status", "reviewed_boxes",
                "reviewed_categories",
            )}
            for row in rows
        ]),
        "sealed_match_policy": {
            "excluded_prefixes": list(SEALED_PREFIXES),
            "found_in_source": sorted({row["match_id"] for row in rows if _sealed(row["match_id"])}),
            "found_in_output": [],
        },
        "counts": {
            "rows": len(rows),
            "status": dict(statuses),
            "machine_empty_frames": class_counts["empty"],
            "machine_positive_frames": len(rows) - class_counts["empty"],
            "machine_empty_percent": percent(class_counts["empty"], len(rows)),
            "suggested_boxes": len(all_boxes),
            "suggested_boxes_per_frame": round(len(all_boxes) / len(rows), 4) if rows else 0,
            "frame_box_count_histogram": dict(sorted((str(k), v) for k, v in frame_counts.items())),
            "directions": dict(direction_counts),
            "machine_frame_class": dict(class_counts),
        },
        "geometry_proxy": geometry,
        "reviewed_proxy": reviewed_proxy,
        "per_match": per_match,
        "duplicate_proxy": {
            "exact_duplicate_groups": exact_groups,
            "exact_duplicate_group_count": len(exact_groups),
            "near_dhash_hamming_le4_pair_count": sum(near_pair_counts.values()),
            "near_dhash_pairs_sample": near_pairs,
            "near_hash_note": "dHash<=4 is a triage proxy, not proof of duplicate content; inspect before dropping a frame.",
        },
        "selection_note": (
            "Machine suggestions and machine-empty frames are sampling strata only. "
            "Every selected frame remains pending and requires full human redraw/add/delete review."
        ),
    }


def _copy_media(source: Path, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(source, target)


def _database_content_sha256(path: Path) -> str:
    with sqlite3.connect(path) as connection:
        connection.row_factory = sqlite3.Row
        rows = [dict(row) for row in connection.execute(
            "SELECT * FROM tasks ORDER BY id"
        )]
    return _canonical_sha256(rows)


def _write_contacts(output: Path, selected: list[dict[str, Any]]) -> None:
    contact_dir = output / "contact-sheets"
    tile_width, tile_height, caption_height, columns = 480, 216, 30, 4
    page_size = 16
    for page_index in range(0, len(selected), page_size):
        page_rows = selected[page_index:page_index + page_size]
        page = Image.new("RGB", (columns * tile_width,
                                  math.ceil(len(page_rows) / columns) * (tile_height + caption_height)),
                         "#111111")
        draw = ImageDraw.Draw(page)
        for index, row in enumerate(page_rows):
            with Image.open(output / row["batch_overlay"]) as image:
                image = image.convert("RGB").resize((tile_width, tile_height), Image.Resampling.BILINEAR)
            left = (index % columns) * tile_width
            top = (index // columns) * (tile_height + caption_height)
            page.paste(image, (left, top))
            draw.text((left + 4, top + tile_height + 5),
                      f"{row['match_id']} {row['at_ms']}ms {row['selection_reason']}",
                      fill="#ffffff")
        contact_dir.mkdir(parents=True, exist_ok=True)
        page.save(contact_dir / f"page-{page_index // page_size + 1:02d}.jpg", quality=92)


def _write_output(output: Path, selected: list[dict[str, Any]], audit: dict[str, Any],
                  selection: dict[str, Any], queue_root: Path,
                  metadata_by_match: dict[str, dict[str, Any]]) -> None:
    if output in {queue_root, queue_root.parent}:
        raise ValueError("Output directory must differ from the source queue")
    if output.exists():
        if not output.is_dir() or any(output.iterdir()):
            raise ValueError(f"Output directory is not empty: {output}")
    output.mkdir(parents=True, exist_ok=True)
    matches: dict[str, dict[str, Any]] = {}
    manifest = {
        "schema_version": 1,
        "kind": "main_enemy",
        "review_mode": "manual",
        "roi": [0, 0, 1, 1],
        "label_roi": [0, 0, 1, 1],
        "warning": (
            "Independent edge audit batch. Machine suggestions are not truth. "
            "Review every frame, delete false boxes, and add every missed visible enemy bar."
        ),
        "sampling": {
            "predictions_used_for_selection": True,
            "selection_is_prediction_independent": False,
            "source_queue": str(queue_root),
            "policy": selection["policy_per_match"],
        },
        "provenance": {
            "source_queue": str(queue_root),
            "source_queue_sha256": audit["source_queue_sha256"],
            "protected_reviewed_rows_omitted": audit["reviewed_proxy"]["reviewed_rows"],
            "sealed_matches_excluded": list(SEALED_PREFIXES),
        },
        "matches": [],
    }
    for task_id, source_row in enumerate(selected, start=1):
        match_id = source_row["match_id"]
        try:
            source_metadata = metadata_by_match[match_id]
        except KeyError as error:
            raise ValueError(
                f"source manifest has no metadata for selected match: {match_id}"
            ) from error
        target_frame = Path("frames") / source_row["split"] / match_id / Path(source_row["frame"]).name
        target_overlay = Path("overlays") / source_row["split"] / match_id / Path(source_row["overlay"]).name
        _copy_media(queue_root / source_row["frame"], output / target_frame)
        _copy_media(queue_root / source_row["overlay"], output / target_overlay)
        source_row["batch_id"] = task_id
        source_row["batch_frame"] = str(target_frame)
        source_row["batch_overlay"] = str(target_overlay)
        item = matches.setdefault(match_id, {
            "id": match_id,
            "split": source_row["split"],
            "video": source_metadata["video"],
            "video_sha256": source_metadata["video_sha256"],
            "orientation": copy.deepcopy(source_metadata["orientation"]),
            "samples": [],
        })
        item["samples"].append({
            "at_ms": source_row["at_ms"],
            "selection": "edge_audit_manual_pending",
            "selection_reason": source_row["selection_reason"],
            "suggested_boxes": _json(source_row["suggested_boxes"]),
            "suggested_categories": _json(source_row.get("suggested_categories", "[]")),
            "directions": _json(source_row["directions"]),
            "review_status": "pending",
            "reviewed_boxes": None,
            "reviewed_categories": None,
            "frame": str(target_frame),
            "overlay": str(target_overlay),
            "source_task_id": source_row["id"],
            "audit_features": source_row["features"],
        })
    manifest["matches"] = [matches[key] for key in sorted(matches)]
    manifest["matches"] = [
        dict(match, samples=sorted(match["samples"], key=lambda sample: sample["at_ms"]))
        for match in manifest["matches"]
    ]
    (output / "review-manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    store = AnnotationStore(output)
    stats = store.stats()
    if stats["counts"]["pending"] != len(selected) or stats["completed"] != 0:
        raise RuntimeError("New edge review batch did not initialize as all-pending")
    _write_contacts(output, selected)

    with (output / "selection.csv").open("w", newline="", encoding="utf-8") as stream:
        fields = ["batch_id", "source_task_id", "match_id", "split", "at_ms",
                  "selection_reason", "machine_class", "machine_box_count",
                  "machine_left_count", "machine_right_count", "frame", "overlay"]
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for row in selected:
            features = row["features"]
            writer.writerow({
                "batch_id": row["batch_id"], "source_task_id": row["id"],
                "match_id": row["match_id"], "split": row["split"],
                "at_ms": row["at_ms"], "selection_reason": row["selection_reason"],
                "machine_class": features["machine_class"],
                "machine_box_count": features["machine_box_count"],
                "machine_left_count": features["machine_left_count"],
                "machine_right_count": features["machine_right_count"],
                "frame": row["batch_frame"], "overlay": row["batch_overlay"],
            })

    audit = dict(audit)
    audit["sealed_match_policy"] = dict(audit["sealed_match_policy"], found_in_output=[])
    audit["output_batch"] = {
        "rows": len(selected),
        "all_pending": True,
        "manifest_sha256": _sha256(output / "review-manifest.json"),
        "database_content_sha256": _database_content_sha256(
            output / "annotations.sqlite3"
        ),
        "media_are_independent_copies": True,
    }
    (output / "audit.json").write_text(
        json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    (output / "selection.json").write_text(
        json.dumps(selection, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    summary = {
        "schema_version": 1,
        "kind": "main_edge_review_batch",
        "rows": len(selected),
        "matches": len(matches),
        "pending": len(selected),
        "suggested_boxes": sum(len(_json(row["suggested_boxes"])) for row in selected),
        "machine_empty_frames": sum(not _json(row["suggested_boxes"]) for row in selected),
        "source_protected_reviewed_rows": audit["reviewed_proxy"]["reviewed_rows"],
        "video9_video12_included": False,
        "warning": "All suggestions remain machine assistance and require human review.",
    }
    (output / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def run(source: Path, output: Path) -> dict[str, Any]:
    source = source.resolve()
    output = output.resolve()
    queue_root = (source / "queue").resolve()
    if output in {source, queue_root}:
        raise ValueError("Output directory must differ from the source queue")
    db_path = queue_root / "annotations.sqlite3"
    if not db_path.is_file():
        raise FileNotFoundError(db_path)
    source_manifest = queue_root / "review-manifest.json"
    metadata_by_match = _load_source_metadata(source_manifest)
    rows = _load_rows(db_path, queue_root)
    audit = _audit(rows, queue_root)
    selected, selection = _select_batch(rows)
    if any(_sealed(row["match_id"]) for row in selected):
        raise AssertionError("sealed match leaked into selected batch")
    _write_output(output, selected, audit, selection, queue_root, metadata_by_match)
    return {
        "audit": audit,
        "selection": selection,
        "output": str(output),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--enrich-existing", type=Path,
        help=("copy source video/video_sha256/orientation metadata into an "
              "existing review manifest; defaults to a sidecar output"),
    )
    parser.add_argument(
        "--source-manifest", type=Path,
        help="source queue manifest used by --enrich-existing",
    )
    parser.add_argument(
        "--in-place", action="store_true",
        help="with --enrich-existing, atomically replace that manifest",
    )
    parser.add_argument(
        "--source", type=Path,
        default=Path("data/private/main-edge-review-v1"),
        help="private main-edge-review-v1 directory",
    )
    parser.add_argument(
        "--output", type=Path,
        default=None,
        help=("new empty directory for the independent review batch, or an "
              "output manifest path with --enrich-existing"),
    )
    args = parser.parse_args()
    if args.enrich_existing is not None:
        if args.in_place and args.output is not None:
            parser.error("--in-place cannot be combined with --output")
        source_manifest = args.source_manifest
        if source_manifest is None:
            source_manifest = (
                args.enrich_existing.resolve().parent.parent
                / "queue" / "review-manifest.json"
            )
        target = args.enrich_existing if args.in_place else args.output
        if target is None:
            target = args.enrich_existing.with_name("review-manifest.enriched.json")
        result = enrich_review_manifest(
            args.enrich_existing, source_manifest, target,
        )
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return
    if args.in_place or args.source_manifest is not None:
        parser.error("--in-place and --source-manifest require --enrich-existing")
    output = args.output or Path("data/private/main-edge-review-v1/review-batch-v1")
    result = run(args.source.resolve(), output.resolve())
    print(json.dumps({
        "output": result["output"],
        "source_rows": result["audit"]["counts"]["rows"],
        "source_suggested_boxes": result["audit"]["counts"]["suggested_boxes"],
        "source_machine_empty_frames": result["audit"]["counts"]["machine_empty_frames"],
        "source_protected_reviewed_rows": result["audit"]["reviewed_proxy"]["reviewed_rows"],
        "selected_rows": result["selection"]["selected_total"],
        "selected_matches": len(result["selection"]["per_match"]),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
