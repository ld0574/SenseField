"""Build an Android emulator demo from synthetic pixels only.

The profile matches a 2400x1080 landscape AOSP emulator whose Gallery player
renders the 320x180 video at 6x scale with a 304-pixel left margin. It must
never be treated as a 王者荣耀 profile or an accuracy benchmark.
"""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path

from PIL import Image

from .bundle_profile import bundle
from .synthetic import create


SCREEN_WIDTH = 2400
SCREEN_HEIGHT = 1080
VIDEO_LEFT = 304
VIDEO_SCALE = 6


def _rect(x: float, y: float, width: float, height: float) -> list[float]:
    return [x / SCREEN_WIDTH, y / SCREEN_HEIGHT,
            width / SCREEN_WIDTH, height / SCREEN_HEIGHT]


def build(output: Path) -> dict[str, Path]:
    fixture = create(output)
    video = output / "fixture-android.mp4"
    loop = output / "fixture-android-loop.mp4"
    subprocess.run([
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-i", str(fixture["video"]), "-c:v", "libx264", "-preset", "fast",
        "-crf", "12", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
        str(video),
    ], check=True)
    subprocess.run([
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-stream_loop", "8", "-i", str(video), "-c", "copy",
        "-movflags", "+faststart", str(loop),
    ], check=True)

    templates = {}
    for key, source, output_name in (
        ("minimap_enemy", "enemy.png", "enemy-screen.png"),
        ("danger_ping", "danger.png", "danger-screen.png"),
    ):
        with Image.open(output / source) as original:
            enlarged = original.convert("RGBA").resize(
                (original.width * VIDEO_SCALE, original.height * VIDEO_SCALE),
                Image.Resampling.BILINEAR,
            )
        enlarged.save(output / output_name)
        templates[key] = output_name

    profile = json.loads(fixture["profile"].read_text(encoding="utf-8"))
    profile["name"] = "synthetic_screen_emulator"
    profile["profile_version"] = "1.0.0-emulator-screen"
    profile["notes"] = "Synthetic-only 2400x1080 AOSP Gallery demo; never use as a game profile"
    profile["rois"] = {
        "minimap": _rect(VIDEO_LEFT, 0, 80 * VIDEO_SCALE, 60 * VIDEO_SCALE),
        "ping_area": _rect(VIDEO_LEFT + 240 * VIDEO_SCALE, 0,
                           80 * VIDEO_SCALE, 60 * VIDEO_SCALE),
        "center_mask": _rect(VIDEO_LEFT + 112 * VIDEO_SCALE, 63 * VIDEO_SCALE,
                             96 * VIDEO_SCALE, 54 * VIDEO_SCALE),
    }
    profile["templates"] = templates
    screen_profile = output / "screen-profile.json"
    screen_profile.write_text(json.dumps(profile, ensure_ascii=False, indent=2) + "\n",
                              encoding="utf-8")
    android_profile = output / "android-screen-profile.json"
    bundle(screen_profile, android_profile)

    ring_profile = json.loads(json.dumps(profile))
    ring_profile["name"] = "synthetic_red_ring_emulator"
    ring_profile["profile_version"] = "1.0.0-emulator-red-ring"
    ring_profile["detectors"]["minimap_template"] = False
    ring_profile["detectors"]["minimap_red_ring"] = True
    ring_profile["templates"]["minimap_enemy"] = None
    ring_profile["thresholds"]["red_min"] = 90
    ring_profile["thresholds"]["red_dominance"] = 1.25
    ring_screen_profile = output / "screen-ring-profile.json"
    ring_screen_profile.write_text(
        json.dumps(ring_profile, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    android_ring_profile = output / "android-ring-profile.json"
    bundle(ring_screen_profile, android_ring_profile)
    return {"video": loop, "desktop_profile": screen_profile,
            "android_profile": android_profile,
            "android_ring_profile": android_ring_profile}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, default=Path("build/synthetic_android"))
    args = parser.parse_args()
    for key, path in build(args.output_dir).items():
        print(f"{key}: {path}")


if __name__ == "__main__":
    main()
