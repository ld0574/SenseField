#!/usr/bin/env python3
"""Start the vision assistant directly behind the existing local HTTPS proxy."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import runpy
import sys


ROOT = Path(__file__).resolve().parent


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="直接启动听野助手后端。")
    parser.add_argument("--config", type=Path,
                        default=Path("/etc/sensefield-assistant/gateway.json"))
    parser.add_argument("--port", type=int, default=18765)
    args = parser.parse_args(argv)
    if not 1 <= args.port <= 65535:
        parser.error("端口必须为1至65535。")

    # Read source from the unpacked directory, so starting does not depend on
    # the path of a previously installed systemd service or Python package.
    sys.path.insert(0, str(ROOT))
    sys.path.insert(0, str(ROOT / "python"))
    try:
        loader = runpy.run_path(str(ROOT / "scripts/assistant_gateway_container_entrypoint.py"))
        loader["load_configuration"](args.config)
    except (OSError, ValueError):
        parser.error(f"无法读取有效的私有配置：{args.config}；请检查文件和当前用户的读取权限。")

    # Reuse credentials unchanged. Transport overrides exist only in this
    # process; the original JSON, tokens, and any old TLS files stay intact.
    for key in ("ASSISTANT_GATEWAY_TLS_CERT", "ASSISTANT_GATEWAY_TLS_KEY"):
        os.environ.pop(key, None)
    os.environ.update(ASSISTANT_GATEWAY_LOCAL_TLS_PROXY="1",
                      ASSISTANT_GATEWAY_HOST="127.0.0.1",
                      ASSISTANT_GATEWAY_PORT=str(args.port),
                      ASSISTANT_GATEWAY_ASR_BACKEND="disabled")
    from mapassist.assistant_gateway.cli import main as serve
    sys.argv = ["听野助手", "--behind-local-proxy", "--host", "127.0.0.1",
                "--port", str(args.port)]
    print(f"正在启动听野助手：http://127.0.0.1:{args.port}；按Ctrl+C停止。", flush=True)
    serve()


if __name__ == "__main__":
    main()
