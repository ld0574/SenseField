#!/usr/bin/env python3
"""Check files intended for the public repository before committing or pushing."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath


MAX_FILE_BYTES = 10 * 1024 * 1024
MAX_SECRET_SCAN_BYTES = MAX_FILE_BYTES
FORBIDDEN_PREFIXES = (
    "output/",
    "deps/",
    "data/private/",
    "data/raw/",
    "video/",
    "validation/private/",
    "envsecrets/",
    ".secrets/",
    "secrets/",
    "model-cache/",
    "model_cache/",
    "modelcache/",
    "models-cache/",
    "models_cache/",
    "build/",
    "android/.gradle/",
    "android/.idea/",
)
FORBIDDEN_NAMES = {
    "开发环境参数.md",
    ".DS_Store",
    ".Rhistory",
    "annotations.sqlite3",
    "local.properties",
    "keystore.properties",
    "credentials.json",
}
FORBIDDEN_SUFFIXES = {
    ".mp4",
    ".mov",
    ".mkv",
    ".avi",
    ".webm",
    ".jks",
    ".keystore",
    ".pem",
    ".p12",
    ".pfx",
    ".pkcs12",
    ".secret",
    ".secrets",
    ".env",
}
MODEL_WEIGHT_SUFFIXES = {
    ".pth",
    ".pt",
    ".onnx",
    ".param",
    ".safetensors",
    ".ckpt",
    ".weights",
    ".tflite",
    ".engine",
    ".gguf",
}
MODEL_BIN_NAME_PARTS = {
    "model",
    "weight",
    "weights",
    "checkpoint",
    "checkpoints",
    "yolo",
    "yolox",
    "detector",
    "network",
}
MODEL_BIN_DIRECTORIES = {"model", "models", "weight", "weights", "checkpoints"}
TEXT_SUFFIXES = {
    "",
    ".c",
    ".cc",
    ".cpp",
    ".css",
    ".gradle",
    ".h",
    ".html",
    ".java",
    ".js",
    ".json",
    ".kts",
    ".md",
    ".properties",
    ".py",
    ".sh",
    ".toml",
    ".txt",
    ".xml",
    ".yml",
    ".yaml",
}
SECRET_PATTERNS = (
    ("private key", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")),
    ("GitHub token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{30,}\b")),
    ("AWS access key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_-]{30,}\b")),
    (
        "model-service API key",
        re.compile(
            r"(?im)\b(?:ZHIPU|BIGMODEL|GLM|DASHSCOPE|QWEN|ALIYUN|OPENAI|ANTHROPIC|"
            r"GEMINI|GOOGLE|DEEPSEEK|MOONSHOT|SILICONFLOW|VOLCENGINE|ARK|MODEL|LLM)"
            r"[A-Z0-9_]*(?:API[_-]?KEY|SECRET[_-]?KEY)\s*[:=]\s*['\"]?"
            r"(?!\$\{|<|your[_-]|example\b|placeholder\b|change[_-]?me\b|"
            r"replace[_-]?me\b|insert[_-]?key\b|set[_-]?your\b|"
            r"dummy\b|xxx\b|redacted\b|none\b|null\b|false\b|true\b)"
            r"[A-Z0-9_./+=-]{16,}"
        ),
    ),
    (
        "device or gateway auth secret",
        re.compile(
            r"(?m)(?:\b[A-Z][A-Z0-9_]*(?:TOKEN|SECRET|PASSWORD|API[_-]?KEY)|"
            r"(?i:device|gateway|client|assistant)[_-](?i:auth[_-]?)?"
            r"(?i:token|secret|password|api[_-]?key))['\"]?[ \t]*[:=][ \t]*['\"]?"
            r"(?!\$\{|<|your[_-]|example\b|placeholder\b|change[_-]?me\b|"
            r"replace[_-]?me\b|insert[_-]?key\b|set[_-]?your\b|"
            r"dummy\b|xxx\b|redacted\b|none\b|null\b|false\b|true\b|"
            r"test(?:[_-]|token)|not[_-]?a[_-]?(?:secret|token))"
            r"(?i:[A-Z0-9_./+=-]{24,})"
        ),
    ),
    (
        "Bearer token",
        re.compile(
            r"(?im)\b(?:Authorization|Proxy-Authorization)['\"]?\s*[:=]\s*['\"]?Bearer\s+"
            r"(?!<|your[_-]|example\b|placeholder\b|redacted\b|test(?:[_-]|token))"
            r"[A-Z0-9._~+/-]{24,}={0,2}"
        ),
    ),
)


def is_secret_file(rel: str) -> bool:
    """Recognize environment and secret files, allowing a safe template."""
    path = PurePosixPath(rel.replace("\\", "/"))
    name = path.name.lower()
    if name == ".env.example":
        return False
    if name == ".env" or name.startswith(".env."):
        return True
    if path.suffix.lower() in {".env", ".secret", ".secrets"}:
        return True
    return False


def is_model_artifact(rel: str, candidates: set[str]) -> bool:
    """Recognize model weights/graphs without treating every .bin as a model."""
    path = PurePosixPath(rel.replace("\\", "/"))
    suffix = path.suffix.lower()
    if suffix in MODEL_WEIGHT_SUFFIXES:
        return True
    if suffix != ".bin":
        return False

    stem_parts = {part for part in re.split(r"[._-]+", path.stem.lower()) if part}
    if stem_parts & MODEL_BIN_NAME_PARTS:
        return True
    if {part.lower() for part in path.parts[:-1]} & MODEL_BIN_DIRECTORIES:
        return True
    companion = path.with_suffix(".param").as_posix()
    return companion in candidates


def git_candidates(root: Path) -> list[str] | None:
    probe = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "--is-inside-work-tree"],
        check=False,
        capture_output=True,
        text=True,
    )
    if probe.returncode != 0:
        return None
    result = subprocess.run(
        [
            "git",
            "-C",
            str(root),
            "ls-files",
            "--cached",
            "--others",
            "--exclude-standard",
            "-z",
        ],
        check=True,
        capture_output=True,
    )
    return sorted(
        item.decode("utf-8", errors="surrogateescape")
        for item in result.stdout.split(b"\0")
        if item
    )


def workspace_candidates(root: Path) -> list[str]:
    """Safe fallback before git init; mirrors the repository's private exclusions."""
    allowed_docs = {
        "docs/README.md",
        "docs/公开文档范围.md",
        "docs/design/技术方案.md",
        "docs/design/端侧事件感知与可靠性增强技术方案.md",
        "docs/design/视野记忆.md",
        "docs/design/赛题背景.md",
        "docs/development/团队协作与本地运行.md",
        "docs/development/外部数据引入与预训练.md",
        "docs/development/app-update.md",
        "docs/development/assistant-android-transport-test.md",
        "docs/development/assistant-gateway-test-deployment.md",
        "docs/plans/黑客松方案收敛与实施路线.md",
        "docs/plans/符合度改造方案.md",
        "docs/references/README.md",
        "docs/requirements/README.md",
        "docs/requirements/为视力障碍玩家打造识别全屏地图的工具.pdf",
        "docs/requirements/赛题背景知识文档：为视力障碍玩家打造识别全屏地图的工具.pdf",
        "docs/requirements/【必看】视力障碍玩家全屏地图识别工具_产品需求书_V1.0.pdf",
        "docs/requirements/符合度评估书03_听野_项目6_符合度53.0pct.pdf",
        "docs/requirements/罕见无界黑客松赛手手册.md",
        "docs/research/游戏无障碍案例与需求验证.md",
        "docs/releases/0.3.0-alpha.1/CHECKLIST.md",
        "docs/releases/0.3.0-alpha.1/RELEASE_NOTES.md",
        "docs/releases/0.3.1/RELEASE_NOTES.md",
        "docs/releases/0.3.2/RELEASE_NOTES.md",
        "docs/releases/0.3.3/RELEASE_NOTES.md",
        "docs/releases/0.3.4/RELEASE_NOTES.md",
        "docs/releases/0.3.5/RELEASE_NOTES.md",
        "docs/releases/0.3.5/复测说明.md",
        "docs/releases/0.3.6/RELEASE_NOTES.md",
        "docs/releases/0.3.7/RELEASE_NOTES.md",
        "docs/releases/0.3.8/RELEASE_NOTES.md",
        "docs/releases/0.4.0/RELEASE_NOTES.md",
        "docs/releases/0.4.1/RELEASE_NOTES.md",
        "docs/releases/GITHUB发布检查清单.md",
        "docs/releases/README.md",
        "docs/releases/版本命名规范.md",
    }
    result: list[str] = []
    skip_dirs = {
        ".git", ".gradle", ".idea", ".pytest_cache", ".venv", ".vscode",
        "__pycache__", "build", "output", "deps", "video", "envsecrets",
        ".secrets", "secrets", "model-cache", "model_cache", "modelcache",
        "models-cache", "models_cache",
    }
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        rel = path.relative_to(root).as_posix()
        parts = set(path.relative_to(root).parts)
        if parts & skip_dirs:
            continue
        if rel.startswith(("data/private/", "data/raw/", "validation/private/")):
            continue
        if is_secret_file(rel):
            continue
        if rel.startswith("docs/") and rel not in allowed_docs:
            continue
        if path.name in FORBIDDEN_NAMES or path.suffix.lower() in FORBIDDEN_SUFFIXES:
            continue
        if "/build/" in f"/{rel}/" or "/.cxx/" in f"/{rel}/":
            continue
        result.append(rel)
    return sorted(result)


