from __future__ import annotations

import subprocess
from pathlib import Path

from scripts.check_public_repo import git_candidates, scan


def test_public_check_rejects_model_artifacts_but_allows_other_bin_files(
    tmp_path: Path,
) -> None:
    (tmp_path / "README.md").write_text("Project", encoding="utf-8")
    (tmp_path / "LICENSE").write_text("License", encoding="utf-8")
    model_files = [
        "weights/model.pth",
        "weights/model.pt",
        "weights/model.onnx",
        "android/app/src/main/assets/minimap-yolox-nano-320.param",
        "android/app/src/main/assets/minimap-yolox-nano-320.bin",
    ]
    ordinary_bin = "assets/texture.bin"
    for name in (*model_files, ordinary_bin):
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"fixture")

    errors, _ = scan(tmp_path, [*model_files, ordinary_bin, "README.md", "LICENSE"])

    model_errors = [error for error in errors if error.startswith("model weight or graph")]
    assert len(model_errors) == len(model_files)
    assert not any(ordinary_bin in error for error in errors)


def test_public_check_recognizes_bin_with_ncnn_param_companion(tmp_path: Path) -> None:
    (tmp_path / "README.md").write_text("Project", encoding="utf-8")
    (tmp_path / "LICENSE").write_text("License", encoding="utf-8")
    model = "android/app/src/main/assets/private-detector.bin"
    graph = model.removesuffix(".bin") + ".param"
    for name in (model, graph):
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"fixture")

    errors, _ = scan(tmp_path, [model, graph, "README.md", "LICENSE"])

    assert any(model in error for error in errors)
    assert any(graph in error for error in errors)


def test_git_scan_checks_tracked_ignored_and_unignored_model_files(tmp_path: Path) -> None:
    (tmp_path / "README.md").write_text("Project", encoding="utf-8")
    (tmp_path / "LICENSE").write_text("License", encoding="utf-8")
    (tmp_path / ".gitignore").write_text("*.pt\nweights/ignored.onnx\n", encoding="utf-8")
    tracked_ignored = tmp_path / "weights/tracked.pt"
    tracked_ignored.parent.mkdir()
    tracked_ignored.write_bytes(b"tracked fixture")
    untracked_ignored = tmp_path / "weights/ignored.onnx"
    untracked_ignored.write_bytes(b"ignored fixture")
    untracked_unignored = tmp_path / "weights/unignored.onnx"
    untracked_unignored.write_bytes(b"unignored fixture")
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    subprocess.run([
        "git", "-C", str(tmp_path), "add", "-f", "weights/tracked.pt",
    ], check=True)

    candidates = git_candidates(tmp_path)
    assert candidates is not None
    assert "weights/tracked.pt" in candidates
    assert "weights/unignored.onnx" in candidates
    assert "weights/ignored.onnx" not in candidates
    errors, _ = scan(tmp_path, candidates)
    assert any("weights/tracked.pt" in error for error in errors)
    assert any("weights/unignored.onnx" in error for error in errors)
