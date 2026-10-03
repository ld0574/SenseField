"""Bounded image decoding and JPEG normalization for the vision API."""

from __future__ import annotations

import base64
import binascii
import io
import warnings
from dataclasses import dataclass

from PIL import Image, UnidentifiedImageError

MAX_IMAGE_BYTES = 512 * 1024
MAX_IMAGE_DIMENSION = 1280
_MAX_ENCODED_BYTES = ((MAX_IMAGE_BYTES + 2) // 3) * 4


class InvalidImage(ValueError):
    """Input did not meet the gateway's image contract."""


@dataclass(frozen=True)
class NormalizedImage:
    jpeg_bytes: bytes
    jpeg_base64: str
    width: int
    height: int


def normalize_image(image_base64: str) -> NormalizedImage:
    if not isinstance(image_base64, str) or not image_base64:
        raise InvalidImage("image_base64 must be a non-empty base64 string")
    if len(image_base64) > _MAX_ENCODED_BYTES:
        raise InvalidImage("image exceeds the 512 KB limit")
    try:
        source = base64.b64decode(image_base64, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise InvalidImage("image_base64 is invalid") from exc
    if not source or len(source) > MAX_IMAGE_BYTES:
        raise InvalidImage("image exceeds the 512 KB limit")

    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", Image.DecompressionBombWarning)
            with Image.open(io.BytesIO(source)) as probe:
                image_format = probe.format
                width, height = probe.size
                if image_format not in {"JPEG", "PNG"}:
                    raise InvalidImage("image must be JPEG or PNG")
                if width < 1 or height < 1 or width > MAX_IMAGE_DIMENSION or height > MAX_IMAGE_DIMENSION:
                    raise InvalidImage("image dimensions may not exceed 1280 by 1280")
                probe.verify()
            with Image.open(io.BytesIO(source)) as decoded:
                image = decoded.convert("RGB")
    except InvalidImage:
        raise
    except (UnidentifiedImageError, OSError, Image.DecompressionBombError, Image.DecompressionBombWarning) as exc:
        raise InvalidImage("image could not be decoded") from exc

    output = io.BytesIO()
    image.save(output, format="JPEG", quality=88, optimize=True)
    jpeg = output.getvalue()
    if len(jpeg) > MAX_IMAGE_BYTES:
        # This is rare for the allowed frame size, but a noisy/high-detail PNG
        # can expand when transcoded. Reduce quality without changing geometry.
        output = io.BytesIO()
        image.save(output, format="JPEG", quality=76, optimize=True)
        jpeg = output.getvalue()
    if len(jpeg) > MAX_IMAGE_BYTES:
        raise InvalidImage("normalized image exceeds the 512 KB limit")
    return NormalizedImage(jpeg, base64.b64encode(jpeg).decode("ascii"), width, height)
