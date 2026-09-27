from __future__ import annotations

from mapassist.roi_safety import (
    coco_roi_blocker,
    has_supported_coco_roi_audit,
    inspect_box_roi,
)


def test_roi_safety_uses_actual_integer_crop_bounds_for_physical_edges() -> None:
    width = 1000
    height = 1000

    # The requested left edge is at 0.5 px, but floor() makes the actual crop
    # start at the physical frame edge.
    left_roi = [0.0005, 0.1, 0.2, 0.2]
    left = inspect_box_roi([0.0, 0.12, 0.05, 0.05], left_roi, width, height)
    assert left["outside"] == []
    assert left["frame_touches"] == ["left"]
    assert left["crop_touches"] == []

    # ceil() likewise makes a right edge within one pixel of the frame physical.
    right_roi = [0.5, 0.1, 0.4995, 0.2]
    right = inspect_box_roi([0.95, 0.12, 0.0495, 0.05], right_roi, width, height)
    assert right["frame_touches"] == ["right"]
    assert right["crop_touches"] == []

    # One pixel inward still describes an expandable crop border.
    expandable = inspect_box_roi(
        [0.0015, 0.12, 0.05, 0.05], [0.0015, 0.1, 0.2, 0.2], width, height
    )
    assert expandable["crop_touches"] == ["left"]
    assert expandable["frame_touches"] == []


def test_unknown_or_incomplete_roi_audit_is_not_treated_as_project_provenance() -> None:
    document = {"info": {"roi_boundary_audit": {
        "schema_version": 99,
        "crop_edge_contacts": [{"sides": ["right"]}],
        "training_eligible": False,
    }}}

    assert not has_supported_coco_roi_audit(document)
    assert coco_roi_blocker(document, "train") is None
