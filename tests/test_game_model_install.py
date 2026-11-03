from __future__ import annotations

import hashlib
import importlib.util
import json
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("install_game_models", ROOT / "deploy/assistant/install-game-models.py")
installer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(installer)


def seed(root: Path, version: str, payload: bytes) -> dict:
    path = root / "hok" / version / "model.bin"
    path.parent.mkdir(parents=True)
    path.write_bytes(payload)
    pack = {"version": version, "files": [{"name": "model.bin", "bytes": len(payload),
                                              "sha256": hashlib.sha256(payload).hexdigest()}]}
    index = {"schema": 1, "packs": {"hok": pack}, "versions": {"hok": {version: pack}}}
    (root / "game-models.json").write_text(json.dumps(index))
    return pack


def test_import_retains_old_current_version_key_and_unrelated_data(tmp_path: Path) -> None:
    source, target = tmp_path / "source", tmp_path / "private"
    current = seed(source, "v2", b"new model")
    previous = seed(target, "v1", b"old model")
    key = b"k" * 32
    (target / ".download-key").write_bytes(key)
    (target / "keep.txt").write_text("keep")

    result = installer.install(source, target)

    index = json.loads((target / "game-models.json").read_text())
    assert index["packs"]["hok"] == current
    assert index["versions"]["hok"]["v1"] == previous
    assert (target / "hok/v1/model.bin").read_bytes() == b"old model"
    assert (target / "hok/v2/model.bin").read_bytes() == b"new model"
    assert (target / ".download-key").read_bytes() == key
    assert (target / "keep.txt").read_text() == "keep"
    assert result["retained_versions"] == 1
    before = (target / "game-models.json").read_bytes()
    installer.install(source, target)
    assert (target / "game-models.json").read_bytes() == before
    assert (target / ".download-key").read_bytes() == key


def test_bad_source_does_not_change_existing_index_or_key(tmp_path: Path) -> None:
    source, target = tmp_path / "source", tmp_path / "private"
    seed(source, "v2", b"new model")
    seed(target, "v1", b"old model")
    (target / ".download-key").write_bytes(b"k" * 32)
    before = (target / "game-models.json").read_bytes()
    (source / "hok/v2/model.bin").write_bytes(b"corrupted")

    with pytest.raises(ValueError, match="校验失败"):
        installer.install(source, target)

    assert (target / "game-models.json").read_bytes() == before
    assert (target / ".download-key").read_bytes() == b"k" * 32
    assert not (target / "hok/v2").exists()


def test_same_content_version_cannot_be_replaced_by_other_model(tmp_path: Path) -> None:
    source, target = tmp_path / "source", tmp_path / "private"
    seed(source, "v1", b"replacement model")
    seed(target, "v1", b"original model")
    before = (target / "game-models.json").read_bytes()

    with pytest.raises(ValueError, match="同一内容版本"):
        installer.install(source, target)

    assert (target / "game-models.json").read_bytes() == before
    assert (target / "hok/v1/model.bin").read_bytes() == b"original model"


def test_project_exception_does_not_allow_other_public_directories(tmp_path: Path) -> None:
    source = tmp_path / "source"
    seed(source, "v1", b"model")
    with pytest.raises(ValueError, match="只允许"):
        installer.install(source, Path("/data/wwwroot/another-site/game-models"))


def test_overwrite_bundle_keeps_live_index_until_startup_then_merges_in_place(tmp_path: Path) -> None:
    target = tmp_path / "game-models"
    previous = seed(target, "v1", b"old model")
    before = (target / "game-models.json").read_bytes()
    key = b"k" * 32
    (target / ".download-key").write_bytes(key)
    incoming = tmp_path / "incoming"
    current = seed(incoming, "v2", b"new model")
    (target / "hok/v2").mkdir(parents=True)
    (target / "hok/v2/model.bin").write_bytes(b"new model")
    (target / installer.BUNDLED_INDEX_NAME).write_bytes((incoming / "game-models.json").read_bytes())
    assert (target / "game-models.json").read_bytes() == before

    installer.install(target, target, index_name=installer.BUNDLED_INDEX_NAME)

    index = json.loads((target / "game-models.json").read_text())
    assert index["packs"]["hok"] == current
    assert index["versions"]["hok"]["v1"] == previous
    assert (target / ".download-key").read_bytes() == key
    before_stat = (target / "game-models.json").stat()
    installer.install(target, target, index_name=installer.BUNDLED_INDEX_NAME)
    assert (target / "game-models.json").stat().st_mtime_ns == before_stat.st_mtime_ns
