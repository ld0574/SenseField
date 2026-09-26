"""Generate synthetic pixels to exercise capture, detection, and cue timing.

This is a pipeline fixture, never a claim of accuracy in 王者荣耀.
"""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw


WIDTH = 320
HEIGHT = 180
FPS = 12
FRAMES = 84


def _templates(output: Path) -> tuple[Image.Image, Image.Image]:
    enemy = Image.new("RGBA", (12, 12), (0, 0, 0, 0))
    draw = ImageDraw.Draw(enemy)
    draw.ellipse((1, 1, 10, 10), fill=(224, 28, 34, 255))
    draw.ellipse((4, 4, 7, 7), fill=(255, 245, 245, 255))
    enemy.save(output / "enemy.png")

    ping = Image.new("RGBA", (14, 14), (0, 0, 0, 0))
    draw = ImageDraw.Draw(ping)
    draw.polygon([(7, 1), (13, 12), (1, 12)], fill=(245, 190, 20, 255))
    draw.rectangle((6, 5, 7, 9), fill=(80, 35, 0, 255))
    ping.save(output / "danger.png")
    return enemy, ping


def create(output: Path) -> dict[str, Path]:
    output.mkdir(parents=True, exist_ok=True)
    enemy, ping = _templates(output)
    profile = {
        "schema_version": 1,
        "name": "synthetic_fixture",
        "profile_version": "1.0.0-synthetic",
        "game": "synthetic_only",
        "verified": False,
        "rois": {
            "minimap": [0.0, 0.0, 0.25, 0.34],
            "ping_area": [0.75, 0.0, 0.25, 0.34],
            "center_mask": [0.35, 0.35, 0.30, 0.30],
        },
        "detectors": {
            "main_red_bar": True,
            "minimap_template": True,
            "minimap_red_ring": False,
            "danger_ping_template": True,
        },
        "templates": {
            "minimap_enemy": "enemy.png",
            "danger_ping": "danger.png",
        },
        "thresholds": {
            "red_min": 155,
            "red_dominance": 1.45,
            "main_min_width_ratio": 0.06,
            "main_max_height_ratio": 0.05,
            "main_min_aspect": 3.5,
            "template_match": 0.88,
        },
        "events": {
            "min_confidence": 0.75,
            "max_observation_age_ms": 250,
            "min_global_gap_ms": 1000,
            "minimap_min_gap_ms": 5000,
            "min_hits_in_three_frames": 2,
            "reset_after_missing_frames": 3,
        },
    }
    profile_path = output / "profile.json"
    profile_path.write_text(json.dumps(profile, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    annotations = {
        "schema_version": 1,
        "video_id": "synthetic_fixture",
        "events": [
            {"kind": "main_enemy", "start_ms": 1000, "end_ms": 2083, "direction": "left"},
            {"kind": "minimap_enemy", "start_ms": 2500, "end_ms": 3583, "direction": "right"},
            {"kind": "danger_ping", "start_ms": 4000, "end_ms": 5083, "direction": None},
        ],
    }
    labels_path = output / "labels.json"
    labels_path.write_text(json.dumps(annotations, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    video_path = output / "fixture.mkv"
    command = [
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-f", "rawvideo", "-pixel_format", "rgba", "-video_size", f"{WIDTH}x{HEIGHT}",
        "-framerate", str(FPS), "-i", "pipe:0", "-c:v", "ffv1",
        "-pix_fmt", "bgr0", str(video_path),
    ]
    process = subprocess.Popen(command, stdin=subprocess.PIPE, stderr=subprocess.PIPE)
    assert process.stdin is not None
    try:
        for index in range(FRAMES):
            frame = Image.new("RGBA", (WIDTH, HEIGHT), (13, 19, 29, 255))
            draw = ImageDraw.Draw(frame)
            draw.rectangle((0, 0, 79, 60), fill=(32, 40, 52, 255))
            for y in (7, 22, 37, 52):
                for x in (7, 27, 47, 67):
                    draw.rectangle((x, y, x + 2, y + 2), fill=(20, 120, 135, 255))
            draw.rectangle((67, 8, 70, 17), fill=(180, 35, 50, 255))
            draw.rectangle((72, 35, 75, 44), fill=(180, 35, 50, 255))
            draw.rectangle((240, 0, 319, 60), fill=(25, 32, 43, 255))
            if 12 <= index <= 24 or index == 70:
                draw.rectangle((15, 85, 58, 90), fill=(220, 24, 26, 255))
            if 30 <= index <= 42:
                frame.alpha_composite(enemy, (52, 25))
            if 48 <= index <= 60:
                frame.alpha_composite(ping, (270, 20))
            process.stdin.write(frame.tobytes())
    finally:
        process.stdin.close()
        stderr = process.stderr.read().decode(errors="replace") if process.stderr else ""
        code = process.wait()
        if code:
            raise RuntimeError(f"ffmpeg could not write synthetic fixture: {stderr[-2000:]}")
    return {"video": video_path, "profile": profile_path, "labels": labels_path}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=Path("build/synthetic"))
    args = parser.parse_args()
    for key, path in create(args.output_dir).items():
        print(f"{key}: {path}")


if __name__ == "__main__":
    main()
