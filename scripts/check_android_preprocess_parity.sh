#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 ]]; then
  printf 'Usage: %s <adb-device-serial>\n' "$0" >&2
  exit 2
fi
parity_serial="$1"
parity_repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
parity_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
case "$(uname -s)" in
  Darwin) parity_host='darwin-x86_64' ;;
  Linux) parity_host='linux-x86_64' ;;
  *) printf 'This helper supports macOS and Linux.\n' >&2; exit 2 ;;
esac
parity_compiler="$parity_sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/$parity_host/bin/aarch64-linux-android29-clang++"
parity_adb="$parity_sdk/platform-tools/adb"
[[ -x "$parity_compiler" && -x "$parity_adb" ]] || {
  printf 'Set ANDROID_HOME to an SDK with NDK 28.2.13676358 and adb.\n' >&2
  exit 2
}
command -v rg >/dev/null || { printf 'ripgrep is required.\n' >&2; exit 2; }
parity_ncnn=''
while IFS= read -r parity_header; do
  case "$parity_header" in
    */ncnn-20260526-android/arm64-v8a/include/ncnn/mat.h)
      parity_ncnn="${parity_header%/include/ncnn/mat.h}"
      break
      ;;
  esac
done < <(rg --files --hidden --no-ignore "$parity_repo/android/app/.cxx" -g mat.h)
[[ -n "$parity_ncnn" && -f "$parity_ncnn/lib/libncnn.a" ]] || {
  printf 'Build the Android app once to populate the pinned ncnn cache.\n' >&2
  exit 2
}
parity_archive="$parity_ncnn/../../ncnn-20260526-android.zip"
if command -v shasum >/dev/null; then
  parity_digest="$(shasum -a 256 "$parity_archive")"
else
  parity_digest="$(sha256sum "$parity_archive")"
fi
[[ "${parity_digest%% *}" == '85b18b875488585c2d21360430e0e54abb6c04aa88094b471c20208ab55ff796' ]] || {
  printf 'Pinned ncnn archive hash does not match.\n' >&2
  exit 2
}
parity_abi="$("$parity_adb" -s "$parity_serial" shell getprop ro.product.cpu.abi | tr -d '\r')"
[[ "$parity_abi" == 'arm64-v8a' ]] || { printf 'An arm64 Android device is required.\n' >&2; exit 2; }
parity_build="$parity_repo/build/score-recovery/preprocess-parity"
mkdir -p "$parity_build"
"$parity_compiler" -O2 -std=c++17 -fopenmp -static-openmp -static-libstdc++ \
  -I"$parity_ncnn/include/ncnn" -I"$parity_repo/native/include" \
  "$parity_repo/native/tests/yolox_preprocess_parity_android.cpp" \
  "$parity_ncnn/lib/libncnn.a" -llog -landroid -o "$parity_build/yolox_preprocess_parity"
parity_remote='/data/local/tmp/sensefield-yolox-preprocess-parity'
trap '"$parity_adb" -s "$parity_serial" shell rm -f "$parity_remote" >/dev/null 2>&1 || true' EXIT
"$parity_adb" -s "$parity_serial" push "$parity_build/yolox_preprocess_parity" "$parity_remote"
"$parity_adb" -s "$parity_serial" shell "$parity_remote"
