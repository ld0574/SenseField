#!/usr/bin/env python3
"""Build private, content-versioned game packs and their public integrity index."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import shutil

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "android/app/src/main/assets"
PACKS = {
    "hok": [f"minimap-yolox-nano{variant}.{suffix}" for variant in ("-320", "-dual-512") for suffix in ("param", "bin")],
    "match3": [f"match3-{name}-v1.json" for name in ("fixed-ui", "gallery", "vine", "gray", "board-feedback", "hud-glyphs")],
}

def package(source: Path, destination: Path, index: Path) -> dict:
    previous_index = destination / "game-models.json"
    previous = json.loads(previous_index.read_text()) if previous_index.is_file() else {}
    versions = previous.get("versions", {})
    for game, pack in previous.get("packs", {}).items():
        versions.setdefault(game, {})[pack["version"]] = pack
    packs = {}
    for game, names in PACKS.items():
        files = []
        for name in names:
            path = source / name
            if not path.is_file() or path.is_symlink():
                raise ValueError(f"Missing private resource: {name}")
            data = path.read_bytes()
            files.append(dict(name=name, bytes=len(data), sha256=hashlib.sha256(data).hexdigest()))
        identity = json.dumps(files, sort_keys=True, separators=(",", ":")).encode()
        version = "v1-" + hashlib.sha256(identity).hexdigest()[:20]
        folder = destination / game / version
        folder.mkdir(parents=True, exist_ok=True)
        for item in files:
            target = folder / item["name"]
            shutil.copyfile(source / item["name"], target)
            if hashlib.sha256(target.read_bytes()).hexdigest() != item["sha256"]:
                raise ValueError("Copied resource checksum mismatch")
        packs[game] = dict(version=version, files=files)
        versions.setdefault(game, {})[version] = packs[game]
    manifest = dict(schema=1, packs=packs)
    text = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"
    index.parent.mkdir(parents=True, exist_ok=True)
    index.write_text(text)
    (destination / "game-models.json").write_text(json.dumps({**manifest, "versions": versions}, ensure_ascii=False, indent=2) + "\n")
    (destination / "README.txt").write_text(
        "听野私有游戏识别资源。仅供听野客户端运行，不授予再分发、训练或开源许可。\n"
        "请部署到 Web 静态目录之外，通过 Python 服务的临时下载凭证分发。\n"
        "历史已公开的材料既有权利不受本说明追溯变更；第三方权利及许可证仍适用。\n")
    return manifest

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=ASSETS)
    parser.add_argument("--destination", type=Path, default=ROOT / "output/model-packs")
    parser.add_argument("--index", type=Path, default=ASSETS / "game-models.json")
    args = parser.parse_args()
    manifest = package(args.source, args.destination, args.index)
    print(json.dumps({game: dict(version=pack["version"], bytes=sum(f["bytes"] for f in pack["files"])) for game, pack in manifest["packs"].items()}))

if __name__ == "__main__":
    main()
