from __future__ import annotations

import base64
import io

import pytest
from PIL import Image, ImageCms

from mapassist.assistant_gateway.images import InvalidImage, normalize_image


def _encode(image: Image.Image, image_format: str = "JPEG", **options: object) -> bytes:
    output = io.BytesIO()
    image.save(output, format=image_format, **options)
    return output.getvalue()


@pytest.mark.parametrize("with_profile", [False, True])
def test_screen_jpeg_keeps_original_compressed_pixels(with_profile: bool) -> None:
    image = Image.new("RGB", (1280, 576), "navy")
    image.paste("white", (10, 20, 33, 90))
    options = {"quality": 75}
    if with_profile:
        options["icc_profile"] = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
    source = _encode(image, **options)
    encoded = base64.b64encode(source).decode("ascii")

    normalized = normalize_image(encoded)

    assert (normalized.width, normalized.height) == image.size
    assert normalized.jpeg_bytes == source
    assert normalized.jpeg_base64 == encoded
    assert not normalized.transcoded


@pytest.mark.parametrize("image_format,mode", [("PNG", "RGBA"), ("JPEG", "L"), ("JPEG", "CMYK")])
def test_other_image_inputs_still_become_rgb_jpeg(image_format: str, mode: str) -> None:
    source = _encode(Image.new(mode, (40, 24)), image_format)
    normalized = normalize_image(base64.b64encode(source).decode("ascii"))
    assert normalized.transcoded
    assert normalized.jpeg_bytes != source
    with Image.open(io.BytesIO(normalized.jpeg_bytes)) as image:
        image.load()
        assert image.format == "JPEG" and image.mode == "RGB"
        assert image.size == (40, 24)


def test_exif_still_uses_transcode_and_is_removed() -> None:
    exif = Image.Exif()
    exif[270] = "PRIVATE_IMAGE_ANNOTATION"
    source = _encode(Image.new("RGB", (40, 24)), exif=exif)
    normalized = normalize_image(base64.b64encode(source).decode("ascii"))
    assert normalized.transcoded
    assert b"PRIVATE_IMAGE_ANNOTATION" not in normalized.jpeg_bytes
    with Image.open(io.BytesIO(normalized.jpeg_bytes)) as image:
        assert not image.getexif()


def test_comment_still_uses_transcode_and_is_removed() -> None:
    source = _encode(Image.new("RGB", (40, 24)), comment=b"PRIVATE_IMAGE_COMMENT")
    normalized = normalize_image(base64.b64encode(source).decode("ascii"))
    assert normalized.transcoded
    assert b"PRIVATE_IMAGE_COMMENT" not in normalized.jpeg_bytes


def test_jpeg_passthrough_requires_complete_pixel_decode() -> None:
    source = _encode(Image.new("RGB", (40, 24)))[:-2]
    with pytest.raises(InvalidImage, match="could not be decoded"):
        normalize_image(base64.b64encode(source).decode("ascii"))


@pytest.mark.parametrize("image_format", ["JPEG", "PNG"])
def test_dimension_limit_applies_before_any_passthrough(image_format: str) -> None:
    source = _encode(Image.new("RGB", (1281, 10)), image_format)
    with pytest.raises(InvalidImage, match="1280 by 1280"):
        normalize_image(base64.b64encode(source).decode("ascii"))


def test_byte_limit_applies_before_any_passthrough() -> None:
    source = _encode(Image.new("RGB", (40, 24)))
    source += b"\0" * (512 * 1024 + 1 - len(source))
    with pytest.raises(InvalidImage, match="512 KB"):
        normalize_image(base64.b64encode(source).decode("ascii"))
