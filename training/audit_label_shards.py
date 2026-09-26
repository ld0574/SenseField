"""Compare manual label shards with low-threshold model suggestions for review."""

from __future__ import annotations

import argparse
import json
import math
from collections import Counter
from pathlib import Path


def _iou(left: list[float], right: list[float]) -> float:
    lx, ly, lw, lh = left
    rx, ry, rw, rh = right
    x0, y0 = max(lx, rx), max(ly, ry)
    x1, y1 = min(lx + lw, rx + rw), min(ly + lh, ry + rh)
    intersection = max(0.0, x1 - x0) * max(0.0, y1 - y0)
    union = lw * lh + rw * rh - intersection
    return intersection / union if union > 0 else 0.0


def _page(items: list[tuple], output: Path, columns: int = 4) -> None:
    from PIL import Image, ImageDraw

    tile_width, image_height, caption_height = 360, 360, 56
    rows = math.ceil(len(items) / columns)
    page = Image.new("RGB", (columns * tile_width, rows * (image_height + caption_height)),
                     "#101010")
    draw = ImageDraw.Draw(page)
    for index, (image, caption) in enumerate(items):
        image = image.resize((tile_width, image_height), Image.Resampling.NEAREST)
        left = index % columns * tile_width
        top = index // columns * (image_height + caption_height)
        page.paste(image, (left, top))
        draw.multiline_text((left + 3, top + image_height + 3), caption,
                            fill="#ffffff", spacing=2)
    output.parent.mkdir(parents=True, exist_ok=True)
    page.save(output, quality=94)


def audit(manifest: Path, prelabels: Path, shards: list[Path], output: Path,
          include_all: bool = False) -> dict:
    from PIL import Image, ImageDraw

    queue = json.loads(manifest.read_text(encoding="utf-8"))
    predictions = json.loads(prelabels.read_text(encoding="utf-8"))
    predicted = {
        (match["id"], sample["at_ms"]): sample.get("suggestions", [])
        for match in predictions["matches"] for sample in match["samples"]
    }
    labels = {}
    for shard in shards:
        data = json.loads(shard.read_text(encoding="utf-8"))
        for match in data["matches"]:
            for sample in match["samples"]:
                key = (match["id"], sample["at_ms"])
                if key in labels:
                    raise ValueError(f"duplicate label: {key}")
                labels[key] = sample
    default_roi = queue["roi"]
    records = []
    flag_counts: Counter[str] = Counter()
    status_counts: Counter[str] = Counter()
    contact_items: dict[str, list[tuple]] = {}
    for match in queue["matches"]:
        roi = match.get("roi", default_roi)
        rx, ry, rw, rh = roi
        for index, source in enumerate(match["samples"]):
            key = (match["id"], source["at_ms"])
            if key not in labels:
                continue
            label = labels[key]
            status = label["review_status"]
            status_counts[status] += 1
            truth = label.get("reviewed_boxes") or []
            candidates = predicted.get(key, [])
            flags = []
            if status == "skip":
                flags.append("skip")
            if status == "negative" and any(item["confidence"] >= 0.5
                                             for item in candidates):
                flags.append("negative_high_conf_candidate")
            if status == "corrected":
                for box in truth:
                    if not any(_iou(box, item["bbox"]) >= 0.15 for item in candidates):
                        flags.append("truth_without_candidate")
                        break
                for item in candidates:
                    if (item["confidence"] >= 0.5 and
                            not any(_iou(box, item["bbox"]) >= 0.15 for box in truth)):
                        flags.append("unmatched_high_conf_candidate")
                        break
                if len(truth) > 5:
                    flags.append("more_than_five_targets")
                for x, y, width, height in truth:
                    if (width / rw < 0.035 or width / rw > 0.38 or
                            height / rh < 0.035 or height / rh > 0.38):
                        flags.append("box_size_outlier")
                        break
            flags = sorted(set(flags))
            for flag in flags:
                flag_counts[flag] += 1
            # Include every flagged frame plus five deterministic control frames per match.
            # Full sheets are useful before accepting a large independently produced shard.
            control = index in {9, 29, 49, 69, 89}
            if include_all or flags or control:
                with Image.open(manifest.parent / source["frame"]) as opened:
                    full = opened.convert("RGB")
                left = math.floor(rx * full.width)
                top = math.floor(ry * full.height)
                right = math.ceil((rx + rw) * full.width)
                bottom = math.ceil((ry + rh) * full.height)
                crop = full.crop((left, top, right, bottom))
                draw = ImageDraw.Draw(crop)
                for box in truth:
                    x, y, width, height = box
                    coords = ((x * full.width - left), (y * full.height - top),
                              ((x + width) * full.width - left),
                              ((y + height) * full.height - top))
                    draw.rectangle(coords, outline="#ff4050", width=2)
                for item in candidates:
                    x, y, width, height = item["bbox"]
                    coords = ((x * full.width - left), (y * full.height - top),
                              ((x + width) * full.width - left),
                              ((y + height) * full.height - top))
                    draw.rectangle(coords, outline="#37ff72", width=1)
                    draw.text((coords[0], max(0, coords[1] - 9)),
                              f"{item['confidence']:.2f}", fill="#37ff72")
                reason = ",".join(flags) if flags else ("control" if control else "all")
                caption = (f"{source['at_ms'] / 1000:.3f}s {status}\n"
                           f"red=truth green=model {reason}")
                contact_items.setdefault(match["id"], []).append((crop, caption))
            records.append({"match": match["id"], "at_ms": source["at_ms"],
                            "status": status, "truth_boxes": len(truth),
                            "suggestions": len(candidates), "flags": flags})
    for match_id, items in contact_items.items():
        for page_index in range(0, len(items), 20):
            _page(items[page_index:page_index + 20], output / "contact-sheets" /
                  f"{match_id}-audit-{page_index // 20 + 1:02d}.jpg")
    report = {"schema_version": 1, "labels": len(records),
              "statuses": dict(sorted(status_counts.items())),
              "flag_counts": dict(sorted(flag_counts.items())), "records": records}
    output.mkdir(parents=True, exist_ok=True)
    (output / "audit.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return {key: report[key] for key in ("labels", "statuses", "flag_counts")}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--prelabels", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--include-all", action="store_true",
        help="Render every labeled frame, in addition to flagged and control frames",
    )
    parser.add_argument("shards", type=Path, nargs="+")
    args = parser.parse_args()
    print(json.dumps(audit(args.manifest, args.prelabels, args.shards, args.output,
                           include_all=args.include_all),
                     ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
