from __future__ import annotations

import ctypes
import subprocess
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

from mapassist.native import MinimapLocatorConfig, Rect, load_library


@pytest.fixture(scope="session")
def native_library(tmp_path_factory: pytest.TempPathFactory) -> Path:
    build_dir = tmp_path_factory.mktemp("layout-locator-native-build")
    root = Path(__file__).resolve().parents[1]
    subprocess.run(
        ["cmake", "-S", str(root / "native"), "-B", str(build_dir)],
        check=True, capture_output=True,
    )
    subprocess.run(
        ["cmake", "--build", str(build_dir)], check=True, capture_output=True,
    )
    return next(build_dir.glob("libmapassist.*"))


def _descriptor() -> list[int]:
    # An asymmetric structure prevents a shifted or mirrored candidate from
    # receiving the same normalized-correlation score.
    return [
        -90, -80, -70, -60, -50, -40, -30, -20,
        -75, -65, -55, -45, -35, -25, -15, -5,
        -60, -50, 80, 75, 70, -10, 0, 10,
        -45, -35, 65, 55, 45, 5, 15, 25,
        -30, -20, 50, 35, 20, 20, 30, 40,
        -15, -5, 35, 15, 0, 35, 45, 55,
        0, 10, 20, 5, -10, 50, 60, 70,
        15, 25, 35, 45, 55, 65, 75, 90,
    ]


def _frame(anchor_x: float = 50, anchor_y: float = 10,
           anchor_width: float = 60, anchor_height: float = 60,
           frame_width: int = 360, frame_height: int = 180,
           content_left: int = 20, content_right: int = 20) -> Image.Image:
    # Black pillar bars surround the active image. Defaults place a 60x60
    # minimap descriptor at full-frame (50, 10) in a 360x180 frame.
    image = Image.new("RGBA", (frame_width, frame_height), (0, 0, 0, 255))
    draw = ImageDraw.Draw(image)
    draw.rectangle(
        (content_left, 0, frame_width - content_right - 1, frame_height - 1),
        fill=(25, 29, 33, 255),
    )
    values = _descriptor()
    for gy in range(8):
        for gx in range(8):
            value = values[gy * 8 + gx] + 128
            draw.rectangle(
                (anchor_x + gx * anchor_width / 8,
                 anchor_y + gy * anchor_height / 8,
                 anchor_x + (gx + 1) * anchor_width / 8 - 1,
                 anchor_y + (gy + 1) * anchor_height / 8 - 1),
                fill=(value, value, value, 255),
            )
    return image


def _config(hold_frames: int = 2, confirm_frames: int = 2,
            radius_x: float = 30 / 180) -> MinimapLocatorConfig:
    return MinimapLocatorConfig(
        Rect(10 / 180, 10 / 180, 60 / 180, 60 / 180),
        radius_x, 10 / 180, 10 / 180,
        1.0, 1.0, 1, 1.0, 1.0, 1,
        8, 8, 0.95, confirm_frames, hold_frames, 30, 1, 5,
    )


def test_locator_normalizes_bars_confirms_and_expires(
    native_library,
) -> None:
    library = load_library(native_library)
    descriptor_values = _descriptor()
    descriptor = (ctypes.c_int8 * len(descriptor_values))(*descriptor_values)
    locator = library.ma_minimap_locator_create(
        ctypes.byref(_config()), descriptor, len(descriptor_values)
    )
    assert locator
    frame = _frame()
    rgba = (ctypes.c_uint8 * len(frame.tobytes())).from_buffer_copy(frame.tobytes())
    roi = Rect()
    content = Rect()
    score = ctypes.c_float()
    try:
        first = library.ma_minimap_locator_update(
            locator, rgba, frame.width, frame.height, frame.width * 4,
            ctypes.byref(roi), ctypes.byref(content), ctypes.byref(score),
        )
        second = library.ma_minimap_locator_update(
            locator, rgba, frame.width, frame.height, frame.width * 4,
            ctypes.byref(roi), ctypes.byref(content), ctypes.byref(score),
        )
        assert first == 0
        assert second == 1
        assert score.value > 0.99
        assert content.x == pytest.approx(20 / 360, abs=1e-5)
        assert content.w == pytest.approx(320 / 360, abs=1e-5)
        assert roi.x == pytest.approx(50 / 360, abs=0.005)
        assert roi.y == pytest.approx(10 / 180, abs=0.005)
        assert roi.w == pytest.approx(60 / 360, abs=0.005)
        assert roi.h == pytest.approx(60 / 180, abs=0.005)

        blank_image = Image.new("RGBA", frame.size, (40, 40, 40, 255))
        blank = (ctypes.c_uint8 * len(blank_image.tobytes())).from_buffer_copy(
            blank_image.tobytes()
        )
        held_one = library.ma_minimap_locator_update(
            locator, blank, frame.width, frame.height, frame.width * 4,
            ctypes.byref(roi), ctypes.byref(content), ctypes.byref(score),
        )
        held_two = library.ma_minimap_locator_update(
            locator, blank, frame.width, frame.height, frame.width * 4,
            ctypes.byref(roi), ctypes.byref(content), ctypes.byref(score),
        )
        expired = library.ma_minimap_locator_update(
            locator, blank, frame.width, frame.height, frame.width * 4,
            ctypes.byref(roi), ctypes.byref(content), ctypes.byref(score),
        )
        assert (held_one, held_two, expired) == (2, 2, 0)
    finally:
        library.ma_minimap_locator_destroy(locator)


def test_locator_rejects_constant_descriptor(native_library) -> None:
    library = load_library(native_library)
    values = (ctypes.c_int8 * 64)(*[0] * 64)
    assert not library.ma_minimap_locator_create(ctypes.byref(_config()), values, 64)


