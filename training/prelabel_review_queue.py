"""Generate low-threshold YOLOX suggestions for a manual review queue."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

from mapassist.roi_safety import (
    DEFAULT_ROI_EDGE_TOLERANCE_PX,
    inspect_box_roi,
)

try:  # Works both as ``python training/...`` and package imports in tests.
    from .yolox_decode import (
        DEFAULT_CLASSES,
        candidates_from_raw,
        class_aware_nms,
        classes_from_metadata,
        confidence_from_metadata,
        confidence_thresholds,
        decode_yolox,
        normalize_classes,
        resolve_classes,
    )
except ImportError:  # pragma: no cover - exercised by the command-line path.
    from yolox_decode import (  # type: ignore
        DEFAULT_CLASSES,
        candidates_from_raw,
        class_aware_nms,
        classes_from_metadata,
        confidence_from_metadata,
        confidence_thresholds,
        decode_yolox,
        normalize_classes,
        resolve_classes,
    )


def _decode(raw, input_size: int, classes=DEFAULT_CLASSES):
    """Decode raw YOLOX rows while retaining all category scores."""
    import numpy as np

    class_names = normalize_classes(classes)
    decoded = decode_yolox(raw, input_size, np=np)
    return decoded[0] if decoded.ndim == 3 else decoded


def _nms(boxes, scores, threshold: float, class_ids=None):
    import numpy as np

    if class_ids is None:
        class_ids = np.zeros(len(boxes), dtype=np.int64)
    return class_aware_nms(boxes, scores, class_ids, threshold, np=np)


def _roi(value: object) -> list[float]:
    if (not isinstance(value, list) or len(value) != 4 or
            any(not isinstance(item, (int, float)) or isinstance(item, bool)
                for item in value)):
        raise ValueError("roi must contain four numeric values")
    try:
        result = [float(item) for item in value]
    except (OverflowError, TypeError, ValueError) as error:
        raise ValueError("roi must contain four finite numbers") from error
    if not all(math.isfinite(item) for item in result):
        raise ValueError("roi must contain four finite numbers")
    x, y, width, height = result
    if x < 0 or y < 0 or width <= 0 or height <= 0 or x + width > 1 or y + height > 1:
        raise ValueError(f"invalid roi: {value}")
    return result


def _label_roi(data: dict, match: dict) -> tuple[list[float] | None, str | None]:
    """Resolve the effective review label ROI using the manifest fallback."""
    if match.get("label_roi") is not None:
        return _roi(match["label_roi"]), "match.label_roi"
    if data.get("label_roi") is not None:
        return _roi(data["label_roi"]), "manifest.label_roi"
    return None, None


def _validate_match_id(match_id: object) -> str:
    if not isinstance(match_id, str) or not match_id:
        raise ValueError("review match needs a non-empty id")
    normalized_id = match_id.casefold().replace("_", "-")
    if normalized_id.startswith(("video9", "video12")):
        raise ValueError(f"sealed match {match_id!r} is not allowed for prelabeling")
    return match_id


def _center_inside_roi(box: list[float], roi: list[float]) -> bool:
    center_x = box[0] + box[2] * 0.5
    center_y = box[1] + box[3] * 0.5
    return (roi[0] <= center_x <= roi[0] + roi[2] and
            roi[1] <= center_y <= roi[1] + roi[3])


def _filter_suggestions(
    suggestions: list[dict], safe_roi: list[float], frame_width: int,
    frame_height: int, label_roi: list[float] | None = None,
    filter_label_roi: bool = False,
    remove_safe_roi_edge_contacts: bool = False,
) -> tuple[list[dict], dict[str, int]]:
    """Apply opt-in center and expandable safe-crop-edge filters."""
    if filter_label_roi and label_roi is None:
        raise ValueError("--filter-label-roi requires label_roi in the review manifest")

    kept = []
    removed_by_label_roi = 0
    removed_by_safe_roi_edge = 0
    for suggestion in suggestions:
        box = suggestion["bbox"]
        if filter_label_roi and not _center_inside_roi(box, label_roi):
            removed_by_label_roi += 1
            continue
        if remove_safe_roi_edge_contacts:
            audit = inspect_box_roi(
                box, safe_roi, frame_width, frame_height,
                tolerance_px=DEFAULT_ROI_EDGE_TOLERANCE_PX,
            )
            # Physical-frame edges cannot be expanded. Remove only contacts on
            # crop edges that have source pixels available beyond the crop.
            if audit["crop_touches"]:
                removed_by_safe_roi_edge += 1
                continue
        kept.append(suggestion)

    return kept, {
        "candidate_count": len(suggestions),
        "removed_by_label_roi": removed_by_label_roi,
        "removed_by_safe_roi_edge": removed_by_safe_roi_edge,
        "kept": len(kept),
    }


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
          confidence: object = None, nms_threshold: float = 0.5,
          filter_label_roi: bool = False,
          remove_safe_roi_edge_contacts: bool = False,
          metadata: Path | dict | None = None,
          classes: list[str] | tuple[str, ...] | None = None) -> dict:
    import cv2
    import numpy as np
    import onnxruntime as ort
    from PIL import Image, ImageDraw

    data = json.loads(manifest.read_text(encoding="utf-8"))
    metadata_value = metadata
    if metadata_value is None:
        for candidate in (
            model.with_suffix(".metadata.json"),
            model.parent / f"{model.stem}.metadata.json",
        ):
            if candidate.is_file():
                metadata_value = candidate
                break
    class_names = resolve_classes(classes=classes, metadata=metadata_value)
    confidence_by_class = (
        confidence_from_metadata(metadata_value, class_names, 0.03)
        if confidence is None else confidence_thresholds(confidence, class_names)
    )
    confidence_scalar = (
        confidence if isinstance(confidence, (int, float)) and
        not isinstance(confidence, bool) else None
    )
    default_roi = _roi(data["roi"])
    effective_label_rois = {}
    for match in data["matches"]:
        match_id = _validate_match_id(match.get("id"))
        label_roi, label_roi_source = _label_roi(data, match)
        if filter_label_roi and label_roi is None:
            raise ValueError(
                "--filter-label-roi requires label_roi in the review manifest "
                f"(missing for {match_id})"
            )
        effective_label_rois[match_id] = (label_roi, label_roi_source)
    session = ort.InferenceSession(str(model.resolve()), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    result = {
        "schema_version": 1,
        "model": str(model.resolve()),
        "input_size": input_size,
        "classes": list(class_names),
        "output_width": 5 + len(class_names),
        "confidence": confidence_scalar,
        "confidence_by_class": confidence_by_class,
        "nms_threshold": nms_threshold,
        "warning": "Low-threshold suggestions are not ground truth; inspect every image.",
        "filtering_provenance": {
            "filters": {
                "label_roi_center": {
                    "enabled": filter_label_roi,
                    "rule": "keep boxes whose normalized center is inside label_roi, inclusive",
                    "roi_source": "match.label_roi, falling back to manifest.label_roi",
                },
                "expandable_safe_roi_edge_contacts": {
                    "enabled": remove_safe_roi_edge_contacts,
                    "tolerance_px": DEFAULT_ROI_EDGE_TOLERANCE_PX,
                    "rule": "remove boxes with inspect_box_roi.crop_touches",
                    "physical_frame_edge_contacts_kept": True,
                },
            },
            "counts": {
                "candidate_count": 0,
                "removed_by_label_roi": 0,
                "removed_by_safe_roi_edge": 0,
                "kept": 0,
            },
            "per_match": [],
        },
        "matches": [],
    }
    total = 0
    aggregate_counts = result["filtering_provenance"]["counts"]
    for match in data["matches"]:
        match_id = match["id"]
        match_roi = match.get("roi")
        roi = default_roi if match_roi is None else _roi(match_roi)
        label_roi, label_roi_source = effective_label_rois[match_id]
        predictions = []
        match_counts = {
            "candidate_count": 0,
            "removed_by_label_roi": 0,
            "removed_by_safe_roi_edge": 0,
            "kept": 0,
        }
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
            raw = session.run(None, {input_name: batch})[0]
            detections = candidates_from_raw(
                raw, input_size, crop_width, crop_height,
                confidence_by_class, nms_threshold, class_names, np=np,
            )
            found = []
            overlay_boxes = {}
            overlay = Image.fromarray(cv2.cvtColor(crop, cv2.COLOR_BGR2RGB))
            draw = ImageDraw.Draw(overlay)
            for detection in detections:
                x0, y0, x1, y1, score, class_id_value = detection
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
                score = float(score)
                class_id = int(class_id_value)
                suggestion = {"bbox": [round(value, 8) for value in normalized],
                              "confidence": round(score, 6),
                              "class_id": class_id,
                              "class_name": class_names[class_id],
                              # ``category`` is the review schema spelling;
                              # keep the explicit class_name for model reports.
                              "category": class_names[class_id],}
                found.append(suggestion)
                overlay_boxes[id(suggestion)] = (x0, y0, x1, y1, score)
            found, counts = _filter_suggestions(
                found, roi, full_width, full_height, label_roi,
                filter_label_roi=filter_label_roi,
                remove_safe_roi_edge_contacts=remove_safe_roi_edge_contacts,
            )
            for suggestion in found:
                x0, y0, x1, y1, score = overlay_boxes[id(suggestion)]
                draw.rectangle((x0, y0, x1, y1), outline="#39ff70", width=1)
                draw.text((x0, max(0, y0 - 9)), f"{score:.2f}", fill="#39ff70")
            for key in match_counts:
                match_counts[key] += counts[key]
                aggregate_counts[key] += counts[key]
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
        result["filtering_provenance"]["per_match"].append({
            "id": match_id,
            "safe_roi": roi,
            "label_roi": label_roi,
            "label_roi_source": label_roi_source,
            **match_counts,
        })
        result["matches"].append({"id": match_id, "roi": roi,
                                  "samples": predictions})
    output.mkdir(parents=True, exist_ok=True)
    (output / "prelabels.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return {"matches": len(result["matches"]),
            "images": sum(len(match["samples"]) for match in result["matches"]),
            "suggestions": total, "output": str(output / "prelabels.json"),
            "filtering_counts": dict(aggregate_counts)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--input-size", type=int, default=320)
    parser.add_argument("--confidence", type=float,
                        help="Override metadata per-class confidence thresholds")
    parser.add_argument("--nms-threshold", type=float, default=0.5)
    parser.add_argument("--metadata", type=Path,
                        help="Model metadata JSON; its classes array is canonical")
    parser.add_argument("--classes", nargs="+", metavar="CLASS",
                        help="Explicit class order when metadata is unavailable")
    parser.add_argument("--filter-label-roi", action="store_true",
                        help="Keep suggestions whose centers fall inside review label_roi")
    parser.add_argument("--remove-safe-roi-edge-contacts", action="store_true",
                        help="Remove suggestions touching expandable safe-crop edges")
    args = parser.parse_args()
    summary = build(args.manifest, args.model, args.output, args.input_size,
                    args.confidence, args.nms_threshold,
                    filter_label_roi=args.filter_label_roi,
                    remove_safe_roi_edge_contacts=args.remove_safe_roi_edge_contacts,
                    metadata=args.metadata, classes=args.classes)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
