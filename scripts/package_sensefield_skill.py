#!/usr/bin/env python3
"""Package the public SenseField skill using an explicit, reviewed file list."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SKILL = "sensefield-game-buddy"
FILES = (
    "SKILL.md", "LICENSE", "agents/openai.yaml", "scripts/format_cue.py",
    "references/honor-of-kings.md", "references/match3.md",
    "references/android-companion.md", "references/examples.md",
    "references/cue-format.md", "assets/demo-match3-cue.json",
    "assets/demo-match3.png", "assets/demo-draft.png",
)


def package(source: Path, destination: Path) -> dict:
    source = source.resolve()
    if source.name != SKILL:
        raise ValueError("Unexpected skill source directory")
    if destination.exists():
        raise ValueError("Output already exists; choose a new path to preserve publication evidence")
    paths = [source / name for name in FILES]
    # Only inspect this newly created skill directory. Never enumerate the repository.
    extra = [p for p in source.rglob("*")
             if p.is_file() and p.relative_to(source).as_posix() not in FILES
             and "__pycache__" not in p.parts and p.name != ".DS_Store"]
    if extra:
        raise ValueError("Unreviewed files in skill: " + ", ".join(str(p.relative_to(source)) for p in extra))
    for path in paths:
        if path.is_symlink() or not path.is_file() or not path.resolve().is_relative_to(source):
            raise ValueError("Missing file or link outside skill: " + path.name)
        if path.suffix in {".md", ".py", ".yaml", ".json"}:
            data = path.read_text(encoding="utf-8")
            if re.search(r"\bsk-[A-Za-z0-9_.-]{20,}|\bgh[pousr]_[A-Za-z0-9]{30,}|-----BEGIN .{0,20}PRIVATE KEY-----", data):
                raise ValueError("Possible credential in " + path.name)
            if any(token in data for token in ("/Users/", "validation/private/", "data/private/")):
                raise ValueError("Private local context in " + path.name)
    total = sum(path.stat().st_size for path in paths)
    if len(paths) > 30 or total > 40_000_000:
        raise ValueError("Competition limit exceeded (30 files / 40 MB)")
    text = paths[0].read_text(encoding="utf-8")
    if not text.startswith("---\nname: " + SKILL + "\n") or "[TODO:" in text:
        raise ValueError("Invalid or unfinished skill entrypoint")
    entries = []
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, path in zip(FILES, paths):
            data = path.read_bytes()
            # A single skill folder, with no repository, models, recordings or credentials.
            info = zipfile.ZipInfo(f"{SKILL}/{name}", date_time=(2026, 11, 14, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
            entries.append({"path": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    with zipfile.ZipFile(destination) as archive:
        if archive.testzip() is not None or len(archive.infolist()) != len(FILES):
            raise ValueError("ZIP verification failed")
    return {"skill": SKILL, "version": "1.0.0", "file_count": len(entries),
            "uncompressed_bytes": total, "zip_bytes": destination.stat().st_size,
            "sha256": hashlib.sha256(destination.read_bytes()).hexdigest(),
            "files": entries}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=ROOT / "skills" / SKILL)
    parser.add_argument("--output", type=Path, default=ROOT / "output/skills" / f"{SKILL}-1.0.0.zip")
    args = parser.parse_args()
    report = package(args.source, args.output)
    report_path = args.output.with_suffix(".manifest.json")
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "files"}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
