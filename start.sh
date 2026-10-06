#!/usr/bin/env bash
# Prepare this directory's Python dependencies, then run a foreground process.
set -euo pipefail
task_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
task_python="$task_dir/.venv/bin/python"
cd "$task_dir"

if ! "$task_python" -c 'import sys; assert (3, 10) <= sys.version_info[:2] < (3, 14)' >/dev/null 2>&1; then
    bootstrap_python="${SENSEFIELD_PYTHON:-python3}"
    "$bootstrap_python" -c 'import sys; assert (3, 10) <= sys.version_info[:2] < (3, 14), "需要Python 3.10至3.13"'
    printf '%s\n' '正在当前目录准备Python环境……'
    "$bootstrap_python" -m venv "$task_dir/.venv"
fi
if ! "$task_python" -c 'import fastapi, uvicorn, httpx, websockets, PIL, numpy' >/dev/null 2>&1; then
    printf '%s\n' '首次启动，正在安装后端依赖……'
    "$task_python" -m pip install "$task_dir[assistant-vision-gateway]"
fi
exec "$task_python" "$task_dir/start.py" "$@"
