from __future__ import annotations

import json
from pathlib import Path

import pytest

from mapassist import combine_detection_manifests as combine_module
from mapassist import merge_detection_manifests as merge_module


WRITERS = {
    "combine": (combine_module, combine_module.combine),
    "merge": (merge_module, merge_module.merge),
}


def _manifest(path: Path, match_id: str = "clip-one") -> Path:
    video = path.parent / f"{match_id}.mp4"
    video.write_bytes(b"fixture video")
    path.write_text(
        json.dumps({
            "schema_version": 1,
            "category": "main_enemy",
            "classes": ["main_enemy"],
            "matches": [{
                "id": match_id,
                "video": video.name,
                "split": "train",
                "roi": [0.0, 0.0, 1.0, 1.0],
                "frames": [{"at_ms": 0, "boxes": []}],
            }],
        }),
        encoding="utf-8",
    )
    return path


@pytest.mark.parametrize("writer_name", sorted(WRITERS))
def test_writer_rejects_output_that_is_an_input(
    tmp_path: Path, writer_name: str,
) -> None:
    _module, writer = WRITERS[writer_name]
    manifest = _manifest(tmp_path / "input.json")
    before = manifest.read_bytes()

    with pytest.raises(ValueError, match="overwrite input"):
        writer([manifest], manifest)

    assert manifest.read_bytes() == before


@pytest.mark.parametrize("writer_name", sorted(WRITERS))
def test_writer_rejects_symlink_output(
    tmp_path: Path, writer_name: str,
) -> None:
    _module, writer = WRITERS[writer_name]
    manifest = _manifest(tmp_path / "input.json")
    target = tmp_path / "sentinel.json"
    target.write_text("sentinel\n", encoding="utf-8")
    output = tmp_path / "output-alias.json"
    try:
        output.symlink_to(target)
    except OSError:
        pytest.skip("symlinks are unavailable")

    with pytest.raises(ValueError, match="symlink"):
        writer([manifest], output)

    assert target.read_text(encoding="utf-8") == "sentinel\n"


@pytest.mark.parametrize("writer_name", sorted(WRITERS))
def test_writer_keeps_existing_output_when_replace_fails(
    tmp_path: Path, writer_name: str, monkeypatch: pytest.MonkeyPatch,
) -> None:
    module, writer = WRITERS[writer_name]
    manifest = _manifest(tmp_path / "input.json")
    output = tmp_path / "output.json"
    output.write_text("old output\n", encoding="utf-8")

    original_replace = module.os.replace

    def fail_replace(source: Path, destination: Path) -> None:
        if Path(destination) == output:
            raise OSError("injected replace failure")
        original_replace(source, destination)

    monkeypatch.setattr(module.os, "replace", fail_replace)
    with pytest.raises(OSError, match="injected replace failure"):
        writer([manifest], output)

    assert output.read_text(encoding="utf-8") == "old output\n"
    assert not list(tmp_path.glob(".output.json.*.tmp"))


@pytest.mark.parametrize("writer_name", sorted(WRITERS))
def test_writer_rejects_explicit_null_dataset_scope(
    tmp_path: Path, writer_name: str,
) -> None:
    _module, writer = WRITERS[writer_name]
    manifest = _manifest(tmp_path / "input.json")
    document = json.loads(manifest.read_text(encoding="utf-8"))
    document["dataset_scope"] = None
    manifest.write_text(json.dumps(document), encoding="utf-8")

    with pytest.raises(ValueError, match="dataset_scope must be an object"):
        writer([manifest], tmp_path / "output.json")

    assert not (tmp_path / "output.json").exists()
