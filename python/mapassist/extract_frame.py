"""Extract a local recording frame for ROI inspection and template cropping."""

from __future__ import annotations

import argparse
import subprocess
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw

from .native import Pipeline, read_profile
from .orientation import VALID_ROTATIONS


def extract(video: Path, at_ms: int, output: Path,
            display_rotation: int | None = None) -> tuple[int, int]:
    """Extract a frame, optionally honoring the recording's display matrix.

    The historical default keeps ``-noautorotate`` for callers that consume
    coded pixels. Dataset manifests pass the declared display rotation so
    annotations and exported images use the same display coordinate system.
    """
    if at_ms < 0:
        raise ValueError("at_ms must be nonnegative")
    if (display_rotation is not None and
            (not isinstance(display_rotation, int) or isinstance(display_rotation, bool) or
             display_rotation not in VALID_ROTATIONS)):
        raise ValueError("display_rotation must be 0, 90, 180, or 270")
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(dir=output.parent, suffix=output.suffix,
                                     delete=False) as file:
        temporary = Path(file.name)
    try:
        # Apply the declared transform ourselves instead of relying on ffmpeg's
        # autorotate flag.  This keeps the manifest authoritative even when a
        # container loses its Display Matrix metadata during a copy.
        command = [
            "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
            "-noautorotate",
        ]
        # ffmpeg otherwise copies the source Display Matrix into still images.
        # That leaves physically rotated pixels carrying a second EXIF rotation,
        # so browsers and Pillow's exif_transpose() rotate the frame twice.
        # The manifest-provided rotation is authoritative; clear the input
        # display metadata before applying the explicit pixel transform below.
        if display_rotation is not None:
            command += ["-display_rotation:v:0", "0"]
        command += ["-ss", f"{at_ms / 1000:.3f}", "-i", str(video)]
        filters = {
            90: "transpose=cclock",
            180: "hflip,vflip",
            270: "transpose=clock",
        }
        if display_rotation in filters:
            command += ["-vf", filters[display_rotation]]
        command += ["-frames:v", "1", str(temporary)]
        subprocess.run(command, check=True)
        if temporary.stat().st_size == 0:
            raise ValueError("No frame at that timestamp")
        with Image.open(temporary) as image:
            size = image.size
            image.verify()
        temporary.replace(output)
        return size
    finally:
        temporary.unlink(missing_ok=True)


def draw_areas(frame: Path, profile: Path, output: Path) -> None:
    _, config = read_profile(profile)
    colors = {"minimap": "#33aaff", "ping_area": "#ffcc22", "center_mask": "#ff5577"}
    with Image.open(frame) as source:
        image = source.convert("RGB")
    draw = ImageDraw.Draw(image)
    for name, color in colors.items():
        x, y, w, h = config["rois"][name]
        box = (round(x * image.width), round(y * image.height),
               round((x + w) * image.width), round((y + h) * image.height))
        draw.rectangle(box, outline=color, width=3)
        draw.text((box[0] + 4, box[1] + 4), name, fill=color)
    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output)


def draw_detections(frame: Path, profile: Path, output: Path, at_ms: int) -> int:
    with Image.open(frame) as source:
        rgba = source.convert("RGBA")
    with Pipeline(profile) as pipeline:
        observations, _ = pipeline.step(rgba.tobytes(), rgba.width, rgba.height, at_ms)
    image = rgba.convert("RGB")
    draw = ImageDraw.Draw(image)
    colors = {"main_enemy": "#ff4d67", "minimap_enemy": "#43ff77",
              "danger_ping": "#ffd43b"}
    for item in observations:
        x, y, w, h = item["bbox_norm"]
        box = (round(x * image.width), round(y * image.height),
               round((x + w) * image.width), round((y + h) * image.height))
        color = colors[item["type"]]
        draw.rectangle(box, outline=color, width=2)
        direction = item["direction"] or "center"
        draw.text((box[0], max(0, box[1] - 11)),
                  f"{item['type']} {direction} {item['confidence']:.2f}", fill=color)
    output.parent.mkdir(parents=True, exist_ok=True)
    image.save(output)
    return len(observations)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("video", type=Path)
    parser.add_argument("--at-ms", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--profile", type=Path, help="GameProfile used to draw ROI boundaries")
    parser.add_argument("--overlay-output", type=Path, help="Separate ROI visualization PNG")
    parser.add_argument("--detections-output", type=Path,
                        help="Separate PNG with detector observations and confidence")
    args = parser.parse_args()
    if (args.overlay_output or args.detections_output) and not args.profile:
        parser.error("--profile is required for visualization outputs")
    if args.profile and not (args.overlay_output or args.detections_output):
        parser.error("--profile needs --overlay-output or --detections-output")
    try:
        width, height = extract(args.video, args.at_ms, args.output)
        if args.overlay_output:
            draw_areas(args.output, args.profile, args.overlay_output)
        detection_count = (draw_detections(args.output, args.profile, args.detections_output,
                                           args.at_ms)
                           if args.detections_output else None)
    except (OSError, ValueError, KeyError, TypeError,
            subprocess.CalledProcessError) as error:
        parser.error(str(error))
    print(f"{args.output}: {width}x{height}")
    if detection_count is not None:
        print(f"{args.detections_output}: {detection_count} observations")


if __name__ == "__main__":
    main()
