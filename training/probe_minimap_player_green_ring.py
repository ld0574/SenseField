#!/usr/bin/env python3
"""Create a read-only green-ring sampling sheet for approved HOK recordings.

This is an exploratory review aid, not a player detector. It reads only the
explicitly allowlisted source videos below, applies their recorded display
rotation and documented minimap widget crop, then emits crop sheets and a
simple HSV annulus proposal score. It never opens video9 or video12.
"""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))
from mapassist.extract_frame import extract  # noqa: E402


# ROI conventions follow validation/VIDEO_INTAKE_2026-09-28.md and the
# video13-specific [x, y, width, height] declaration in validation/VIDEO13.md.
# All other entries here are widget bounds [left, top, right, bottom).
SOURCES: dict[str, dict[str, object]] = {
    "video1hd": {"path": "video/video1hd.mp4", "rotation": 90,
                 "roi": (126, 0, 464, 334), "times_ms": (120_000, 600_000)},
    "video2hd": {"path": "video/video2hd.mp4", "rotation": 90,
                 "roi": (120, 0, 465, 347), "times_ms": (180_000, 800_000)},
    "video3hd": {"path": "video/video3hd.mp4", "rotation": 90,
                 "roi": (94, 0, 466, 352), "times_ms": (300_000, 1_200_000)},
    "video4hd": {"path": "video/video4hd.mp4", "rotation": 90,
                 "roi": (120, 0, 465, 347), "times_ms": (180_000, 800_000)},
    "video5hd": {"path": "video/video5hd.mp4", "rotation": 90,
                 "roi": (120, 0, 465, 347), "times_ms": (180_000, 750_000)},
    "video7": {"path": "video/video7.mp4", "rotation": 0,
               "roi": (143, 0, 524, 378), "times_ms": (180_000, 780_000)},
    "video8": {"path": "video/video8.mp4", "rotation": 90,
               "roi": (106, 0, 454, 344), "times_ms": (180_000, 720_000)},
    "video10": {"path": "video/video10.mp4", "rotation": 0,
                "roi": (96, 0, 372, 277), "times_ms": (180_000, 780_000)},
    "video11": {"path": "video/video11.mp4", "rotation": 0,
                "roi": (96, 0, 372, 277), "times_ms": (120_000, 800_000)},
    # video13 ROI is [x, y, width, height], unlike the bounds above.
    "video13": {"path": "video/video13.mp4", "rotation": 0,
                "roi": (80, 70, 310, 280), "roi_xywh": True,
                "times_ms": (180_000, 1_200_000)},
}


def widget_crop(source: Image.Image, spec: dict[str, object]) -> Image.Image:
    x0, y0, third, fourth = spec["roi"]  # type: ignore[misc]
    if spec.get("roi_xywh"):
        box = (x0, y0, x0 + third, y0 + fourth)
    else:
        box = (x0, y0, third, fourth)
    return source.crop(box)


