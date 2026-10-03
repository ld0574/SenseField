from __future__ import annotations

import subprocess
from pathlib import Path

from scripts.check_public_repo import git_candidates, scan, workspace_candidates


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


def test_public_check_rejects_pkcs12_signing_material(tmp_path: Path) -> None:
    (tmp_path / "README.md").write_text("Project", encoding="utf-8")
    (tmp_path / "LICENSE").write_text("License", encoding="utf-8")
    keystore = "release-signing.p12"
    (tmp_path / keystore).write_bytes(b"private signing material")

    errors, _ = scan(tmp_path, [keystore, "README.md", "LICENSE"])

    assert any("private or binary artifact is public" in error for error in errors)


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


def test_workspace_fallback_includes_public_docs_but_excludes_local_sources(tmp_path: Path) -> None:
    public_docs = [
        "docs/README.md",
        "docs/公开文档范围.md",
        "docs/research/游戏无障碍案例与需求验证.md",
        "docs/requirements/README.md",
        "docs/requirements/为视力障碍玩家打造识别全屏地图的工具.pdf",
        "docs/requirements/赛题背景知识文档：为视力障碍玩家打造识别全屏地图的工具.pdf",
        "docs/requirements/【必看】视力障碍玩家全屏地图识别工具_产品需求书_V1.0.pdf",
        "docs/requirements/符合度评估书03_听野_项目6_符合度53.0pct.pdf",
        "docs/requirements/罕见无界黑客松赛手手册.md",
        "docs/releases/README.md",
        "docs/releases/0.3.0-alpha.1/RELEASE_NOTES.md",
        "docs/releases/0.3.0-alpha.1/CHECKLIST.md",
        "docs/releases/0.3.5/RELEASE_NOTES.md",
        "docs/releases/0.3.8/RELEASE_NOTES.md",
        "docs/plans/符合度改造方案.md",
        "docs/development/assistant-android-transport-test.md",
        "docs/development/assistant-gateway-test-deployment.md",
        "docs/development/app-update.md",
        "docs/releases/0.4.0/RELEASE_NOTES.md",
        "docs/releases/0.4.1/RELEASE_NOTES.md",
    ]
    for name in public_docs:
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("release document\n", encoding="utf-8")

    local_sources = [
        "docs/references/meetings/source.md",
        "docs/references/research/original.md",
        "docs/requirements/source.pdf",
        "output/research/source.pdf",
        "deps/private/source.pdf",
        "models_cache/export.bin",
    ]
    for name in local_sources:
        reference = tmp_path / name
        reference.parent.mkdir(parents=True, exist_ok=True)
        reference.write_text("local reference\n", encoding="utf-8")

    candidates = workspace_candidates(tmp_path)

    assert all(name in candidates for name in public_docs)
    assert all(name not in candidates for name in local_sources)


def test_public_check_ignores_auth_variable_flow_and_key_name_constants(tmp_path: Path) -> None:
    (tmp_path / "README.md").write_text("Project", encoding="utf-8")
    (tmp_path / "LICENSE").write_text("License", encoding="utf-8")
    source = tmp_path / "auth.py"
    source.write_text(
        'TOKEN = "assistant_device_token"\n'
        'token = preferences.getString(TOKEN, "").strip()\n'
        'scheme, separator, token = header.partition(" ")\n'
        'TOKEN = "test-device-token-not-a-secret"\n',
        encoding="utf-8",
    )

    errors, _ = scan(tmp_path, ["README.md", "LICENSE", "auth.py"])

    assert errors == []


def test_public_check_ignores_password_input_type_ternary(tmp_path: Path) -> None:
    (tmp_path / "README.md").write_text("Project", encoding="utf-8")
    (tmp_path / "LICENSE").write_text("License", encoding="utf-8")
    source = tmp_path / "Settings.java"
    source.write_text(
        "field.setInputType(secret\n"
        "    ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD\n"
        "    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);\n",
        encoding="utf-8",
    )

    errors, _ = scan(tmp_path, ["README.md", "LICENSE", "Settings.java"])

    assert errors == []


def test_public_check_scans_secrets_throughout_files_under_the_size_limit(tmp_path: Path) -> None:
    (tmp_path / "README.md").write_text("Project", encoding="utf-8")
    (tmp_path / "LICENSE").write_text("License", encoding="utf-8")
    key = "a1b2c3d4e5f6g7h8i9j0k1l2m3n4"
    source = tmp_path / "large_config.py"
    source.write_text("#" + "x" * (2 * 1024 * 1024 + 16) + f"\nZHIPU_API_KEY={key}\n",
                      encoding="utf-8")

    errors, _ = scan(tmp_path, ["README.md", "LICENSE", "large_config.py"])

    assert any("model-service API key: large_config.py" in error for error in errors)
    assert key not in "\n".join(errors)
