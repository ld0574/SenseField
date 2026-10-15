#!/usr/bin/env python3
"""Recognize previous native units and preserve credentials during migration."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import tempfile
import uuid


def normalized_unit(text: str) -> list[str]:
    return [line.strip() for line in text.splitlines()
            if line.strip() and not line.lstrip().startswith(("#", ";"))]


def recognized_service(unit: Path, template: Path) -> bool:
    """Use the previous service's own application path, not the new target's marker."""
    if unit.is_symlink() or not unit.is_file():
        return False
    lines = normalized_unit(unit.read_text(encoding="utf-8"))
    directories = [line.removeprefix("WorkingDirectory=")
                   for line in lines if line.startswith("WorkingDirectory=")]
    if len(directories) != 1:
        return False
    directory = directories[0]
    if (not re.fullmatch(r"/[A-Za-z0-9._/-]+", directory)
            or directory == "/" or ".." in Path(directory).parts):
        return False
    proxy_env = ("Environment=ASSISTANT_GATEWAY_LOCAL_TLS_PROXY=1"
                 if "Environment=ASSISTANT_GATEWAY_LOCAL_TLS_PROXY=1" in lines else "")
    expected = template.read_text(encoding="utf-8").replace(
        "@APP_DIR@", directory).replace("@LOCAL_PROXY_ENV@", proxy_env)
    # Recognize the previous package layout without relaxing the unit's
    # user, environment, executable or isolation settings.
    legacy = expected.replace(f"{directory}/deploy/assistant/serve.py",
                              f"{directory}/serve.py")
    state = ("StateDirectory=sensefield-assistant\nStateDirectoryMode=0700\n"
             "Environment=ASSISTANT_GATEWAY_PUBLIC_QUOTA_DB=/var/lib/sensefield-assistant/public-quota.sqlite3\n")
    return any(lines == normalized_unit(candidate) for candidate in
               (expected, legacy, expected.replace(state, ""), legacy.replace(state, "")))


def migrate_configuration(path: Path, transport: str) -> bool:
    if path.is_symlink() or not path.is_file():
        raise ValueError("配置必须是普通文件，不能是符号链接。")
    previous = path.read_bytes()
    values = json.loads(previous)
    if not isinstance(values, dict) or not values:
        raise ValueError("配置必须是非空 JSON 对象。")
    for key, value in values.items():
        if (not isinstance(key, str) or not isinstance(value, str)
                or re.fullmatch(r"(?:ASSISTANT_GATEWAY_[A-Z0-9_]+|ZHIPU_API_KEY)", key) is None
                or "\x00" in value):
            raise ValueError("配置含不支持的条目。")
    updated = dict(values)
    updated.update(ASSISTANT_GATEWAY_HOST="127.0.0.1", ASSISTANT_GATEWAY_PORT="18765",
                   ASSISTANT_GATEWAY_ASR_BACKEND="disabled")
    if transport == "local-proxy":
        updated.pop("ASSISTANT_GATEWAY_TLS_CERT", None)
        updated.pop("ASSISTANT_GATEWAY_TLS_KEY", None)
        updated["ASSISTANT_GATEWAY_LOCAL_TLS_PROXY"] = "1"
    elif transport == "backend-tls":
        updated.pop("ASSISTANT_GATEWAY_LOCAL_TLS_PROXY", None)
        updated["ASSISTANT_GATEWAY_TLS_CERT"] = "/etc/sensefield-assistant/tls/backend.crt"
        updated["ASSISTANT_GATEWAY_TLS_KEY"] = "/etc/sensefield-assistant/tls/backend.key"
    else:
        raise ValueError("不支持的传输模式。")
    if updated == values:
        return False
    original_stat = path.stat()
    backup = path.with_name(path.name + ".before-migration-" + uuid.uuid4().hex)
    # A root-run installer makes this backup root-owned and owner-readable only.
    with open(backup, "xb", opener=lambda name, flags: os.open(name, flags, 0o600)) as stream:
        stream.write(previous)
        stream.flush()
        os.fsync(stream.fileno())
    temporary_path = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=path.parent,
                                         prefix=".gateway-migration-", delete=False) as stream:
            temporary_path = Path(stream.name)
            json.dump(updated, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary_path, 0o400)
        if (temporary_path.stat().st_uid, temporary_path.stat().st_gid) != (
                original_stat.st_uid, original_stat.st_gid):
            os.chown(temporary_path, original_stat.st_uid, original_stat.st_gid)
        os.replace(temporary_path, path)
    finally:
        if temporary_path is not None:
            temporary_path.unlink(missing_ok=True)
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    actions = parser.add_subparsers(dest="action", required=True)
    check = actions.add_parser("check-service")
    check.add_argument("--unit", type=Path, required=True)
    check.add_argument("--template", type=Path, required=True)
    migrate = actions.add_parser("migrate-config")
    migrate.add_argument("--config", type=Path, required=True)
    migrate.add_argument("--transport", choices=("local-proxy", "backend-tls"), required=True)
    args = parser.parse_args()
    try:
        if args.action == "check-service":
            return 0 if recognized_service(args.unit, args.template) else 1
        changed = migrate_configuration(args.config, args.transport)
    except (OSError, ValueError):
        raise SystemExit("迁移检查失败；原文件已保留，请核对文件格式和权限。") from None
    if changed:
        print("已私下备份并迁移传输字段；上游密钥与设备连接码保持不变。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
