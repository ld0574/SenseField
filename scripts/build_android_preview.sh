#!/usr/bin/env bash

set -euo pipefail

readonly PREVIEW_VERSION_NAME='0.4.0'
readonly PREVIEW_VERSION_CODE='17'
readonly PREVIEW_ABI='arm64-v8a'
readonly PREVIEW_MIN_SDK='29'
readonly PREVIEW_TARGET_SDK='35'

fail() {
  printf '错误：%s\n' "$1" >&2
  exit 1
}

usage() {
  cat <<'EOF'
用法：bash scripts/build_android_preview.sh

默认只构建并核验 Debug candidate，输出文件名包含 debug-candidate。
如需构建签名的 release candidate，请在环境变量中同时提供：

  SENSEFIELD_KEYSTORE_PATH
  SENSEFIELD_KEY_ALIAS
  SENSEFIELD_KEYSTORE_PASSWORD
  SENSEFIELD_KEY_PASSWORD

脚本只读取已有发布 keystore，不创建、复制或提交发布 keystore；无签名参数时沿用 Android Gradle 的标准 debug signing，也不会调用 GitHub Release。
EOF
}

while (($#)); do
  case "$1" in
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
android_dir="$repo_root/android"
build_gradle="$android_dir/app/build.gradle"
gradle_wrapper="$android_dir/gradlew"

[[ -x "$gradle_wrapper" ]] || fail "找不到可执行的 android/gradlew。"
[[ -f "$build_gradle" ]] || fail "找不到 Android 版本配置：$build_gradle"
[[ ! -f "$android_dir/app/src/debug/res/raw/assistant_transport_test_ca.crt" ]] || fail \
  "助手传输测试的临时 CA 仍在，请停止测试并清理后再构建交付包。"

configured_version_code="$(sed -nE 's/^[[:space:]]*versionCode[[:space:]]+([0-9]+).*/\1/p' "$build_gradle" | head -n 1)"
configured_version_name="$(sed -nE "s/^[[:space:]]*versionName[[:space:]]+['\"]([^'\"]+)['\"].*/\1/p" "$build_gradle" | head -n 1)"
configured_min_sdk="$(sed -nE 's/^[[:space:]]*minSdk[[:space:]]+([0-9]+).*/\1/p' "$build_gradle" | head -n 1)"
configured_target_sdk="$(sed -nE 's/^[[:space:]]*targetSdk[[:space:]]+([0-9]+).*/\1/p' "$build_gradle" | head -n 1)"
[[ "$configured_version_name" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail \
  "versionName 必须使用 a.b.c 三段数字；用途标签请写在交付说明中。"
[[ "$configured_version_code" == "$PREVIEW_VERSION_CODE" ]] || fail \
  "android/app/build.gradle 的 versionCode 为 ${configured_version_code:-空值}，预期为 $PREVIEW_VERSION_CODE。"
[[ "$configured_version_name" == "$PREVIEW_VERSION_NAME" ]] || fail \
  "android/app/build.gradle 的 versionName 为 ${configured_version_name:-空值}，预期为 $PREVIEW_VERSION_NAME。"
[[ "$configured_min_sdk" == "$PREVIEW_MIN_SDK" ]] || fail \
  "android/app/build.gradle 的 minSdk 为 ${configured_min_sdk:-空值}，预期为 $PREVIEW_MIN_SDK。"
[[ "$configured_target_sdk" == "$PREVIEW_TARGET_SDK" ]] || fail \
  "android/app/build.gradle 的 targetSdk 为 ${configured_target_sdk:-空值}，预期为 $PREVIEW_TARGET_SDK。"
grep -Eq "abiFilters.*(sensefieldAbi|'$PREVIEW_ABI')" "$build_gradle" || fail \
  "android/app/build.gradle 未声明当前预览需要的 ABI 配置。"

signing_values=(
  "${SENSEFIELD_KEYSTORE_PATH:-}"
  "${SENSEFIELD_KEY_ALIAS:-}"
  "${SENSEFIELD_KEYSTORE_PASSWORD:-}"
  "${SENSEFIELD_KEY_PASSWORD:-}"
)
provided_signing_values=0
for value in "${signing_values[@]}"; do
  if [[ -n "${value//[[:space:]]/}" ]]; then
    provided_signing_values=$((provided_signing_values + 1))
  fi
done

if ((provided_signing_values != 0 && provided_signing_values != 4)); then
  fail "签名参数不完整。请同时设置四个 SENSEFIELD_* 变量，或全部留空以构建 Debug candidate。"
fi

signed_candidate=0
if ((provided_signing_values == 4)); then
  signed_candidate=1
  [[ -f "$SENSEFIELD_KEYSTORE_PATH" ]] || fail \
    "SENSEFIELD_KEYSTORE_PATH 不存在或不是文件；脚本不会创建 keystore。"
  # Gradle resolves relative file() paths from the app project, while this
  # wrapper validates paths from the caller's directory. Export one canonical
  # absolute path so both checks always refer to the same keystore.
  keystore_dir="$(cd "$(dirname "$SENSEFIELD_KEYSTORE_PATH")" && pwd -P)"
  export SENSEFIELD_KEYSTORE_PATH="$keystore_dir/$(basename "$SENSEFIELD_KEYSTORE_PATH")"
fi

if [[ -n "${JAVA_HOME:-}" ]]; then
  [[ -x "$JAVA_HOME/bin/java" ]] || fail "JAVA_HOME 未指向包含 bin/java 的 JDK。"
elif ! command -v java >/dev/null 2>&1; then
  fail "找不到 Java。请设置 JAVA_HOME，或把 JDK 17 放入 PATH。"
fi

# Prefer an explicitly selected SDK, then local.properties, then the usual
# macOS/Linux SDK locations. The script never writes local.properties.
sdk_candidates=()
if [[ -n "${ANDROID_HOME:-}" ]]; then
  sdk_candidates+=("$ANDROID_HOME")
fi
if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
  sdk_candidates+=("$ANDROID_SDK_ROOT")
fi
if [[ -f "$android_dir/local.properties" ]]; then
  local_sdk="$(sed -n 's/^sdk\.dir=//p' "$android_dir/local.properties" | tail -n 1)"
  local_sdk="${local_sdk//\\:/\:}"
  local_sdk="${local_sdk//\\ / }"
  [[ -n "$local_sdk" ]] && sdk_candidates+=("$local_sdk")
fi
if [[ -n "${HOME:-}" ]]; then
  sdk_candidates+=("$HOME/Library/Android/sdk" "$HOME/Android/Sdk")
fi

apksigner_bin=""
if command -v apksigner >/dev/null 2>&1; then
  apksigner_bin="$(command -v apksigner)"
fi
for sdk_root in "${sdk_candidates[@]}"; do
  [[ -d "$sdk_root" ]] || continue
  for candidate in "$sdk_root"/build-tools/*/apksigner; do
    [[ -x "$candidate" ]] || continue
    apksigner_bin="$candidate"
  done
  [[ -n "$apksigner_bin" ]] && break
done
[[ -n "$apksigner_bin" ]] || fail \
  "找不到 apksigner。请安装 Android SDK Build-Tools，或把 apksigner 放入 PATH。"

if ((signed_candidate)); then
  variant='release'
  gradle_task=':app:assembleRelease'
  candidate_label='signed-candidate'
  source_apk="$android_dir/app/build/outputs/apk/release/app-release.apk"
else
  variant='debug'
  gradle_task=':app:assembleDebug'
  candidate_label='debug-candidate'
  source_apk="$android_dir/app/build/outputs/apk/debug/app-debug.apk"
fi

preview_dir="$android_dir/app/build/outputs/preview"
candidate_apk="$preview_dir/sensefield-${PREVIEW_VERSION_NAME}-${PREVIEW_ABI}-${candidate_label}.apk"
# Remove any prior handoff before starting a new build. A failed build must not
# leave an older candidate at the path that the release checklist uploads.
rm -rf "$preview_dir"

printf '==> 构建 SenseField %s（versionCode %s，%s，%s）\n' \
  "$PREVIEW_VERSION_NAME" "$PREVIEW_VERSION_CODE" "$PREVIEW_ABI" "$variant"
if ((signed_candidate)); then
  printf '%s\n' '==> 使用已有发布 keystore；不会打印密码或创建发布 keystore。'
else
  printf '%s\n' '==> 未提供完整签名环境变量；只构建 Debug candidate，使用标准 debug signing。'
fi

(
  cd "$android_dir"
  ./gradlew --no-daemon "-PsensefieldAbi=$PREVIEW_ABI" "$gradle_task"
)

[[ -f "$source_apk" ]] || fail "Gradle 完成但没有生成预期 APK：$source_apk"

mkdir -p "$preview_dir"
cp "$source_apk" "$candidate_apk"

printf '==> 核验 APK 签名：%s\n' "$candidate_apk"
"$apksigner_bin" verify --verbose --print-certs "$candidate_apk"

apk_sha256=''
if command -v shasum >/dev/null 2>&1; then
  apk_sha256="$(shasum -a 256 "$candidate_apk" | awk '{print $1}')"
elif command -v sha256sum >/dev/null 2>&1; then
  apk_sha256="$(sha256sum "$candidate_apk" | awk '{print $1}')"
else
  fail "找不到 shasum 或 sha256sum，无法输出 APK SHA-256。"
fi
apk_bytes="$(wc -c < "$candidate_apk" | tr -d '[:space:]')"
checksum_file="${candidate_apk}.sha256"
printf '%s  %s\n' "$apk_sha256" "$(basename "$candidate_apk")" > "$checksum_file"

printf '\n完成：%s\n' "$candidate_apk"
printf '候选类型：%s\n' "$candidate_label"
printf '文件大小：%s bytes\n' "$apk_bytes"
printf 'SHA-256：%s\n' "$apk_sha256"
printf '校验文件：%s\n' "$checksum_file"
printf '%s\n' '脚本未创建或上传发布 keystore，也未发布 GitHub Release。'
