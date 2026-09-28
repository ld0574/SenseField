"""Build a private, split-safe frame queue for manual detector review.

Suggested boxes are copied from replay output and are deliberately marked
pending. They are not ground truth until a reviewer accepts or corrects them.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw

from .extract_frame import extract
from .orientation import from_manifest, resolve, rotation
from .roi_safety import normalized_roi


SPLITS = ("train", "val", "test")


def _evenly(items: list[dict], count: int) -> list[dict]:
    if count <= 0 or not items:
        return []
    if len(items) <= count:
        return items
    if count == 1:
        return [items[len(items) // 2]]
    indices = [round(index * (len(items) - 1) / (count - 1)) for index in range(count)]
    return [items[index] for index in indices]


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _active_intervals(value: object, match_id: str) -> list[tuple[int, int]] | None:
    if value is None:
        return None
    if not isinstance(value, list) or not value:
        raise ValueError(f"active_intervals_ms for {match_id} must be a nonempty list")
    intervals = []
    for item in value:
        if (not isinstance(item, list) or len(item) != 2 or
                any(not isinstance(number, int) or isinstance(number, bool) for number in item)):
            raise ValueError(f"active_intervals_ms for {match_id} needs [start_ms, end_ms] pairs")
        start, end = item
        if start < 0 or end <= start:
            raise ValueError(f"Invalid active interval for {match_id}: {item}")
        if intervals and start <= intervals[-1][1]:
            raise ValueError(f"Active intervals for {match_id} must be sorted and non-overlapping")
        intervals.append((start, end))
    return intervals


def _prediction_frames(path: Path, kind: str,
                       intervals: list[tuple[int, int]] | None = None) -> tuple[list[dict], list[dict]]:
    positives = []
    empty = []
    cue_times = []
    records = []
    with path.open(encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, start=1):
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"Invalid JSONL at {path}:{line_number}") from error
            timestamp = record.get("timestamp_ms")
            if not isinstance(timestamp, int) or isinstance(timestamp, bool) or timestamp < 0:
                raise ValueError(f"Invalid timestamp_ms at {path}:{line_number}")
            if intervals is not None and not any(start <= timestamp <= end
                                                 for start, end in intervals):
                continue
            observations = [item for item in record.get("observations", [])
                            if item.get("type") == kind]
            cues = [item for item in record.get("cues", []) if item.get("kind") == kind]
            candidate = {"at_ms": timestamp, "suggested_boxes": [
                item["bbox_norm"] for item in observations
            ], "directions": [item.get("direction") for item in observations]}
            records.append((candidate, bool(observations), bool(cues)))
            if cues:
                positives.append(candidate)
                cue_times.append(timestamp)

    # One candidate per second is enough before stratified sampling. Keep it at
    # least two seconds away from cues so the negative queue is less ambiguous.
    last_empty_ms = -1000
    for candidate, has_observation, _ in records:
        if has_observation or candidate["at_ms"] - last_empty_ms < 1000:
            continue
        if any(abs(candidate["at_ms"] - cue_time) < 2000 for cue_time in cue_times):
            continue
        empty.append(candidate)
        last_empty_ms = candidate["at_ms"]
    return positives, empty


def _draw_overlay(source: Path, output: Path, sample: dict) -> None:
    with Image.open(source) as opened:
        image = opened.convert("RGB")
    draw = ImageDraw.Draw(image)
    for box, direction in zip(sample["suggested_boxes"], sample["directions"]):
        x, y, width, height = box
        pixels = (round(x * image.width), round(y * image.height),
                  round((x + width) * image.width), round((y + height) * image.height))
        draw.rectangle(pixels, outline="#43ff77", width=max(2, image.width // 360))
        draw.text((pixels[0], max(0, pixels[1] - 12)), direction or "center", fill="#43ff77")
    draw.rectangle((0, image.height - 18, 230, image.height), fill="#000000")
    draw.text((4, image.height - 15),
              f"{sample['selection']} {sample['at_ms'] / 1000:.3f}s", fill="#ffffff")
    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output)


def _contact_sheet(images: list[Path], output: Path, columns: int = 4) -> None:
    if not images:
        return
    thumb_width = 400
    with Image.open(images[0]) as first:
        thumb_height = round(thumb_width * first.height / first.width)
    rows = math.ceil(len(images) / columns)
    sheet = Image.new("RGB", (columns * thumb_width, rows * thumb_height), "#101010")
    for index, path in enumerate(images):
        with Image.open(path) as opened:
            thumb = opened.convert("RGB")
            thumb.thumbnail((thumb_width, thumb_height))
        sheet.paste(thumb, ((index % columns) * thumb_width,
                            (index // columns) * thumb_height))
    output.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(output, quality=90)


def _minimap_contact_sheet(images: list[Path], samples: list[dict], roi: list[float],
                           output: Path, columns: int = 5) -> None:
    if not images:
        return
    tile_width = 240
    crop_height = 240
    caption_height = 20
    rows = math.ceil(len(images) / columns)
    sheet = Image.new("RGB", (columns * tile_width,
                              rows * (crop_height + caption_height)), "#101010")
    for index, (path, sample) in enumerate(zip(images, samples)):
        with Image.open(path) as opened:
            image = opened.convert("RGB")
        x, y, width, height = roi
        crop = image.crop((round(x * image.width), round(y * image.height),
                           round((x + width) * image.width),
                           round((y + height) * image.height)))
        crop = crop.resize((tile_width, crop_height), Image.Resampling.NEAREST)
        left = (index % columns) * tile_width
        top = (index // columns) * (crop_height + caption_height)
        sheet.paste(crop, (left, top))
        draw = ImageDraw.Draw(sheet)
        draw.text((left + 4, top + crop_height + 3),
                  f"{sample['selection']} {sample['at_ms'] / 1000:.3f}s", fill="#ffffff")
    output.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(output, quality=92)


def build(manifest: Path, output: Path, positives_per_match: int = 20,
          negatives_per_match: int = 10) -> dict:
    data = json.loads(manifest.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise ValueError("Expected review manifest schema_version 1")
    kind = data.get("kind", "minimap_enemy")
    roi = data.get("roi")
    if roi is not None and (not isinstance(roi, list) or len(roi) != 4 or
                            any(not isinstance(value, (int, float)) for value in roi)):
        raise ValueError("roi must be normalized [x, y, width, height]")
    widget_roi = data.get("widget_roi")
    if widget_roi is not None:
        widget_roi = normalized_roi(widget_roi, "widget_roi")
    label_roi = data.get("label_roi")
    if label_roi is not None:
        label_roi = normalized_roi(label_roi, "label_roi")
    matches = data.get("matches")
    if not isinstance(matches, list) or not matches:
        raise ValueError("Review manifest needs matches")

    seen_ids = set()
    top_orientation = from_manifest(data)
    video_splits: dict[Path, str] = {}
    prepared = []
    for match in matches:
        match_id = match.get("id")
        split = match.get("split")
        if not isinstance(match_id, str) or not match_id or match_id in seen_ids:
            raise ValueError(f"Invalid or duplicate match id: {match_id}")
        if split not in SPLITS:
            raise ValueError(f"Invalid split for {match_id}: {split}")
        seen_ids.add(match_id)
        video = (manifest.parent / match["video"]).resolve()
        predictions = (manifest.parent / match["predictions"]).resolve()
        if not video.is_file() or not predictions.is_file():
            raise ValueError(f"Missing video or predictions for {match_id}")
        prior = video_splits.setdefault(video, split)
        if prior != split:
            raise ValueError(f"One recording cannot cross splits: {video}")
        match_roi = match.get("roi", roi)
        if (match_roi is not None and
                (not isinstance(match_roi, list) or len(match_roi) != 4 or
                 any(not isinstance(value, (int, float)) or isinstance(value, bool)
                     for value in match_roi))):
            raise ValueError(f"roi for {match_id} must be normalized [x, y, width, height]")
        match_widget_roi = match.get("widget_roi", widget_roi)
        if match_widget_roi is not None:
            match_widget_roi = normalized_roi(
                match_widget_roi, f"widget_roi for {match_id}")
        match_label_roi = match.get("label_roi", label_roi)
        if match_label_roi is not None:
            match_label_roi = normalized_roi(
                match_label_roi, f"label_roi for {match_id}")
        intervals = _active_intervals(match.get("active_intervals_ms"), match_id)
        match_orientation = resolve(
            video, from_manifest(match, f"{match_id}") or top_orientation)
        prepared.append((match_id, split, video, predictions, intervals, match_roi,
                         "roi" in match, match_orientation, match_widget_roi,
                         match_label_roi, "label_roi" in match))

    output.mkdir(parents=True, exist_ok=True)
    exported = {"schema_version": 1, "kind": kind, "roi": roi,
                "warning": "Suggested boxes are unreviewed and are not ground truth.",
                "matches": []}
    if widget_roi is not None:
        exported["widget_roi"] = widget_roi
    if label_roi is not None:
        exported["label_roi"] = label_roi
    if top_orientation is not None:
        exported["orientation"] = top_orientation
    summary = {split: {"matches": 0, "cue_samples": 0, "background_samples": 0}
               for split in SPLITS}
    for (match_id, split, video, predictions, intervals, match_roi,
         has_roi_override, match_orientation, match_widget_roi,
         match_label_roi, has_label_override) in prepared:
        positive, negative = _prediction_frames(predictions, kind, intervals)
        selected = [("cue", item) for item in _evenly(positive, positives_per_match)]
        selected += [("background", item) for item in _evenly(negative, negatives_per_match)]
        samples = []
        overlays = []
        for selection, item in sorted(selected, key=lambda pair: pair[1]["at_ms"]):
            timestamp = item["at_ms"]
            stem = f"{match_id}_{timestamp:09d}"
            frame = output / split / match_id / f"{stem}.png"
            overlay = output / split / match_id / f"{stem}-overlay.jpg"
            extract(video, timestamp, frame,
                    display_rotation=rotation(match_orientation))
            sample = {"at_ms": timestamp, "selection": selection,
                      "suggested_boxes": item["suggested_boxes"],
                      "directions": item["directions"], "review_status": "pending",
                      "reviewed_boxes": None,
                      "frame": str(frame.relative_to(output)),
                      "overlay": str(overlay.relative_to(output))}
            _draw_overlay(frame, overlay, sample)
            samples.append(sample)
            overlays.append(overlay)
            key = "cue_samples" if selection == "cue" else "background_samples"
            summary[split][key] += 1
        _contact_sheet(overlays, output / "contact-sheets" / f"{match_id}.jpg")
        if match_roi is not None:
            _minimap_contact_sheet(overlays, samples, match_roi,
                                   output / "contact-sheets" /
                                   f"{match_id}-minimap.jpg")
        summary[split]["matches"] += 1
        exported_match = {"id": match_id, "split": split,
                          "video": str(video), "video_sha256": _sha256(video),
                          "orientation": match_orientation,
                          "predictions": str(predictions), "samples": samples}
        if has_roi_override:
            exported_match["roi"] = match_roi
        if match_widget_roi is not None:
            exported_match["widget_roi"] = match_widget_roi
        if has_label_override and match_label_roi is not None:
            exported_match["label_roi"] = match_label_roi
        if intervals is not None:
            exported_match["active_intervals_ms"] = [list(item) for item in intervals]
        exported["matches"].append(exported_match)
    (output / "review-manifest.json").write_text(
        json.dumps(exported, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (output / "summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return summary


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--positive-per-match", type=int, default=20)
    parser.add_argument("--negative-per-match", type=int, default=10)
    args = parser.parse_args()
    try:
        result = build(args.manifest, args.output, args.positive_per_match,
                       args.negative_per_match)
        print(json.dumps(result, ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError, json.JSONDecodeError) as error:
        print(f"error: {error}", file=sys.stderr)
        raise SystemExit(2) from error


if __name__ == "__main__":
    main()
