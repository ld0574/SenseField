"""Re-run the current native detector on reviewed development frames."""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
import tempfile
from pathlib import Path

from PIL import Image

from .detection_evaluate import evaluate_review
from .native import Pipeline, Rect, default_library_path


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _roi(value: object, label: str) -> list[float]:
    if (not isinstance(value, list) or len(value) != 4 or
            any(not isinstance(item, (int, float)) or isinstance(item, bool)
                for item in value)):
        raise ValueError(f"{label} must be normalized [x, y, width, height]")
    result = [float(item) for item in value]
    x, y, width, height = result
    if (x < 0 or y < 0 or width <= 0 or height <= 0 or
            x + width > 1.000001 or y + height > 1.000001):
        raise ValueError(f"{label} is outside the normalized frame")
    return result


def evaluate_current_detector(review_manifest: Path, profile: Path,
                              library: Path | None = None) -> dict:
    review_manifest = review_manifest.resolve()
    profile = profile.resolve()
    library = (library.resolve() if library is not None else
               default_library_path().resolve())
    data = json.loads(review_manifest.read_text(encoding="utf-8"))
    if data.get("schema_version") != 1:
        raise ValueError("Expected review manifest schema_version 1")
    default_roi = _roi(data.get("roi"), "review roi")
    matches = data.get("matches")
    if not isinstance(matches, list) or not matches:
        raise ValueError("Review manifest needs matches")

    replayed = copy.deepcopy(data)
    frame_root = review_manifest.parent
    detected_frames = 0
    with Pipeline(profile, library) as pipeline:
        for match in replayed["matches"]:
            match_id = match.get("id")
            roi = _roi(match.get("roi", default_roi), f"{match_id} roi")
            pipeline.profile.minimap = Rect(*roi)
            for sample in match.get("samples", []):
                status = sample.get("review_status", "pending")
                if status in {"pending", "skip", "excluded"}:
                    continue
                if status == "accepted":
                    # Accepted boxes are human-confirmed truth from the original
                    # detector. Preserve them before replacing its suggestions.
                    sample["review_status"] = "corrected"
                    sample["reviewed_boxes"] = copy.deepcopy(
                        sample.get("suggested_boxes", []))
                frame_value = sample.get("frame")
                if not isinstance(frame_value, str) or not frame_value:
                    raise ValueError(f"{match_id}@{sample.get('at_ms')} has no frame")
                frame = (frame_root / frame_value).resolve()
                if frame_root not in frame.parents or not frame.is_file():
                    raise ValueError(f"Invalid review frame: {frame_value}")
                with Image.open(frame) as source:
                    image = source.convert("RGBA")
                observations, _ = pipeline.step(
                    image.tobytes(), image.width, image.height, int(sample["at_ms"]))
                minimap = [item for item in observations
                           if item["type"] == "minimap_enemy"]
                sample["suggested_boxes"] = [item["bbox_norm"] for item in minimap]
                sample["directions"] = [item["direction"] for item in minimap]
                detected_frames += 1

    with tempfile.NamedTemporaryFile(mode="w", suffix=".json", encoding="utf-8",
                                     delete=False) as temporary:
        temporary.write(json.dumps(replayed, ensure_ascii=False))
        temporary_path = Path(temporary.name)
    try:
        report = evaluate_review(temporary_path)
    finally:
        temporary_path.unlink(missing_ok=True)

    report["prediction_source"] = {
        "mode": "current_native_on_review_frames",
        "detected_frames": detected_frames,
        "profile": {"path": str(profile), "sha256": _sha256(profile)},
        "native_library": {"path": str(library), "sha256": _sha256(library)},
    }
    report["warning"] = (
        "The current detector was re-run on manually reviewed development frames. "
        "These matches were used during development, so this is a regression diagnostic, "
        "not an independent holdout result."
    )
    return report


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Re-run the current native detector on reviewed development frames."
    )
    parser.add_argument("review_manifest", type=Path)
    parser.add_argument("--profile", type=Path, required=True)
    parser.add_argument("--library", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = evaluate_current_detector(
        args.review_manifest, args.profile, args.library)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
