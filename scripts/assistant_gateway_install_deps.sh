#!/usr/bin/env bash
set -euo pipefail

python_bin="${PYTHON:-python3}"
"$python_bin" - <<'PY'
import sys
if not ((3, 10) <= sys.version_info[:2] < (3, 14)):
    raise SystemExit("Assistant gateway dependencies require Python 3.10 through 3.13.")
PY

if "$python_bin" -m pip --version >/dev/null 2>&1; then
  installer=("$python_bin" -m pip install)
elif command -v uv >/dev/null 2>&1; then
  installer=(uv pip install --python "$python_bin")
else
  echo "Install pip or uv for the selected Python environment." >&2
  exit 2
fi

platform="$($python_bin -c 'import sys; print(sys.platform)')"
wheelhouse_args=()
if [[ -n "${ASSISTANT_GATEWAY_WHEELHOUSE:-}" ]]; then
  wheelhouse_args=(--find-links "$ASSISTANT_GATEWAY_WHEELHOUSE")
fi

if [[ "$platform" == "linux" ]]; then
  "${installer[@]}" \
    --index-url https://download.pytorch.org/whl/cpu \
    --extra-index-url https://pypi.org/simple \
    "${wheelhouse_args[@]}" \
    "torch==2.8.0+cpu"
  "${installer[@]}" \
    --index-url https://pypi.org/simple \
    --extra-index-url https://download.pytorch.org/whl/cpu \
    "${wheelhouse_args[@]}" \
    -e ".[assistant-gateway]"
else
  "${installer[@]}" -e ".[assistant-gateway]"
fi
