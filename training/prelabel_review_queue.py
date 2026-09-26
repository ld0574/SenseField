"""Generate low-threshold YOLOX suggestions for a manual review queue."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path


def _decode(raw, input_size: int):
    import numpy as np

    grids = []
    strides = []
    for stride in (8, 16, 32):
        side = input_size // stride
        y, x = np.meshgrid(np.arange(side), np.arange(side), indexing="ij")
        grid = np.stack((x, y), axis=2).reshape(-1, 2)
        grids.append(grid)
        strides.append(np.full((side * side, 1), stride))
    grid = np.concatenate(grids, axis=0).astype(raw.dtype)
    expanded_stride = np.concatenate(strides, axis=0).astype(raw.dtype)
    decoded = raw.copy()[0]
    decoded[:, :2] = (decoded[:, :2] + grid) * expanded_stride
    decoded[:, 2:4] = np.exp(decoded[:, 2:4]) * expanded_stride
    return decoded


def _nms(boxes, scores, threshold: float):
    import numpy as np

    if not len(boxes):
        return np.empty((0,), dtype=np.int64)
    areas = np.maximum(0, boxes[:, 2] - boxes[:, 0]) * np.maximum(
        0, boxes[:, 3] - boxes[:, 1]
    )
    order = scores.argsort()[::-1]
    keep = []
    while order.size:
        current = int(order[0])
        keep.append(current)
        if order.size == 1:
            break
        remaining = order[1:]
        x0 = np.maximum(boxes[current, 0], boxes[remaining, 0])
        y0 = np.maximum(boxes[current, 1], boxes[remaining, 1])
        x1 = np.minimum(boxes[current, 2], boxes[remaining, 2])
        y1 = np.minimum(boxes[current, 3], boxes[remaining, 3])
        intersection = np.maximum(0, x1 - x0) * np.maximum(0, y1 - y0)
        union = areas[current] + areas[remaining] - intersection
        overlap = np.divide(
            intersection, union, out=np.zeros_like(intersection), where=union > 0
        )
        order = remaining[overlap <= threshold]
    return np.asarray(keep, dtype=np.int64)


def _roi(value: object) -> list[float]:
    if not isinstance(value, list) or len(value) != 4:
        raise ValueError("roi must contain four values")
    result = [float(item) for item in value]
    x, y, width, height = result
    if x < 0 or y < 0 or width <= 0 or height <= 0 or x + width > 1 or y + height > 1:
        raise ValueError(f"invalid roi: {value}")
    return result


def _normalized_box(roi: list[float], crop_x: int, crop_y: int,
                    full_width: int, full_height: int,
                    x0: float, y0: float, x1: float, y1: float) -> list[float] | None:
    """Map a crop-pixel box to the frame and clip floor/ceil spill to the ROI."""
    left = max(roi[0], (crop_x + x0) / full_width)
    top = max(roi[1], (crop_y + y0) / full_height)
    right = min(roi[0] + roi[2], (crop_x + x1) / full_width)
    bottom = min(roi[1] + roi[3], (crop_y + y1) / full_height)
    if right <= left or bottom <= top:
        return None
    return [left, top, right - left, bottom - top]


def _page(images: list, captions: list[str], output: Path, columns: int = 4) -> None:
    from PIL import Image, ImageDraw

    tile_width = 360
    image_height = 360
    caption_height = 34
    rows = math.ceil(len(images) / columns)
    page = Image.new("RGB", (columns * tile_width, rows * (image_height + caption_height)),
                     "#111111")
    draw = ImageDraw.Draw(page)
    for index, (image, caption) in enumerate(zip(images, captions)):
        image = image.resize((tile_width, image_height), Image.Resampling.NEAREST)
        left = index % columns * tile_width
        top = index // columns * (image_height + caption_height)
        page.paste(image, (left, top))
        draw.text((left + 4, top + image_height + 4), caption, fill="#ffffff")
    output.parent.mkdir(parents=True, exist_ok=True)
    page.save(output, quality=94)


def build(manifest: Path, model: Path, output: Path, input_size: int = 320,
          confidence: float = 0.03, nms_threshold: float = 0.5) -> dict:
    import cv2
    import numpy as np
    import onnxruntime as ort
    from PIL import Image, ImageDraw

    data = json.loads(manifest.read_text(encoding="utf-8"))
    default_roi = _roi(data["roi"])
    session = ort.InferenceSession(str(model.resolve()), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    result = {
        "schema_version": 1,
        "model": str(model.resolve()),
        "input_size": input_size,
        "confidence": confidence,
        "nms_threshold": nms_threshold,
        "warning": "Low-threshold suggestions are not ground truth; inspect every image.",
        "matches": [],
    }
    total = 0
    for match in data["matches"]:
        roi = _roi(match.get("roi", default_roi))
        predictions = []
        page_images = []
        page_captions = []
        page_number = 1
        for sample in match["samples"]:
            frame_path = manifest.parent / sample["frame"]
            full = cv2.imread(str(frame_path))
            if full is None:
                raise FileNotFoundError(frame_path)
            full_height, full_width = full.shape[:2]
            crop_x = math.floor(roi[0] * full_width)
            crop_y = math.floor(roi[1] * full_height)
            crop_right = math.ceil((roi[0] + roi[2]) * full_width)
            crop_bottom = math.ceil((roi[1] + roi[3]) * full_height)
            crop = full[crop_y:crop_bottom, crop_x:crop_right]
            crop_height, crop_width = crop.shape[:2]
            ratio = min(input_size / crop_height, input_size / crop_width)
            resized = cv2.resize(
                crop, (round(crop_width * ratio), round(crop_height * ratio)),
                interpolation=cv2.INTER_LINEAR,
            )
            canvas = np.full((input_size, input_size, 3), 114, dtype=np.uint8)
            canvas[:resized.shape[0], :resized.shape[1]] = resized
            batch = canvas.transpose(2, 0, 1)[None].astype(np.float32)
            decoded = _decode(session.run(None, {input_name: batch})[0], input_size)
            scores = decoded[:, 4] * decoded[:, 5]
            selected = scores >= confidence
            decoded = decoded[selected]
            scores = scores[selected]
            boxes = np.empty((len(decoded), 4), dtype=np.float32)
            boxes[:, 0] = decoded[:, 0] - decoded[:, 2] / 2
            boxes[:, 1] = decoded[:, 1] - decoded[:, 3] / 2
            boxes[:, 2] = decoded[:, 0] + decoded[:, 2] / 2
            boxes[:, 3] = decoded[:, 1] + decoded[:, 3] / 2
            keep = _nms(boxes, scores, nms_threshold)
            found = []
            overlay = Image.fromarray(cv2.cvtColor(crop, cv2.COLOR_BGR2RGB))
            draw = ImageDraw.Draw(overlay)
            for index in keep:
                x0, y0, x1, y1 = boxes[index] / ratio
                x0 = float(np.clip(x0, 0, crop_width))
                y0 = float(np.clip(y0, 0, crop_height))
                x1 = float(np.clip(x1, 0, crop_width))
                y1 = float(np.clip(y1, 0, crop_height))
                if x1 <= x0 or y1 <= y0:
                    continue
                # floor/ceil makes the pixel crop fractionally larger than the
                # normalized ROI at its right and bottom edges.  Clamp the
                # exported coordinates back to the declared ROI so an edge
                # suggestion remains a valid editable annotation.
                normalized = _normalized_box(
                    roi, crop_x, crop_y, full_width, full_height, x0, y0, x1, y1
                )
                if normalized is None:
                    continue
                score = float(scores[index])
                found.append({"bbox": [round(value, 8) for value in normalized],
                              "confidence": round(score, 6)})
                draw.rectangle((x0, y0, x1, y1), outline="#39ff70", width=1)
                draw.text((x0, max(0, y0 - 9)), f"{score:.2f}", fill="#39ff70")
            predictions.append({"at_ms": sample["at_ms"], "frame": sample["frame"],
                                "suggestions": found})
            total += len(found)
            page_images.append(overlay)
            page_captions.append(
                f"{sample['at_ms'] / 1000:.3f}s  candidates={len(found)}"
            )
            if len(page_images) == 20:
                _page(page_images, page_captions,
                      output / "contact-sheets" /
                      f"{match['id']}-page-{page_number:02d}.jpg")
                page_images = []
                page_captions = []
                page_number += 1
        if page_images:
            _page(page_images, page_captions,
                  output / "contact-sheets" / f"{match['id']}-page-{page_number:02d}.jpg")
        result["matches"].append({"id": match["id"], "roi": roi,
                                  "samples": predictions})
    output.mkdir(parents=True, exist_ok=True)
    (output / "prelabels.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return {"matches": len(result["matches"]),
            "images": sum(len(match["samples"]) for match in result["matches"]),
            "suggestions": total, "output": str(output / "prelabels.json")}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--input-size", type=int, default=320)
    parser.add_argument("--confidence", type=float, default=0.03)
    parser.add_argument("--nms-threshold", type=float, default=0.5)
    args = parser.parse_args()
    summary = build(args.manifest, args.model, args.output, args.input_size,
                    args.confidence, args.nms_threshold)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
