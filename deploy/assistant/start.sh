#!/usr/bin/env bash
# Prepare dependencies and start the assistant in the background by default.
set -euo pipefail
deploy_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
app_dir="$(cd "$deploy_dir/../.." && pwd)"
task_python="$app_dir/.venv/bin/python"
cd "$app_dir"

# Management and help do not need to install the server's dependencies.
case "${1:-start}" in
    stop|status|logs|-h|--help)
        [[ -x "$task_python" ]] || task_python="${SENSEFIELD_PYTHON:-python3}"
        exec "$task_python" "$deploy_dir/start.py" "$@"
        ;;
esac

if ! "$task_python" -c 'import sys; assert (3, 10) <= sys.version_info[:2] < (3, 14)' >/dev/null 2>&1; then
    bootstrap_python="${SENSEFIELD_PYTHON:-python3}"
    "$bootstrap_python" -c 'import sys; assert (3, 10) <= sys.version_info[:2] < (3, 14), "需要Python 3.10至3.13"'
    printf '%s\n' '正在应用目录准备Python环境……'
    "$bootstrap_python" -m venv "$app_dir/.venv"
fi
if ! "$task_python" -c 'import fastapi, uvicorn, httpx, websockets, PIL, numpy' >/dev/null 2>&1; then
    printf '%s\n' '首次启动，正在安装后端依赖……'
    "$task_python" -m pip install "$app_dir[assistant-vision-gateway]"
fi
exec "$task_python" "$deploy_dir/start.py" "$@"
