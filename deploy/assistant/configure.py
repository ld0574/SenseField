#!/usr/bin/env python3
"""Interactively prepare private server configuration without echoing credentials."""
from __future__ import annotations

import argparse
import getpass
import json
import os
from pathlib import Path
import re
import secrets
from urllib.parse import urlsplit


def write_private(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    # Never replace an existing configuration or silently rotate player codes.
    with open(path, "x", encoding="utf-8", opener=lambda name, flags: os.open(name, flags, 0o600)) as file:
        file.write(text)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--codes-output", type=Path, required=True)
    parser.add_argument("--runtime", choices=("container", "native"), default="container")
    args = parser.parse_args()
    if args.output.exists() or args.codes_output.exists():
        parser.error("Configuration or player codes already exist; preserve them or move them explicitly.")
    base_url = input("视觉服务商接口地址（HTTPS）：").strip().rstrip("/")
    parsed = urlsplit(base_url)
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username is not None
            or parsed.password is not None or parsed.query or parsed.fragment):
        parser.error("Use a credential-free HTTPS provider URL.")
    model = input("模型标识 [qwen/qwen3.8-27b]：").strip() or "qwen/qwen3.8-27b"
    if re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._/-]{0,127}", model) is None:
        parser.error("Invalid provider model identifier.")
    key = getpass.getpass("视觉服务商 API Key（不会回显）：").strip()
    if not key or "\x00" in key or "\n" in key or "\r" in key:
        parser.error("A valid provider key is required.")
    tokens = [secrets.token_hex(24) for _ in range(2)]
    tls_dir = "/etc/sensefield-assistant/tls" if args.runtime == "native" else "/run/tls"
    config = {
        "ASSISTANT_GATEWAY_VISION_PROVIDER": "compatible",
        "ASSISTANT_GATEWAY_VISION_BASE_URL": base_url,
        "ASSISTANT_GATEWAY_VISION_MODEL": model,
        "ASSISTANT_GATEWAY_VISION_API_KEY": key,
        "ASSISTANT_GATEWAY_VISION_MAX_TOKENS": "256",
        "ASSISTANT_GATEWAY_DEVICE_TOKENS": ",".join(tokens),
        "ASSISTANT_GATEWAY_ASR_BACKEND": "disabled",
        "ASSISTANT_GATEWAY_ACCOUNT_CONCURRENCY": "1",
        "ASSISTANT_GATEWAY_HOST": "127.0.0.1" if args.runtime == "native" else "0.0.0.0",
        "ASSISTANT_GATEWAY_PORT": "18765",
        "ASSISTANT_GATEWAY_TLS_CERT": tls_dir + "/backend.crt",
        "ASSISTANT_GATEWAY_TLS_KEY": tls_dir + "/backend.key",
    }
    write_private(args.output, json.dumps(config, ensure_ascii=False, indent=2) + "\n")
    write_private(args.codes_output, "听野体验连接码（逐人私下提供，不上传 CDN 或 GitHub）\n\n"
                  + "\n".join(f"体验者{i + 1}：{value}" for i, value in enumerate(tokens)) + "\n")
    print("配置和两份体验连接码已保存；未输出任何密钥或连接码。")


if __name__ == "__main__":
    main()