def test_locator_rejects_combinatorial_search_budget(native_library) -> None:
    library = load_library(native_library)
    descriptor_values = _descriptor()
    descriptor = (ctypes.c_int8 * len(descriptor_values))(*descriptor_values)
    config = _config()
    config.search_radius_x_short = 1.0
    config.search_radius_y_short = 1.0
    config.position_step_short = 0.001
    assert not library.ma_minimap_locator_create(
        ctypes.byref(config), descriptor, len(descriptor_values)
    )


def test_unconfirmed_relocation_holds_then_silences_stale_roi(native_library) -> None:
    library = load_library(native_library)
    descriptor_values = _descriptor()
    descriptor = (ctypes.c_int8 * len(descriptor_values))(*descriptor_values)
    locator = library.ma_minimap_locator_create(
        ctypes.byref(_config(hold_frames=1, confirm_frames=3, radius_x=100 / 180)),
        descriptor, len(descriptor_values),
    )
    assert locator
    roi = Rect()
    content = Rect()
    score = ctypes.c_float()

    def update(image: Image.Image) -> int:
        rgba = (ctypes.c_uint8 * len(image.tobytes())).from_buffer_copy(image.tobytes())
        return library.ma_minimap_locator_update(
            locator, rgba, image.width, image.height, image.width * 4,
            ctypes.byref(roi), ctypes.byref(content), ctypes.byref(score),
        )

    try:
        assert [update(_frame(50)) for _ in range(3)] == [0, 0, 1]
        # One transient candidate is held, then the valid current ROI returns.
        # That successful validation must discard the pending relocation so
        # nonconsecutive hits cannot accumulate toward confirmation.
        assert update(_frame(120)) == 2
        assert roi.x == pytest.approx(50 / 360, abs=0.005)
        assert update(_frame(50)) == 1

        # The old location now disappears. The distant candidate still needs
        # three fresh hits: one bounded hold, one silent search, then a lock.
        assert update(_frame(120)) == 2
        assert update(_frame(120)) == 0
        assert (roi.x, roi.y, roi.w, roi.h) == (0.0, 0.0, 0.0, 0.0)
        assert update(_frame(120)) == 1
        assert roi.x == pytest.approx(120 / 360, abs=0.005)
    finally:
        library.ma_minimap_locator_destroy(locator)


def test_invalid_update_clears_every_supplied_output(native_library) -> None:
    library = load_library(native_library)
    descriptor_values = _descriptor()
    descriptor = (ctypes.c_int8 * len(descriptor_values))(*descriptor_values)
    locator = library.ma_minimap_locator_create(
        ctypes.byref(_config()), descriptor, len(descriptor_values)
    )
    assert locator
    pixel = (ctypes.c_uint8 * 4)(1, 2, 3, 4)
    roi = Rect(1, 1, 1, 1)
    content = Rect(1, 1, 1, 1)
    score = ctypes.c_float(1)
    try:
        state = library.ma_minimap_locator_update(
            locator, pixel, 1, 1, 4,
            ctypes.byref(roi), ctypes.byref(content), ctypes.byref(score),
        )
        assert state == 0
        assert (roi.x, roi.y, roi.w, roi.h) == (0.0, 0.0, 0.0, 0.0)
        assert (content.x, content.y, content.w, content.h) == (0.0, 0.0, 0.0, 0.0)
        assert score.value == -2.0
    finally:
        library.ma_minimap_locator_destroy(locator)


def test_aspect_safe_insets_scale_and_resolution_change_reconfirm(
    native_library,
) -> None:
    library = load_library(native_library)
    descriptor_values = _descriptor()
    descriptor = (ctypes.c_int8 * len(descriptor_values))(*descriptor_values)
    config = _config(confirm_frames=2)
    config.min_scale = config.max_scale = 1.2
    config.scale_steps = 1
    config.min_aspect = config.max_aspect = 1.1
    config.aspect_steps = 1
    locator = library.ma_minimap_locator_create(
        ctypes.byref(config), descriptor, len(descriptor_values)
    )
    assert locator
    roi = Rect()
    content = Rect()
    score = ctypes.c_float()

    def update(image: Image.Image) -> int:
        raw = image.tobytes()
        rgba = (ctypes.c_uint8 * len(raw)).from_buffer_copy(raw)
        return library.ma_minimap_locator_update(
            locator, rgba, image.width, image.height, image.width * 4,
            ctypes.byref(roi), ctypes.byref(content), ctypes.byref(score),
        )

    try:
        first = _frame(anchor_width=79.2, anchor_height=72)
        assert update(first) == 0
        assert update(first) == 1
        assert roi.w == pytest.approx(79.2 / 360, abs=0.006)
        assert roi.h == pytest.approx(72 / 180, abs=0.006)

        # Switch to a wider frame, asymmetric safe insets, and a larger ROI in
        # the same short-edge layout. The first frame must search again rather
        # than reuse pixel coordinates from the old resolution.
        wider = _frame(
            anchor_x=70, anchor_y=240 * 10 / 180,
            anchor_width=105.6, anchor_height=96,
            frame_width=600, frame_height=240,
            content_left=30, content_right=10,
        )
        assert update(wider) == 0
        assert (roi.x, roi.y, roi.w, roi.h) == (0.0, 0.0, 0.0, 0.0)
        assert update(wider) == 1
        assert content.x == pytest.approx(30 / 600, abs=1e-5)
        assert content.w == pytest.approx(560 / 600, abs=1e-5)
        assert roi.x == pytest.approx(70 / 600, abs=0.006)
        assert roi.w == pytest.approx(105.6 / 600, abs=0.006)
        assert roi.h == pytest.approx(96 / 240, abs=0.006)
    finally:
        library.ma_minimap_locator_destroy(locator)