def scan(root: Path, candidates: list[str]) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    warnings: list[str] = []
    normalized_candidates = {item.replace("\\", "/") for item in candidates}
    for rel in candidates:
        path = root / rel
        if not path.is_file():
            continue
        normalized = rel.replace("\\", "/")
        if normalized.startswith(FORBIDDEN_PREFIXES):
            errors.append(f"private/generated path is public: {rel}")
        if path.name in FORBIDDEN_NAMES or path.suffix.lower() in FORBIDDEN_SUFFIXES:
            errors.append(f"private or binary artifact is public: {rel}")
        if is_secret_file(normalized):
            errors.append(f"secret or environment file is public: {rel}")
        if is_model_artifact(normalized, normalized_candidates):
            errors.append(f"model weight or graph is public: {rel}")
        size = path.stat().st_size
        if size > MAX_FILE_BYTES:
            errors.append(f"file exceeds 10 MiB ({size / 1024 / 1024:.1f} MiB): {rel}")
        if size > MAX_SECRET_SCAN_BYTES or path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for label, pattern in SECRET_PATTERNS:
            if pattern.search(content):
                errors.append(f"possible {label}: {rel}")
    if not any((root / name).is_file() for name in ("LICENSE", "LICENSE.md", "LICENSE.txt")):
        errors.append("LICENSE is missing; choose the project license before public release")
    if not (root / "README.md").is_file():
        errors.append("README.md is missing")
    if not (root / "CONTRIBUTING.md").is_file():
        warnings.append("CONTRIBUTING.md is missing")
    return errors, warnings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root", type=Path, default=Path(__file__).resolve().parents[1],
        help="repository root (default: parent of scripts directory)",
    )
    args = parser.parse_args()
    root = args.root.resolve()
    candidates = git_candidates(root)
    source = "git tracked and unignored files"
    if candidates is None:
        candidates = workspace_candidates(root)
        source = "workspace fallback scan"
    errors, warnings = scan(root, candidates)
    print(f"Checked {len(candidates)} candidate files using {source}.")
    for warning in warnings:
        print(f"WARNING: {warning}")
    for error in errors:
        print(f"ERROR: {error}")
    if errors:
        print(f"Public repository check failed with {len(errors)} error(s).")
        return 1
    print("Public repository check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
