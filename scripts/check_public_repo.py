#!/usr/bin/env python3
"""Check files intended for the public repository before committing or pushing."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath


MAX_FILE_BYTES = 10 * 1024 * 1024
MAX_SECRET_SCAN_BYTES = 2 * 1024 * 1024
FORBIDDEN_PREFIXES = (
    "data/private/",
    "data/raw/",
    "video/",
    "validation/private/",
    "build/",
    "android/.gradle/",
    "android/.idea/",
)
FORBIDDEN_NAMES = {
    ".DS_Store",
    ".Rhistory",
    "annotations.sqlite3",
    "local.properties",
    "keystore.properties",
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
)


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
        "docs/团队协作与本地运行.md",
        "docs/GITHUB发布检查清单.md",
    }
    result: list[str] = []
    skip_dirs = {
        ".git", ".gradle", ".idea", ".pytest_cache", ".venv", ".vscode",
        "__pycache__", "build", "video",
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
