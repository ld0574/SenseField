"""Shared checks for annotations near or outside a detector crop boundary."""

from __future__ import annotations

from math import ceil, floor, isfinite


DEFAULT_ROI_EDGE_TOLERANCE_PX = 1.0
ROI_BOUNDARY_AUDIT_SCHEMA_VERSION = 1


def normalized_roi(value: object, label: str = "ROI") -> list[float]:
    """Validate and normalize a finite, positive rectangle in frame coordinates."""
    if (not isinstance(value, list) or len(value) != 4 or
            any(not isinstance(item, (int, float)) or isinstance(item, bool)
                for item in value)):
        raise ValueError(f"{label} must be normalized [x, y, width, height]")
    try:
        result = [float(item) for item in value]
    except (OverflowError, ValueError) as error:
        raise ValueError(f"{label} must contain finite normalized numbers") from error
    if any(not isfinite(item) for item in result):
        raise ValueError(f"{label} must contain finite normalized numbers")
    x, y, width, height = result
    if (x < 0 or y < 0 or width <= 0 or height <= 0 or
            x + width > 1.000001 or y + height > 1.000001):
        raise ValueError(f"{label} is outside the normalized frame")
    return result


def _supported_roi_boundary_audit(document: dict) -> dict | None:
    """Return a complete audit written in the schema this exporter supports."""
    info = document.get("info", {})
    audit = info.get("roi_boundary_audit") if isinstance(info, dict) else None
    if not isinstance(audit, dict):
        return None
    version = audit.get("schema_version")
    if (not isinstance(version, int) or isinstance(version, bool) or
            version != ROI_BOUNDARY_AUDIT_SCHEMA_VERSION):
        return None
    tolerance = audit.get("edge_tolerance_px")
    if not isinstance(tolerance, (int, float)) or isinstance(tolerance, bool):
        return None
    try:
        tolerance = float(tolerance)
    except (OverflowError, ValueError):
        return None
    if not isfinite(tolerance) or tolerance < 0:
        return None
    if not isinstance(audit.get("policy"), str) or not audit["policy"].strip():
        return None
    for key in ("training_eligible", "usable_for_training_or_evaluation"):
        if not isinstance(audit.get(key), bool):
            return None

    def valid_contacts(value: object) -> bool:
        if not isinstance(value, list):
            return False
        valid_sides = {"left", "right", "top", "bottom"}
        for contact in value:
            if not isinstance(contact, dict):
                return False
            match_id = contact.get("match_id")
            at_ms = contact.get("at_ms")
            box_index = contact.get("box_index")
            sides = contact.get("sides")
            if (not isinstance(match_id, str) or not match_id or
                    not isinstance(at_ms, int) or isinstance(at_ms, bool) or at_ms < 0 or
                    not isinstance(box_index, int) or isinstance(box_index, bool) or
                    box_index < 1 or not isinstance(sides, list) or not sides or
                    any(not isinstance(side, str) or side not in valid_sides
                        for side in sides)):
                return False
        return True

    crop_contacts = audit.get("crop_edge_contacts")
    physical_contacts = audit.get("physical_edge_contacts")
    if not valid_contacts(crop_contacts) or not valid_contacts(physical_contacts):
        return None
    # The alias is emitted for older report readers. If present, it must agree
    # with the canonical crop contact list; malformed provenance stays unknown.
    if "edge_contacts" in audit and audit["edge_contacts"] != crop_contacts:
        return None
    return audit


def has_supported_coco_roi_audit(document: dict) -> bool:
    return _supported_roi_boundary_audit(document) is not None


def coco_roi_blocker(document: dict, split_name: str) -> str | None:
    """Return a blocker for this exporter's unsafe crop metadata, if present.

    Generic COCO datasets do not carry enough provenance to classify an image-edge
    target as a crop error or a legitimate physical-frame-edge target. In that case
    callers should report edge contacts as unknown rather than blocking the split.
    """
    audit = _supported_roi_boundary_audit(document)
    if audit is None:
        return None
    contacts = audit["crop_edge_contacts"]
    if (contacts or audit.get("training_eligible") is False or
            audit.get("usable_for_training_or_evaluation") is False):
        count = len(contacts) if isinstance(contacts, list) else 1
        return (
            f"{split_name} COCO data have {count} target box(es) touching an "
            "expandable crop edge; review crop completeness and re-export"
        )
    return None


def assert_coco_roi_safe(document: dict, split_name: str) -> None:
    blocker = coco_roi_blocker(document, split_name)
    if blocker:
        raise ValueError(blocker)


