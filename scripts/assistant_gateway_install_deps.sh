#!/usr/bin/env bash
set -euo pipefail

vision_only=0
while (($#)); do
  case "$1" in
    --vision-only)
      if ((vision_only)); then
        echo "Usage: $0 [--vision-only]" >&2
        exit 2
      fi
      vision_only=1
      ;;
    *)
      echo "Usage: $0 [--vision-only]" >&2
      exit 2
      ;;
  esac
  shift
done

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

install_with_wheelhouse() {
  if [[ -n "${ASSISTANT_GATEWAY_WHEELHOUSE:-}" ]]; then
    "${installer[@]}" --find-links "$ASSISTANT_GATEWAY_WHEELHOUSE" "$@"
  else
    "${installer[@]}" "$@"
  fi
}

if ((vision_only)); then
  install_with_wheelhouse -e ".[assistant-vision-gateway]"
  exit 0
fi

if [[ "$platform" == "linux" ]]; then
  install_with_wheelhouse \
    --index-url https://download.pytorch.org/whl/cpu \
    --extra-index-url https://pypi.org/simple \
    "torch==2.8.0+cpu"
  install_with_wheelhouse \
    --index-url https://pypi.org/simple \
    --extra-index-url https://download.pytorch.org/whl/cpu \
    -e ".[assistant-gateway]"
else
  install_with_wheelhouse -e ".[assistant-gateway]"
fi
