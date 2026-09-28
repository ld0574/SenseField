"""Build and conservatively validate player death/alive UI signatures."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import math
from dataclasses import dataclass
from pathlib import Path

from PIL import Image


@dataclass(frozen=True)
class Features:
    dhash: int
    luma: bytes
    chroma: bytes
    crop_sha256: str


def _sample(image: Image.Image, roi: tuple[float, float, float, float],
            gx: int, gy: int, grid_w: int, grid_h: int) -> tuple[int, int, int, int]:
    x0, y0, x1, y1 = _bounds(image, roi)
    x = max(x0, min(x1 - 1, x0 + (2 * gx + 1) * (x1 - x0) // (2 * grid_w)))
    y = max(y0, min(y1 - 1, y0 + (2 * gy + 1) * (y1 - y0) // (2 * grid_h)))
    return image.getpixel((x, y))


def _bounds(image: Image.Image, roi: tuple[float, float, float, float]) -> tuple[int, int, int, int]:
    width, height = image.size
    x0 = max(0, min(width - 1, int(roi[0] * width)))
    y0 = max(0, min(height - 1, int(roi[1] * height)))
    x1 = max(x0 + 1, min(width, math.ceil((roi[0] + roi[2]) * width)))
    y1 = max(y0 + 1, min(height, math.ceil((roi[1] + roi[3]) * height)))
    return x0, y0, x1, y1


def _luma(pixel: tuple[int, int, int, int]) -> int:
    return (77 * pixel[0] + 150 * pixel[1] + 29 * pixel[2]) >> 8


def extract_features(path: Path, roi: tuple[float, float, float, float]) -> Features:
    with Image.open(path) as source:
        image = source.convert("RGBA")
    raw_luma = [
        _luma(_sample(image, roi, x, y, 8, 8))
        for y in range(8) for x in range(8)
    ]
    mean = sum(raw_luma) // 64
    normalized = bytes(max(0, min(255, value - mean + 128)) for value in raw_luma)
    dhash = 0
    for y in range(8):
        for x in range(8):
            if _luma(_sample(image, roi, x, y, 9, 8)) < \
                    _luma(_sample(image, roi, x + 1, y, 9, 8)):
                dhash |= 1 << (y * 8 + x)
    chroma = bytearray()
    for y in range(4):
        for x in range(4):
            r, g, b, _ = _sample(image, roi, x, y, 4, 4)
            chroma.append(max(0, min(255, 128 + ((-43 * r - 85 * g + 128 * b) >> 8))))
            chroma.append(max(0, min(255, 128 + ((128 * r - 107 * g - 21 * b) >> 8))))
    crop_digest = hashlib.sha256(image.crop(_bounds(image, roi)).tobytes()).hexdigest()
    return Features(dhash, normalized, bytes(chroma), crop_digest)


def _distance(left: Features, right: Features) -> tuple[int, float, float, float]:
    hamming = (left.dhash ^ right.dhash).bit_count()
    luma = sum(abs(a - b) for a, b in zip(left.luma, right.luma)) / (64 * 255)
    chroma = sum(abs(a - b) for a, b in zip(left.chroma, right.chroma)) / (32 * 255)
    return hamming, luma, chroma, (hamming / 64 + luma + chroma) / 3


def _classify(query: Features, references: list[tuple[str, Features]],
              max_hash: int, max_luma: float, max_chroma: float,
              margin: float) -> str | None:
    costs = {"dead": float("inf"), "alive": float("inf")}
    accepted = {"dead": False, "alive": False}
    for label, reference in references:
        hamming, luma, chroma, cost = _distance(query, reference)
        accepted_reference = (
            hamming <= max_hash and luma <= max_luma and chroma <= max_chroma
        )
        if not accepted_reference:
            continue
        costs[label] = min(costs[label], cost)
        accepted[label] = True
    if accepted["dead"] and costs["dead"] + margin <= costs["alive"]:
        return "dead"
    if accepted["alive"] and costs["alive"] + margin <= costs["dead"]:
        return "alive"
    return None


def _metrics(samples: list[tuple[str, Features]], thresholds: tuple[int, float, float, float]) -> dict:
    true_positive = false_positive = false_negative = 0
    for index, (truth, query) in enumerate(samples):
        prediction = _classify(query, samples[:index] + samples[index + 1:], *thresholds)
        if prediction == truth:
            true_positive += 1
        else:
            false_negative += 1
            if prediction is not None:
                false_positive += 1
    precision = true_positive / max(1, true_positive + false_positive)
    recall = true_positive / max(1, true_positive + false_negative)
    return {"tp": true_positive, "fp": false_positive, "fn": false_negative,
            "precision": precision, "recall": recall}


def select_thresholds(samples: list[tuple[str, Features]]) -> tuple[tuple[int, float, float, float], dict]:
    candidates: list[tuple[tuple[int, float, float, float], dict]] = []
    for max_hash in range(2, 26, 2):
        for luma_step in range(4, 29, 4):
            for chroma_step in range(4, 29, 4):
                for margin_step in range(0, 19, 3):
                    thresholds = (max_hash, luma_step / 100, chroma_step / 100,
                                  margin_step / 100)
                    metrics = _metrics(samples, thresholds)
                    if metrics["precision"] >= 0.95 and metrics["recall"] >= 0.90:
                        candidates.append((thresholds, metrics))
    if not candidates:
        raise ValueError("development signatures do not meet precision>=0.95 and recall>=0.90")
    candidates.sort(key=lambda item: (
        item[1]["fp"], -item[1]["recall"], item[0][0], item[0][1], item[0][2],
        -item[0][3],
    ))
    return candidates[0]


def calibrate(dead: list[Path], alive: list[Path], roi: tuple[float, float, float, float]) -> dict:
    if len(dead) < 3 or len(alive) < 3:
        raise ValueError("at least three dead and three alive images are required")
    if len(dead) + len(alive) > 64:
        raise ValueError("at most 64 total signatures are supported by the runtime")
    try:
        valid_roi = (len(roi) == 4 and all(
            not isinstance(value, bool) and isinstance(value, (int, float)) and
            math.isfinite(value) and 0 <= value <= 1
            for value in roi
        ) and roi[2] > 0 and roi[3] > 0 and
            roi[0] + roi[2] <= 1 and roi[1] + roi[3] <= 1)
    except (OverflowError, TypeError, ValueError):
        valid_roi = False
    if not valid_roi:
        raise ValueError("ROI must be a normalized rectangle inside the frame")
    samples = [("dead", extract_features(path, roi)) for path in dead]
    samples += [("alive", extract_features(path, roi)) for path in alive]
    crop_hashes = [features.crop_sha256 for _, features in samples]
    for label in ("dead", "alive"):
        class_hashes = [features.crop_sha256 for sample_label, features in samples
                        if sample_label == label]
        if len(set(class_hashes)) < 3:
            raise ValueError(f"at least three unique {label} signatures are required")
    if len(set(crop_hashes)) != len(crop_hashes):
        raise ValueError("dead and alive signatures must have unique crop_sha256 values")
    thresholds, metrics = select_thresholds(samples)

    def encoded(label: str) -> list[dict]:
        return [{
            "dhash64": f"{features.dhash:016x}",
            "luma8x8_b64": base64.b64encode(features.luma).decode("ascii"),
            "chroma4x4_b64": base64.b64encode(features.chroma).decode("ascii"),
            "crop_sha256": features.crop_sha256,
        } for sample_label, features in samples if sample_label == label]

    return {
        "schema": "mapassist.player_life_signatures",
        "schema_version": 1,
        "enabled": True,
        "roi": list(roi),
        "thresholds": {
            "max_dhash_distance": thresholds[0],
            "max_luma_mae": thresholds[1],
            "max_chroma_mae": thresholds[2],
            "min_state_margin": thresholds[3],
        },
        "development_leave_one_out": metrics,
        "dead": encoded("dead"),
        "alive": encoded("alive"),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dead", type=Path, nargs="+", required=True)
    parser.add_argument("--alive", type=Path, nargs="+", required=True)
    parser.add_argument("--roi", type=float, nargs=4, required=True, metavar=("X", "Y", "W", "H"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = calibrate(args.dead, args.alive, tuple(args.roi))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                               encoding="utf-8")
    except (OSError, ValueError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
