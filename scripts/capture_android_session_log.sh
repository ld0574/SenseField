#!/usr/bin/env bash

set -euo pipefail

fail() {
  printf '错误：%s\n' "$1" >&2
  exit 1
}

usage() {
  cat <<'EOF'
用法：bash scripts/capture_android_session_log.sh [--serial 设备序列号] [--output 日志路径]

启动前会清空设备 logcat。在手机上完成一次授权、测试和“停止”后，
回到这个终端按 Ctrl+C。
EOF
}

requested_serial=""
output_file=""
while (($#)); do
  case "$1" in
    --serial)
      (($# >= 2)) || fail "--serial 后需要设备序列号。"
      requested_serial="$2"
      shift 2
      ;;
    --output)
      (($# >= 2)) || fail "--output 后需要日志路径。"
      output_file="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      fail "无法识别参数：$1"
      ;;
  esac
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
adb_bin="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}/platform-tools/adb"
[[ -x "$adb_bin" ]] || fail "找不到 adb，请设置 ANDROID_HOME 或 ANDROID_SDK_ROOT。"

devices=()
while read -r serial state _; do
  [[ "${state:-}" == "device" ]] && devices+=("$serial")
done < <("$adb_bin" devices -l | tail -n +2)

if [[ -n "$requested_serial" ]]; then
  selected=""
  for serial in "${devices[@]}"; do
    [[ "$serial" == "$requested_serial" ]] && selected="$serial"
  done
  [[ -n "$selected" ]] || fail "设备 $requested_serial 未连接或未授权。"
else
  ((${#devices[@]} == 1)) || fail \
    "需要恰好一台已授权设备；当前为 ${#devices[@]} 台。多设备时请传 --serial。"
  selected="${devices[0]}"
fi

if [[ -z "$output_file" ]]; then
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  output_file="$repo_root/validation/private/android-session-$stamp.log"
elif [[ "$output_file" != /* ]]; then
  output_file="$repo_root/$output_file"
fi
mkdir -p "$(dirname "$output_file")"

finish() {
  trap - INT TERM EXIT
  printf '\n日志已保存：%s\n' "$output_file"
  if [[ -s "$output_file" ]]; then
    printf 'SHA-256：'
    shasum -a 256 "$output_file" | awk '{print $1}'
    summaries="$(rg -c 'SessionSummary' "$output_file" 2>/dev/null || true)"
    printf 'SessionSummary 数量：%s\n' "${summaries:-0}"
  fi
}
trap finish INT TERM EXIT

printf '目标设备：%s\n' "$selected"
printf '日志文件：%s\n' "$output_file"
printf '现在可以在手机上开始测试。点击“停止”后，回到此处按 Ctrl+C。\n'

"$adb_bin" -s "$selected" logcat -c
"$adb_bin" -s "$selected" logcat -v threadtime \
  'MapAssistCapture:I' 'MapAssistAudio:I' 'MapAssistYolox:I' '*:S' \
  | tee "$output_file"
