from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path

import pytest


spec = importlib.util.spec_from_file_location(
    "asr_package", Path(__file__).resolve().parents[1] / "deploy/assistant/package-asr-model.py")
assert spec and spec.loader
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


def fixture(tmp_path):
    assets = tmp_path / "assets"
    assets.mkdir()
    files = {"model.int8.onnx": bytes(range(256)) * 20, "tokens.txt": b"fixture tokens"}
    for name, contents in files.items():
        (assets / name).write_bytes(contents)
    metadata = {"revision": "fixture-unchanged", "files": {
        name: {"size": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        for name, data in files.items()}}
    return assets, metadata


def test_parts_reconstruct_exact_zip_and_pin_each_digest(tmp_path):
    assets, metadata = fixture(tmp_path)
    output = tmp_path / "out"
    result = package.package_model(metadata, assets, output, part_bytes=128)
    parts = result["download"]["parts"]
    joined = b"".join((output / p["name"]).read_bytes() for p in parts)
    assert len(parts) > 1 and len(parts) <= 8
    assert joined == (output / "sensevoice-int8-v1.zip").read_bytes()
    assert result["files"] == metadata["files"] and "download" not in metadata
    assert result["download"]["archive_sha256"] == hashlib.sha256(joined).hexdigest()
    for part in parts:
        data = (output / part["name"]).read_bytes()
        assert part["bytes"] == len(data) <= 128 < 100_000_000
        assert part["sha256"] == hashlib.sha256(data).hexdigest()
    assert json.loads((output / "model.json").read_text()) == result


def test_original_zip_reused_byte_for_byte_without_repacking(tmp_path):
    assets, metadata = fixture(tmp_path)
    first = tmp_path / "first"
    pinned = package.package_model(metadata, assets, first)
    (assets / "model.int8.onnx").unlink()
    second = tmp_path / "second"
    result = package.package_model(pinned, assets, second, part_bytes=128,
                                   existing_archive=first / "sensevoice-int8-v1.zip")
    assert result["download"]["archive_sha256"] == pinned["download"]["archive_sha256"]
    assert result["files"] == pinned["files"]


def test_wrong_weights_or_original_archive_rejected(tmp_path):
    assets, metadata = fixture(tmp_path)
    pinned = package.package_model(metadata, assets, tmp_path / "first")
    (assets / "model.int8.onnx").write_bytes(b"wrong")
    with pytest.raises(ValueError, match="pinned"):
        package.package_model(metadata, assets, tmp_path / "bad")
    corrupt = tmp_path / "wrong.zip"
    corrupt.write_bytes(b"wrong")
    with pytest.raises(ValueError, match="pinned"):
        package.package_model(pinned, assets, tmp_path / "bad2", existing_archive=corrupt)


@pytest.mark.parametrize("size", [0, 100_000_000, -1])
def test_attachment_limit_and_invalid_part_size_rejected(tmp_path, size):
    assets, metadata = fixture(tmp_path)
    with pytest.raises(ValueError, match="Part size"):
        package.package_model(metadata, assets, tmp_path / "out", part_bytes=size)


@pytest.mark.parametrize("url", [
    "http://gitee.com/leda/SenseField/releases/download/0.4.1",
    "https://gitee.com/other/SenseField/releases/download/0.4.1",
    "https://gitee.com/leda/SenseField/releases/download/..",
    "https://gitee.com/leda/SenseField/releases/download/0.4.1?token=fixture",
])
def test_unsafe_release_route_rejected(tmp_path, url):
    assets, metadata = fixture(tmp_path)
    with pytest.raises(ValueError, match="Gitee Release"):
        package.package_model(metadata, assets, tmp_path / "out", release_url=url)
