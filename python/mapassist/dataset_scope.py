"""Shared validation for dataset-scope provenance metadata."""

from __future__ import annotations

from typing import Any


_MISSING = object()


def validate_dataset_scope(
    value: object = _MISSING,
    label: str = "Detection manifest dataset_scope",
    *,
    require_training_truth: bool = False,
) -> dict[str, Any] | None:
    """Validate a manifest or COCO ``dataset_scope`` object.

    Older manifests and COCO documents may omit the field altogether.  Once
    ``dataset_scope`` is present, however, it is a complete contract and must
    contain ``training_truth`` as a real JSON boolean.  Callers that need to
    distinguish an explicit JSON ``null`` from a legacy ``dict.get`` result
    must check key presence before calling this helper.  The
    ``require_training_truth`` argument remains for callers written against
    the earlier API; presence of the scope is now always strict.
    """
    if value is _MISSING:
        return None
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be an object")
    if "training_truth" not in value:
        raise ValueError(f"{label}.training_truth must be a boolean")
    elif not isinstance(value["training_truth"], bool):
        raise ValueError(f"{label}.training_truth must be a boolean")
    return value


def coco_dataset_scope(
    document: dict[str, Any], label: str = "COCO document"
) -> dict[str, Any] | None:
    """Read and validate ``info.dataset_scope`` from a COCO document."""
    info = document.get("info")
    if info is None or not isinstance(info, dict):
        return None
    if "dataset_scope" not in info:
        return None
    if info["dataset_scope"] is None:
        raise ValueError(f"{label}.info.dataset_scope must be an object")
    return validate_dataset_scope(
        info["dataset_scope"], f"{label}.info.dataset_scope"
    )
