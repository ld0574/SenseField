"""Embed locally calibrated PNG templates in a profile for Android import."""

from __future__ import annotations

import argparse
import base64
import json
import os
from pathlib import Path

from PIL import Image

from .native import read_profile


TEMPLATE_KEYS = ("minimap_enemy", "danger_ping")


def bundle(profile_path: Path, output_path: Path) -> dict:
    _, profile = read_profile(profile_path)
    bundled = dict(profile)
    encoded: dict[str, str] = {}
    for key in TEMPLATE_KEYS:
        relative = profile.get("templates", {}).get(key)
        if not relative:
            continue
        template_path = profile_path.parent / relative
        raw = template_path.read_bytes()
        with Image.open(template_path) as image:
            if image.format != "PNG" or image.width < 1 or image.height < 1:
                raise ValueError(f"{key} must be a PNG image")
            if image.width > 256 or image.height > 256:
                raise ValueError(f"{key} exceeds the Android 256-pixel template limit")
        encoded[key] = base64.b64encode(raw).decode("ascii")
    detectors = profile["detectors"]
    if detectors["minimap_template"] and "minimap_enemy" not in encoded:
        raise ValueError("Enabled minimap detector requires a minimap_enemy PNG")
    if detectors["danger_ping_template"] and "danger_ping" not in encoded:
        raise ValueError("Enabled ping detector requires a danger_ping PNG")
    bundled["templates_b64"] = encoded
    payload = (json.dumps(bundled, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if len(payload) > 2_000_000:
        raise ValueError("Bundled profile exceeds the Android 2 MB import limit")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = output_path.with_name(output_path.name + ".tmp")
    temporary.write_bytes(payload)
    os.replace(temporary, output_path)
    return {"output": str(output_path), "embedded_templates": sorted(encoded),
            "verified": bool(profile.get("verified", False))}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("profile", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(bundle(args.profile, args.output), ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
