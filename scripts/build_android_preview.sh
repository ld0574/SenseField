#!/usr/bin/env bash

set -euo pipefail

readonly PREVIEW_VERSION_NAME='0.4.5'
readonly PREVIEW_VERSION_CODE='22'
readonly DEFAULT_UPDATE_MANIFEST_URL='https://888413.xyz/apk/latest.json'
readonly DEFAULT_UPDATE_APK_URL="https://gitee.com/leda/SenseField/releases/download/${PREVIEW_VERSION_NAME}/sensefieldv${PREVIEW_VERSION_NAME}.apk"
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

默认版本清单：https://888413.xyz/apk/latest.json
APK 默认从听野 Gitee Release 下载。如需更换清单地址，设置 SENSEFIELD_UPDATE_MANIFEST_URL。
构建后生成 output/releases/<版本>/gitee-upload/ 中的唯一 APK 和 cdn-upload/latest.json。
更换 APK 地址时设置 SENSEFIELD_UPDATE_APK_URL；允许同源 HTTPS，或固定清单搭配听野 Gitee Release。

只构建并核验 Release candidate，请在环境变量中同时提供现有签名：

  SENSEFIELD_KEYSTORE_PATH
  SENSEFIELD_KEY_ALIAS
  SENSEFIELD_KEYSTORE_PASSWORD
  SENSEFIELD_KEY_PASSWORD

脚本只读取已有 keystore，不创建、复制或提交 keystore。缺少签名或完整内置语音时停止，不退回 Debug。
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
[[ ! -f "$android_dir/app/src/debug/res/raw/app_update_test_ca.crt" ]] || fail \
  "升级测试的临时 CA 仍在，请停止测试并清理后再构建交付包。"
[[ -z "${ORG_GRADLE_PROJECT_sensefieldTestVersionCode:-}${ORG_GRADLE_PROJECT_sensefieldTestVersionName:-}${ORG_GRADLE_PROJECT_sensefieldUpdateManifestUrl:-}" ]] || fail \
  "交付构建不能继承升级测试的版本或更新源覆盖参数。"
export SENSEFIELD_UPDATE_MANIFEST_URL="${SENSEFIELD_UPDATE_MANIFEST_URL:-$DEFAULT_UPDATE_MANIFEST_URL}"
export SENSEFIELD_UPDATE_APK_URL="${SENSEFIELD_UPDATE_APK_URL:-$DEFAULT_UPDATE_APK_URL}"

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

if ((provided_signing_values != 4)); then
  fail "发布必须提供四个 SENSEFIELD_* 签名变量；不得退回 Debug。请沿用 0.4.3 的证书。"
fi

[[ -f "$SENSEFIELD_KEYSTORE_PATH" ]] || fail \
  "SENSEFIELD_KEYSTORE_PATH 不存在或不是文件；脚本不会创建 keystore。"
keystore_dir="$(cd "$(dirname "$SENSEFIELD_KEYSTORE_PATH")" && pwd -P)"
export SENSEFIELD_KEYSTORE_PATH="$keystore_dir/$(basename "$SENSEFIELD_KEYSTORE_PATH")"

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
  if [[ -n "$apksigner_bin" ]]; then
    # The SDK used for signature tools must also reach AGP, even without local.properties.
    export ANDROID_HOME="$sdk_root"
    break
  fi
done
[[ -n "$apksigner_bin" ]] || fail \
  "找不到 apksigner。请安装 Android SDK Build-Tools，或把 apksigner 放入 PATH。"

variant='release'
gradle_task=':app:assembleRelease'
candidate_label='signed-release-candidate'
source_apk="$android_dir/app/build/outputs/apk/release/app-release.apk"

preview_dir="$repo_root/output/releases/${PREVIEW_VERSION_NAME}"
candidate_apk="$preview_dir/gitee-upload/sensefieldv${PREVIEW_VERSION_NAME}.apk"
python3 "$repo_root/scripts/prepare_bundled_speech.py" verify
# Remove any prior handoff before starting a new build. A failed build must not
# leave an older candidate at the path that the release checklist uploads.
rm -f "$candidate_apk" "$preview_dir/cdn-upload/latest.json"

printf '==> 构建 SenseField %s（versionCode %s，%s，%s）\n' \
  "$PREVIEW_VERSION_NAME" "$PREVIEW_VERSION_CODE" "$PREVIEW_ABI" "$variant"
printf '%s\n' '==> 使用已有发布 keystore；不会打印密码或创建发布 keystore。'

(
  cd "$android_dir"
  ./gradlew --no-daemon "-PsensefieldAbi=$PREVIEW_ABI" \
    "-PsensefieldUpdateManifestUrl=$SENSEFIELD_UPDATE_MANIFEST_URL" "$gradle_task"
)

[[ -f "$source_apk" ]] || fail "Gradle 完成但没有生成预期 APK：$source_apk"

printf '==> 核验构建产物签名：%s\n' "$source_apk"
"$apksigner_bin" verify --verbose --print-certs "$source_apk"
python3 "$repo_root/scripts/verify_android_release.py" "$source_apk" \
  --output "$preview_dir/validation/release-package.json"
# Only verified real Release bytes may reach the upload directory.
mkdir -p "$(dirname "$candidate_apk")"
mv "$source_apk" "$candidate_apk"

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
printf '%s\n' '脚本未创建或上传发布 keystore，也未上传 CDN 文件。'

# Hash the exact signed candidate; never hand-maintain a revision checksum.
python_bin="$repo_root/.venv/bin/python"
if [[ ! -x "$python_bin" ]]; then
  python_bin="$(command -v python3)" || fail "生成 CDN 清单需要 Python 3。"
fi
"$python_bin" - <<'PY'
import os, re
from urllib.parse import urlsplit
def origin(name):
    value = urlsplit(os.environ[name])
    return value.scheme, value.hostname, value.port or 443
index = urlsplit(os.environ['SENSEFIELD_UPDATE_MANIFEST_URL'])
apk = urlsplit(os.environ['SENSEFIELD_UPDATE_APK_URL'])
project_release = (apk.scheme == 'https' and apk.hostname == 'gitee.com'
    and apk.port in (None, 443) and not apk.username and not apk.password
    and not apk.query and not apk.fragment
    and re.fullmatch(r'/leda/SenseField/releases/download/[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*\.apk', apk.path))
owned_index = (os.environ['SENSEFIELD_UPDATE_MANIFEST_URL'] == 'https://888413.xyz/apk/latest.json')
if origin('SENSEFIELD_UPDATE_MANIFEST_URL') != origin('SENSEFIELD_UPDATE_APK_URL') and not (owned_index and project_release):
    raise SystemExit('APK 必须同源，或由固定版本清单指向听野 Gitee Release。')
PY
cdn_dir="$preview_dir/cdn-upload"
mkdir -p "$cdn_dir"
"$python_bin" "$repo_root/scripts/build_app_update_manifest.py" \
  --apk "$candidate_apk" \
  --apk-url "$SENSEFIELD_UPDATE_APK_URL" --output "$cdn_dir/latest.json" \
  --notes-file "$repo_root/docs/releases/${PREVIEW_VERSION_NAME}/UPDATE_SUMMARY.txt"
printf 'Gitee APK：%s\n' "$candidate_apk"
printf '网站版本清单：%s/latest.json\n' "$cdn_dir"
