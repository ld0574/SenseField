"""把社区服务跑起来：``python -m mapassist.community --host 127.0.0.1 --port 8765``

uvicorn 只在这里 import——``store.py`` / ``app.py`` 本身不依赖它，单测照旧不用装。
两者都是 pyproject.toml 里已声明的版本，不算新增依赖。

默认只听回环地址：服务跑在手机上时，端侧的缺省地址 http://127.0.0.1:8765 正好对得上，
既不用改任何配置，也不把端口暴露到局域网。要让同网段别的设备访问才加 ``--host 0.0.0.0``。
"""

from __future__ import annotations

import argparse
import os
from pathlib import Path

DEFAULT_DB_NAME = "sensefield_community.db"


def default_db() -> str:
    """默认把库放在家目录下；手机上（Termux）就是 ~/sensefield_community.db。"""
    return str(Path(os.path.expanduser("~")) / DEFAULT_DB_NAME)


def main() -> None:
    parser = argparse.ArgumentParser(description="听野社区发帖服务")
    parser.add_argument("--host", default="127.0.0.1",
                        help="监听地址，默认只听本机回环")
    parser.add_argument("--port", type=int, default=8765,
                        help="端口，需与端侧 community_endpoint 一致（缺省 8765）")
    parser.add_argument("--db", default=default_db(),
                        help=f"sqlite 库路径，默认 ~/{DEFAULT_DB_NAME}")
    args = parser.parse_args()

    try:
        import uvicorn
    except ImportError:
        raise SystemExit(
            "缺 uvicorn，先装：pip install 'uvicorn==0.54.0' 'fastapi==0.142.2'"
        ) from None

    from .app import create_app
    from .store import CommunityStore

    Path(args.db).parent.mkdir(parents=True, exist_ok=True)
    app = create_app(CommunityStore(args.db))
    print(f"社区服务 http://{args.host}:{args.port}/docs  数据 {args.db}", flush=True)
    uvicorn.run(app, host=args.host, port=args.port, log_level="info")


if __name__ == "__main__":
    main()