def green_annulus_proposals(image: Image.Image) -> list[dict[str, float | int]]:
    """Rank small circles by green coverage on a 11-20px annulus.

    HSV limits and score are intentionally exposed only as weak review
    proposals. They have not passed a precision/recall evaluation.
    """
    hsv = cv2.cvtColor(np.asarray(image.convert("RGB")), cv2.COLOR_RGB2HSV)
    # OpenCV hue units are 0..179. This broad green mask accommodates modest
    # compression shifts; foliage and effects are expected hard negatives.
    mask = cv2.inRange(hsv, np.array((45, 80, 70), dtype=np.uint8),
                       np.array((80, 255, 255), dtype=np.uint8)).astype(np.float32) / 255.0
    radius_scores: list[np.ndarray] = []
    radii = list(range(11, 21))
    for radius in radii:
        extent = radius + 2
        yy, xx = np.ogrid[-extent:extent + 1, -extent:extent + 1]
        distance = np.sqrt(xx * xx + yy * yy)
        kernel = ((distance >= radius - 1.5) &
                  (distance <= radius + 1.5)).astype(np.float32)
        score = cv2.filter2D(mask, cv2.CV_32F, kernel,
                             borderType=cv2.BORDER_CONSTANT) / kernel.sum()
        radius_scores.append(score)

    stack = np.stack(radius_scores, axis=0)
    scores = np.max(stack, axis=0)
    best_radii = np.asarray(radii, dtype=np.int16)[np.argmax(stack, axis=0)]
    peaks = cv2.dilate(scores, np.ones((15, 15), dtype=np.uint8))
    ys, xs = np.where((scores >= 0.04) & (scores >= peaks - 1e-7))
    ordered = sorted(((float(scores[y, x]), int(x), int(y), int(best_radii[y, x]))
                      for y, x in zip(ys, xs)), reverse=True)
    selected: list[dict[str, float | int]] = []
    for score, x, y, radius in ordered:
        if all((x - int(item["x"])) ** 2 + (y - int(item["y"])) ** 2 >= 24 ** 2
               for item in selected):
            selected.append({"score": round(score, 4), "x": x, "y": y,
                             "radius_px": radius})
        if len(selected) == 5:
            break
    return selected


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--video", action="append", choices=[*SOURCES, "all"],
                        help="Allowlisted recording id; repeat to select several (default: all).")
    parser.add_argument("--out-dir", type=Path,
                        default=ROOT / "build/minimap-player-green-ring-probe",
                        help="Output directory for widget crops, sheets, and proposal JSON.")
    args = parser.parse_args()

    requested = args.video or ["all"]
    selected = list(SOURCES) if "all" in requested else list(dict.fromkeys(requested))
    args.out_dir.mkdir(parents=True, exist_ok=True)
    proposal_report: dict[str, object] = {
        "purpose": "exploratory green-annulus proposals; not a validated detector",
        "hsv_opencv": {"hue": [45, 80], "saturation_min": 80, "value_min": 70},
        "radii_px": [11, 20],
        "sources": {},
    }

    for video_id in selected:
        spec = SOURCES[video_id]
        video_path = ROOT / str(spec["path"])
        if not video_path.is_file():
            raise FileNotFoundError(video_path)
        video_out = args.out_dir / video_id
        video_out.mkdir(parents=True, exist_ok=True)
        crops: list[tuple[int, Image.Image]] = []
        records: list[dict[str, object]] = []
        with tempfile.TemporaryDirectory(prefix="minimap-player-probe-") as temp_dir:
            for at_ms in spec["times_ms"]:  # type: ignore[union-attr]
                frame_path = Path(temp_dir) / f"{video_id}-{at_ms}.png"
                extract(video_path, int(at_ms), frame_path,
                        display_rotation=int(spec["rotation"]))
                with Image.open(frame_path) as frame:
                    crop = widget_crop(frame.convert("RGB"), spec)
                crop_path = video_out / f"{video_id}-{int(at_ms):09d}.png"
                crop.save(crop_path)
                crops.append((int(at_ms), crop.copy()))
                records.append({"at_ms": int(at_ms), "widget_crop": crop_path.name,
                                "green_annulus_proposals": green_annulus_proposals(crop)})

        cell_w, cell_h = 380, 390
        sheet = Image.new("RGB", (cell_w * 2, cell_h), (24, 24, 24))
        draw = ImageDraw.Draw(sheet)
        for index, (at_ms, crop) in enumerate(crops):
            x = index * cell_w
            draw.text((x + 5, 5), f"{video_id} {at_ms / 1000:.1f}s", fill="white")
            crop.thumbnail((cell_w, cell_h - 28))
            sheet.paste(crop, (x, 26))
        sheet.save(video_out / "contact.jpg", quality=94)
        proposal_report["sources"][video_id] = records  # type: ignore[index]

    report_path = args.out_dir / "green_annulus_proposals.json"
    report_path.write_text(json.dumps(proposal_report, indent=2) + "\n", encoding="utf-8")
    print(f"sampled {len(selected)} allowlisted videos; outputs: {args.out_dir}")
    print(f"proposal summary: {report_path}")


if __name__ == "__main__":
    main()
