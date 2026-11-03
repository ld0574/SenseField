#!/usr/bin/env python3
"""Import the bundled private game resources, retaining older indexed versions."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile


DEFAULT_MODELS_DIR = Path("/data/wwwroot/sf/game-models")
BUNDLED_INDEX_NAME = "bundle-index.json"


def versions(index: dict) -> dict:
    if index.get("schema") != 1 or not isinstance(index.get("packs"), dict):
        raise ValueError("资源索引格式无效")
    result = {}

    def add(game, version, pack):
        if (not isinstance(game, str) or not re.fullmatch(r"[a-z][a-z0-9-]{0,63}", game)
                or not isinstance(version, str)
                or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", version)
                or not isinstance(pack, dict) or pack.get("version") != version):
            raise ValueError("资源版本格式无效")
        files = pack.get("files")
        if not isinstance(files, list) or not 1 <= len(files) <= 64:
            raise ValueError("资源文件清单无效")
        names = set()
        for item in files:
            name = item.get("name", "")
            if (not isinstance(name, str)
                    or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,159}", name)
                    or ".." in name or name in names
                    or type(item.get("bytes")) is not int
                    or not 1 <= item["bytes"] <= 512 * 1024 * 1024
                    or not re.fullmatch(r"[0-9a-f]{64}", item.get("sha256", ""))):
                raise ValueError("资源文件条目无效")
            names.add(name)
        key = (game, version)
        if key in result and result[key] != pack:
            raise ValueError("同一资源版本的索引内容冲突")
        result[key] = pack

    for game, retained in index.get("versions", {}).items():
        for version, pack in retained.items():
            add(game, version, pack)
    for game, pack in index["packs"].items():
        add(game, pack["version"], pack)
    return result


def resource(root: Path, game: str, version: str, name: str) -> Path:
    path = root / game / version / name
    if path.is_symlink() or not path.resolve().is_relative_to(root):
        raise ValueError("资源路径越界或使用了符号链接")
    return path


def matches(path: Path, item: dict) -> bool:
    if not path.is_file() or path.stat().st_size != item["bytes"]:
        return False
    with path.open("rb") as stream:
        digest = hashlib.sha256()
        for block in iter(lambda: stream.read(65536), b""):
            digest.update(block)
    return digest.hexdigest() == item["sha256"]


def validate(root: Path, entries: dict) -> None:
    for (game, version), pack in entries.items():
        for item in pack["files"]:
            if not matches(resource(root, game, version, item["name"]), item):
                raise ValueError(f"资源缺失或校验失败：{game}/{version}/{item['name']}")


def atomic_write(path: Path, data: bytes) -> None:
    with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".import-", delete=False) as stream:
        pending = Path(stream.name)
        try:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        except BaseException:
            pending.unlink(missing_ok=True)
            raise
    try:
        os.replace(pending, path)
    finally:
        pending.unlink(missing_ok=True)


def install(source: Path, target: Path, *, index_name: str = "game-models.json") -> dict:
    source, target = source.resolve(), target.resolve()
    if index_name not in {"game-models.json", BUNDLED_INDEX_NAME}:
        raise ValueError("不支持的资源索引文件名")
    in_place = source == target and index_name == BUNDLED_INDEX_NAME
    if not in_place and (source.is_relative_to(target) or target.is_relative_to(source)):
        raise ValueError("来源目录与目标目录不能重叠")
    # This deployment keeps the private store beside the source. Nginx must
    # deny /game-models and /game-models/; only the ticketed API serves files.
    if target != DEFAULT_MODELS_DIR.resolve():
        for webroot in (Path("/data/wwwroot"), Path("/var/www"), Path("/usr/share/nginx/html")):
            if target.is_relative_to(webroot.resolve()):
                raise ValueError("网站目录内只允许已配置静态拒绝访问的 /data/wwwroot/sf/game-models")
    incoming = json.loads((source / index_name).read_text(encoding="utf-8"))
    new_versions = versions(incoming)
    validate(source, new_versions)
    index_path = target / "game-models.json"
    if index_path.is_symlink():
        raise ValueError("目标资源索引不能为符号链接")
    previous = json.loads(index_path.read_text(encoding="utf-8")) if index_path.exists() else {"schema": 1, "packs": {}}
    old_versions = versions(previous)
    validate(target, old_versions)
    for key, pack in new_versions.items():
        if key in old_versions and old_versions[key] != pack:
            raise ValueError("同一内容版本出现不同索引，已停止更新")
        for item in pack["files"]:
            destination = resource(target, *key, item["name"])
            if destination.exists() and not matches(destination, item):
                raise ValueError(f"目标已有冲突文件，已停止更新：{destination}")
    target.mkdir(mode=0o700, parents=True, exist_ok=True)
    for (game, version), pack in new_versions.items():
        for item in pack["files"]:
            destination = resource(target, game, version, item["name"])
            if not destination.exists():
                destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                atomic_write(destination, resource(source, game, version, item["name"]).read_bytes())
    merged = dict(previous)
    merged["packs"] = {**previous["packs"], **incoming["packs"]}
    merged["versions"] = {}
    for (game, version), pack in {**old_versions, **new_versions}.items():
        merged["versions"].setdefault(game, {})[version] = pack
    validate(target, versions(merged))
    encoded = (json.dumps(merged, ensure_ascii=False, indent=2) + "\n").encode()
    if not index_path.exists() or index_path.read_bytes() != encoded:
        atomic_write(index_path, encoded)
    return {"model_directory": str(target), "imported_payloads": sum(len(p["files"]) for p in new_versions.values()),
            "retained_versions": len(set(old_versions) - set(new_versions)), "download_key_unchanged": True}


def main() -> None:
    parser = argparse.ArgumentParser(description="导入完整包内的私有模型，保留原索引中的旧版本和 .download-key。")
    parser.add_argument("--models-dir", type=Path, default=DEFAULT_MODELS_DIR,
                        help="模型目标目录（默认 /data/wwwroot/sf/game-models）")
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parent / "game-models",
                        help="完整更新包内的 game-models 目录")
    args = parser.parse_args()
    os.umask(0o077)
    try:
        result = install(args.source, args.models_dir)
    except (OSError, ValueError, TypeError, KeyError, AttributeError) as error:
        parser.exit(1, f"模型未完成导入：{error}\n")
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