def assert_coco_boxes_within_images(document: dict, split_name: str) -> None:
    """Reject malformed COCO boxes and boxes extending beyond their images."""
    images = document.get("images")
    annotations = document.get("annotations")
    if not isinstance(images, list) or not isinstance(annotations, list):
        raise ValueError(f"{split_name} COCO images and annotations must be lists")

    dimensions: dict[int, tuple[int, int]] = {}
    for image in images:
        if not isinstance(image, dict):
            raise ValueError(f"{split_name} COCO image entry is invalid")
        image_id = image.get("id")
        width = image.get("width")
        height = image.get("height")
        if (not isinstance(image_id, int) or isinstance(image_id, bool) or
                not isinstance(width, int) or isinstance(width, bool) or width <= 0 or
                not isinstance(height, int) or isinstance(height, bool) or height <= 0):
            raise ValueError(f"{split_name} COCO image has invalid dimensions or ID")
        dimensions[image_id] = (width, height)

    for annotation in annotations:
        if not isinstance(annotation, dict):
            raise ValueError(f"{split_name} COCO annotation is invalid")
        image_id = annotation.get("image_id")
        bbox = annotation.get("bbox")
        if (not isinstance(image_id, int) or isinstance(image_id, bool) or
                image_id not in dimensions or not isinstance(bbox, list) or len(bbox) != 4 or
                any(not isinstance(value, (int, float)) or isinstance(value, bool)
                    for value in bbox)):
            raise ValueError(
                f"{split_name} COCO annotation boxes are outside or invalid for their image"
            )
        try:
            x, y, width, height = (float(value) for value in bbox)
        except (OverflowError, ValueError):
            raise ValueError(
                f"{split_name} COCO annotation boxes are outside or invalid for their image"
            ) from None
        if (not all(isfinite(value) for value in (x, y, width, height)) or
                width <= 0 or height <= 0):
            raise ValueError(
                f"{split_name} COCO annotation boxes are outside or invalid for their image"
            )
        image_width, image_height = dimensions[image_id]
        if (x < -1e-9 or y < -1e-9 or
                x + width > image_width + 1e-9 or
                y + height > image_height + 1e-9):
            raise ValueError(
                f"{split_name} COCO annotation boxes are outside or invalid for their image"
            )


def inspect_box_roi(
    box: list[float] | tuple[float, float, float, float],
    roi: list[float] | tuple[float, float, float, float],
    frame_width: int,
    frame_height: int,
    tolerance_px: float = DEFAULT_ROI_EDGE_TOLERANCE_PX,
) -> dict[str, list[str]]:
    """Report crop sides crossed or approached by a normalized frame-space box.

    ``outside`` identifies a box that the crop exporter would have to clip after
    its floor/ceil pixel rounding.
    ``touches`` includes a one-pixel safety band. ``crop_touches`` only includes
    ROI sides that could be expanded within the source frame; ``frame_touches``
    marks sides coincident with a physical source-frame edge.
    """
    if frame_width <= 0 or frame_height <= 0:
        raise ValueError("frame dimensions must be positive")
    if not isfinite(tolerance_px) or tolerance_px < 0:
        raise ValueError("tolerance_px must be finite and nonnegative")
    if len(box) != 4 or len(roi) != 4:
        raise ValueError("box and roi must each contain four values")
    values = [float(value) for value in (*box, *roi)]
    if not all(isfinite(value) for value in values):
        raise ValueError("box and roi values must be finite")
    x, y, width, height, rx, ry, rw, rh = values
    if width <= 0 or height <= 0 or rw <= 0 or rh <= 0:
        raise ValueError("box and roi dimensions must be positive")

    right = x + width
    bottom = y + height
    roi_right = rx + rw
    roi_bottom = ry + rh
    crop_left_px = floor(rx * frame_width)
    crop_top_px = floor(ry * frame_height)
    crop_right_px = ceil(roi_right * frame_width)
    crop_bottom_px = ceil(roi_bottom * frame_height)
    box_left_px = x * frame_width
    box_top_px = y * frame_height
    box_right_px = right * frame_width
    box_bottom_px = bottom * frame_height
    outside = []
    if box_left_px < crop_left_px - 1e-9:
        outside.append("left")
    if box_right_px > crop_right_px + 1e-9:
        outside.append("right")
    if box_top_px < crop_top_px - 1e-9:
        outside.append("top")
    if box_bottom_px > crop_bottom_px + 1e-9:
        outside.append("bottom")

    touches = []
    crop_touches = []
    frame_touches = []
    if (box_left_px <= crop_left_px + tolerance_px and
            box_right_px >= crop_left_px - 1e-9):
        touches.append("left")
        (frame_touches if crop_left_px <= 0 else crop_touches).append("left")
    if (box_right_px >= crop_right_px - tolerance_px and
            box_left_px <= crop_right_px + 1e-9):
        touches.append("right")
        (frame_touches if crop_right_px >= frame_width else crop_touches).append("right")
    if (box_top_px <= crop_top_px + tolerance_px and
            box_bottom_px >= crop_top_px - 1e-9):
        touches.append("top")
        (frame_touches if crop_top_px <= 0 else crop_touches).append("top")
    if (box_bottom_px >= crop_bottom_px - tolerance_px and
            box_top_px <= crop_bottom_px + 1e-9):
        touches.append("bottom")
        (frame_touches if crop_bottom_px >= frame_height else crop_touches).append("bottom")
    return {
        "outside": outside,
        "touches": touches,
        "crop_touches": crop_touches,
        "frame_touches": frame_touches,
    }
